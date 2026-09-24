# Despliegue en AWS

## Alcance y advertencia principal

La especificación define la frontera de seguridad, no una topología VPC completa. El despliegue backend de producción todavía requiere diseñar e implementar, como mínimo:

- una VPC con subredes públicas y privadas;
- una plataforma de ejecución privada, por ejemplo ECS/Fargate o equivalente;
- resolución privada y conectividad segura entre BFF, catalog y orders;
- Oracle privado y gestionado, o una alternativa Oracle equivalente;
- IAM, secretos, observabilidad, backups, alta disponibilidad y pipelines de despliegue.

El Terraform incluido en `infra/aws/terraform` crea **solo el esqueleto de API Gateway HTTP API**. No crea una red ni hace desplegable el backend por sí solo.

> **API Gateway debe ser la única URL pública del backend.** BFF, catalog, orders y Oracle no pueden tener acceso desde Internet, IP pública, listener público ni URL privada entregada al frontend. El frontend puede publicarse como aplicación web estática; es una superficie pública distinta del backend.

## Arquitectura objetivo

```text
Navegador
  ├── frontend estático (CloudFront/S3 o hosting equivalente)
  └── Authorization: Bearer <access_token>
            │
            ▼
  API Gateway HTTP API (HTTPS, JWT authorizer, CORS)
  Única URL pública del backend
            │
            ▼
  conectividad privada hacia BFF
            │
            ▼
  VPC
  ├── ALB/service discovery privado → BFF
  │                         ├── orders privado ── Oracle privado
  │                         └── catalog privado ─ Oracle privado
  └── gateways de salida/Endpoints VPC solo para dependencias necesarias
```

Catalog y orders se alcanzan entre sí únicamente por sus nombres/Discovery Service privados. El BFF no tiene JDBC, datasource ni credenciales de Oracle.

## 1. Preparar identidad

Seguir `docs/CONFIGURACION_ENTRA_ID.md` y verificar:

- app registration SPA `frontend-pedidos360`;
- API `pedidos360-api` con scope `api://150f51db-4084-4979-b1a1-e6a6e7893a01/access_as_user`;
- audiencia igual al Application (client) ID de `pedidos360-api`;
- issuer `https://login.microsoftonline.com/1feca74f-8331-414a-bd8d-2d687b22a7b3/v2.0`;
- roles `Admin`, `Operador` y `Cliente` asignados mediante el claim `roles`.

En BFF se selecciona explícitamente el modo Entra; los tres servicios usan el mismo issuer y audience:

```dotenv
JWT_MODE=entra
ENTRA_ISSUER=https://login.microsoftonline.com/1feca74f-8331-414a-bd8d-2d687b22a7b3/v2.0
ENTRA_API_AUDIENCE=150f51db-4084-4979-b1a1-e6a6e7893a01
```

No usar el secreto HMAC del perfil `local` en AWS. Antes de aplicar Terraform, confirmar que `pedidos360-api` emite access tokens v2 con el issuer y audience de las líneas anteriores. Si el registro emite v1 (`sts.windows.net` y `api://{client-id}`), ajustar el authorizer y los servicios de forma coordinada; no desactivar la validación JWT.

## 2. Diseñar la red privada

Antes de aplicar cualquier Terraform se debe decidir y documentar:

1. **Cuentas y regiones**: preferiblemente cuentas separadas para red, datos, workloads y seguridad según el nivel de aislamiento requerido.
2. **VPC y CIDR**: subredes en varias AZ para BFF, catalog/orders y datos; tablas de rutas y salida controlada.
3. **Runtime**: ECS/Fargate en subredes privadas es una opción; también puede usarse EKS u otro runtime equivalente.
4. **Descubrimiento de servicios**: Cloud Map, Service Connect o DNS privado, sin nombres públicos.
5. **Entrada al BFF**: target group/service discovery privado. Solo API Gateway debe poder alcanzarlo mediante el mecanismo privado elegido.
6. **Conexión API Gateway–BFF**: la infraestructura real debe implementar y probar el mecanismo privado compatible con API Gateway. Puede exigir recursos y configuración adicionales que no están en el esqueleto actual.
7. **Salida**: Entra JWKS, repositorios de imágenes y servicios de observabilidad necesitan una ruta controlada; no abrir todo el tráfico de salida.
8. **Security groups**: reglas por puerto y origen mínimo. Sin `0.0.0.0/0` hacia microservicios u Oracle.

