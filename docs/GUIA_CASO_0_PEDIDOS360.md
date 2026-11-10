# Guía EP1 — Caso 0: Pedidos360

Esta es la versión de referencia para el PDF `Prueba cloud.pdf`. El equipo implementa el **Caso 0**, no los casos alternativos 1 a 6.

## Alcance de la identidad

La interfaz web se autentica con **un único proveedor: Microsoft Entra ID**, mediante Authorization Code con PKCE y `@azure/msal-react`, para todos los llamados a `/api/**`.

El backend conserva además una **segunda cadena de seguridad independiente** en `/aws/api/**`, validada con tokens de Amazon Cognito (Authorization Code + PKCE S256). Las dos cadenas están implementadas y probadas por separado, con validadores y propiedades propios, y un token de un proveedor nunca se acepta en el namespace del otro. Esa segunda cadena **no tiene pantalla de acceso en la SPA**: el rediseño del frontend dejó Entra ID como único proveedor visible, así que la capacidad se demuestra con las pruebas de integración del BFF y de los microservicios, no desde la interfaz.

El resto de este documento aplica al Caso 0.

## Stack

- Frontend: React 19, Vite 8, TypeScript, React Router 7.
- Entra ID: `@azure/msal-react`, único proveedor de la SPA, para `/api/**`.
- Cognito: segunda cadena del backend para `/aws/api/**`, implementada y testeada, sin pantalla en la SPA.
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
Navegador (SPA)
   +-- Entra ---------> /api/**
              |
              v
      API Gateway HTTP API
              |
              v
      BFF Spring Boot
          +-- Catalog -- Oracle
          +-- Orders  -- Oracle

Cadena adicional del backend, sin pantalla en la SPA:
   /aws/api/** -- Cognito (validacion de tokens en el BFF)
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
| `/api/**` | Entra ID (SPA) | `/login` |
| `/aws/api/**` | Cognito (backend) | no expuesto en la SPA |

## Frontend React

Archivos principales:

- `src/main.tsx`
- `src/App.tsx`
- `src/auth/AuthContext.tsx`
- `src/auth/RequireAuth.tsx`
- `src/auth/RequireCatalogRole.tsx`
- `src/auth/RedirectHandler.tsx`
- `src/api/client.ts`
- `src/api/services.ts`

Rutas: `/login`, `/dashboard`, `/orders` y `/catalog`, más la tienda pública (`/`, `/producto/:id`, `/carrito`, `/checkout`, `/pedido/:id`, `/seguimiento`).

El cliente HTTP agrega `Authorization: Bearer <access_token>` con el token que MSAL obtiene para `/api/**`. La SPA no mantiene sesión de Cognito ni almacena tokens de ese proveedor.

## Cadena Cognito del backend

Esta cadena no se usa desde la SPA. Existe para demostrar el aislamiento de dos configuraciones de seguridad y se verifica con pruebas de integración del BFF y de los microservicios: los validadores, las propiedades y los matchers de `/aws/api/**` son independientes de los de Entra.

Los valores son públicos por diseño, pero cambian por entorno, así que el repositorio publica placeholders en lugar de los de una cuenta real:

```text
COGNITO_USER_POOL_ID=us-east-1_example
COGNITO_USER_POOL_CLIENT_ID=example-client-id
COGNITO_ISSUER=https://cognito-idp.us-east-1.amazonaws.com/us-east-1_example
```

App Client (solo emisión y validación de tokens; no se usa para iniciar sesión desde la aplicación):

- Solo Authorization Code.
- PKCE S256.
- Sin client secret.
- Scopes: `openid`, `email`, `profile`.
- Grupos exactos: `Admin`, `Operador`, `Cliente`.
- Sin callbacks de la SPA registrados, porque la interfaz no redirige a Cognito.

## Pruebas y evidencia

Las cifras reales de la entrega, que superan el mínimo del enunciado porque se agregaron pruebas de regresión para defectos encontrados durante la implementación:

| Repositorio | Pruebas | Fallos |
|---|---|---|
| frontend | 141 | 0 |
| bff | 45 | 0 |
| catalog | 89 | 0 |
| orders | 118 | 0 |
| **total** | **393** | **0** |

- Smoke local: pedidos, ownership, transiciones, stock y las dos cadenas de seguridad del backend.
- Terraform: `fmt` y `validate` correctos.
- Cobertura mínima exigida: 70% de instrucciones en Java y 75% de líneas en el frontend. La CI publica los valores vigentes en cada ejecución.
- La CI de GitHub Actions ejecuta la suite completa y la cobertura en cada push y pull request.
- No guardar JWT completos, contraseñas, client secrets, AWS keys ni `*.tfvars`.

## Observación importante

La guía original enumera casos 0 a 6 como alternativas. Para este proyecto solo se entrega el **Caso 0: Pedidos360**. El PDF fue regenerado para que el cuerpo completo corresponda a esa estructura React y no a una guía genérica con casos alternativos.
