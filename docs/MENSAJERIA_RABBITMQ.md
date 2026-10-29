# Mensajería con RabbitMQ

Reserva de stock asíncrona entre `orders` y `catalog`, con outbox transaccional, reintentos con backoff exponencial y DLQ.

## Qué problema resuelve

Antes, aceptar un pedido llamaba por HTTP al catálogo **dentro** de la transacción local. Eran dos commits en dos sistemas sin transacción distribuida:

- Si el catálogo reservaba y después fallaba el commit local, el stock quedaba inmovilizado.
- Aceptar o cancelar devolvía `502` cuando el catálogo no respondía.

Ahora `orders` sólo escribe en su propia base: el cambio de estado y el evento, en la misma transacción. Lo demás ocurre por mensajería.

## Flujo

```
orders                                   RabbitMQ                              catalog
──────                                   ────────                              ───────
PATCH ACEPTADO
 └─ tx: estado + outbox_event ─┐
                               │ OutboxPublisher (cada 2 s, publisher confirms)
                               └──▶ pedidos360.events ──pedido.aceptado──▶ stock.reserve ──▶ reserve(orderId)
                                                                                              │
     applyStockReserved ◀── orders.stock-result ◀──stock.reservado / stock.reserva-fallida────┘
       ACEPTADO → stockReserved=true
       o rechazo → CANCELADO + cancellationReason + pedido.cancelado
```

## Contrato de eventos

| Routing key | Publica | Cola | Consume | Cuerpo JSON |
|---|---|---|---|---|
| `pedido.aceptado` | orders (outbox) | `stock.reserve` | catalog | `{"orderId","items":[{"productId","quantity"}]}` |
| `pedido.cancelado` | orders (outbox) | `stock.release` | catalog | `{"orderId"}` |
| `pedido.reserva-huerfana` | orders (outbox) | `stock.release` | catalog | `{"orderId"}` |
| `stock.reservado` | catalog | `orders.stock-result` | orders | `{"orderId","outcome":"RESERVED"}` |
| `stock.reserva-fallida` | catalog | `orders.stock-result` | orders | `{"orderId","outcome":"FAILED","errorCode","reason"}` |

Exchange: `pedidos360.events` (topic, durable). Los mensajes viajan como `application/json`, persistentes. Cambiar un nombre de campo o de routing key en un lado sin cambiar el otro deja mensajes sin procesar: hay un test de contrato en cada servicio.

**Propiedad de la topología:** cada servicio declara el exchange al que publica y **las colas que consume**. Si los dos declararan la misma cola, la primera diferencia de argumentos haría fallar la declaración con `PRECONDITION_FAILED`.

## Garantías

| Garantía | Cómo se obtiene |
|---|---|
| No se pierde un evento | Outbox en la misma transacción; el publicador sólo marca `published_at` con confirmación del broker y cola destino (`mandatory`). Sin broker, el evento queda pendiente y se reintenta en cada ciclo. |
| Entrega at-least-once | Un crash entre publicar y marcar reenvía el evento. |
| Un duplicado no tiene efecto | `reserve` y `release` del catálogo son idempotentes por `orderId`; `applyStockReserved`/`applyStockRejected` también. |
| Ack manual | `acknowledge-mode: manual`; `ResilientConsumer` confirma recién después de que el handler terminó. |
| Nada se descarta en silencio | Todo mensaje termina procesado, en una cola de reintento o en la DLQ. |

**El orden entre colas no está garantizado.** `pedido.aceptado` y `pedido.cancelado` van a colas distintas, así que el catálogo puede liberar *antes* de reservar. Si llega `stock.reservado` para un pedido ya cancelado, `orders` emite `pedido.reserva-huerfana` una sola vez y el catálogo libera. En la verificación local este caso ocurrió en las 6 carreras de "aceptar y cancelar enseguida": sin la compensación, cada una habría dejado stock inmovilizado.

**Preparar exige stock confirmado.** `ACEPTADO → EN_PREPARACION` responde `409 El stock del pedido todavía no está confirmado` hasta que llega `stock.reservado`. Con la reserva síncrona eso estaba implícito al aceptar.

## Reintentos y DLQ

Cada cola consumida tiene tres colas de retardo y una DLQ:

```
cola ──fallo transitorio──▶ pedidos360.retry ──▶ cola.retry.N (TTL) ──vence──▶ cola
  └──rechazo sin reencolar (veneno o reintentos agotados)──▶ pedidos360.dlx ──▶ cola.dlq
```

| Intento | Cola | Espera |
|---|---|---|
| 1 | `<cola>.retry.1` | 5 s |
| 2 | `<cola>.retry.2` | 30 s |
| 3 | `<cola>.retry.3` | 2 min |
| agotado | `<cola>.dlq` | — |