El valor `bff_integration_uri` del esqueleto debe ser el ARN de un listener/Service Connect privado. `vpc_subnet_ids` y `vpc_security_group_ids` crean el VPC Link, pero no reemplazan la implementación de la VPC ni del listener.

## 3. Ejecutar los servicios privados

Se recomienda construir imágenes multiplataforma en ECR y versionarlas por commit y digest. Cada servicio ECS debe:

- ejecutarse sin IP pública;
- usar IAM role de tarea con permisos mínimos;
- leer configuración no secreta de Parameter Store/Task Definition;
- leer credenciales Oracle desde Secrets Manager mediante el mecanismo seguro de runtime;
- usar comprobaciones de estado, logging, métricas, alarmas y política de reinicio;
- satisfacer los mínimos de CPU/memoria y disponibilidad del proyecto.

Configuración interna esperada:

| Servicio | Configuración crítica |
|---|---|
| BFF | URLs privadas de orders/catalog, issuer, audience, CORS; ninguna configuración JDBC. |
| Orders | Configuración Oracle, issuer/audience y URL privada de catalog para reservas internas. |
| Catalog | Configuración Oracle, issuer/audience. |

Las operaciones `/internal/catalog/stock/reservations` se usan entre servicios privados y nunca se agregan como rutas de API Gateway.

### Oracle

La instalación productiva debe definir explícitamente esquema/usuarios, aislamiento de datos, migraciones, backups, PITR, retención, cifrado, rotación y licencias. Los dos microservicios no deben recibir el rol de administrador de Oracle. El BFF no recibe credenciales de base de datos.

El compose local usa una instancia Oracle Free de desarrollo y un volumen local; no es una referencia de alta disponibilidad cloud.

## 4. Configurar API Gateway

El esqueleto crea:

- HTTP API en HTTPS;
- authorizer JWT contra el issuer del tenant y la audiencia de la API;
- CORS para orígenes exactos;
- integración proxy al BFF;
- rutas explícitas `/api/orders/*` y `/api/catalog/*`;
- stage `$default` con auto deploy.

No crea ruta `$default`, no expone rutas internas y delega en BFF/microservicios la autorización por rol y por pertenencia.

### Integración privada pendiente

La implementación completa debe añadir los recursos de red y el tipo de integración privada que se adopte. Antes de `terraform apply` se deben probar:

- resolución privada y TLS hacia el BFF;
- timeouts y respuestas 503/504 con alarmas;
- propagación de `Authorization` sin exponerlo a otro servicio público;
- ausencia de acceso desde Internet a BFF, catalog y orders;
- estado y rollback de la integración.

La URL pública que se entrega a frontend y usuarios es el output `api_endpoint` de API Gateway. No se construye `API_BASE_URL` con la URL de ECS, ALB interno, Cloud Map ni RDS.

## 5. Aplicar el esqueleto de API Gateway

Validación sin crear infraestructura:

```bash
cd infra/aws/terraform
terraform init -backend=false
terraform fmt -check -recursive
terraform validate
```

Configurar de forma local y no versionada:

- `aws_region`;
- `entra_tenant_id`;
- `entra_api_audience`;
- `allowed_origins`;
- `bff_integration_uri` con el ARN privado del listener/Service Connect;
- `bff_tls_server_name`;
- `vpc_subnet_ids` y `vpc_security_group_ids` de la VPC existente.

Después de añadir el backend remoto y la red:

```bash
terraform init
terraform validate
terraform plan -out=tfplan
terraform show tfplan
terraform apply tfplan
```

