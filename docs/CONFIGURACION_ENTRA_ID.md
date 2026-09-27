# Configuración de Microsoft Entra ID

> Esta guía cubre exclusivamente el proveedor de las rutas `/api/**`. Las rutas `/aws/api/**` usan Amazon Cognito y se configuran en [`CONFIGURACION_COGNITO.md`](CONFIGURACION_COGNITO.md). No se intercambian tokens, audiences ni callbacks.

Esta guía separa los dos registros que requiere Pedidos360:

1. una SPA para el frontend React;
2. una API que expone el scope delegado usado por esa SPA.

Esta guía está configurada para el tenant `EmilioAzure` usado por Pedidos360. Si posteriormente se cambia el tenant o los registros, deben actualizarse los identificadores, issuer, audiencia y scope.

## Valores que se deben obtener

| Valor | Uso |
|---|---|
| Tenant ID | `1feca74f-8331-414a-bd8d-2d687b22a7b3` |
| `frontend-pedidos360` — Application (client) ID | `19c346ad-cb86-4cc7-8ccf-23d36bb60ebf` |
| `pedidos360-api` — Application (client) ID | `150f51db-4084-4979-b1a1-e6a6e7893a01` — **audience** esperada. |
| Application ID URI de la API | `api://150f51db-4084-4979-b1a1-e6a6e7893a01` |
| Scope | `api://150f51db-4084-4979-b1a1-e6a6e7893a01/access_as_user` |
| Issuer | `https://login.microsoftonline.com/1feca74f-8331-414a-bd8d-2d687b22a7b3/v2.0` |
| JWKS | `https://login.microsoftonline.com/1feca74f-8331-414a-bd8d-2d687b22a7b3/discovery/v2.0/keys` |

No se confunden la audiencia y el scope:

- `aud` debe contener el **Application (client) ID** de `pedidos360-api`;
- el frontend solicita el scope delegado `api://150f51db-4084-4979-b1a1-e6a6e7893a01/access_as_user`.

## Compatibilidad de issuer y audience

La configuración local acepta únicamente dos formatos exactos para esta API: el formato v2 de Entra (`https://login.microsoftonline.com/{tenant}/v2.0` y GUID del cliente) y el formato v1 que puede emitir el registro actual (`https://sts.windows.net/{tenant}/` y `api://{client-id}`). No se aceptan comodines ni otros tenants.

Para AWS se debe configurar el registro `pedidos360-api` para solicitar access tokens v2 en su manifiesto (`requestedAccessTokenVersion: 2`), o alinear el authorizer de API Gateway con el formato que realmente emita ese registro. La compatibilidad local no cambia el requisito de usar API Gateway como única entrada pública.

## 1. Registrar la aplicación frontend SPA

En **Microsoft Entra admin center → App registrations → New registration**:

1. Name: `frontend-pedidos360`.
2. Supported account types: usar el tenant de Pedidos360.
3. No se requiere secreto de cliente para una SPA pública.
4. En **Authentication → Add a platform → Single-page application**, registrar exactamente:
   - local: `http://localhost:4200/login`;
   - AWS: `https://<host-frontend>/login`.
5. No usar un redirect URI de tipo **Web** para la SPA.
6. No añadir query strings, fragmentos ni barras finales distintas de las registradas. El redirect URI efectivo debe coincidir exactamente con MSAL.

En **API permissions → Add a permission → Microsoft platform → My APIs**, seleccionar `pedidos360-api` y el permiso delegado:

```text
api://150f51db-4084-4979-b1a1-e6a6e7893a01/access_as_user
```

No usar un permiso de aplicación para el flujo de usuario de React. Tras el primer uso, un administrador del tenant debe conceder admin consent si la organización lo exige.

### Configuración del frontend

```dotenv
ENTRA_TENANT_ID=1feca74f-8331-414a-bd8d-2d687b22a7b3
ENTRA_CLIENT_ID=19c346ad-cb86-4cc7-8ccf-23d36bb60ebf
API_SCOPE=api://150f51db-4084-4979-b1a1-e6a6e7893a01/access_as_user
API_BASE_URL=http://localhost:8080
REDIRECT_URI=http://localhost:4200/login
POST_LOGOUT_REDIRECT_URI=http://localhost:4200/login
```

En AWS, `API_BASE_URL` debe ser el endpoint HTTPS entregado por API Gateway. `REDIRECT_URI` y `POST_LOGOUT_REDIRECT_URI` deben usar el origen HTTPS real del frontend. No se publica la URL privada del BFF al navegador.

> React y Vite compilan la configuración según la implementación del proyecto. Si la imagen final es estática, los valores deben estar disponibles durante el build o mediante un mecanismo de configuración en tiempo de ejecución seguro; asignar solo una variable del contenedor no cambia un bundle ya construido.

## 2. Registrar la API

Crear otra app registration:

