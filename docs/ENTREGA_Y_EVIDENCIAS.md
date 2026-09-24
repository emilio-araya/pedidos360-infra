# Entrega y evidencias

Este documento define el inventario, las verificaciones y el paquete de evidencia de Pedidos360. Una captura correcta de ejecución no sustituye las pruebas de seguridad, autorización, stock ni transiciones.

## 1. Inventario de entrega

### Raíz e infraestructura

- `README.md`: ejecución local, arquitectura y enlaces.
- `.gitignore`: secretos y artefactos generados.
- `docker-compose.yml`: Oracle, catalog, orders, BFF y frontend.
- `scripts/run-local.sh`: prevalidación y arranque del stack Oracle.
- `scripts/smoke-local.sh`: prueba E2E con H2 para autenticación, ownership, transiciones y stock.
- `scripts/stop-local.sh`: parada sin borrar el volumen Oracle.
- `infra/frontend.Dockerfile`: imagen SPA parametrizable sin modificar el proyecto frontend.
- `infra/aws/openapi.yaml`: contrato público de API.
- `infra/aws/terraform/README.md`: alcance y límites del Terraform.
- `infra/aws/terraform/*.tf`: esqueleto API Gateway HTTP API.

### Proyectos

- `ms-pedidos360-catalog/`
- `ms-pedidos360-orders/`
- `ms-pedidos360-bff/`
- `frontend-pedidos360/`

### Documentación

- `docs/ESPECIFICACION_TECNICA.md`
- `docs/CONFIGURACION_ENTRA_ID.md`
- `docs/DESPLIEGUE_AWS.md`
- `docs/ENTREGA_Y_EVIDENCIAS.md`
- `docs/VALIDACION_LOCAL.md`
- `docs/MANUAL_PEDIDOS360.md` y `docs/MANUAL_PEDIDOS360.pdf`

## 2. Comandos de verificación

Ejecutar desde la raíz salvo indicación contraria. Registrar versión de Java, Node, npm, Maven, Terraform, provider AWS y sistema operativo.

### Backend Spring Boot

```bash
mvn -f ms-pedidos360-catalog/pom.xml clean verify
mvn -f ms-pedidos360-orders/pom.xml clean verify
mvn -f ms-pedidos360-bff/pom.xml clean verify
```

Si los proyectos incluyen wrappers, se puede preferir el wrapper versionado. No mezclar el resultado de una base H2 local con evidencia de Oracle o AWS.

### Frontend React + Vite

```bash
npm --prefix frontend-pedidos360 ci
npm --prefix frontend-pedidos360 test
npm --prefix frontend-pedidos360 run build
./scripts/smoke-local.sh
```

### YAML, shell y Terraform

```bash
python3 -c 'import pathlib, yaml; yaml.safe_load(pathlib.Path("docker-compose.yml").read_text()); yaml.safe_load(pathlib.Path("infra/aws/openapi.yaml").read_text())'
bash -n scripts/run-local.sh
bash -n scripts/stop-local.sh
terraform -chdir=infra/aws/terraform fmt -check -recursive
terraform -chdir=infra/aws/terraform init -backend=false
terraform -chdir=infra/aws/terraform validate
```

Además del parser YAML, conviene validar `infra/aws/openapi.yaml` con un linter OpenAPI y ejecutar `shellcheck` si está disponible.

### Docker Compose y Oracle

```bash
docker compose --env-file .env -f docker-compose.yml config --quiet
./scripts/run-local.sh
docker compose -f docker-compose.yml ps
```

Abrir SQL*Plus dentro del contenedor Oracle:

```bash
docker compose -f docker-compose.yml exec oracle bash -lc \
  'printf "%s\n" "WHENEVER SQLERROR EXIT SQL.SQLCODE" "SELECT 1 FROM dual;" "EXIT;" | "${ORACLE_HOME}/bin/sqlplus" -s "${APP_USER}/${APP_USER_PASSWORD}@localhost:1521/${ORACLE_DATABASE:-FREEPDB1}"'
```

Ejecutar consultas de smoke test y Flyway solo con el usuario de la aplicación. Dentro del contenedor, `APP_USER`/`APP_USER_PASSWORD` corresponden al principal de catalog; orders usa `ORDERS_DB_USERNAME`/`ORDERS_DB_PASSWORD`. No elevar a `SYS` durante las pruebas. La configuración y los comandos destructivos deben estar aprobados antes de ejecutarlos.

