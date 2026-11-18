# Evidencia — Evaluación Parcial N.º 5 (Kafka)

Registro de lo ejecutado y observado el **2026-11-18, 16:34 (UTC-3)**, en la máquina de
desarrollo, con Docker 29.10.0 y Docker Compose 5.8.0. Actualizado el mismo día para añadir
la corrida del cluster de 3 brokers (§2.7) y el estado escribible de las Kafka UI (§3 y §4).

Todo lo que aparece abajo es salida real de comandos corridos contra los contenedores. No hay
resultados supuestos. Lo que quedó pendiente está en la sección 5.

Cómo se levantó:

```bash
# Stack principal (Kafka KRaft, RabbitMQ, Kafka UI, Oracle)
docker compose --env-file .env -f docker-compose.yml up -d

# Variante ZooKeeper (proyecto, red y volúmenes propios: convive con el anterior)
docker compose -f docker-compose.kafka-zk.yml up -d

# Servicios de negocio con perfil `local` (H2 en memoria + tokens HMAC),
# para disparar el flujo sin depender de Entra ID ni de Oracle.
# override desechable fuera del repo (perfil local), p.ej. /tmp/.../ep5-local.yml
docker compose --env-file .env -f docker-compose.yml -f <override-local> up -d --no-deps catalog orders
```

El override desechable sólo cambia `SPRING_PROFILES_ACTIVE` y publica 8081/8082 en loopback.
No forma parte del repositorio.

---

## 1. Resumen por indicador

| # | Indicador | Peso | Estado | Dónde está la evidencia |
| --- | --- | --- | --- | --- |
| 1 | Configuración del cliente Kafka | 12 % | Cumple | §2.1 |
| 2 | Productores y consumidores para los tópicos | 12 % | Parcial (un tópico de dominio) | §2.2 |
| 3 | Microservicio administrador de Kafka | 6 % | Cumple | §2.3 |
| 4 | Implementación de productores | 14 % | Cumple | §2.4 |
| 5 | Implementación de consumidores | 14 % | Cumple | §2.5 |
| 6 | Manejo de errores, logs y reintentos | 12 % | Cumple | §2.6 |
| 7 | Retención, particiones y replicación | 12 % | Cumple (RF=3 con cluster de 3 brokers) | §2.7 |
| 8 | Consumer Groups | 12 % | Cumple (demo) / parcial (código) | §2.8 |
| 9 | Administración de tópicos y grupos | 6 % | Cumple | §2.9 |

---

## 2. Evidencia por indicador

### 2.1 Configuración del cliente Kafka

Productor en `pedidos360-orders/src/main/resources/application.yml`:

```yaml
kafka:
  bootstrap-servers: ${SPRING_KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
  producer:
    key-serializer: org.apache.kafka.common.serialization.StringSerializer
    value-serializer: org.apache.kafka.common.serialization.StringSerializer
    acks: all
    properties:
      enable.idempotence: true
      max.block.ms: ${KAFKA_PRODUCER_MAX_BLOCK_MS:10000}
```

Consumidor en `pedidos360-catalog/src/main/resources/application.yml`:

```yaml
kafka:
  bootstrap-servers: localhost:9092
  consumer:
    auto-offset-reset: earliest
    enable-auto-commit: false
```

Consola del productor que arrancó `orders` al publicar el primer evento:

```text
[Producer clientId=ms-pedidos360-orders-producer-1] Instantiated an idempotent producer.
[Producer clientId=ms-pedidos360-orders-producer-1] Cluster ID: lt4pMihWTs2WdoOIy9jBqg
```

### 2.2 Productores y consumidores para los tópicos

Tópicos en uso hoy: un tópico de dominio (`pedidos360.order-events`) con su DLT compartida
(`pedidos360.order-events.DLT`). El productor vive en `orders` (`KafkaOutboxPublisher`) y el
consumidor en `catalog` (`SalesProjectionListener`).

