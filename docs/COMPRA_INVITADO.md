# Compra como invitado

El cliente compra sin iniciar sesión. Al confirmar el pedido deja solo su correo, que se usa para enviarle el voucher. Con un enlace secreto puede ver, seguir y cancelar su pedido. Microsoft Entra ID queda para el personal (Operador y Admin).

## Flujo

1. El frontend envía `POST /api/guest-orders` sin `Authorization`, con `email`, `items` y `notes` opcional.
2. `orders` calcula los precios con el catálogo, guarda el pedido y responde `201` con `trackingToken`. Es la única respuesta que lo incluye.
3. Después del commit, `orders` envía el voucher por SMTP con el enlace `<FRONTEND_PUBLIC_URL>/seguimiento/<token>`.
4. El cliente abre el enlace: `GET /api/guest-orders/{token}` muestra estado, ítems, total y motivo de cancelación.
5. Mientras el pedido está en `CREADO`, puede cancelarlo con `POST /api/guest-orders/{token}/cancel`.
6. Si pierde el enlace, pide uno nuevo con `POST /api/guest-orders/link-requests` (`email` y `orderNumber`).

El contrato completo está en [`infra/aws/openapi.yaml`](../infra/aws/openapi.yaml), etiqueta `GuestOrders`.

| Ruta | Respuesta correcta | Errores |
|---|---|---|
| `POST /api/guest-orders` | `201` con `trackingToken` | `400` con `errors[{field,message}]`, `429` |
| `GET /api/guest-orders/{token}` | `200` | `404` |
| `POST /api/guest-orders/{token}/cancel` | `200` | `404`, `409` si no está en `CREADO` |
| `POST /api/guest-orders/link-requests` | `202` sin cuerpo, siempre | `400` si el formato es inválido, `429` |

## Modelo de seguridad

- **El token es la credencial.** Tiene 256 bits aleatorios, en 64 caracteres hexadecimales. Quien tiene el enlace puede ver y cancelar el pedido, igual que con un enlace de "ver pedido" de cualquier tienda.
- **En la base solo se guarda el hash.** `orders` guarda el SHA-256 del token, nunca el token en claro: ni en la tabla de pedidos, ni en la outbox, ni en los logs, ni en RabbitMQ. Por eso el correo lo envía `orders` directamente después del commit y no pasa por la outbox.
- **El enlace se muestra una sola vez.** Aparece en la confirmación del checkout y en el correo. Ninguna otra respuesta lo devuelve.
- **El reenvío rota el token.** Cada solicitud válida de reenvío genera un token nuevo y reemplaza el hash; el enlace anterior deja de funcionar.
- **Sin enumeración.** Un token inválido o desconocido responde `404` sin distinguir el caso. El reenvío responde `202` exista o no el pedido, así no revela qué correos compraron.
- **Vista mínima.** La respuesta pública nunca expone el id interno, `customerId`, `stockReserved` ni el correo completo (solo `maskedEmail`, por ejemplo `c***@example.com`).
- **El personal no cambia.** `/api/orders` y `/aws/api/orders` siguen exigiendo token.

## Correo en local: Mailpit

El Compose levanta Mailpit (`axllent/mailpit`), un servidor SMTP de pruebas que atrapa todos los mensajes y no los entrega a buzones reales.

| Puerto | Uso | Alcance |
|---|---|---|
| `1025` | SMTP | Solo dentro de las redes de Docker. No se publica en el host. |
| `8025` | Interfaz web | Solo en `127.0.0.1` del host. |

Para ver los correos enviados, abre:

```text
http://127.0.0.1:8025
```

Si el puerto está ocupado, define `MAILPIT_UI_PORT` en `.env`.

## Variables de entorno de `orders`

Todas se leen del entorno. Ninguna credencial queda escrita en el Compose ni en el repositorio.

