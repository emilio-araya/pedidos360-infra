# Configuración de Amazon Cognito

Pedidos360 usa dos proveedores de identidad con namespaces separados:

| Namespace | Proveedor | Issuer | Rutas |
|---|---|---|---|
| `/api/**` | Microsoft Entra ID | `https://login.microsoftonline.com/1feca74f-8331-414a-bd8d-2d687b22a7b3/v2.0` | `/api/**` |
| `/aws/api/**` | Amazon Cognito | `https://cognito-idp.us-east-1.amazonaws.com/us-east-1_UmEhPRYdI` | `/aws/api/**` |

Un token de un proveedor no se acepta en el namespace del otro. API Gateway, BFF, Catalog y Orders tienen authorizers/validadores separados.

## Valores públicos

Estos identificadores no son secretos y pueden estar en variables de GitHub Actions o en el build de la SPA:

```dotenv
COGNITO_USER_POOL_ID=us-east-1_UmEhPRYdI
COGNITO_USER_POOL_CLIENT_ID=59be26pgg5ginu2sutr8eetgjg
COGNITO_ISSUER=https://cognito-idp.us-east-1.amazonaws.com/us-east-1_UmEhPRYdI
COGNITO_JWK_SET_URI=https://cognito-idp.us-east-1.amazonaws.com/us-east-1_UmEhPRYdI/.well-known/jwks.json
COGNITO_API_AUDIENCE=59be26pgg5ginu2sutr8eetgjg
COGNITO_DOMAIN=us-east-1umehprydi.auth.us-east-1.amazoncognito.com
COGNITO_REDIRECT_URI=http://localhost:4200/auth/cognito/callback
COGNITO_LOGOUT_URI=http://localhost:4200/login
COGNITO_API_SCOPE="openid email profile"
COGNITO_AUTHORIZATION_SCOPES=["openid"]
```

`COGNITO_API_AUDIENCE` es el App Client público en la configuración baseline. Los servicios aceptan el `client_id` cuando el access token no trae `aud`, pero siempre validan issuer, JWKS, audience/client ID y `token_use=access`. Las rutas Cognito de API Gateway también exigen el scope `openid`, lo que ayuda a que un ID token sin `scope` no sea aceptado en la entrada pública.

## App Client

En el App Client de Cognito configurar exactamente:

1. **Allowed callback URLs**:
   - `http://localhost:4200/auth/cognito/callback`
   - `https://<FRONTEND_ORIGIN>/auth/cognito/callback`
2. **Allowed sign-out URLs**:
   - `http://localhost:4200/login`
   - `https://<FRONTEND_ORIGIN>/login`
3. **OAuth grant types**: solo `Authorization code grant`.
4. **PKCE**: habilitado y obligatorio con `S256`.
5. **Client secret**: desactivado. La SPA es un cliente público.
6. **OAuth scopes**: `openid`, `email` y `profile`.
7. **Hosted UI**: habilitado con el dominio anterior.

No agregar un client secret a Docker, GitHub, Terraform, `.env` versionado ni al bundle React.

## Grupos y roles

Los grupos deben existir con mayúsculas exactas:

- `Admin`
- `Operador`
- `Cliente`

Asignar cada usuario a uno de esos grupos. El claim `cognito:groups` del **access token** se convierte en `ROLE_Admin`, `ROLE_Operador` o `ROLE_Cliente`. Un grupo desconocido no concede permisos. Se recomienda un solo `Admin`; los demás usuarios deben ser `Operador` o `Cliente`.

## Login y callback

El frontend React usa Authorization Code + PKCE mediante Amplify. El token de Cognito se mantiene en `sessionStorage` (nunca en `localStorage`, código fuente, logs ni Terraform); el fallback de memoria se usa cuando el navegador no expone storage. El flujo de Cognito es independiente de MSAL:

1. El usuario pulsa **Acceder con Amazon Cognito**.
2. Cognito devuelve a `/auth/cognito/callback`.
3. La SPA procesa `code` y `state`, obtiene el access token y navega a `/aws`.
4. Las llamadas de ese portal usan exclusivamente `/aws/api/**`.

El callback de Entra continúa siendo `/login` y el callback de Cognito es separado para que MSAL no procese el código de Cognito.

## Configuración del backend

Los tres servicios reciben los valores desde el entorno/Secrets Manager de AWS:

```dotenv
COGNITO_ISSUER=https://cognito-idp.us-east-1.amazonaws.com/us-east-1_UmEhPRYdI
COGNITO_API_AUDIENCE=59be26pgg5ginu2sutr8eetgjg
COGNITO_JWK_SET_URI=https://cognito-idp.us-east-1.amazonaws.com/us-east-1_UmEhPRYdI/.well-known/jwks.json
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

También comprobar que el callback exacto esté registrado. El error `redirect_mismatch` significa que la URL que Cognito está rechazando no coincide exactamente con una lista registrada.