Hay una cola por nivel y no un TTL por mensaje en una sola cola porque RabbitMQ sólo expira la cabeza de la cola: un mensaje de 2 minutos bloquearía a los de 5 segundos que tiene detrás.

| Situación | Destino |
|---|---|
| Procesado | `ack` |
| Rechazo de negocio del catálogo (stock insuficiente, producto inexistente o inactivo) | Se publica `stock.reserva-fallida` y `ack`: es una respuesta, no un error |
| Veneno: content type no JSON, JSON ilegible, sin `orderId`, pedido inexistente, `outcome` desconocido | DLQ inmediata, sin reintentos |
| Fallo transitorio (base caída, broker sin confirmar) | Siguiente cola de retardo; agotadas, DLQ |
| No se puede programar el reintento | `nack` con reencolado: vuelve a la cola original |

### Ver la DLQ

El management UI se publica sólo en loopback. En local: `http://127.0.0.1:15672`, con las credenciales de `.env`. En el servidor, por un túnel SSH:

```bash
ssh -i <llave> -L 15672:127.0.0.1:15672 ubuntu@<host>
# y abrir http://127.0.0.1:15672 en el navegador local
```

`Queues` → `<cola>.dlq` → `Get messages` muestra el cuerpo y, en los logs del servicio, la línea `Mensaje <id> de <cola> enviado a la DLQ: <motivo>` explica por qué.

Para reprocesar mensajes de una DLQ, el management UI ofrece `Move messages` cuando están habilitados los plugins `rabbitmq_shovel` y `rabbitmq_shovel_management`. **Este procedimiento no se verificó** en este proyecto; antes de reprocesar, corregir la causa: reenviar un mensaje veneno lo devuelve a la DLQ.

## Variables de entorno

Agregar a `.env` (no se versiona):

```dotenv
# --- RabbitMQ ---
RABBITMQ_USERNAME=pedidos360
RABBITMQ_PASSWORD=<una clave larga>
RABBITMQ_VHOST=pedidos360
RABBITMQ_MANAGEMENT_PORT=15672
RABBITMQ_2_MANAGEMENT_PORT=15673
RABBITMQ_ERLANG_COOKIE=<una cadena larga y aleatoria, igual en los dos nodos>
MESSAGING_ADMIN_PORT=8085
```

`docker-compose.yml` las exige con `:?` y se las pasa a `rabbitmq-1`, `rabbitmq-2`, `catalog`, `orders` y `messaging-admin`. Opcionales en `orders`: `OUTBOX_PUBLISHER_ENABLED` (por defecto `true`), `OUTBOX_PUBLISHER_DELAY_MS` (`2000`), `OUTBOX_CONFIRM_TIMEOUT_MS` (`5000`).

> `RABBITMQ_DEFAULT_USER`/`PASS` sólo se aplican cuando los volúmenes `pedidos360_rabbitmq-1-data` y `pedidos360_rabbitmq-2-data` están vacíos. Cambiar las credenciales en `.env` después del primer arranque **no** cambia el usuario del broker: hay que cambiarlo con `rabbitmqctl` o recrear el volumen.

## Ejecución local

```bash
docker compose up -d rabbitmq-1 rabbitmq-2   # sólo el clúster
docker compose up -d                         # todo el stack
```

Con el perfil `local` de cada servicio (`mvn spring-boot:run`), la conexión usa `RABBITMQ_HOST`/`PORT`/`USERNAME`/`PASSWORD`/`VHOST`; sin broker, la API HTTP funciona, `/actuator/health` informa `DOWN` y los eventos esperan en la outbox.

`mvn test` **no** necesita broker: `src/test/resources/application.properties` desactiva conexión, declaración de topología, listeners y publicador.

## Clúster de dos nodos

`rabbitmq-1` y `rabbitmq-2` forman un clúster al arrancar (`rabbitmq/rabbitmq.conf`):

- **Descubrimiento `classic_config`**: cada nodo conoce al otro por nombre (`rabbit@rabbitmq-1`, `rabbit@rabbitmq-2`). El nombre sale del `hostname` del contenedor, por eso está fijado en el compose.
- **Erlang cookie compartida** (`RABBITMQ_ERLANG_COOKIE`): es el secreto que autoriza a un nodo a unirse. Debe ser igual en ambos y no se versiona.
- **Particiones con `autoheal`**: con dos nodos no hay mayoría posible. `pause_minority` pausaría también al nodo que sobrevive; se probó y el clúster quedaba sin nodos activos. `autoheal` mantiene vivo al nodo restante y, tras un corte de red, reinicia el lado con menos clientes.
- **Clientes con las dos direcciones** (`SPRING_RABBITMQ_ADDRESSES=rabbitmq-1:5672,rabbitmq-2:5672`): si un nodo cae, Spring se reconecta al otro y vuelve a declarar su topología.
- **Replicación**: las colas del sistema son `classic` y viven en un nodo. Las colas `QUORUM` creadas con el administrador se replican en los dos (`members: [rabbit@rabbitmq-1, rabbit@rabbitmq-2]`). Una cola quorum **necesita mayoría para crearse**: con un nodo caído, el administrador responde `503` y no deja restos.

