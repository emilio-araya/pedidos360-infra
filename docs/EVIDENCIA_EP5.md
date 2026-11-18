# Evidencia — Evaluación Parcial N.º 5 (Kafka)

Registro de lo ejecutado y observado el **2026-11-18, 16:34 (UTC-3)**, en la máquina de
desarrollo, con Docker 29.10.0 y Docker Compose 5.8.0. Actualizado el mismo día para
añadir: la corrida del cluster de 3 brokers y la prueba de caída de broker (§2.7); las
Kafka UI escribibles (§3 y §4); el consumidor de **auditoría en código**
(`pedidos360-audit`) y el flujo **notification-events** (§2.8 y §2.10) con corrida en
vivo completa (invitado → ACEPTADO → correo en Mailpit).

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
| 2 | Productores y consumidores para los tópicos | 12 % | Cumple (dos tópicos de negocio + DLT) | §2.2, §2.10 |
| 3 | Microservicio administrador de Kafka | 6 % | Cumple | §2.3 |
| 4 | Implementación de productores | 14 % | Cumple | §2.4, §2.10 |
| 5 | Implementación de consumidores | 14 % | Cumple | §2.5, §2.10 |
| 6 | Manejo de errores, logs y reintentos | 12 % | Cumple | §2.6 |
| 7 | Retención, particiones y replicación | 12 % | Cumple (RF=3, prueba de caída de broker) | §2.7 |
| 8 | Consumer Groups | 12 % | Cumple (tres grupos, dos de ellos en código) | §2.8 |
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

Dos tópicos de negocio con productor en `orders` (outbox transaccional) y consumidor en `catalog`/`orders`:

- `pedidos360.order-events` — eventos de dominio del pedido. Productor: `KafkaOutboxPublisher`
  (clave = `orderId`). Consumidor: `SalesProjectionListener` (grupo `catalog-sales-projection`)
  para la proyección de ventas, y `AuditTrailListener` (grupo `pedidos360-audit`) para la auditoría.
- `pedidos360.notification-events` — intención de notificación por correo a invitados.
  Productor: el mismo outbox (columna `target_topic`, desde V6). Consumidor:
  `NotificationListener` (grupo `pedidos360-notifications`), en `orders`.

Más la DLT compartida `pedidos360.order-events.DLT` (destino de recuperación, no un flujo de negocio).

```text
$ docker exec pedidos360-kafka-1 kafka-topics.sh --bootstrap-server localhost:9092 --describe
Topic: pedidos360.order-events           PartitionCount: 3  ReplicationFactor: 1
Topic: pedidos360.order-events.DLT       PartitionCount: 3  ReplicationFactor: 1
Topic: pedidos360.notification-events    PartitionCount: 3  ReplicationFactor: 1
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

El outbox enruta por fila (`target_topic`, V6): los eventos de dominio van a
`pedidos360.order-events` y las intenciones de notificación (`NOTIFICATION_REQUESTED`, sólo
pedidos de invitado en transiciones) a `pedidos360.notification-events`, siempre en la misma
transacción que cambia el pedido. Evidencia en vivo (§2.10):

```json
{"eventId":"2599fe17-…","orderId":"6f4eef92-…","orderNumber":"PED-1F1F001A",
 "status":"ACEPTADO","previousStatus":"CREADO","email":"ep5.demo@example.com",
 "occurredAt":"2026-11-18T23:09:32.370508892Z"}
```

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

**Prueba de caída de broker (2026-11-18, misma corrida):**

```text
== ANTES ==
Topic: pedidos360.order-events  PartitionCount: 3  ReplicationFactor: 3  Configs: min.insync.replicas=2,...
	Partition: 0  Leader: 1  Replicas: 3,1,2  Isr: 1,2,3
	Partition: 1  Leader: 1  Replicas: 1,2,3  Isr: 1,2,3
	Partition: 2  Leader: 1  Replicas: 2,3,1  Isr: 1,2,3

== docker stop pedidos360-kafka-cluster-kafka-3-1 ==
Topic: pedidos360.order-events
	Partition: 0  Leader: 1  Replicas: 3,1,2  Isr: 1,2
	Partition: 1  Leader: 1  Replicas: 1,2,3  Isr: 1,2
	Partition: 2  Leader: 1  Replicas: 2,3,1  Isr: 1,2

