# Configuración de Amazon Cognito

> **Alcance.** Esta guía documenta la segunda cadena de seguridad del backend: Amazon Cognito para `/aws/api/**`. La SPA no la usa; su único proveedor es Microsoft Entra ID en `/api/**`. Ver `GUIA_CASO_0_PEDIDOS360.md`, sección "Alcance de la identidad".

Pedidos360 usa dos cadenas de seguridad con namespaces separados, aunque solo Entra tiene cliente en la interfaz:

| Namespace | Proveedor | Issuer | Rutas | Cliente |
|---|---|---|---|---|
| `/api/**` | Microsoft Entra ID | `https://login.microsoftonline.com/1feca74f-8331-414a-bd8d-2d687b22a7b3/v2.0` | `/api/**` | SPA React |
| `/aws/api/**` | Amazon Cognito | `https://cognito-idp.us-east-1.amazonaws.com/us-east-1_example` | `/aws/api/**` | solo backend |

Un token de un proveedor no se acepta en el namespace del otro. API Gateway, BFF, Catalog y Orders tienen authorizers/validadores separados.

## Valores públicos

Estos identificadores no son secretos y pueden estar en variables de GitHub Actions o en el entorno del backend:

```dotenv
COGNITO_USER_POOL_ID=us-east-1_example
COGNITO_USER_POOL_CLIENT_ID=example-client-id
COGNITO_ISSUER=https://cognito-idp.us-east-1.amazonaws.com/us-east-1_example
COGNITO_JWK_SET_URI=https://cognito-idp.us-east-1.amazonaws.com/us-east-1_example/.well-known/jwks.json
COGNITO_API_AUDIENCE=example-client-id
COGNITO_AUTHORIZATION_SCOPES=["openid"]
```

`COGNITO_API_AUDIENCE` es el App Client público en la configuración baseline. Los servicios aceptan el `client_id` cuando el access token no trae `aud`, pero siempre validan issuer, JWKS, audience/client ID y `token_use=access`. Las rutas Cognito de API Gateway también exigen el scope `openid`, lo que ayuda a que un ID token sin `scope` no sea aceptado en la entrada pública.

## App Client

El App Client define el audience de la cadena; la aplicación no inicia ningún flujo OAuth con él. En la configuración baseline:

1. **OAuth grant types**: `Authorization code grant`, solo si se habilitará un cliente externo de pruebas.
2. **PKCE**: `S256`.
3. **Client secret**: desactivado.
4. **OAuth scopes**: `openid`, `email` y `profile`.
5. **Callbacks**: no se registran callbacks de la SPA, porque el frontend ya no redirige a Cognito.

No agregar un client secret a Docker, GitHub, Terraform, `.env` versionado ni al bundle React.

## Grupos y roles

Los grupos deben existir con mayúsculas exactas:

- `Admin`
- `Operador`
- `Cliente`

Asignar cada usuario a uno de esos grupos. El claim `cognito:groups` del **access token** se convierte en `ROLE_Admin`, `ROLE_Operador` o `ROLE_Cliente`. Un grupo desconocido no concede permisos. Se recomienda un solo `Admin`; los demás usuarios deben ser `Operador` o `Cliente`.

## Emisión de tokens para pruebas

La SPA no inicia sesión con Cognito: no hay botón, callback ni almacenamiento de tokens de este proveedor. Para ejercitar la cadena del backend se obtiene un access token de prueba fuera de la aplicación, por ejemplo con `aws cognito-idp admin-initiate-auth` sobre un usuario del User Pool, y se envía como `Authorization: Bearer` contra `/aws/api/**`. Ese token no sirve en `/api/**`.

El callback de Entra sigue siendo `/login` y no se comparte con Cognito.

## Configuración del backend

Los tres servicios reciben los valores desde el entorno/Secrets Manager de AWS:

```dotenv
COGNITO_ISSUER=https://cognito-idp.us-east-1.amazonaws.com/us-east-1_example
COGNITO_API_AUDIENCE=example-client-id
COGNITO_JWK_SET_URI=https://cognito-idp.us-east-1.amazonaws.com/us-east-1_example/.well-known/jwks.json
```

No activar el perfil `local` en AWS. El HMAC local es solo para desarrollo.

## Matriz mínima de pruebas

| Token | `/api/**` | `/aws/api/**` |
|---|---:|---:|
| Access token Entra con rol | 200/403 | 401 |
| Access token Cognito con grupo | 401 | 200/403 |
| Cognito ID token | 401 | 401 |
| Token sin grupo reconocido | 403 | 403 |
| Token expirado o de otra audience | 401 | 401 |

También comprobar que un token de Cognito sea rechazado en `/api/**` y que un token de Entra lo sea en `/aws/api/**`.