```bash
docker compose exec rabbitmq-1 rabbitmqctl cluster_status        # Running Nodes: los dos
docker compose exec rabbitmq-1 rabbitmqctl list_queues -p pedidos360 name type members
```

## Microservicio administrador

`messaging-admin/` expone una API REST para crear, consultar y eliminar colas, exchanges y bindings sin entrar a la consola de RabbitMQ. Ver [`messaging-admin/README.md`](../messaging-admin/README.md). Nunca toca los nombres del sistema (`stock.`, `orders.`, `pedidos360.`), así que no puede romper el procesamiento de pedidos.

## Seguridad

- AMQP (5672) no se publica al host: sólo es accesible dentro de la red de Docker.
- La API del administrador (8085) se publica sólo en `127.0.0.1`: puede eliminar colas y no tiene autenticación propia.
- El management UI (15672 y 15673) se publica sólo en `127.0.0.1`. Como Docker no publica puertos de un contenedor atado únicamente a redes `internal: true`, el broker está también en `egress`.
- Credenciales sólo por `.env`. TLS desactivado: el tráfico AMQP no sale de la red interna de Docker.

## Verificación realizada

RabbitMQ 3.13 real, con `orders` y `catalog` levantados con el perfil `local` (H2):

| Escenario | Resultado |
|---|---|
| Aceptar responde sin esperar al catálogo; la reserva se confirma después | `stockReserved` pasa a `true` en ~1 s; stock baja una vez |
| `pedido.aceptado` duplicado | No descuenta dos veces |
| Stock insuficiente | Pedido `CANCELADO` con `cancellationReason`; stock intacto |
| Cancelar un pedido reservado | Stock restituido |
| Broker detenido 12 s | Aceptar responde `200`; 16 fallos de publicación registrados; al volver, se reserva una sola vez (8,1 s) |
| Mensaje `text/plain` para el catálogo | `stock.reserve.dlq` |
| Resultado para un pedido inexistente | `orders.stock-result.dlq` |
| Aceptar y cancelar enseguida, 6 veces | Sin stock inmovilizado; 6 reservas huérfanas detectadas y liberadas |
| Colas principales al terminar | Vacías |

Clúster y administrador (2026-10-28, proyecto de compose aislado, `orders`, `catalog` y `messaging-admin` conectados a los dos nodos):

| Escenario | Resultado |
|---|---|
| Arranque desde cero | `Running Nodes: rabbit@rabbitmq-1, rabbit@rabbitmq-2` |
| Servicios contra el clúster | Arrancan sin errores AMQP; tras borrar los volúmenes se reconectan y redeclaran sus 15 colas |
| Cola `QUORUM` creada por el administrador | Réplicas en los dos nodos |
| Nodo 1 detenido | Nodo 2 sigue activo; el administrador lee colas por el nodo 2 (`200`) y crea colas `classic` (`201`) |
| Cola `QUORUM` con un nodo caído | `503` con mensaje claro, sin cola ni DLQ a medias |
| Nodo 1 vuelve | Se reintegra solo al clúster |

No se verificó: el camino de reintento transitorio contra el broker real (cubierto por tests unitarios), ni el despliegue en EC2.

## Despliegue en EC2: pendiente

El override `deploy/ec2/docker-compose.ec2.yml` vive sólo en el servidor. Para desplegar hace falta agregar el bloque `RABBITMQ_*` al `.env` del servidor y revisar que el override no contradiga la red ni los puertos del broker. El broker suma un contenedor a la instancia; su consumo de memoria no se midió en este proyecto y conviene revisarlo con `docker stats` después del primer despliegue.

## Desviaciones respecto de la directriz de arquitectura

| Directriz | Implementado | Motivo |
|---|---|---|
| EMQX como broker por defecto | RabbitMQ | La propia directriz asigna "trabajo entre microservicios" a RabbitMQ; el caso es `orders → catalog`. EMQX/MQTT no ofrece DLQ ni ack de aplicación nativos en su edición open source. |
| Tópico `pedidos/creado` | `pedido.aceptado` | En este dominio un pedido `CREADO` no está aprobado: reservar al crear permitiría a cualquier cliente inmovilizar inventario. El nombre vive en una constante. |
| Tópico `stock/reservado` | `stock.reservado` y `stock.reserva-fallida` | AMQP usa `.` como separador; el rechazo necesita su propio evento para compensar. |
| Autenticación JWT en el broker | Usuario y clave por `.env` | JWT en RabbitMQ requiere el plugin OAuth 2; el broker no se expone fuera de la red de Docker. |
| Sprint 2: evaluar Mosquitto | No realizado | Fuera del alcance de esta entrega. |