== producer acks=all, con UN broker menos ==
$ kafka-console-producer.sh --topic pedidos360.notification-events --producer-property acks=all
{"eventType":"BROKER_DEATH_CHECK","detail":"escrito con kafka-3 caido"}
== leido desde kafka-2 ==
Partition:1  Offset:0  {"eventType":"BROKER_DEATH_CHECK","detail":"escrito con kafka-3 caido"}

== docker start pedidos360-kafka-cluster-kafka-3-1 ; espera a healthy ==
Topic: pedidos360.order-events
	Partition: 0  Leader: 1  Replicas: 3,1,2  Isr: 1,2,3
	Partition: 1  Leader: 1  Replicas: 1,2,3  Isr: 1,2,3
	Partition: 2  Leader: 1  Replicas: 2,3,1  Isr: 1,2,3
```

Es decir: con RF=3 y `min.insync.replicas=2`, escribir con `acks=all` sobrevive a la pérdida del
tercer broker y los datos siguen disponibles; al volver, el broker recupera su sitio en el ISR.
Nota: el clúster también elevó `__consumer_offsets` a RF=3 con `min.insync.replicas=2`
(`Configs: ...min.insync.replicas=2`), así que también los offsets de grupos sobreviven a la
caída.

### 2.8 Consumer Groups

Tres grupos leyendo/interviniendo en el mismo contrato, dos de ellos en código:

```text
$ kafka-consumer-groups.sh --list
catalog-sales-projection
pedidos360-audit
pedidos360-notifications

$ kafka-consumer-groups.sh --describe --group pedidos360-audit
GROUP            TOPIC                   PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG
pedidos360-audit pedidos360.order-events 0          3               3               0
pedidos360-audit pedidos360.order-events 1          5               5               0
pedidos360-audit pedidos360.order-events 2          1               1               0

$ kafka-consumer-groups.sh --describe --group pedidos360-notifications
GROUP                    TOPIC                          PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG
pedidos360-notifications pedidos360.notification-events  0          0               0               0
pedidos360-notifications pedidos360.notification-events  1          1               1               0
pedidos360-notifications pedidos360.notification-events  2          0               0               0
```

- `catalog-sales-projection` (catalog): proyecta ventas; lo consume todo el flujo.
- `pedidos360-audit` (**código**): `AuditTrailListener`, persiste cada evento en la tabla
  `audited_order_events` con la clave `event_id` (idempotente por construcción). Offsets 3/5/1,
  lag 0 en las tres particiones tras procesar todos los eventos de la demo.
- `pedidos360-notifications` (**código**): `NotificationListener`, envía un correo por
  transición a invitados; offset 1 en la partición donde cayó `NOTIFICATION_REQUESTED`, lag 0.

Los dos grupos en código llevan offsets **independientes**: `pedidos360-audit` consumió los
mismos eventos que `catalog-sales-projection` sin compartir progreso y en paralelo con él.

Histórico: el grupo de consola `ep5-auditoria` se usó antes de tener el consumidor de
auditoría para demostrar offsets independientes; ya no existe en el código ni en el clúster
actual, el rol lo asume `pedidos360-audit`.

### 2.9 Administración de tópicos y Consumer Groups

Reset de offsets sobre un grupo vacío (`ep5-auditoria`, captura histórica; hoy el rol lo
asume `pedidos360-audit`):

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

El tópico nuevo queda protegido por el admin sin cambios: `pedidos360.` está reservado en
`messaging-admin` (`application.yml`, `reserved-prefixes`), así que `pedidos360.notification-events`
no se puede crear, modificar ni borrar desde la API.

### 2.10 Segundo flujo de negocio: notificaciones a invitados (2026-11-18)

Flujo completo ejecutado contra el stack local con perfil `local` (H2 + tokens HMAC):

```text
1. POST /api/guest-orders  {"email":"ep5.demo@example.com","items":[...],"notes":"demo EP5 notificaciones"}
   → 201  {"orderNumber":"PED-1F1F001A","status":"CREADO", ...}

2. PATCH /api/orders/6f4eef92-5428-4231-a603-5ddaab8257a9/status  {"status":"ACEPTADO"}
   → 200  "estado: ACEPTADO"

