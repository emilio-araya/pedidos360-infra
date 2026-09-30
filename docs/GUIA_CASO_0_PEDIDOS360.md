# Guía EP1 — Caso 0: Pedidos360

Esta es la versión de referencia para el PDF `Prueba cloud.pdf`. El equipo implementa el **Caso 0**, no los casos alternativos 1 a 6.

## Alcance: qué pedía el enunciado y qué se agregó

El enunciado del profesor pide **Microsoft Entra ID** como mecanismo de autenticación del backend. Eso es el alcance de la entrega.

El namespace `/aws/api/**` con **Amazon Cognito** no fue solicitado. Se agregó como extensión propia para demostrar que el BFF puede aislar dos cadenas de seguridad independientes sin mezclarlas. Está implementado, probado y documentado, y su configuración se separa por completo de la de Entra: un token de un proveedor nunca se acepta en el namespace del otro.

Todo lo demás en este documento aplica al Caso 0. Las secciones que son extensión se marcan explícitamente.

## Stack

- Frontend: React 19, Vite 8, TypeScript, React Router 7.
- Entra ID: `@azure/msal-react` para `/api/**`.
- Cognito: Authorization Code + PKCE S256 para `/aws/api/**`. **Extensión, no pedida por el enunciado.**
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
   +-- Cognito -------> /aws/api/**      (extensión, ver "Alcance")
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
| `/api/**` | Entra ID (enunciado) | `/login` |
| `/aws/api/**` | Cognito (extensión) | `/auth/cognito/callback` |

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

## Extensión: identidad con Cognito

Esta sección documenta la extensión de Cognito. El enunciado solo pedía Entra ID; ver la sección **Alcance** más arriba.

Los valores son públicos por diseño, pero cambian por entorno, así que el repositorio publica placeholders en lugar de los de una cuenta real:

```text
COGNITO_USER_POOL_ID=us-east-1_example
COGNITO_USER_POOL_CLIENT_ID=example-client-id
COGNITO_ISSUER=https://cognito-idp.us-east-1.amazonaws.com/us-east-1_example
```

App Client:

- Solo Authorization Code.
- PKCE S256.
- Sin client secret.
- Scopes: `openid`, `email`, `profile`.
- Grupos exactos: `Admin`, `Operador`, `Cliente`.

## Pruebas y evidencia

Las cifras reales de la entrega, que superan el mínimo del enunciado porque se agregaron pruebas de regresión para defectos encontrados durante la implementación:

| Repositorio | Pruebas | Fallos |
|---|---|---|
| frontend | 23 | 0 |
| bff | 26 | 0 |
| catalog | 24 | 0 |
| orders | 37 | 0 |
| **total** | **110** | **0** |

- Smoke local: pedidos, ownership, transiciones, stock y namespaces Entra/Cognito.
- Terraform: `fmt` y `validate` correctos.
- Cobertura de instrucciones: 74,9% (bff), 80,5% (catalog), 80,3% (orders), con umbral de 70% que hace fallar la build.
- La CI de GitHub Actions ejecuta la suite completa y la cobertura en cada push y pull request.
- No guardar JWT completos, contraseñas, client secrets, AWS keys ni `*.tfvars`.

## Observación importante

La guía original enumera casos 0 a 6 como alternativas. Para este proyecto solo se entrega el **Caso 0: Pedidos360**. El PDF fue regenerado para que el cuerpo completo corresponda a esa estructura React y no a una guía genérica con casos alternativos.
