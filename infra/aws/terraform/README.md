# Terraform — infraestructura de Pedidos360

Esta configuración crea la infraestructura completa del Caso 0 en AWS:

| Capa | Recursos | Nota de seguridad |
|---|---|---|
| Entrada pública | API Gateway HTTP API | Única URL pública del backend |
| Autorización | 2 authorizers JWT | Entra en `/api/**`, Cognito en `/aws/api/**` |
| Red | VPC, subredes públicas/app/datos, NAT, VPC Endpoints | Sin IP pública en tareas |
| Entrada privada | ALB interno + VPC Link | El ALB no tiene listener público |
| Ejecución | ECS Fargate: bff, catalog, orders | `assign_public_ip = false` |
| Descubrimiento | Cloud Map privado | catalog y orders sin nombres públicos |
| Datos | RDS Oracle (opcional) | Contraseñas fuera de Terraform |
| Secretos | Secrets Manager + KMS | Sin secretos en el repositorio |
| Frontend | S3 privado + CloudFront (SPA) | Bucket no público |
| Imágenes | ECR con scan-on-push | Se publican con OIDC |

## Estructura

```text
infra/aws/terraform/
├── bootstrap/     -> se aplica una vez, con estado local
└── (raíz)         -> el resto, con backend S3 remoto
```

El bootstrap va aparte por una razón técnica: **un backend remoto no puede
crear su propio contenedor**. El bucket de estado, la tabla de bloqueo, el
proveedor OIDC de GitHub y el rol de despliegue tienen que existir antes de que
exista el estado que los usa.

## 1. Bootstrap

```bash
cd infra/aws/terraform/bootstrap
cp terraform.tfvars.example terraform.tfvars
# completar github_org y github_repositories
terraform init
terraform validate
terraform plan -out=bootstrap.tfplan
terraform apply bootstrap.tfplan
```

Guarda estos valores, son las variables que necesita GitHub:

```text
state_bucket           -> TF_STATE_BUCKET
state_lock_table       -> TF_LOCK_TABLE
github_deploy_role_arn -> AWS_DEPLOY_ROLE_ARN
```

**El estado del bootstrap contiene el ARN del rol y el nombre del bucket, no
secretos.** Aun así, trátalo como información sensible y no lo subas.

## 2. Infraestructura principal

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
terraform show tfplan        # revisar ANTES de aplicar
terraform apply tfplan
```

## 3. Secretos de base de datos (obligatorio)

Los Secrets se crean **vacíos a propósito**. Terraform no genera ni guarda
contraseñas. Mientras no haya versión del secreto, ECS no inicia la tarea y lo
indica explícitamente: fallar de forma visible es preferible a arrancar con
credenciales vacías.

Crea los usuarios de aplicación en Oracle (no uses el usuario maestro para los
microservicios) y luego carga cada secreto desde un archivo temporal:

```bash
# catalog
umask 077
printf '{"username":"<CATALOG_DB_USER>","password":"<PASSWORD>"}' > /tmp/secret.json
aws secretsmanager put-secret-value \
  --secret-id pedidos360/catalog/db \
  --secret-string file:///tmp/secret.json
shred -u /tmp/secret.json