3. outbox (V6 target_topic) publicó pedidos360.notification-events:
   Partition:1  Offset:0
   {"eventId":"2599fe17-...","orderId":"6f4eef92-...","orderNumber":"PED-1F1F001A",
    "status":"ACEPTADO","previousStatus":"CREADO","email":"ep5.demo@example.com",
    "occurredAt":"2026-11-18T23:09:32.370508892Z"}

4. Grupo pedidos360-notifications consumió y envió el correo; Mailpit (127.0.0.1:8025) registró:
   - "Pedido PED-1F1F001A confirmado"                    (voucher, al crear)
   - "Tu pedido PED-1F1F001A cambió a ACEPTADO"           (notification-events, al aceptar)

5. orders log:
   NotificationListener : Notificación de cambio de estado enviada para el pedido PED-1F1F001A (CREADO → ACEPTADO)

6. El mismo aceptado movió la proyección de ventas:
   {"productId":"17befa55-…","unitsSold":1,"unitsDelivered":0,"unitsCancelled":0,"netUnits":1}
```

Notas honestas del flujo:

- El correo sólo va a pedidos de invitado (sólo ellos guardan `customer_email`); un pedido con
  cuenta Entra ID no encola `NOTIFICATION_REQUESTED` (cubierto por test).
- El cuerpo remite a la página genérica `/seguimiento`: el token de seguimiento crudo NUNCA se
  persiste (sólo su hash), así que un aviso posterior no puede reconstruir el enlace del alta.
- Pedidos recién creados NO encolan notificación (el voucher ya avisó); cada cambio de estado
  posterior sí (ACEPTADO, EN_PREPARACION, DESPACHADO, ENTREGADO, CANCELADO).
- `orders` no define una DLT para el tópico de notificaciones: ante fallos transitorios de SMTP
  reintenta dos veces cada 2 s y descarta con error en el log, para no bloquear la partición por
  un correo. El evento de dominio (orders → catalog) sigue garantizado por la outbox.
- La proyección de ventas, la auditoría y la notificación conviven en el mismo pedido sin
  pisarse: cada consumidor con su grupo.

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

1. **Demo del frontend (login IDaaS + JWT).** La instancia EC2 volvió (2026-11-18 19:35):
   la web sirve (HTTP 200, `healthz` → `ok`), `/api/catalog/products?size=1` responde 200 con
   datos reales y `/api/health` exige autenticación (401). Detalle operativo: al reiniciar, el
   BFF tarda ~97 s en arrancar; las llamadas a `/api/*` durante esa ventana devuelven 502 y se
   resuelven solas. El login interactivo de Entra ID requiere una prueba con navegador (flujo
   OAuth) y no se puede grabar sin credenciales.
2. **Reintentos con backoff en vivo.** El camino no reintentable se demostró en vivo (payload
   inválido → DLT). El camino reintentable está cubierto por `SalesProjectionErrorHandlerTest`,
   no por una caída de base provocada a propósito.

Nota aparte: la replicación ya está resuelta con `docker-compose.kafka-cluster.yml` (§2.7); el
stack principal sigue en RF=1 a propósito (VM única), y el flujo de negocio documentado en §2.10
corre sobre ese stack de un broker: el cluster de tres brokers demuestra la replicación, no el
flujo E2E. Flyway en catalog queda diferido (decisión del grupo); Hibernate sigue en
`ddl-auto: update` ahí, con `ddl-auto: validate` + Flyway en orders.

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

# flujo de notificaciones (§2.10): invitado → ACEPTADO → correo en Mailpit
curl -sS -X POST localhost:8081/api/guest-orders -H 'Content-Type: application/json' \
  -d '{"email":"tu@correo.cl","items":[{"productId":"<id>","quantity":1}],"notes":"demo"}'
curl -sS -X PATCH localhost:8081/api/orders/<id>/status -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer <operator-token>' -d '{"status":"ACEPTADO"}'
curl -sS localhost:8025/api/v1/messages   # ver el correo "Tu pedido ... cambió a ACEPTADO"

# auditoría: group offsets moviéndose en paralelo
docker exec pedidos360-kafka-1 /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --describe --group pedidos360-audit
```

Los pedidos de prueba se crean con `pedidos360-bff/scripts/generate-local-token.py` y el perfil
`local`; en AWS el mismo flujo usa el token de Entra ID.