1. Name: `pedidos360-api`.
2. Supported account types: usar el tenant de Pedidos360.
3. En **Expose an API**:
   - Application ID URI: `api://150f51db-4084-4979-b1a1-e6a6e7893a01`;
   - Nombre del scope: `access_as_user`;
   - scope completo: `api://150f51db-4084-4979-b1a1-e6a6e7893a01/access_as_user`;
   - descripción: acceso delegado a Pedidos360.
4. Guardar el **Application (client) ID** de esta API. Ese GUID es `ENTRA_API_AUDIENCE` y la audiencia que deben validar API Gateway, BFF y microservicios.
5. No configurar redirect URI para la API: la consumirán el frontend y los servicios, no un navegador en nombre de ella.
6. Esta solución no usa credenciales de cliente ni flujo de credenciales de aplicación; no se debe distribuir un secreto a React.

La API debe tener habilitada la asignación de roles de aplicación a usuarios o grupos.

## 3. Crear los roles de aplicación

En la app registration `pedidos360-api`, crear exactamente estos **valores** de app role:

| Valor | Visible | Asignable a |
|---|---|---|
| `Admin` | Sí | Users |
| `Operador` | Sí | Users |
| `Cliente` | Sí | Users |

Los valores son sensibles a mayúsculas y minúsculas. Se pueden asignar a usuarios o grupos desde **Enterprise applications → pedidos360-api → Users and groups**. Tras asignar un rol puede ser necesario iniciar sesión de nuevo o forzar renovación del token.

La API usa el claim `roles`. Identidad de cliente y autorización provienen de `oid` o `sub` y de `roles`; un nombre de rol en UI no sustituye una asignación real.

### Capacidades

- `Admin` y `Operador` pueden mantener productos y stock, y ejecutar transiciones operacionales.
- `Cliente` puede crear pedidos, consultar los propios y cancelarlos solo mientras estén en `CREADO`.
- Todos los roles pueden ver el dashboard y los productos necesarios para crear un pedido.
- Un `Cliente` no navega al catálogo administrativo ni ve todos los pedidos.

## 4. Configurar los servicios

Valores comunes de BFF y microservicios:

```dotenv
ENTRA_ISSUER=https://login.microsoftonline.com/1feca74f-8331-414a-bd8d-2d687b22a7b3/v2.0
ENTRA_API_AUDIENCE=150f51db-4084-4979-b1a1-e6a6e7893a01
```

BFF:

```dotenv
JWT_MODE=entra
ORDERS_SERVICE_URL=http://<url-privada-orders>
CATALOG_SERVICE_URL=http://<url-privada-catalog>
CORS_ALLOWED_ORIGINS=https://<host-frontend>
```

API Gateway usa el mismo issuer y audience para el authorizer JWT. CORS debe permitir únicamente el origen exacto del frontend, por ejemplo `https://pedidos360.example.com`; no usar `*` con credenciales.

El perfil Spring `local` puede usar JWT HMAC solo para desarrollo. **Nunca activar HMAC en local dentro de una imagen cloud**; en AWS se usa `JWT_MODE=entra` y validación de firma contra JWKS.

## 5. Obtener un token de usuario para pruebas con `curl`

La opción recomendada para la aplicación es el login de MSAL en la SPA. Para una prueba puntual por terminal, Azure CLI obtiene un token para la sesión de usuario actual:

```bash
export ENTRA_TENANT_ID=1feca74f-8331-414a-bd8d-2d687b22a7b3

az login --tenant "$ENTRA_TENANT_ID" --use-device-code
TOKEN="$(az account get-access-token \
  --tenant "$ENTRA_TENANT_ID" \
  --scope api://150f51db-4084-4979-b1a1-e6a6e7893a01/access_as_user \
  --query accessToken \
  --output tsv)"
```

`az account get-access-token` representa al usuario que inició sesión. Para las pruebas de `Admin`, `Operador` o `Cliente`, ese usuario debe tener el rol correspondiente. No se deben reemplazar estas pruebas por un client secret de la SPA.

Comprobar `aud`, `roles` y el identificador sin imprimir el token completo:

```bash
python3 - "$TOKEN" <<'PY'
import base64
import json
import sys

parts = sys.argv[1].split(".")
payload = parts[1] + "=" * (-len(parts[1]) % 4)
claims = json.loads(base64.urlsafe_b64decode(payload))
print(json.dumps(
    {key: claims.get(key) for key in ("aud", "iss", "oid", "sub", "scp", "roles")},
    indent=2,
))
PY
```

El resultado debe tener `aud` igual a `150f51db-4084-4979-b1a1-e6a6e7893a01` o `api://150f51db-4084-4979-b1a1-e6a6e7893a01`, `iss` igual al issuer v2 o al issuer v1 exacto del tenant y el rol esperado dentro de `roles`.

## 6. Probar los endpoints documentados

En local con Docker Compose, el BFF se escucha únicamente en loopback:

```bash
export BASE_URL=http://localhost:8080
```

En AWS se usa exclusivamente el output de API Gateway:

```bash
export BASE_URL="$(terraform -chdir=infra/aws/terraform output -raw api_endpoint)"
```

Definir IDs solo cuando existan:

```bash
export PRODUCT_ID=<UUID_DE_PRODUCTO>
export ORDER_ID=<UUID_DE_PEDIDO>
```

### JWT y CORS

```bash
curl -i "$BASE_URL/api/orders"
```

Se espera `401` por token ausente. Con un token de otra API, expirado o manipulado también se espera `401`.

```bash
curl -i -X OPTIONS "$BASE_URL/api/orders" \
  -H "Origin: https://<host-frontend>" \
  -H "Access-Control-Request-Method: GET" \
  -H "Access-Control-Request-Headers: authorization,content-type"
```

La respuesta debe permitir el origen exacto configurado y los métodos/headers del contrato.

### Catálogo

```bash
curl -i "$BASE_URL/api/catalog/products" \
  -H "Authorization: Bearer $TOKEN"

curl -i "$BASE_URL/api/catalog/products/$PRODUCT_ID" \
  -H "Authorization: Bearer $TOKEN"
```

Crear y actualizar como `Admin` u `Operador`:

```bash
curl -i -X POST "$BASE_URL/api/catalog/products" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  --data '{
    "sku": "P-001",
    "name": "Mouse",
    "description": "Mouse inalámbrico",
    "price": 19990.00,
    "stock": 25,
    "active": true
  }'

curl -i -X PUT "$BASE_URL/api/catalog/products/$PRODUCT_ID" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  --data '{
    "sku": "P-001",
    "name": "Mouse",
    "description": "Mouse inalámbrico",
    "price": 19990.00,
    "stock": 25,
    "active": true
  }'
```

Administrar stock y eliminar requieren `Admin` u `Operador`:

```bash
curl -i -X PATCH "$BASE_URL/api/catalog/products/$PRODUCT_ID/stock" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  --data @stock.json

curl -i -X DELETE "$BASE_URL/api/catalog/products/$PRODUCT_ID" \
  -H "Authorization: Bearer $TOKEN"
```

El cuerpo de stock es un valor absoluto no negativo:

```json
{ "stock": 25 }
```

### Pedidos

```bash
curl -i "$BASE_URL/api/orders" \
  -H "Authorization: Bearer $TOKEN"

curl -i "$BASE_URL/api/orders/$ORDER_ID" \
  -H "Authorization: Bearer $TOKEN"

curl -i -X POST "$BASE_URL/api/orders" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  --data '{
    "items": [{ "productId": "'"$PRODUCT_ID"'", "quantity": 2 }],
    "notes": "Entregar después de las 18:00"
  }'
```

Actualizar y cancelar:

```bash
curl -i -X PUT "$BASE_URL/api/orders/$ORDER_ID" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  --data @order-update.json

curl -i -X DELETE "$BASE_URL/api/orders/$ORDER_ID" \
  -H "Authorization: Bearer $TOKEN"
```

`order-update.json` usa el mismo formato de creación y solo se acepta mientras el pedido continúa en `CREADO`. `DELETE` cancela únicamente cuando el pedido sigue en `CREADO`.

Cambiar estado como `Admin` u `Operador`:

```bash
curl -i -X PATCH "$BASE_URL/api/orders/$ORDER_ID/status" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  --data '{ "status": "ACEPTADO" }'
```

Las transiciones inválidas deben ser rechazadas. En particular, no se puede despachar un pedido que no esté en `EN_PREPARACION`.

### Matriz negativa recomendada

| Caso | Resultado esperado |
|---|---|
| Sin Bearer | `401` |
| Firma/issuer/audience/exp/nbf inválidos | `401` |
| `Cliente` intenta crear, editar o eliminar producto | `403` |
| `Cliente` intenta una transición operacional | `403` |
| `Cliente` consulta o cancela pedido ajeno | `403` o recurso no visible, sin fuga de datos |
| `Cliente` cancela pedido que ya no está en `CREADO` | `409` o rechazo de negocio equivalente |
| Se salta `ACEPTADO` para despachar | Rechazo de transición |
| Se ejecuta una reserva dos veces con el mismo `orderId` | No duplica descuento de stock |
| Se cancela un pedido con stock reservado | Devuelve el stock una sola vez |

## 7. Probar la SPA

1. Confirmar que la URI de redirección de `http://localhost:4200/login` está guardada para desarrollo.
2. En AWS, guardar solo la URI HTTPS definitiva.
3. Solicitar `api://150f51db-4084-4979-b1a1-e6a6e7893a01/access_as_user` mediante MSAL.
4. Verificar en DevTools que el access token se envía como `Authorization: Bearer ...` y que `aud` es el client ID de la API.
5. Probar cada rol con usuarios asignados.
6. Comprobar que un `Cliente` no ve navegación administrativa, pero sí puede llegar a productos para crear su pedido.

Nunca registrar en evidencias el token, secretos de base de datos ni secretos de Entra.