```text
$ docker exec pedidos360-kafka-1 kafka-topics.sh --bootstrap-server localhost:9092 --describe
Topic: pedidos360.order-events      PartitionCount: 3  ReplicationFactor: 1  Configs: cleanup.policy=delete,retention.ms=604800000
Topic: pedidos360.order-events.DLT  PartitionCount: 3  ReplicationFactor: 1  Configs: retention.ms=1209600000
```

### 2.3 Microservicio administrador de Kafka

`pedidos360-infra/messaging-admin`, puerto 8085, ruta base `/api/admin/kafka`.

Altas y consultas:

```text
$ curl -s localhost:8085/api/admin/kafka/topics
[{"name":"pedidos360.order-events","partitions":3,"replicationFactor":1,"internal":false},
 {"name":"pedidos360.order-events.DLT","partitions":3,"replicationFactor":1,"internal":false}]

$ curl -s -X POST localhost:8085/api/admin/kafka/topics \
    -d '{"name":"ep5.demo","partitions":3,"replicationFactor":1,
         "configs":{"retention.ms":"86400000","cleanup.policy":"delete"}}'
201  → {"name":"ep5.demo","configs":{"cleanup.policy":"delete","retention.ms":"86400000","min.insync.replicas":"1"}, ...}

$ curl -s -X PATCH localhost:8085/api/admin/kafka/topics/ep5.demo/configs \
    -d '{"configs":{"retention.ms":"3600000"}}'
200  → retention.ms: "3600000"

$ curl -s -X POST localhost:8085/api/admin/kafka/topics/ep5.demo/partitions -d '{"partitions":6}'
200  → 6 particiones devueltas (0..5)

$ curl -s -X DELETE localhost:8085/api/admin/kafka/topics/ep5.demo
204
```

Guarda sobre los tópicos del sistema:

```text
$ curl -s -X DELETE localhost:8085/api/admin/kafka/topics/pedidos360.order-events
409
{"type":"about:blank","title":"Conflicto","status":409,
 "detail":"pedidos360.order-events pertenece al sistema y no se puede crear, modificar ni eliminar desde esta API"}
```

### 2.4 Implementación de productores

Pedido creado por la API (`POST :8081/api/orders`, token HMAC `Cliente`):

```json
{"id":"f888591a-d35a-4d7b-8dc9-e37c616813bf","orderNumber":"PED-F5FA2F16","status":"CREADO",
 "items":[{"productId":"9b5fc5e2-4e66-40b1-b31b-65f7a8fe5335","quantity":2}],
 "total":179980.0}
```

El outbox publicó el evento con **clave = orderId**, y el pedido pasó a `ACEPTADO`:

```text
Partition:1  Offset:0  f888591a-d35a-4d7b-8dc9-e37c616813bf
{"eventId":"983cf91d-...","eventType":"ORDER_CREATED","schemaVersion":1,
 "orderId":"f888591a-d35a-4d7b-8dc9-e37c616813bf","status":"CREADO","previousStatus":null, ...}

Partition:1  Offset:1  f888591a-d35a-4d7b-8dc9-e37c616813bf
{"eventId":"29313c3d-...","eventType":"ORDER_ACCEPTED","schemaVersion":1,
 "orderId":"f888591a-d35a-4d7b-8dc9-e37c616813bf","status":"ACEPTADO","previousStatus":"CREADO", ...}
```

Los dos eventos del mismo pedido cayeron en la **misma partición (1)** y en orden: eso es la
clave por `orderId` haciendo su trabajo (`KafkaOutboxPublisher`).

### 2.5 Implementación de consumidores

```text
$ kafka-consumer-groups.sh --describe --group catalog-sales-projection
GROUP                    TOPIC                  PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG
catalog-sales-projection pedidos360.order-events 0          3               3               0
catalog-sales-projection pedidos360.order-events 1          3               3               0
catalog-sales-projection pedidos360.order-events 2          1               1               0
```

Efecto observable en el catálogo (proyección de ventas del producto del pedido):

```text
$ curl -s localhost:8082/api/catalog/sales/products/9b5fc5e2-...
{"productId":"9b5fc5e2-...","unitsSold":2,"unitsDelivered":0,"unitsCancelled":0,"netUnits":2}
```