No aplicar el esqueleto contra un BFF público. Configurar un backend Terraform remoto cifrado con bloqueo antes de compartir el estado.

## 6. Publicar el frontend

El frontend se puede publicar en S3/CloudFront o hosting estático equivalente:

- `ENTRA_TENANT_ID`: solo identificador público;
- `ENTRA_CLIENT_ID`: solo client ID de la SPA;
- `API_SCOPE=api://150f51db-4084-4979-b1a1-e6a6e7893a01/access_as_user`;
- `API_BASE_URL=https://<API_GATEWAY_ENDPOINT>`.
- `REDIRECT_URI=https://<FRONTEND_ORIGIN>/login`.
- `POST_LOGOUT_REDIRECT_URI=https://<FRONTEND_ORIGIN>/login`.

No incluir secretos, connection strings ni client secrets en el bundle. Si la aplicación React usa configuración compilada, los valores deben estar presentes durante el build; una variable de runtime no reescribe por sí sola un bundle estático.

Configurar CORS en API Gateway y BFF con la misma allowlist exacta, por ejemplo `https://pedidos360.example.com`. Evitar comodines, cookies de sesión y credenciales CORS; la autenticación viaja en Bearer.

## 7. Secuencia de despliegue

1. Desplegar migraciones Oracle de forma compatible y verificadas.
2. Desplegar catalog y orders sin ingress público.
3. Verificar conectividad privada, salud, JWT y llamadas internas de stock.
4. Desplegar BFF sin ingress público.
5. Completar la integración privada API Gateway–BFF y aplicar/verificar rutas JWT/CORS.
6. Desplegar el frontend con `API_BASE_URL` igual al endpoint de API Gateway y `REDIRECT_URI`/`POST_LOGOUT_REDIRECT_URI` iguales a `https://<FRONTEND_ORIGIN>/login`.
7. Ejecutar smoke tests y pruebas de seguridad de `docs/ENTREGA_Y_EVIDENCIAS.md`.

No desplegar una versión que requiera el nuevo contrato antes de que estén disponibles sus dependencias.

## 8. Verificación desde fuera

Obtener un token real del tenant y usar solo la URL de API Gateway:

```bash
export API_GATEWAY_URL="$(terraform -chdir=infra/aws/terraform output -raw api_endpoint)"
export TOKEN="$(az account get-access-token \
  --tenant 1feca74f-8331-414a-bd8d-2d687b22a7b3 \
  --scope api://150f51db-4084-4979-b1a1-e6a6e7893a01/access_as_user \
  --query accessToken \
  --output tsv)"

curl -i "$API_GATEWAY_URL/api/catalog/products" \
  -H "Authorization: Bearer $TOKEN"

curl -i "$API_GATEWAY_URL/api/orders" \
  -H "Authorization: Bearer $TOKEN"
```

Verificar además:

- sin token: `401`;
- token de otra audiencia: `401`;
- `Cliente` en escritura de catálogo/transición: `403`;
- rutas no documentadas: `404`, no exposición de microservicios;
- CORS preflight desde el origen permitido y rechazo desde otro origen;
- ninguna URL de orders, catalog u Oracle en la configuración del frontend.

## 9. Observabilidad y rollback

Como mínimo, registrar:

- `requestId`, API Gateway request ID, ruta, status y latencia sin Authorization;
- métricas de throttling, 4xx/5xx e integración hacia BFF;
- health, reinicios y saturación de cada task;
- errores de JWT diferenciados sin incluir tokens;
- fallos de conexión a Oracle y migraciones;
- auditoría de cambios de estado, stock y cancelaciones, con correlación pero sin secretos.

El rollback debe conservar compatibilidad con el esquema. No revertir una migración destructiva automáticamente. Para la reversión de la aplicación, usar imágenes o versiones anteriores inmutables y despliegues graduales; para API Gateway, conservar una revisión de stage/integración validada. No hacer rollback destructivo de Oracle.
