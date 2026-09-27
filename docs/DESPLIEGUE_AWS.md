# Despliegue en AWS

## Alcance

La configuración de `infra/aws/terraform` crea la infraestructura del Caso 0 completa: VPC con subredes públicas, de aplicación y de datos; NAT Gateway y VPC Endpoints; ALB interno; cluster de ECS Fargate con `bff`, `catalog` y `orders`; descubrimiento privado con Cloud Map; Secrets Manager con KMS; repositorios ECR; API Gateway HTTP API con los dos authorizers; y el frontend estático en S3 con CloudFront.

**API Gateway es la única URL pública del backend.** BFF, catalog, orders y Oracle se ejecutan sin IP pública, sin listener público y sin IP flotante. El frontend se publica como aplicación web estática: es una superficie pública distinta del backend y no es una ruta alternativa hacia los servicios.

> El despliegue se hace en dos etapas. `infra/aws/terraform/bootstrap` se aplica una vez, con estado local, y crea el bucket de estado remoto, la tabla de bloqueo, el proveedor OIDC de GitHub y el rol de despliegue. No puede ir en la misma etapa porque un backend remoto no puede crear su propio contenedor.

La especificación define la frontera de seguridad; esta configuración implementa una topología concreta. Sigue siendo responsabilidad del equipo antes de producción: dimensionar la VPC al crecimiento esperado, definir el modelo de licencia de Oracle, y separar cuentas si la organización lo exige.

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

## 1.1 Configurar Cognito para `/aws/api`

Seguir `docs/CONFIGURACION_COGNITO.md`. El App Client `59be26pgg5ginu2sutr8eetgjg` debe tener solo Authorization Code, PKCE S256, sin client secret, y callbacks exactos para `/auth/cognito/callback`. Los grupos `Admin`, `Operador` y `Cliente` se asignan a usuarios del User Pool `us-east-1_UmEhPRYdI`.

Las variables públicas para BFF, Catalog, Orders y frontend son:

```dotenv
COGNITO_ISSUER=https://cognito-idp.us-east-1.amazonaws.com/us-east-1_UmEhPRYdI
COGNITO_API_AUDIENCE=59be26pgg5ginu2sutr8eetgjg
COGNITO_JWK_SET_URI=https://cognito-idp.us-east-1.amazonaws.com/us-east-1_UmEhPRYdI/.well-known/jwks.json
COGNITO_USER_POOL_ID=us-east-1_UmEhPRYdI
COGNITO_USER_POOL_CLIENT_ID=59be26pgg5ginu2sutr8eetgjg
COGNITO_DOMAIN=us-east-1umehprydi.auth.us-east-1.amazoncognito.com
```

Los servicios rechazan un token de Cognito en `/api/**` y un token de Entra en `/aws/api/**`. El frontend solo manda el access token, nunca el ID token.

## 2. Red privada

La configuración crea la red y el runtime. Estos son los puntos de decisión:

1. **Región y cuenta**: `us-east-1` por defecto. Separa cuentas para red, datos y workloads si la organización lo requiere.
2. **VPC y CIDR**: `10.20.0.0/16` con tres capas de subredes /24 por Availability Zone (públicas 0-1, aplicación 10-11, datos 20-21). Cambiable con `vpc_cidr` y `availability_zone_names`.
3. **Runtime**: ECS Fargate en subredes privadas con `assign_public_ip = false` y plataforma 1.4.0.
4. **Descubrimiento**: AWS Cloud Map con namespace privado. `catalog.pedidos360.local` y `orders.pedidos360.local` no resuelven desde Internet.
5. **Entrada al BFF**: ALB de tipo application con `internal = true`, target group de tipo `ip` sobre el puerto 8080 y health check en `/actuator/health`. El security group del ALB solo acepta tráfico del security group del VPC Link.
6. **Conexión API Gateway–BFF**: integración `HTTP_PROXY` con `connection_type = "VPC_LINK"`. El `integration_uri` es el ARN del listener del ALB, porque API Gateway rechaza URLs. Para usar TLS en el tramo privado hay que crear un listener HTTPS con certificado ACM y definir `bff_tls_server_name`.
7. **Salida**: un NAT Gateway (configurable con `nat_gateway_count`) más VPC Endpoints para ECR, CloudWatch Logs y Secrets Manager. El tráfico a los JWKS de Entra y Cognito sale por el NAT, solo por 443.
8. **Security groups**: ninguna regla de puerto de aplicación acepta `0.0.0.0/0`. El único oráculo de la red es el SG del VPC Link.

Detalles de operación:

- Los VPC Links son inmutables. Cambiar subredes o security groups obliga a recrearlos.
- Si no se traffic por el VPC Link durante 60 días, AWS lo marca `INACTIVE` y elimina sus interfaces. Al recibir tráfico nuevamente lo reprovisiona, lo que puede tardar minutos.
- Con `enable_vpc_endpoints = false` las tareas siguen funcionando por el NAT, pero sale más tráfico a Internet.

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

La configuración crea:

- HTTP API en HTTPS, con CORS para orígenes exactos (el dominio de CloudFront se agrega automáticamente);
- authorizer JWT de Entra para `/api/**`;
- authorizer JWT de Cognito para `/aws/api/**`, con requirement de scope `openid`;
- VPC Link en las subredes privadas de aplicación;
- integración privada `HTTP_PROXY` hacia el ALB interno;
- rutas explícitas `/api/orders/*`, `/api/catalog/*` y sus equivalentes `/aws/api/*`;
- repositorios ECR para `bff`, `catalog` y `orders`;
- stage `$default` con auto deploy, métricas, throttling y access logs a CloudWatch.

No crea ruta `$default`, no expone rutas internas y delega en BFF y microservicios la autorización por rol y por pertenencia. El frontend ya no se publica como imagen: se sirve desde S3 y CloudFront.

### Integración privada

Queda por probar en la cuenta real:

- propagación de `Authorization` desde el gateway hasta el BFF sin exponerlo a otro servicio público;
- timeouts y respuestas 503/504 con alarmas;
- ausencia de acceso desde Internet a BFF, catalog y orders;
- estado y rollback de la integración.

La URL pública que se entrega al frontend y a los usuarios es el output `api_endpoint`. No se construye `API_BASE_URL` con la URL de ECS, del ALB interno, de Cloud Map ni de RDS.

## 5. Aplicar la configuración

### 5.1 Bootstrap

```bash
cd infra/aws/terraform/bootstrap
cp terraform.tfvars.example terraform.tfvars
# completar github_org y github_repositories
terraform init
terraform validate
terraform plan -out=bootstrap.tfplan
terraform apply bootstrap.tfplan
```

Anota los outputs `state_bucket`, `state_lock_table` y `github_deploy_role_arn`.

### 5.2 Infraestructura principal

```bash
cd infra/aws/terraform
cp terraform.tfvars.example terraform.tfvars
# completar entra_tenant_id, entra_api_audience y oracle_host

terraform init \
  -backend-config="bucket=<state_bucket>" \
  -backend-config="key=pedidos360/terraform.tfstate" \
  -backend-config="region=us-east-1" \
  -backend-config="dynamodb_table=<lock_table>" \
  -backend-config="encrypt=true"

terraform fmt -check -recursive
terraform validate
terraform plan -out=tfplan
terraform show tfplan
terraform apply tfplan
```

El plan crea del orden de 130 recursos. Revísalo antes de aplicar: NAT, endpoints y Fargate tienen costo desde el primer día.

### 5.3 Secretos de base de datos

Los Secrets se crean vacíos a propósito; Terraform no genera ni guarda contraseñas. ECS no podrá iniciar las tareas hasta que se carguen:

```bash
umask 077
printf '{"username":"<CATALOG_DB_USER>","password":"<PASSWORD>"}' > /tmp/secret.json
aws secretsmanager put-secret-value \
  --secret-id pedidos360/catalog/db \
  --secret-string file:///tmp/secret.json
shred -u /tmp/secret.json
```

Repite con `pedidos360/orders/db`. Usa un archivo temporal y no la contraseña en la línea de comandos: queda en el historial y en la lista de procesos.

## 6. Publicar el frontend

El frontend se puede publicar en S3/CloudFront o hosting estático equivalente:

- `ENTRA_TENANT_ID`: solo identificador público;
- `ENTRA_CLIENT_ID`: solo client ID de la SPA;
- `API_SCOPE=api://150f51db-4084-4979-b1a1-e6a6e7893a01/access_as_user`;
- `API_BASE_URL=https://<API_GATEWAY_ENDPOINT>`.
- `REDIRECT_URI=https://<FRONTEND_ORIGIN>/login`.
- `POST_LOGOUT_REDIRECT_URI=https://<FRONTEND_ORIGIN>/login`.
- `COGNITO_REDIRECT_URI=https://<FRONTEND_ORIGIN>/auth/cognito/callback`.
- `COGNITO_LOGOUT_URI=https://<FRONTEND_ORIGIN>/login`.
- `COGNITO_USER_POOL_ID`, `COGNITO_USER_POOL_CLIENT_ID`, `COGNITO_DOMAIN` e `COGNITO_ISSUER` con los valores públicos del App Client.