Idempotencia: `apply()` descarta `eventId` ya procesado (`processed_order_events`), así que una
reentrega no vuelve a sumar.

### 2.6 Manejo de errores, logs y reintentos

Payload que no es JSON llegando al tópico:

```text
$ printf 'esto no es un evento json\n' | kafka-console-producer.sh --topic pedidos360.order-events
```

El consumidor lo rechaza y lo publica en la DLT con las cabeceras del error:

```text
$ kafka-console-consumer.sh --topic pedidos360.order-events.DLT --from-beginning \
    --formatter-property print.headers=true
kafka_dlt-exception-fqcn:org.springframework.kafka.listener.ListenerExecutionFailedException
kafka_dlt-exception-cause-fqcn:com.pedidos360.catalog.messaging.kafka.InvalidOrderEventException
kafka_dlt-exception-message:Listener method 'public void com.pedidos360.catalog.messaging.kafka.SalesProjectio...
```

Configuración del handler (`KafkaConsumerConfiguration`):

- `DefaultErrorHandler` con `ExponentialBackOffWithMaxRetries(4)`, intervalo inicial 1000 ms,
  multiplicador 2, techo 10000 ms (1 s, 2 s, 4 s, 8 s).
- `DeadLetterPublishingRecoverer` hacia `pedidos360.order-events.DLT` (partición elegida por clave).
- `addNotRetryableExceptions(InvalidOrderEventException.class)`: un evento mal formado va **directo**
  a la DLT, sin reintentos, para no bloquear la partición.

Prueba automatizada del camino con reintentos:

```text
$ mvn -o test -Dtest=SalesProjectionErrorHandlerTest
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Los tres casos cubren: evento inválido → DLT sin reintento; fallo transitorio
(`QueryTimeoutException`) → se reintenta y **no** se recupera al primer intento; destino de DLT
compartido.

**La DLT es un destino de recuperación e inspección, no un segundo flujo de negocio.** Ningún
consumidor procesa `pedidos360.order-events.DLT`: los mensajes quedan ahí para diagnóstico y, si
hace falta, reinyección manual (por ejemplo, reintentar el evento una vez corregido el motivo del
rechazo, usando `POST /consumer-groups/{groupId}/offsets/reset` o republicándolo con la clave
original para conservar el orden por `orderId`). Esto es intencional: la DLT absorbe fallos sin
bloquear la partición ni perder el evento, que es el patrón que pide la pauta.

### 2.7 Retención, particiones y replicación

Stack principal (`docker-compose.yml`, un broker):

```text
Topic: pedidos360.order-events      PartitionCount: 3  ReplicationFactor: 1  retention.ms=604800000  cleanup.policy=delete
Topic: pedidos360.order-events.DLT  PartitionCount: 3  ReplicationFactor: 1  retention.ms=1209600000
```

Los tópicos los crea `kafka-init` con `KAFKA_AUTO_CREATE_TOPICS_ENABLE=false`, así que un nombre
mal escrito falla en vez de crear un tópico vacío en silencio.

Cluster de 3 brokers (`docker-compose.kafka-cluster.yml`, KRaft, proyecto `pedidos360-kafka-cluster`),
corrido el 2026-11-18:

```bash
docker compose -f docker-compose.kafka-cluster.yml up -d
```

Los tres brokers sanos y los mismos tópicos del contrato creados con factor de replicación 3:

```text
$ docker exec pedidos360-kafka-cluster-kafka-1-1 /opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092 | cut -d: -f1
kafka-1
kafka-2
kafka-3
```

```text
Topic: pedidos360.order-events.DLT  PartitionCount: 3  ReplicationFactor: 3  Configs: min.insync.replicas=2,retention.ms=1209600000
	Partition: 0  Leader: 1  Replicas: 1,2,3  Isr: 1,2,3
	Partition: 1  Leader: 2  Replicas: 2,3,1  Isr: 2,3,1
	Partition: 2  Leader: 3  Replicas: 3,1,2  Isr: 3,1,2
