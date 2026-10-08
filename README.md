# Pedidos360 infrastructure

[![CI](https://github.com/emilio-araya/pedidos360-infra/actions/workflows/ci.yml/badge.svg)](https://github.com/emilio-araya/pedidos360-infra/actions/workflows/ci.yml)
[![Terraform](https://img.shields.io/badge/Terraform-1.9.8-7B42BC?logo=terraform&logoColor=white)](https://www.terraform.io)
[![AWS](https://img.shields.io/badge/AWS-232F3E?logo=amazonaws&logoColor=white)](https://aws.amazon.com)
[![Docker](https://img.shields.io/badge/Docker-2496ED?logo=docker&logoColor=white)](https://www.docker.com)

Repository central de infraestructura de Pedidos360. Contiene Docker Compose, Terraform, OpenAPI, scripts y documentación compartida.

## Repositorios hermanos

El Compose local espera los otros cuatro repositorios como carpetas hermanas:

```text
Pedidos360-repos/
├── pedidos360-frontend/
├── pedidos360-bff/
├── pedidos360-catalog/
├── pedidos360-orders/
└── pedidos360-infra/
```

Clona los cinco repositorios con esos nombres. No se versionan credenciales, `.env` ni el volumen de Oracle.

## Configuración local

### Opción 1: Stack completo con Oracle (recomendado para desarrollo real)

```bash
cp .env.example .env
chmod +x scripts/*.sh
./scripts/run-local.sh
```

El Compose construye los cuatro componentes desde sus repositorios hermanos. Solo frontend y BFF publican puertos locales; Catalog, Orders y Oracle permanecen en redes privadas.

### Opción 2: Stack con WireMock (desarrollo rápido sin Oracle)

```bash
cp .env.example .env
chmod +x scripts/*.sh
./scripts/start-with-wiremock.sh
```

Esta opción usa WireMock como Identity Provider simulado en lugar de HMAC. Es más segura y simula mejor el flujo real de autenticación. Ver [`wiremock/README.md`](./wiremock/README.md) para más detalles.

Para detener conservando los datos:

```bash
./scripts/stop-local.sh
```

Para borrar el volumen local de Oracle:

```bash
docker compose --env-file .env -f docker-compose.yml down --volumes
```

## GitHub Actions

- `ci.yml` valida Terraform, Docker Compose y scripts en cada push o pull request.
- El workflow de despliegue AWS se ejecuta manualmente y usa GitHub OIDC; no requiere almacenar AWS access keys en el repositorio.
- Cada repositorio de aplicación tiene `publish-ecr.yml` para publicar su imagen en ECR con el mismo rol OIDC; Terraform crea los cuatro repositorios con cifrado y scan-on-push.
- La configuración de Cognito, callbacks, PKCE y grupos está en `docs/CONFIGURACION_COGNITO.md`.

## AWS

La capa de API Gateway está en `infra/aws/terraform`. BFF, Catalog, Orders y Oracle se despliegan en una VPC privada; API Gateway es la única entrada pública del backend.

### Estado del despliegue

La capa de red y la puerta de entrada **están aplicadas y verificadas** en una cuenta de laboratorio. El runtime y la base de datos están escritos y validados, pero su aplicación quedó bloqueada.

| Componente | Estado | Detalle |
|---|---|---|
| VPC `10.20.0.0/16` | aplicado | 4 subredes en 2 AZ, subredes de aplicación sin IP pública |
| Security groups | aplicado | 6 grupos, tráfico entre capas restringido |
| NAT gateway | aplicado | salida a internet desde subredes privadas |
| ALB interno | aplicado | única entrada al BFF, sin exposición a Internet |
| VPC Link | aplicado | enlace privado hacia el ALB |
| API Gateway | aplicado | 2 authorizers (Entra y Cognito), 24 rutas, stage `$default` |
| Bootstrap de Terraform | aplicado | bucket de estado, lock en DynamoDB, OIDC de GitHub, rol de despliegue |
| ECS Fargate | bloqueado | el rol del laboratorio no tiene permisos y la organización aplica una SCP que lo impide |
| Secrets Manager | bloqueado | mismo bloqueo de permisos |
| CloudFront + S3 | bloqueado | depende de que la API esté viva primero (desacople de CORS) |
| RDS Oracle | pendiente | requiere una decisión de licencia antes de crearlo |

La verificación ejecutada sobre lo aplicado cubrió: `/api/**` y `/aws/api/**` sin token devuelven `401`; rutas fuera del contrato y operaciones internas devuelven `404`; el ALB no responde desde Internet. El contrato público está en `infra/aws/openapi.yaml` y el procedimiento de despliegue por fases en `docs/DESPLIEGUE_AWS.md`.

## Base de datos

La base de datos no se guarda en Git ni en este repositorio. En local se conserva en el volumen Docker `pedidos360_oracle-data`; en AWS se debe usar Oracle privado con migraciones y secretos gestionados.