| Variable | Valor local por defecto | Uso |
|---|---|---|
| `MAIL_HOST` | `mailpit` | Servidor SMTP. |
| `MAIL_PORT` | `1025` | Puerto SMTP. |
| `MAIL_USERNAME` | vacío | Usuario SMTP. Vacío: sin autenticación. |
| `MAIL_PASSWORD` | vacío | Clave SMTP. |
| `MAIL_FROM` | `no-reply@pedidos360.local` | Remitente del voucher. |
| `FRONTEND_PUBLIC_URL` | `http://localhost:4200` | Base del enlace de seguimiento. |
| `MAILPIT_UI_PORT` | `8025` | Puerto del host para la interfaz de Mailpit (solo Compose). |

Si el SMTP falla, el pedido igual queda creado y la respuesta `201` no cambia. El fallo queda en el log de `orders`, sin el token. El cliente conserva el enlace de la confirmación y puede pedir otro con el reenvío.

## Correo en AWS: Amazon SES por SMTP

En AWS las mismas variables apuntan al SMTP de Amazon SES. No hace falta cambiar código.

| Variable | Valor |
|---|---|
| `MAIL_HOST` | `email-smtp.<region>.amazonaws.com`, por ejemplo `email-smtp.us-east-1.amazonaws.com` |
| `MAIL_PORT` | `587` (STARTTLS) |
| `MAIL_USERNAME` | Usuario SMTP de SES |
| `MAIL_PASSWORD` | Clave SMTP de SES |
| `MAIL_FROM` | Una dirección o dominio verificado en SES |
| `FRONTEND_PUBLIC_URL` | URL pública del frontend (CloudFront) |

Puntos a tener en cuenta:

- **Credenciales SMTP de SES.** Se generan en la consola de SES ("SMTP settings"). No son las access keys de AWS. Guárdalas en Secrets Manager e inyéctalas como secretos del task de ECS, nunca como variables en texto plano.
- **STARTTLS.** El puerto `587` exige STARTTLS. La configuración de Spring Mail en `orders` debe activarlo y exigirlo para este puerto.
- **Modo sandbox.** Una cuenta nueva de SES está en sandbox: solo envía a identidades verificadas y con cuotas bajas. Para enviar a cualquier cliente hay que pedir la salida del sandbox.
- **Remitente verificado.** `MAIL_FROM` debe pertenecer a una identidad verificada en SES, o SES rechaza el envío.

La definición del task de `orders` en `infra/aws/terraform/ecs.tf` todavía no incluye estas variables. Se agregan cuando el runtime de ECS se aplique.

## API Gateway

Las rutas públicas no usan authorizer (`authorization_type = "NONE"`). Están en `local.public_route_keys` de `infra/aws/terraform/main.tf`:

- `GET /api/catalog/products`
- `GET /api/catalog/products/{id}`
- `POST /api/guest-orders`
- `GET /api/guest-orders/{token}`
- `POST /api/guest-orders/{token}/cancel`
- `POST /api/guest-orders/link-requests`

Las lecturas del catálogo ya eran públicas en el BFF, pero en AWS exigían JWT. Ahora coinciden. El resto de `/api/**` sigue con el authorizer de Entra y todo `/aws/api/**` con el de Cognito, sin cambios.

Las dos lecturas del catálogo ya existían como rutas de Entra. Terraform las mueve en el estado con bloques `moved`, así el cambio es una actualización en sitio y API Gateway no rechaza una route key duplicada.

### Throttling

| Ruta | Burst | Rate (req/s) |
|---|---|---|
| `POST /api/guest-orders` | 5 | 1 |
| `POST /api/guest-orders/link-requests` | 5 | 1 |
| Resto de rutas (por defecto del stage) | 100 | 50 |

Al superar el límite, API Gateway responde `429` sin llegar al BFF.

**Importante:** en una HTTP API el throttling por ruta es un cupo compartido por todos los clientes de esa ruta, no un límite por IP. Protege al backend y al SMTP de una avalancha, pero un solo cliente abusivo puede agotar el cupo para los demás. Un límite por IP requeriría AWS WAF, que no se asocia directamente a una HTTP API; queda fuera de alcance, junto con el CAPTCHA.