Detener preservando el volumen:

```bash
./scripts/stop-local.sh
```

No se considera evidencia de una prueba una imagen de pantalla obtenida mientras se expone un secreto.

## 3. Matriz de aceptación funcional

### Identidad y autorización

| ID | Caso | Evidencia mínima |
|---|---|---|
| ID-01 | Token Entra válido: firma, `iss`, `aud`, `exp` y `nbf` | respuesta exitosa y claims no sensibles inspeccionados |
| ID-02 | Token sin Bearer | `401` en API Gateway/BFF |
| ID-03 | Audience de otra API | `401` |
| ID-04 | Token expirado o `nbf` futuro | `401` |
| ID-05 | Firma manipulada | `401` |
| ID-06 | `Admin` completo | operaciones permitidas |
| ID-07 | `Operador` | gestión y transiciones permitidas; se documenta cualquier diferencia |
| ID-08 | `Cliente` | dashboard, productos para pedido, creación, consulta propia y cancelación propia en `CREADO` |
| ID-09 | `Cliente` intenta administrar catálogo | `403` |
| ID-10 | `Cliente` intenta transición operacional | `403` |
| ID-11 | Usuario consulta/cancela pedido ajeno | `403` o recurso no visible, sin datos filtrados |
| ID-12 | Cambio de rol | nuevo token refleja `roles`; un token viejo no gana permisos nuevos |

### Contrato HTTP

Probar solo las rutas definidas en `docs/ESPECIFICACION_TECNICA.md`:

- Catálogo: `GET/POST /api/catalog/products`, `GET/PUT/DELETE /api/catalog/products/{id}` y `PATCH /api/catalog/products/{id}/stock`.
- Pedidos: `GET/POST /api/orders`, `GET/PUT/DELETE /api/orders/{id}` y `PATCH /api/orders/{id}/status`.
- El cliente nunca consume `/internal/catalog/stock/reservations` ni las URL privadas de microservicios.

Para cada método conservar request, response, status, ID de correlación (request ID) y timestamp, ocultando Bearer y datos personales innecesarios.

### Estados de pedido

| Estado actual | Destino solicitado | Esperado |
|---|---|---|
| `CREADO` | `ACEPTADO` | permitido |
| `CREADO` | `CANCELADO` | permitido |
| `ACEPTADO` | `EN_PREPARACION` | permitido |
| `ACEPTADO` | `CANCELADO` | permitido |
| `EN_PREPARACION` | `DESPACHADO` | permitido |
| `EN_PREPARACION` | `CANCELADO` | permitido |
| `DESPACHADO` | `ENTREGADO` | permitido |
| `CREADO` | `DESPACHADO` | rechazado; no puede saltarse `ACEPTADO` |
| `DESPACHADO` o `ENTREGADO` | `CANCELADO` | rechazado |
| `ENTREGADO` | cualquier transición | rechazado |
| `CANCELADO` | cualquier transición | rechazado |

Probar también saltos hacia atrás, repeticiones de una transición no idempotente y estados inexistentes. El status HTTP exacto de errores puede variar, pero el rechazo y la ausencia de mutación son obligatorios.

### Stock

| ID | Caso | Evidencia mínima |
|---|---|---|
| ST-01 | Aceptar pedido | stock descontado para todos los ítems |
| ST-02 | Cancelar pedido con reserva | stock restituido |
| ST-03 | Repetir reserva con mismo `orderId` | no descuenta dos veces |
| ST-04 | Repetir liberación con mismo `orderId` | no restituye dos veces |
| ST-05 | Cancelar dos veces | segunda operación no altera stock |
| ST-06 | Stock insuficiente | rechazo transaccional coherente; no aceptar dejando sobreventa |
| ST-07 | Concurrencia de aceptación/cancelación | invariantes de stock y estado preservados |

Las consultas SQL de Oracle deben mostrar valores antes/después usando el mismo `orderId`; no mezclar consultas H2 para la evidencia cloud.

### CORS y gateway

