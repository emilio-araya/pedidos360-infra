# Guion de demostración: RabbitMQ

Cada punto de la evaluación con el lugar del código y el comando que lo muestra.
Se ejecuta desde `pedidos360-infra` con el stack levantado (`docker compose up -d`).

## 1. Clúster de dos nodos

```bash
docker compose exec rabbitmq-1 rabbitmqctl cluster_status
```

Muestra `Running Nodes: rabbit@rabbitmq-1, rabbit@rabbitmq-2`. Configuración en
`rabbitmq/rabbitmq.conf` (descubrimiento `classic_config`, particiones `autoheal`)
y Erlang cookie compartida en `RABBITMQ_ERLANG_COOKIE`.

Prueba de falla: `docker compose stop rabbitmq-1`; el nodo 2 sigue activo, el
administrador sigue respondiendo y las colas `QUORUM` siguen disponibles.
`docker compose start rabbitmq-1` lo reintegra. Las colas del sistema son
`classic` y viven en el nodo 1: mientras está caído, catalog y orders no procesan.

## 2. Tres colas con su DLQ

```bash
docker compose exec rabbitmq-1 rabbitmqctl list_queues -p pedidos360 name messages consumers
```

| Cola | Servicio | DLQ |
|---|---|---|
| `stock.reserve` | catalog | `stock.reserve.dlq` |
| `stock.release` | catalog | `stock.release.dlq` |
| `orders.stock-result` | orders | `orders.stock-result.dlq` |

Cada una tiene además tres colas de reintento con espera creciente (`.retry.1`
a `.retry.3`). Detalle en [`MENSAJERIA_RABBITMQ.md`](MENSAJERIA_RABBITMQ.md).

## 3. Exchanges direct y topic

```bash
docker compose exec rabbitmq-1 rabbitmqctl list_exchanges -p pedidos360 name type
```

- `pedidos360.events` (**topic**): eventos de pedido y de stock (`pedido.aceptado`, `stock.reservado`).
- `pedidos360.retry` y `pedidos360.dlx` (**direct**): reintentos y mensajes muertos.

Interfaz web: `http://127.0.0.1:15672` (nodo 1) y `http://127.0.0.1:15673` (nodo 2).

## 4. Código de mensajería (por indicador de la pauta)

| Indicador | Dónde mostrarlo |
|---|---|
| Nombres centralizados | `MessagingTopology`/`RetryTopology` en catalog y orders; `app.messaging.admin.*` en `messaging-admin/src/main/resources/application.yml` |
| Beans `Queue`, `Exchange`, `Binding` | `MessagingConfiguration` y `RetryTopology` de cada servicio |
| Mensajería separada del negocio | paquete `messaging/`; el negocio publica a través de la outbox |
| `@RabbitListener` por dominio | `MessagingEntryPoints`: stock en catalog, resultados en orders |
| ACK manual y errores | `acknowledge-mode: manual`, `basicAck`/`basicNack`, reintentos y DLQ |
| Microservicio administrador | `messaging-admin/` (`MessagingAdminController`) |
| Lógica encapsulada | `RabbitAdminService`, única clase que usa `AmqpAdmin` |
| Validación de entrada | `messaging-admin/.../web/dto/*Request` |

## 5. Microservicio administrador en vivo

```bash
A=http://localhost:8085/api/admin
curl -i -X POST $A/queues -H 'Content-Type: application/json' -d '{"name":"notifications.email","type":"QUORUM"}'
docker compose exec rabbitmq-1 rabbitmqctl list_queues -p pedidos360 name type members | grep notifications
curl -i -X POST $A/exchanges -H 'Content-Type: application/json' -d '{"name":"tickets.events","type":"TOPIC"}'
curl -i -X POST $A/bindings -H 'Content-Type: application/json' -d '{"exchange":"pedidos360.events","queue":"notifications.email","routingKey":"pedido.*"}'
curl -i -X DELETE $A/queues/stock.reserve                                   # 409: cola del sistema
curl -i -X POST $A/queues -H 'Content-Type: application/json' -d '{"name":""}'   # 400 con errores por campo
curl -i -X DELETE $A/queues/notifications.email                             # 204, también borra su DLQ
```

La cola `QUORUM` muestra `members: [rabbit@rabbitmq-1, rabbit@rabbitmq-2]`: está replicada.

## Preguntas probables

- **¿Por qué `autoheal` y no `pause_minority`?** Con dos nodos no hay mayoría: `pause_minority` pausa también al nodo que sobrevive. Se probó y el clúster quedaba sin nodos activos.
- **¿Por qué no se puede crear una cola quorum con un nodo caído?** Raft necesita mayoría para elegir líder; con 1 de 2 no la hay. La API responde `503` y no deja restos.
- **¿Por qué las colas del sistema son classic?** No se cambió su tipo para no alterar el comportamiento ya verificado. Consecuencia: si cae el nodo donde viven, catalog y orders se detienen hasta que vuelve (los eventos esperan en la outbox). Las colas quorum del administrador sí sobreviven a la caída de un nodo.
- **¿Qué impide borrar `stock.reserve` desde la API?** Los prefijos reservados en `app.messaging.admin.reserved-prefixes`.
