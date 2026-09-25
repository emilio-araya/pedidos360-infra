# Terraform — API Gateway HTTP API

Este directorio es un **esqueleto válido** para la capa de entrada de Pedidos360. Crea una AWS API Gateway HTTP API con:

- authorizer JWT de Microsoft Entra ID para `/api/**`;
- authorizer JWT de Amazon Cognito para `/aws/api/**`;
- audiencia de `pedidos360-api` para Entra y App Client público `59be26pgg5ginu2sutr8eetgjg` para Cognito;
- CORS limitado a los orígenes suministrados;
- VPC Link con subredes y security groups existentes para una integración privada;
- proxy de las rutas públicas `/api/orders/*`, `/api/catalog/*` y sus equivalentes `/aws/api/*` hacia el listener/Service Connect privado del BFF;
- repositorios ECR cifrados con scan-on-push para `frontend`, `bff`, `catalog` y `orders`;
- etapa predeterminada con auto deploy y métricas.

No se crea una ruta `$default`. Tampoco se publican las operaciones internas `/internal/catalog/stock/reservations` del catálogo.

## Límite importante: no es el despliegue backend completo

La arquitectura exige que BFF, catalog, orders y Oracle estén en una red privada. **Este esqueleto crea un VPC Link usando subredes y security groups de una VPC existente, pero no crea la VPC, sus rutas, NAT, balanceador interno, ECS/Fargate ni una base Oracle gestionada.** Antes de `terraform apply` se debe:

1. definir la red privada y el runtime de los tres servicios backend, preferiblemente ECS/Fargate o una solución equivalente;
2. colocar Oracle en subredes privadas, con secretos gestionados, copias de seguridad y alta disponibilidad según requisitos;
3. implementar y validar el mecanismo privado que permite a API Gateway alcanzar **solo** el BFF;
4. resolver DNS, TLS, timeouts y reglas de security groups de esa conectividad;
5. configurar el URI de integración para el BFF y mantener cerrados catalog y orders.

La integración exige `connection_type = "VPC_LINK"` y un ARN privado de listener o Service Connect; una URL pública no es válida. API Gateway conserva la **única URL pública del backend**. El frontend se publica por separado, por ejemplo como un sitio estático; no habilita puertos backend adicionales.

## Archivos

- `versions.tf`: versiones de Terraform y del provider AWS.
- `variables.tf`: región, tenant, audiencia, orígenes CORS, VPC Link y destino privado del BFF.
- `main.tf`: HTTP API, dos JWT authorizers, repositorios ECR, VPC Link, integración privada, rutas y stage.
- `outputs.tf`: ID, endpoint y rutas desplegadas.

No se incluye backend de Terraform. Para producción se debe añadir un backend remoto cifrado (por ejemplo, S3 con bloqueo y DynamoDB para lock) antes del primer `apply`.

## Validación local

```bash
cd infra/aws/terraform
terraform init -backend=false
terraform fmt -check -recursive
terraform validate
```

La instalación de providers requiere acceso de red. `terraform validate` no crea recursos en AWS.

## Ejemplo de variables

No se versionan archivos `*.tfvars` porque pueden contener identificadores o parámetros del entorno. Para un plan manual se puede usar un archivo local no versionado, por ejemplo `dev.auto.tfvars`:

```hcl
aws_region         = "us-east-1"
entra_tenant_id    = "<TENANT_ID_GUID>"
entra_api_audience = "150f51db-4084-4979-b1a1-e6a6e7893a01"
cognito_user_pool_id = "us-east-1_UmEhPRYdI"
cognito_region = "us-east-1"
cognito_issuer = "https://cognito-idp.us-east-1.amazonaws.com/us-east-1_UmEhPRYdI"
cognito_api_audience = "59be26pgg5ginu2sutr8eetgjg"
cognito_authorization_scopes = ["openid"]
allowed_origins       = ["https://pedidos360.example.com"]
bff_integration_uri   = "arn:aws:elasticloadbalancing:<region>:<account>:listener/app/private-bff/<id>/<id>"
bff_tls_server_name   = "bff.private.example.com"
vpc_subnet_ids        = ["subnet-private-a", "subnet-private-b"]
vpc_security_group_ids = ["sg-api-gateway-private"]

tags = {
  Project   = "Pedidos360"
  ManagedBy = "Terraform"
}
```

> `entra_api_audience` es el **Application (client) ID** de la API. El scope solicitado por el frontend es `api://150f51db-4084-4979-b1a1-e6a6e7893a01/access_as_user`; no se confunden audience y scope.

## Plan y despliegue condicionado

Una vez implementado el diseño de red e integración privada:

```bash
terraform init
terraform fmt -check -recursive
terraform validate
terraform plan -out=tfplan
terraform show tfplan
terraform apply tfplan
```

No se debe aplicar este esqueleto apuntando a un BFF público. Durante la implementación completa, se deben añadir los recursos de red, IAM, secretos, observabilidad y ciclo de vida que falten, y revisar el plan antes de cambios productivos.

## Rutas

Las rutas de Terraform se definen por método para no reenviar métodos distintos del contrato:

| Ruta | Métodos públicos del contrato |
|---|---|
| `/api/orders` | `GET`, `POST` |
| `/api/orders/{id}` | `GET`, `PUT`, `DELETE` |
| `/api/orders/{id}/status` | `PATCH` |
| `/api/catalog/products` | `GET`, `POST` |
| `/api/catalog/products/{id}` | `GET`, `PUT`, `DELETE` |
| `/api/catalog/products/{id}/stock` | `PATCH` |

Las mismas 12 rutas por método se duplican bajo `/aws/api/**` y usan exclusivamente el authorizer Cognito. No existe catch-all `$default`; las reservas internas nunca se publican.

La autorización por rol sigue en BFF y microservicios. API Gateway valida el JWT, pero no reemplaza la segunda validación ni las reglas de `Admin`, `Operador` y `Cliente`.