# orders, mismo procedimiento con pedidos360/orders/db
```

No escribas la contraseña en el comando: queda en el historial del shell y en la
lista de procesos. No la pegues en este repositorio, en un `.tfvars` ni en un
chat.

## 4. Imagen y despliegue

```bash
# publicar imágenes (desde cada repo, con el workflow publish-ecr.yml)
# luego fijar el tag en la infraestructura
terraform apply -var container_image_tag=<sha-del-commit>
```

El frontend no necesita imagen: se publica como estático con
`deploy-frontend.yml`, que compila con Vite y sincroniza a S3.

## Orden de despliegue en GitHub

1. Crear el environment `aws-production` con **required reviewers**.
2. Crear los secrets/environments con las variables que usan los workflows
   (ver `docs/DESPLIEGUE_AWS.md`).
3. Abrir el PR de `feature/cognito-aws` para que corra el CI.
4. `AWS Terraform` → input `apply: true` después de aprobar el environment.
5. `Publish ... to ECR` para bff, catalog y orders.
6. `Deploy frontend to S3 and CloudFront`.

## Lo que este Terraform NO hace

- **No crea el User Pool de Cognito.** Ya existe (`us-east-1_UmEhPRYdI`) y solo
  se consume su issuer y su App Client público.
- **No crea la app de Entra ID.** Se referencia por tenant y audience.
- **No crea usuarios, ni producto, ni pedidos.** Los datos de negocio se crean
  con Flyway al arrancar catalog y orders.
- **No genera ni almacena contraseñas.** Ver la sección 3.
- **No expone el backend por otra vía que API Gateway.** BFF, catalog, orders y
  Oracle no tienen IP pública, listener público ni IP flotante.

## Costo mensual aproximado (us-east-1, 2 AZ)

| Recurso | Costo aproximado |
|---|---|
| NAT Gateway (1) | ~32 USD + tráfico |
| ECS Fargate (6 tareas) | ~90 USD |
| ALB | ~18 USD + LCU |
| VPC Endpoints de interfaz (4) | ~29 USD |
| API Gateway | ~1 USD |
| S3 + CloudFront | ~2 USD |
| Oracle | según clase y licencia |

Sin incluir Oracle. Antes de aplicar, revisa el costo total: los VPC Endpoints y
el NAT Gateway se pueden facturar aunque la aplicación no se use. Para una
entrega académica se puede desactivar `enable_vpc_endpoints` y aceptar que las
tareas salgan por el NAT.

## Rutas

Las rutas de Terraform se definen por método para no reenviar métodos distintos
del contrato:

| Ruta | Métodos públicos del contrato |
|---|---|
| `/api/orders` | `GET`, `POST` |
| `/api/orders/{id}` | `GET`, `PUT`, `DELETE` |
| `/api/orders/{id}/status` | `PATCH` |
| `/api/catalog/products` | `GET`, `POST` |
| `/api/catalog/products/{id}` | `GET`, `PUT`, `DELETE` |
| `/api/catalog/products/{id}/stock` | `PATCH` |

Las mismas 12 rutas por método se duplican bajo `/aws/api/**` y usan
exclusivamente el authorizer Cognito. No existe catch-all `$default`: las rutas
no documentadas devuelven `404` y las reservas internas nunca se publican.

## Detalles que conviene conocer

- **`integration_uri` es el ARN del listener**, no una URL. API Gateway exige
  `arn:...:listener/...` o el ARN de un servicio de Cloud Map. Una URL pública
  se rechaza con `BadRequestException`.
- **Los VPC Links son inmutables.** Cambiar subredes o security groups obliga a
  recrear el link: elimínalo y vuelve a aplicar.
- **CORS depende de CloudFront.** El dominio del frontend se agrega siempre a
  los orígenes permitidos; `allowed_origins` es solo para casos adicionales.
- **El ALB es interno.** `internal = true` y su DNS no resuelve desde Internet.
- **El prefijo `/health` no existe en la API.** Los health checks del ALB van
  directo al BFF por la red privada, no por API Gateway.
- **`readonlyRootFilesystem` con tmpfs en `/tmp`**: la imagen es inmutable y la
  JVM sigue teniendo un directorio temporal escribible.

## Validación local

```bash
cd infra/aws/terraform
terraform init -backend=false
terraform fmt -check -recursive
terraform validate

cd bootstrap
terraform init -backend=false
terraform fmt -check -recursive
terraform validate
```

`terraform validate` no crea recursos en AWS, pero sí necesita descargar el
provider. No sustituye al `plan` revisado contra la cuenta real.

> `entra_api_audience` es el **Application (client) ID** de la API. El scope que
> pide el frontend es `api://150f51db-4084-4979-b1a1-e6a6e7893a01/access_as_user`;
> no se confunden audience y scope.
