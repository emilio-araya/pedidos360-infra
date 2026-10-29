# messaging-admin

API REST que administra colas, exchanges y bindings de RabbitMQ para Pedidos360.
Corre junto al clúster de dos nodos y nunca toca los nombres de los que depende
el sistema en marcha.

## Diseño

| Capa | Clase | Responsabilidad |
|---|---|---|
| Web | `MessagingAdminController` | Rutas, verbos HTTP y códigos de estado. No conoce RabbitMQ. |
| Web | `dto/*Request` | Validación de entrada con anotaciones de Bean Validation. |
| Web | `AdminErrorHandler` | Cada falla se responde como `ProblemDetail` (RFC 9457) con un mensaje claro. |
| Servicio | `RabbitAdminService` | Única clase que habla con `AmqpAdmin`: declara, consulta y elimina. |
| Configuración | `MessagingAdminProperties` + `application.yml` | Todos los nombres de RabbitMQ en un solo lugar (`app.messaging.admin.*`). |

## API

Ruta base: `/api/admin`. Errores: `400` entrada inválida, `404` cola inexistente,
`409` recurso existente o del sistema, `503` broker no disponible.

| Método | Ruta | Cuerpo / parámetros | Resultado |
|---|---|---|---|
| `POST` | `/queues` | `{"name", "durable"?, "deadLetter"?, "type"?, "messageTtlMs"?, "maxLength"?}` | `201` + `Location` |
| `GET` | `/queues/{name}` | — | `200` `{name, messageCount, consumerCount}` |
| `DELETE` | `/queues/{name}` | — | `204`; también elimina su `.dlq` |
| `POST` | `/exchanges` | `{"name", "type": "DIRECT"\|"TOPIC"\|"FANOUT"\|"HEADERS", "durable"?}` | `201` |
| `DELETE` | `/exchanges/{name}` | — | `204` |
| `POST` | `/bindings` | `{"exchange", "queue", "routingKey"}` | `201` |
| `DELETE` | `/bindings` | `?exchange=&queue=&routingKey=` | `204` |

Valores por defecto de una cola nueva: durable, `classic` y con una cola de
mensajes muertos `<nombre>.dlq` enlazada al exchange directo
`pedidos360.admin.dlx`. Las colas `QUORUM` se replican en los dos nodos del
clúster y deben ser durables.

### Validación

- Nombres: obligatorios, hasta 200 caracteres, empiezan con una letra o un
  número y siguen con letras, números, `.`, `-` o `_`. El prefijo `amq.` lo
  reserva RabbitMQ.
- `messageTtlMs`: de 1 ms a 7 días. `maxLength`: de 1 a 1.000.000 mensajes.
- `type`: un valor desconocido se rechaza indicando los permitidos.
- Routing keys: letras, números, `.`, `-`, `_`, `*` y `#`; vacía para fanout.

### Nombres protegidos

Los nombres que empiezan con `stock.`, `orders.` o `pedidos360.` pertenecen al
sistema (por ejemplo `stock.reserve`). Se pueden consultar, pero nunca crear,
modificar ni eliminar (`409`). Las colas nuevas sí pueden enlazarse a
`pedidos360.events`.

## Ejemplos

```bash
A=http://localhost:8085/api/admin
curl -i -X POST $A/queues -H 'Content-Type: application/json' \
  -d '{"name":"notifications.email","type":"QUORUM"}'
curl -i -X POST $A/exchanges -H 'Content-Type: application/json' \
  -d '{"name":"tickets.events","type":"TOPIC"}'
curl -i -X POST $A/bindings -H 'Content-Type: application/json' \
  -d '{"exchange":"pedidos360.events","queue":"notifications.email","routingKey":"pedido.*"}'
curl -s $A/queues/stock.reserve
curl -i -X DELETE $A/queues/stock.reserve        # 409: cola del sistema
curl -i -X POST $A/queues -H 'Content-Type: application/json' -d '{"name":""}'   # 400
```

## Ejecución

```bash
docker compose up -d rabbitmq-1 rabbitmq-2 messaging-admin   # desde pedidos360-infra
# o contra un clúster ya levantado:
RABBITMQ_ADDRESSES=localhost:5672 RABBITMQ_USERNAME=... RABBITMQ_PASSWORD=... \
RABBITMQ_VHOST=pedidos360 mvn spring-boot:run
mvn test   # no necesita broker: AmqpAdmin es un doble de prueba
```

## Seguridad

La API puede eliminar colas y no tiene autenticación propia, por eso el compose
la publica sólo en `127.0.0.1`. Exponerla fuera del host sólo detrás de un
gateway que autentique.