| ID | Caso | Esperado |
|---|---|---|
| GW-01 | Origen frontend permitido | `Access-Control-Allow-Origin` exacto |
| GW-02 | Origen no permitido | sin permiso CORS |
| GW-03 | Preflight OPTIONS | métodos y headers requeridos permitidos sin credenciales CORS |
| GW-04 | Ruta documentada sin token | `401` |
| GW-05 | Método/ruta no documentados | `404`; no existe `$default` que reenvíe |
| GW-06 | `/internal/...` desde Internet | no existe en API Gateway |
| GW-07 | Seguridad de red | BFF, catalog, orders y Oracle sin ingress público; API Gateway es la única URL pública del backend |
| GW-08 | Token validado dos veces | API Gateway valida JWT y BFF/microservicios vuelven a validarlo |

## 4. Evidencia de local, Oracle y AWS

### Perfil local sin Docker

1. Ejecutar las pruebas de cada proyecto con perfil Spring `local` y H2.
2. Confirmar que el BFF no depende de JDBC ni de un datasource.
3. Generar tokens HMAC únicamente en ese perfil con una clave de desarrollo.
4. Registrar comandos, tests y resultados.

### Compose con Oracle

1. Levantar con `./scripts/run-local.sh` y esperar que Oracle esté saludable.
2. Verificar esquema, migraciones y datos mínimos de catalog/orders.
3. Ejecutar pruebas de negocio y stock con tokens de un tenant de pruebas.
4. Confirmar que catalog y orders no publican puertos al host.
5. Conservar `docker compose ps`, logs relevantes y una exportación de datos saneada.

### AWS

1. Exportar el plan de Terraform aprobado.
2. Guardar identificadores no sensibles, definiciones de tareas versionadas y digest de imágenes.
3. Ejecutar smoke tests desde fuera **solo contra API Gateway**.
4. Adjuntar hallazgos de Security Groups, VPC Flow Logs, WAF si se adoptó, alarmas y configuración de CORS.
5. Verificar que la revisión de red demuestra que no hay URL pública del backend distinta de API Gateway.

La URL de frontend es pública por necesidad, pero no es una entrada al backend. CloudWatch/S3 del frontend no debe convertirse en una ruta alternativa a BFF, catalog, orders u Oracle.

## 5. Organización sugerida del paquete

No incluir secretos ni valores `*.tfvars`. Usar una carpeta versionable por entrega, por ejemplo:

```text
evidencias/<fecha>-<version>/
  01-inventario.txt
  02-resultados-automatizados/
  03-matriz-autorizacion.csv
  04-transiciones-y-stock.md
  05-oracle-consultas-saneadas.txt
  06-api-gateway-smoke.md
  07-red-y-endpoints.md
  08-terraform-plan-saneado.txt
```

Cada evidencia debe incluir:

- fecha y zona horaria;
- commit/tag de los cuatro proyectos;
- comando exacto;
- entorno (local, Oracle compose o AWS) sin credenciales;
- resultado esperado y observado;
- request ID/correlation ID;
- responsable y fecha de revisión.

No adjuntar:

- JWT completos o capturas que los muestren;
- secretos de Entra, Docker, Oracle o AWS;
- connection strings;
- dumps con datos personales de clientes;
- `.env`, `*.tfvars`, estados o claves privadas.

## 6. Criterios de cierre

La entrega está lista para aceptación cuando:

- [ ] los cuatro proyectos construyen y sus pruebas automáticas pasan;
- [ ] el contrato OpenAPI contiene exclusivamente las rutas públicas documentadas;
- [ ] JWT y roles se validan en API Gateway y en cada frontera backend aplicable;
- [ ] las capacidades por `Admin`, `Operador` y `Cliente` están probadas;
- [ ] todas las transiciones legales e ilegales se comportan según la especificación;
- [ ] descuento, idempotencia y restitución de stock están probados en Oracle;
- [ ] el perfil local usa H2 y nunca HMAC en cloud;
- [ ] el BFF no accede a base de datos;
- [ ] solo API Gateway expone el backend y la red privada está evidenciada;
- [ ] CORS funciona solo para el frontend configurado;
- [ ] Terraform/OpenAPI/YAML/shell pasan las validaciones disponibles;
- [ ] el paquete de evidencias está completo y saneado;
- [ ] la operación, costos, alta disponibilidad y recuperación de Oracle están definidos antes de producción.

La instalación de AWS continúa pendiente hasta que exista el diseño de red privada, ECS o equivalente, Oracle gestionado y el mecanismo probado de integración privada hacia BFF. El esqueleto de API Gateway no sustituye ese trabajo.
