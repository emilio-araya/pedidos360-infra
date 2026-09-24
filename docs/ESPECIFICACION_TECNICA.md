# Especificación técnica compartida — Pedidos360

## Arquitectura

```text
React + Vite + MSAL
      │ Authorization: Bearer <access_token>
      ▼
AWS API Gateway (HTTP API, JWT authorizer y CORS)
      │ Única entrada pública al backend
      ▼
ms-pedidos360-bff (Spring Security; segunda validación JWT y roles)
      ├── ms-pedidos360-orders ─── Oracle
      └── ms-pedidos360-catalog ── Oracle

```

El BFF nunca se conecta a la base de datos. Los microservicios solo son accesibles dentro de la red privada.

## Roles

Los roles de aplicación aparecerán en el claim `roles` de Microsoft Entra ID.

> **Criterio ante contradicciones de la guía:** la tabla final de diferenciación atribuye algunas acciones al Cliente que contradicen la descripción de actores y el acceso de la pantalla `/catalog`. Este proyecto aplica el principio de menor privilegio y usa las responsabilidades de actores: el Cliente crea y sigue sus pedidos; Admin y Operador gestionan el dominio; únicamente Admin/Operador administran productos y stock.

| Capacidad | Admin | Operador | Cliente |
|---|---:|---:|---:|
| Ver dashboard | Sí | Sí | Sí |
| Navegar al catálogo | Sí | Sí | No |
| Ver productos para crear pedido | Sí | Sí | Sí |
| Crear pedido | Sí | Sí | Sí |
| Ver pedidos propios | Sí | Sí | Sí |
| Ver todos los pedidos | Sí | Sí | No |
| Cancelar pedido propio aún no aceptado | Sí | Sí | Sí |
| Ejecutar transiciones operacionales | Sí | Sí | No |
| Crear/editar productos | Sí | Sí | No |
| Administrar stock | Sí | Sí | No |

## Estados de pedido

Flujo principal:

```text
CREADO → ACEPTADO → EN_PREPARACION → DESPACHADO → ENTREGADO
   └────────────────── CANCELADO ────────────────────┘
```

Transiciones permitidas:

- `CREADO -> ACEPTADO | CANCELADO`
- `ACEPTADO -> EN_PREPARACION | CANCELADO`
- `EN_PREPARACION -> DESPACHADO | CANCELADO`
- `DESPACHADO -> ENTREGADO`
- `ENTREGADO` y `CANCELADO` son finales.

Reglas:

1. No se puede `DESPACHAR` un pedido que no esté `EN_PREPARACION`; en particular, no se puede despachar sin pasar por `ACEPTADO`.
2. Al aceptar, el catálogo descuenta stock de todos los ítems.
3. Al cancelar un pedido que ya había descontado stock, el catálogo restituye el stock.
4. Un cliente solo puede consultar y cancelar sus propios pedidos mientras estén en `CREADO`.
5. Las reservas de stock son idempotentes por `orderId`.

## Contrato HTTP

Las rutas siguientes se consumen mediante el BFF. Los microservicios las implementan internamente.

### Catálogo

- `GET /api/catalog/products`
- `GET /api/catalog/products/{id}`
- `POST /api/catalog/products` — Admin/Operador
- `PUT /api/catalog/products/{id}` — Admin/Operador
- `DELETE /api/catalog/products/{id}` — Admin/Operador
- `PATCH /api/catalog/products/{id}/stock` — Admin/Operador

Producto:

```json
{
  "id": "uuid",
  "sku": "P-001",
  "name": "Mouse",
  "description": "Mouse inalámbrico",
  "price": 19990.00,
  "stock": 25,
  "active": true
}
```

Reserva interna, utilizada por orders al aceptar y liberar stock:

- `POST /internal/catalog/stock/reservations`
- `DELETE /internal/catalog/stock/reservations/{orderId}`

```json
{
  "orderId": "uuid",
  "items": [{ "productId": "uuid", "quantity": 2 }]
}
```

### Pedidos

- `GET /api/orders`
- `GET /api/orders/{id}`
- `POST /api/orders`
- `PUT /api/orders/{id}`
- `DELETE /api/orders/{id}` — cancela si está en CREADO
- `PATCH /api/orders/{id}/status`

Crear pedido:

```json
{
  "items": [{ "productId": "uuid", "quantity": 2 }],
  "notes": "Entregar después de las 18:00"
}
```

Cambiar estado:

```json
{ "status": "ACEPTADO" }
```

Respuesta de pedido:

```json
{
  "id": "uuid",
  "orderNumber": "PED-ABC123",
  "customerId": "oid-del-cliente",
  "status": "CREADO",
  "items": [
    {
      "productId": "uuid",
      "productName": "Mouse",
      "quantity": 2,
      "unitPrice": 19990.00,
      "subtotal": 39980.00
    }
  ],
  "total": 39980.00,
  "notes": "...",
  "stockReserved": false,
  "createdAt": "2026-09-23T18:00:00Z",
  "updatedAt": "2026-09-23T18:00:00Z"
}
```

## Identidad y JWT

- Frontend SPA: `frontend-pedidos360`
- APIscope: `api://150f51db-4084-4979-b1a1-e6a6e7893a01/access_as_user`
- Audience esperado por BFF y microservicios: identificador de la API en formato v2 o `api://{client-id}` cuando el registro de Entra emita un token v1.
- Claims utilizados: `oid` o `sub`; `roles`.
- Validación obligatoria: firma, `iss`, `aud`, `exp` y `nbf`.
- Perfil local para desarrollo: JWT HMAC únicamente bajo el perfil Spring `local`; nunca habilitar en cloud.

## Configuración

Variables principales:

- Frontend: `ENTRA_TENANT_ID`, `ENTRA_CLIENT_ID`, `API_SCOPE`, `API_BASE_URL`, `REDIRECT_URI` y `POST_LOGOUT_REDIRECT_URI`.
- BFF: `ENTRA_ISSUER`, `ENTRA_API_AUDIENCE`, `ORDERS_SERVICE_URL`, `CATALOG_SERVICE_URL`, `CORS_ALLOWED_ORIGINS`.
- Microservicios: `ENTRA_ISSUER`, `ENTRA_API_AUDIENCE` y su configuración Oracle.

## Base de datos

- Perfil cloud: Oracle.
- Perfil `local`: H2 en memoria para ejecución y pruebas sin Docker.
- El BFF no incluye driver JDBC ni datasource.