Topic: pedidos360.order-events      PartitionCount: 3  ReplicationFactor: 3  Configs: min.insync.replicas=2,cleanup.policy=delete,retention.ms=604800000
	Partition: 0  Leader: 3  Replicas: 3,1,2  Isr: 3,1,2
	Partition: 1  Leader: 1  Replicas: 1,2,3  Isr: 1,2,3
	Partition: 2  Leader: 2  Replicas: 2,3,1  Isr: 2,3,1
```

`order-events` quedó con líderes repartidos 3, 1, 2 y la DLT con 1, 2, 3; el ISR completo en
las 6 particiones: el cluster tolera la pérdida de un broker con `min.insync.replicas=2`.

Prueba de punta a punta en el cluster (topic de verificación, borrado después):

```text
$ kafka-topics.sh --create --topic ep5.cluster-check --partitions 3 --replication-factor 3 --config min.insync.replicas=2
Created topic ep5.cluster-check.
$ kafka-console-producer.sh --bootstrap-server localhost:9092 --topic ep5.cluster-check
{"eventType":"CLUSTER_CHECK","detail":"ep5 cluster 3 brokers"}
$ kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic ep5.cluster-check --from-beginning --max-messages 1   # contra kafka-2
{"eventType":"CLUSTER_CHECK","detail":"ep5 cluster 3 brokers"}
$ kafka-topics.sh --delete --topic ep5.cluster-check   # OK
```

El stack principal queda RF=1 por diseño (VM única / presupuesto EC2); la replicación real se
demuestra con `docker-compose.kafka-cluster.yml`, que convive con el resto sin tocar Oracle ni
las réplicas de las aplicaciones.

### 2.8 Consumer Groups

Dos grupos leyendo el mismo tópico con offsets independientes:

```text
$ kafka-consumer-groups.sh --list
ep5-auditoria
catalog-sales-projection

$ kafka-consumer-groups.sh --describe --all-groups
catalog-sales-projection pedidos360.order-events 0  3  3  0
catalog-sales-projection pedidos360.order-events 1  3  3  0
catalog-sales-projection pedidos360.order-events 2  1  1  0
ep5-auditoria            pedidos360.order-events 0  0  3  3
ep5-auditoria            pedidos360.order-events 1  0  3  3
ep5-auditoria            pedidos360.order-events 2  0  1  1
```

`ep5-auditoria` quedó en offset 0 con lag 3 mientras `catalog-sales-projection` está al día en el
final del log: cada grupo lleva su propio progreso. Ese desfase es, además, la huella del reset de
offsets de §2.9.

Reparto de particiones del grupo de la aplicación (3 particiones, 1 instancia):

```text
catalog-sales-projection: partitions assigned: [pedidos360.order-events-0, -1, -2]
```

**Nota honesta:** el segundo grupo se demuestra con un consumidor de consola. Un segundo grupo
propio en el código (por ejemplo, auditoría o analítica) todavía no existe.

### 2.9 Administración de tópicos y Consumer Groups

Reset de offsets sobre un grupo vacío (`ep5-auditoria`):

```text
$ curl -s -X POST localhost:8085/api/admin/kafka/consumer-groups/ep5-auditoria/offsets/reset \
    -d '{"topic":"pedidos360.order-events","strategy":"LATEST"}'
200 → {"groupId":"ep5-auditoria","offsets":[{"partition":0,"currentOffset":3,"logEndOffset":3,"lag":0}, ...]}

$ ... -d '{"topic":"pedidos360.order-events","strategy":"EARLIEST"}'
200 → offsets en 0
```

Reset sobre un grupo con consumidores activos:

```text
$ curl -s -X POST localhost:8085/api/admin/kafka/consumer-groups/catalog-sales-projection/offsets/reset \
    -d '{"topic":"pedidos360.order-events","strategy":"EARLIEST"}'
