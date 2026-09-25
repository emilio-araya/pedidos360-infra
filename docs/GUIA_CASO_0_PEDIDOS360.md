# Guía EP1 — Caso 0: Pedidos360

Esta es la versión de referencia para el PDF `Prueba cloud.pdf`. El equipo implementa el **Caso 0**, no los casos alternativos 1 a 6.

## Stack

- Frontend: React 19, Vite 8, TypeScript, React Router 7.
- Entra ID: `@azure/msal-react` para `/api/**`.
- Cognito: Authorization Code + PKCE S256 para `/aws/api/**`.
- Backend: BFF, Catalog y Orders en Spring Boot 3.5/Java 17.
- Datos: Oracle.
- AWS: API Gateway HTTP API, ECR, GitHub Actions con OIDC y Terraform.

## Estructura

```text
Pedidos360-repos/
├── pedidos360-frontend/
├── pedidos360-bff/
├── pedidos360-catalog/
├── pedidos360-orders/
└── pedidos360-infra/
```

## Arquitectura

```text
Navegador
   +-- Entra ---------> /api/**
   +-- Cognito -------> /aws/api/**
              |
              v
      API Gateway HTTP API
              |
              v
      BFF Spring Boot
          +-- Catalog -- Oracle
          +-- Orders  -- Oracle
```

El BFF no tiene JDBC. API Gateway es la única entrada pública del backend. Un token de un proveedor no se acepta en el namespace del otro.

## Caso 0

### Actores

- **Admin**: productos, stock, pedidos y transiciones.
- **Operador**: operación diaria, productos, stock, pedidos y transiciones.
- **Cliente**: crea pedidos, consulta sus pedidos y cancela los propios mientras estén en `CREADO`.

### Módulos

- Pedidos: `CREADO`, `ACEPTADO`, `EN_PREPARACION`, `DESPACHADO`, `ENTREGADO`, `CANCELADO`.
- Catálogo: productos, precios y stock.
- Regla principal: no se puede `DESPACHAR` sin `ACEPTAR`.
- El stock disminuye al aceptar y se restituye al cancelar.
- Las reservas son idempotentes por `orderId`.

## Rutas

| Namespace | Proveedor | Callback |
|---|---|---|
| `/api/**` | Entra ID | `/login` |
| `/aws/api/**` | Cognito | `/auth/cognito/callback` |

## Frontend React

Archivos principales:

- `src/main.tsx`
- `src/App.tsx`
- `src/auth/AuthContext.tsx`
- `src/auth/CognitoAuthContext.tsx`
- `src/auth/RequireAuth.tsx`
- `src/auth/RequireCatalogRole.tsx`
- `src/auth/RequireCognitoAuth.tsx`
- `src/auth/cognito.ts`
- `src/auth/RedirectHandler.tsx`
- `src/pages/CognitoCallbackPage.tsx`
- `src/pages/AwsPortalPage.tsx`
- `src/api/client.ts`
- `src/api/services.ts`

Rutas: `/login`, `/dashboard`, `/orders`, `/catalog`, `/auth/cognito/callback` y `/aws`.

El cliente HTTP agrega `Authorization: Bearer <access_token>` únicamente al namespace del proveedor activo. Cognito usa `sessionStorage` con fallback de memoria y nunca utiliza client secret.

## Identidad y Cognito

Valores públicos:

```text
COGNITO_USER_POOL_ID=us-east-1_UmEhPRYdI
COGNITO_USER_POOL_CLIENT_ID=59be26pgg5ginu2sutr8eetgjg
COGNITO_ISSUER=https://cognito-idp.us-east-1.amazonaws.com/us-east-1_UmEhPRYdI
```

App Client:

- Solo Authorization Code.
- PKCE S256.
- Sin client secret.
- Scopes: `openid`, `email`, `profile`.
- Grupos exactos: `Admin`, `Operador`, `Cliente`.

## Pruebas y evidencia

- Frontend: 23 pruebas.
- BFF: 21 pruebas.
- Catalog: 19 pruebas.
- Orders: 33 pruebas.
- Total: 96 pruebas, 0 fallos.
- Smoke local: pedidos, ownership, transiciones, stock y namespaces Entra/Cognito.
- Terraform: `fmt` y `validate` correctos.
- No guardar JWT completos, contraseñas, client secrets, AWS keys ni `*.tfvars`.

## Observación importante

La guía original enumera casos 0 a 6 como alternativas. Para este proyecto solo se entrega el **Caso 0: Pedidos360**. El PDF fue regenerado para que el cuerpo completo corresponda a esa estructura React y no a una guía genérica con casos alternativos.
