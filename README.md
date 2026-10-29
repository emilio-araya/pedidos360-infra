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

## Mensajería

- RabbitMQ corre como **clúster de dos nodos** (`rabbitmq-1`, `rabbitmq-2`): ver [`docs/MENSAJERIA_RABBITMQ.md`](docs/MENSAJERIA_RABBITMQ.md).
- [`messaging-admin/`](messaging-admin/README.md) es el microservicio que administra colas, exchanges y bindings por API REST.
- Guion de demostración con los comandos de cada punto: [`docs/DEMO_RABBITMQ.md`](docs/DEMO_RABBITMQ.md).

## Configuración local

### Opción 1: Stack completo con Oracle (recomendado para desarrollo real)

```bash
cp .env.example .env
chmod +x scripts/*.sh
./scripts/run-local.sh
```

El Compose construye los cuatro componentes desde sus repositorios hermanos. Solo frontend y BFF publican puertos locales; Catalog, Orders y Oracle permanecen en redes privadas.

El Compose también levanta Mailpit para los correos de la compra como invitado. Su SMTP (`1025`) solo es accesible dentro de Docker; los correos enviados se ven en `http://127.0.0.1:8025`. Las variables `MAIL_*` y `FRONTEND_PUBLIC_URL` tienen valores locales por defecto; el detalle y la configuración con Amazon SES están en [`docs/COMPRA_INVITADO.md`](./docs/COMPRA_INVITADO.md).

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
- La mensajería con RabbitMQ (outbox, contrato de eventos, reintentos y DLQ) está en `docs/MENSAJERIA_RABBITMQ.md`.
- La compra como invitado (enlace secreto, correo con Mailpit o SES, rutas públicas y throttling) está en `docs/COMPRA_INVITADO.md`.

## AWS

La capa de API Gateway está en `infra/aws/terraform`. El diseño despliega BFF, Catalog, Orders y Oracle en una VPC privada, con API Gateway como única entrada pública del backend. El estado real está abajo.

### Estado del despliegue

Estado verificado el 2026-10-28 en la cuenta del AWS Academy Learner Lab (`us-east-1`), con consultas de solo lectura (`aws ... describe/get/list`).

**Lo que funciona hoy:** el backend corre en una instancia EC2 (`pedidos360-backend`) con Docker Compose, levantada **fuera de Terraform**. Es la que se usa para la demo.

**Lo que creó Terraform:** un `apply` anterior quedó a medias, por los límites del laboratorio:

| Componente | Estado | Detalle |
|---|---|---|
| VPC `10.20.0.0/16` | existe | 4 subredes en 2 AZ, tablas de rutas e internet gateway |
| Security groups | existen | 6 grupos de la aplicación |
| API Gateway | existe | 2 authorizers (Entra y Cognito), 24 rutas con JWT, stage `$default`; versión anterior a la compra como invitado |
| VPC Link y target group | existen | sin balanceador detrás: el API rechaza sin token (`401`), pero no tiene backend al que enviar las peticiones válidas |
| Bucket del frontend | existe | vacío |
| NAT gateway | no existe | quedó su IP elástica sin asociar |
| ALB interno | no existe | — |
| Bootstrap (bucket de estado, DynamoDB, OIDC) | no existe | no hay estado remoto en esta cuenta |
| ECS Fargate | bloqueado | el laboratorio niega `iam:CreateRole`; solo permite pasar el `LabRole` existente |
| CloudFront, RDS, Cloud Map | bloqueados | la simulación de permisos los niega (puede haber excepciones condicionales) |

El estado de Terraform de esta cuenta no se conserva, así que lo creado quedó **sin administrar**: un `terraform apply` desde cero intentaría crear todo de nuevo. Desplegar el Terraform completo requiere una cuenta AWS que permita crear roles IAM, o adaptarlo para usar el `LabRole`.

El contrato público está en `infra/aws/openapi.yaml` y el procedimiento de despliegue por fases en `docs/DESPLIEGUE_AWS.md`.

La compra como invitado agrega seis rutas sin authorizer (las lecturas del catálogo bajo `/api` y las cuatro de `/api/guest-orders`), con throttling propio en los dos `POST` públicos. Están escritas y validadas (`terraform validate`), pero no aplicadas, por el mismo bloqueo. Ver `docs/COMPRA_INVITADO.md`.

## Base de datos

La base de datos no se guarda en Git ni en este repositorio. En local se conserva en el volumen Docker `pedidos360_oracle-data`; en AWS se debe usar Oracle privado con migraciones y secretos gestionados.