409
{"title":"Conflicto","status":409,
 "detail":"El grupo catalog-sales-projection está en estado STABLE; detén sus consumidores antes de reiniciar los offsets"}
```

---

## 3. Kafka UI

Al momento de la captura estaba en solo lectura:

```text
$ curl -s localhost:8086/api/clusters
[{"name":"pedidos360","status":"ONLINE","brokerCount":1,"topicCount":3,
  "readOnly":true,"features":["TOPIC_DELETION","FTS_ENABLED"],"controller":"KRAFT"}]
```

Desde la revisión para la entrega, la UI quedó con **escritura habilitada**
(`KAFKA_CLUSTERS_0_READONLY: "false"` en `docker-compose.yml`), porque la pauta pide
administrar brokers y tópicos también desde la interfaz. Sigue publicada **solo en loopback**
(`127.0.0.1:8086`): ahí se pueden crear/borrar tópicos y mover offsets, así que nunca debe
exponerse a la LAN ni a la IP pública de la EC2.

## 4. Variante ZooKeeper

```bash
docker compose -f docker-compose.kafka-zk.yml up -d
```

```text
$ docker exec pedidos360-kafka-zk-zookeeper-1 zookeeper-shell localhost:2181 ls /brokers/ids
[1]

$ docker logs pedidos360-kafka-zk-kafka-init-1
Created topic pedidos360.order-events.
Created topic pedidos360.order-events.DLT.
Completed updating config for topic pedidos360.order-events.
Completed updating config for topic pedidos360.order-events.DLT.
```

Corre como proyecto aparte (`pedidos360-kafka-zk`), con su propia red y volúmenes, así que
puede levantarse junto al stack principal sin chocar. Ahora también incluye su **Kafka UI**
(`pedidos360-zk`) en `http://127.0.0.1:8087`, misma imagen, solo loopback y con escritura
habilitada, para administrar este clúster desde la interfaz.

## 5. Lo que queda parcial o pendiente

1. **Segundo consumer group en código.** Hoy el segundo grupo es un consumidor de consola; no hay
   un segundo consumidor propio.
2. **Demo del frontend (login IDaaS + JWT).** La instancia EC2 volvió (2026-11-18 19:35):
   la web sirve (HTTP 200, `healthz` → `ok`), `/api/catalog/products?size=1` responde 200 con
   datos reales y `/api/health` exige autenticación (401). Detalle operativo: al reiniciar, el
   BFF tarda ~97 s en arrancar; las llamadas a `/api/*` durante esa ventana devuelven 502 y se
   resuelven solas. El login interactivo de Entra ID requiere una prueba con navegador (flujo
   OAuth) y no se puede grabar sin credenciales.
3. **Reintentos con backoff en vivo.** El camino no reintentable se demostró en vivo (payload
   inválido → DLT). El camino reintentable está cubierto por `SalesProjectionErrorHandlerTest`,
   no por una caída de base provocada a propósito.

Nota aparte: la replicación ya está resuelta con `docker-compose.kafka-cluster.yml` (§2.7); el
stack principal sigue en RF=1 a propósito (VM única).

## 6. Cómo reproducir

```bash
# infraestructura
docker compose --env-file .env -f docker-compose.yml up -d

# cluster de 3 brokers con RF=3 (evidencia de replicación, §2.7)
docker compose -f docker-compose.kafka-cluster.yml up -d
docker compose -f docker-compose.kafka-cluster.yml exec kafka-1 \
  /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --describe

# tópicos y grupos
docker exec pedidos360-kafka-1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --describe
docker exec pedidos360-kafka-1 /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --list

# admin
curl -s localhost:8085/api/admin/kafka/topics
curl -s localhost:8085/api/admin/kafka/consumer-groups

# contrato de eventos y proyección
docker exec pedidos360-kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic pedidos360.order-events --from-beginning \
  --formatter-property print.partition=true --formatter-property print.offset=true
```

Los pedidos de prueba se crean con `pedidos360-bff/scripts/generate-local-token.py` y el perfil
`local`; en AWS el mismo flujo usa el token de Entra ID.