No incluir secretos, connection strings ni client secrets en el bundle. Si la aplicación React usa configuración compilada, los valores deben estar presentes durante el build; una variable de runtime no reescribe por sí sola un bundle estático.

Configurar CORS en API Gateway y BFF con la misma allowlist exacta, por ejemplo `https://pedidos360.example.com`. Evitar comodines, cookies de sesión y credenciales CORS; la autenticación viaja en Bearer.

## 7. Publicación de imágenes y GitHub OIDC

El rol OIDC y el proveedor los crea `bootstrap`. La política de confianza acepta únicamente los repositorios declarados y el environment `aws-production`; los workflows no usan llaves de acceso.

Crear el environment en GitHub con **required reviewers** y declarar estas variables (ninguna es secreta):

```text
AWS_REGION=us-east-1
AWS_ACCOUNT_ID=<account-id>
AWS_DEPLOY_ROLE_ARN=<output github_deploy_role_arn>
BFF_ECR_REPOSITORY=pedidos360-api-bff
CATALOG_ECR_REPOSITORY=pedidos360-api-catalog
ORDERS_ECR_REPOSITORY=pedidos360-api-orders
FRONTEND_BUCKET=<output frontend_bucket>
CLOUDFRONT_DISTRIBUTION_ID=<id de la distribución>
API_BASE_URL=<output api_endpoint>
```

Y en el environment `aws-production` del repositorio de infraestructura, para el workflow de Terraform:

```text
TF_STATE_BUCKET=<output state_bucket>
TF_STATE_KEY=pedidos360/terraform.tfstate
TF_LOCK_TABLE=<output state_lock_table>
ENTRA_TENANT_ID=1feca74f-8331-414a-bd8d-2d687b22a7b3
ENTRA_API_AUDIENCE=150f51db-4084-4979-b1a1-e6a6e7893a01
ORACLE_HOST=<host de Oracle>
```

No guardar AWS access keys, client secrets ni tokens en GitHub Secrets. El frontend requiere además sus variables públicas de build (`API_BASE_URL`, callbacks, App Client y dominio Cognito).

## 8. Secuencia de despliegue

1. Aplicar `bootstrap` y anotar sus outputs.
2. Aplicar la infraestructura principal y **cargar los secretos** de base de datos.
3. Crear los usuarios de aplicación en Oracle; ninguno de los microservicios usa el usuario maestro.
4. Verificar que las tareas de catalog y orders inician sanas y que Flyway crea el esquema.
5. Publicar las imágenes de `bff`, `catalog` y `orders` con `publish-ecr.yml` usando GitHub OIDC.
6. Fijar `container_image_tag` al SHA publicado y volver a aplicar, para que el despliegue sea reproducible.
7. Publicar el frontend con `deploy-frontend.yml` y registrar en Entra y en el App Client de Cognito los callbacks que entrega el output `frontend_cognito_redirect_uris`.
8. Ejecutar los smoke tests y las pruebas de seguridad de `docs/ENTREGA_Y_EVIDENCIAS.md`.

## 9. Verificación desde fuera

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
- token de Entra en `/aws/api/**`: `401`;
- token de Cognito en `/api/**`: `401`;
- ID token de Cognito en cualquiera de los dos namespaces: `401`;
- `Cliente` en escritura de catálogo/transición: `403`;
- token Cognito válido con grupo `Admin`, `Operador` o `Cliente` en `/aws/api/**`: `200/403` según operación;
- rutas no documentadas: `404`, no exposición de microservicios;
- CORS preflight desde el origen permitido y rechazo desde otro origen;
- ninguna URL de orders, catalog u Oracle en la configuración del frontend.

## 10. Observabilidad y rollback

Como mínimo, registrar:

- `requestId`, API Gateway request ID, ruta, status y latencia sin Authorization;
- métricas de throttling, 4xx/5xx e integración hacia BFF;
- health, reinicios y saturación de cada task;
- errores de JWT diferenciados sin incluir tokens;
- fallos de conexión a Oracle y migraciones;
- auditoría de cambios de estado, stock y cancelaciones, con correlación pero sin secretos.

El rollback debe conservar compatibilidad con el esquema. No revertir una migración destructiva automáticamente. Para la reversión de la aplicación, usar imágenes o versiones anteriores inmutables y despliegues graduales; para API Gateway, conservar una revisión de stage/integración validada. No hacer rollback destructivo de Oracle.
