# API de administración de Kafka

`messaging-admin` expone `/api/admin/kafka`: crea y consulta tópicos, cambia su
configuración, amplía particiones y revisa o reinicia los offsets de un grupo de
consumidores. Los topics del sistema (`pedidos360.order-events` y su `.DLT`) se
pueden **leer**, pero nunca crear, modificar ni eliminar desde esta API.

## Diseño

| Capa | Clase | Responsabilidad |
|---|---|---|
| Web | `KafkaAdminController` | Rutas, verbos HTTP y códigos de estado. No conoce Kafka. |
| Web | `dto/*Request` / `*Response` | Validación de entrada y forma de la respuesta. |
| Web | `AdminErrorHandler` | Cada falla se responde como `ProblemDetail` (RFC 9457). |
| Servicio | `KafkaAdminService` | Reglas de negocio: nombres reservados, validaciones, lag y offsets. |
| Adaptador | `KafkaAdminClientOperations` | Única clase que habla con `AdminClient`, con timeout por operación. |
| Configuración | `KafkaAdminProperties` + `application.yml` | Prefijos reservados, configuraciones permitidas y valores por defecto. |

El servicio depende de la interfaz `KafkaOperations`, así que sus pruebas no
necesitan broker. `KafkaAdminClientOperationsIntegrationTest` levanta un broker
embebido (KRaft) para probar el adaptador de punta a punta.

## API

Ruta base: `/api/admin/kafka`. Errores: `400` entrada inválida, `404` tópico o
grupo inexistente, `409` recurso protegido, tópico existente o grupo no vacío,
`503` Kafka no disponible.

| Método | Ruta | Cuerpo / parámetros | Resultado |
|---|---|---|---|
| `POST` | `/topics` | `{"name", "partitions"?, "replicationFactor"?, "configs"?}` | `201` + `Location` |
| `GET` | `/topics` | `?includeInternal=false` | `200` lista `{name, partitions, replicationFactor, internal}` |
| `GET` | `/topics/{name}` | — | `200` `{name, internal, configs, partitions[]}` |
| `PATCH` | `/topics/{name}/configs` | `{"configs": {...}}` | `200` detalle actualizado |
| `POST` | `/topics/{name}/partitions` | `{"partitions": N}` | `200` detalle actualizado |
| `DELETE` | `/topics/{name}` | — | `204` |
| `GET` | `/consumer-groups` | — | `200` lista `{groupId, state, members, totalLag}` |
| `GET` | `/consumer-groups/{groupId}` | — | `200` `{groupId, state, coordinator, members[], offsets[]}` |
| `POST` | `/consumer-groups/{groupId}/offsets/reset` | `{"topic", "partitions"?, "strategy", "offset"?, "timestamp"?}` | `200` offsets resultantes |

Valores por defecto al crear: 3 particiones, factor de replicación 1
(`app.messaging.admin.kafka.default-*`).

### Nombres reservados

Los nombres que empiezan con `pedidos360.`, `orders.`, `stock.` o `__` pertenecen
al sistema en marcha: se pueden consultar, pero crear, modificar, ampliar o
eliminar devuelve `409`.

### Validación

- Nombre de tópico: 1 a 249 caracteres, sólo letras, números, `.`, `-` o `_`.
- Particiones: de 1 a 1.000. Kafka sólo permite **aumentarlas**; reducirlas es
  `409` y el cuerpo indica el número actual.
- Factor de replicación: entre 1 y la cantidad de brokers disponibles.
- Configuraciones permitidas (lista cerrada en `application.yml`):
  `retention.ms`, `retention.bytes`, `cleanup.policy`, `segment.ms`,
  `max.message.bytes`, `min.insync.replicas`. Cualquier otra clave es `400`.
- `cleanup.policy`: `delete`, `compact` o `compact,delete`.
- `min.insync.replicas`: entre 1 y el factor de replicación del tópico.

### Reinicio de offsets

Sólo se permite cuando el grupo está en estado `EMPTY` (sin consumidores
activos); si está `STABLE`, `PREPARING_REBALANCE` o similar, responde `409` y
pide detener los consumidores primero. Estrategias:

| `strategy` | Campos | Significado |
|---|---|---|
| `EARLIEST` | — | Al inicio del log |
| `LATEST` | — | Al final del log |
| `OFFSET` | `offset` | A un offset explícito (≥ 0) |
| `TIMESTAMP` | `timestamp` | Al primer offset del instante indicado |

`partitions` es opcional: omitido significa todas las particiones del tópico.
Con `TIMESTAMP`, si el instante es posterior al último mensaje se usa el inicio
del log para esa partición.

## Ejemplos

```bash
A=http://localhost:8085/api/admin/kafka
curl -i -X POST $A/topics -H 'Content-Type: application/json' \
  -d '{"name":"tickets.events","partitions":3,"configs":{"retention.ms":"604800000"}}'
curl -s  $A/topics
curl -s  $A/topics/pedidos360.order-events          # lectura permitida
curl -i -X PATCH $A/topics/tickets.events/configs -H 'Content-Type: application/json' \
  -d '{"configs":{"retention.ms":"1209600000","cleanup.policy":"delete"}}'
curl -i -X POST $A/topics/tickets.events/partitions -H 'Content-Type: application/json' \
  -d '{"partitions":6}'
curl -i -X DELETE $A/topics/pedidos360.order-events  # 409: tópico del sistema
curl -s  $A/consumer-groups
curl -s  $A/consumer-groups/catalog-sales-projection
curl -i -X POST $A/consumer-groups/catalog-sales-projection/offsets/reset \
  -H 'Content-Type: application/json' \
  -d '{"topic":"pedidos360.order-events","strategy":"EARLIEST"}'
```

## Ejecución

```bash
docker compose up -d kafka kafka-init messaging-admin   # desde pedidos360-infra
# o contra un broker ya levantado:
SPRING_KAFKA_BOOTSTRAP_SERVERS=localhost:9092 mvn spring-boot:run
mvn test   # servicio y controlador sin broker; el adaptador usa uno embebido
```

`messaging-admin` espera a que `kafka-init` termine, igual que `catalog` y
`orders`: si el job falla, el servicio no arranca contra un clúster sin los
topics del contrato.

## Seguridad

La API puede eliminar tópicos y reiniciar offsets, y no tiene autenticación
propia: el compose la publica sólo en `127.0.0.1`. La Kafka UI del stack es de
sólo lectura; los cambios se hacen por esta API o con la CLI.
