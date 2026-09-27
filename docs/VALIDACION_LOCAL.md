# Reporte de validación local — Pedidos360

**Fecha:** 2026-09-24  
**Entorno:** Linux, Java 17.0.20.1, Maven 3.9.16, Node.js 26.10.0 y npm 12.1.0.

## Resultado

La implementación local pasó compilación, pruebas automatizadas, análisis estático, smoke test H2 y una ejecución real del stack Docker con Oracle, frontend, BFF, catalog y orders.

| Componente | Resultado |
|---|---|
| `pedidos360-catalog` | 19 pruebas, 0 fallos; SpotBugs sin hallazgos no excluidos |
| `pedidos360-orders` | 33 pruebas, 0 fallos; SpotBugs sin hallazgos no excluidos |
| `pedidos360-bff` | 21 pruebas, 0 fallos; SpotBugs sin hallazgos |
| `pedidos360-frontend` | 23 pruebas, 0 fallos; TypeScript, Vite build y bundle Nginx correctos |
| Total automatizado | **96 pruebas, 0 fallos** |

Comandos principales:

```bash
mvn -f ../pedidos360-catalog/pom.xml clean verify
mvn -f ../pedidos360-orders/pom.xml clean verify
mvn -f ../pedidos360-bff/pom.xml clean verify
npm --prefix ../pedidos360-frontend test
npm --prefix ../pedidos360-frontend run build
npm --prefix ../pedidos360-frontend audit --omit=dev --audit-level=high
```

`npm audit` devolvió **0 vulnerabilidades**. Los filtros `spotbugs-exclude.xml` de catalog y orders excluyen solo los falsos positivos de colaboradores privados en beans Spring y la advertencia de constructor en entidades que no declaran finalizadores.

## Smoke E2E

El comando siguiente levantó los tres JAR con perfil `local`, ejecutó el flujo y los detuvo al finalizar:

```bash
./scripts/smoke-local.sh
```

Resultado observado:

```text
Smoke E2E aprobado: auth=401/403/200, ownership=403, skip=409,
stock=3->2->2, ciclo completo=OK
```

Se comprobó lo siguiente:

- petición sin token: `401`;
- Cliente lee catálogo: `200`;
- Cliente intenta administrar catálogo: `403`;
- Operador crea producto: `201`;
- Cliente crea pedido: `201`;
- Cliente consulta un pedido ajeno: `403`;
- Cliente intenta una transición operacional: `403`;
- intento de `CREADO -> DESPACHADO`: `409`;
- aceptar descuenta stock: `3 -> 2`;
- ciclo `ACEPTADO -> EN_PREPARACION -> DESPACHADO -> ENTREGADO`: correcto;
- cancelar una reserva aceptada restituye stock;
- cantidad inválida: `400`.

Durante esta revisión también se detectó y corrigió una incompatibilidad real del proxy BFF con el método HTTP `PATCH`; se sustituyó el cliente HTTP y se agregó una prueba de regresión.

## Infraestructura

| Validación | Resultado |
|---|---|
| Docker Compose v2.32.4 `config --quiet` | Correcto con `.env.example` |
| Exposición de puertos | Solo frontend y BFF en `127.0.0.1` |
| OpenAPI 3.0.3 con Redocly | Válido; una advertencia documental por URL de licencia |
| Terraform 1.9.8 + AWS provider 6.66.0 | `terraform validate`: correcto |
| Terraform API Gateway | JWT authorizer, CORS, VPC Link y rutas privadas explícitas |
| Bash | `run-local.sh`, `stop-local.sh`, `smoke-local.sh` y `ensure-oracle-users.sh`: sintaxis correcta |
| Build parametrizable de React + Vite | Compila con IDs y URL de API sustituidos; imagen Nginx construida desde `dist/` |
| Docker/Oracle | Stack integrado `healthy`: Oracle, catalog, orders, BFF y frontend; principals separados para catalog/orders |
| Redirect URI SPA | Build Docker verificado con `http://localhost:4200/login`; el bundle no contiene `app.example.com` |
| Callbacks de identidad | Entra procesa `/login`; Cognito procesa `/auth/cognito/callback`; 23 pruebas frontend aprobadas |
| Compatibilidad JWT local | Validadores Backend aceptan v1/v2 exactos para el mismo tenant y API; token real debe comprobarse después del rebuild |

## Integración Cognito añadida

La rama `feature/cognito-aws` agrega el segundo proveedor sin cambiar el namespace de Entra:

- frontend React con login Cognito Authorization Code + PKCE S256, callback separado `/auth/cognito/callback` y 23 pruebas frontend;
- BFF, Catalog y Orders con cadenas/validadores separados para Entra `/api/**` y Cognito `/aws/api/**`;
- validadores de issuer, audience/client ID, `token_use=access` y grupos `cognito:groups`;
- Terraform con dos authorizers, rutas duplicadas y repositorios ECR;
- pruebas de que un token de un namespace no se acepta en el otro cuando se ejecuta con los decoders reales.

La validación contra un User Pool real y la aplicación de Terraform en AWS siguen requiriendo configurar el App Client y el rol OIDC; no se han inventado credenciales ni aplicado cambios destructivos.

## Alcance de esta ejecución

El stack Docker/Oracle real quedó levantado y todos sus servicios reportan `healthy`. Se comprobó además que el BFF responde `401` sin token, que el frontend responde `200` en `/healthz` y que el preflight CORS permite el origen configurado.

El provisioning local crea y valida dos usuarios Oracle independientes (`PEDIDOS360_CATALOG` y `PEDIDOS360_ORDERS`) sin imprimir credenciales. El volumen existente se conservó durante la validación.

La primera prueba con un token real de Entra desde el navegador mostró que el registro estaba emitiendo un access token v1 (`sts.windows.net` y `api://{client-id}`), mientras el BFF exigía únicamente v2. Se ajustaron los validadores para aceptar ambos formatos exactos del tenant/API y se reconstruyeron los servicios; queda pendiente repetir la prueba real después de la reconstrucción. Tampoco se desplegó AWS: siguen pendientes la VPC, el runtime privado, Oracle gestionado, el listener privado y las credenciales de la cuenta AWS.

Para completar esas evidencias se deben seguir:

1. `docs/CONFIGURACION_ENTRA_ID.md`;
2. `docs/DESPLIEGUE_AWS.md`;
3. `docs/ENTREGA_Y_EVIDENCIAS.md`.

Los resultados anteriores no sustituyen las capturas ni las pruebas exigidas por el docente contra un tenant y AWS reales.
