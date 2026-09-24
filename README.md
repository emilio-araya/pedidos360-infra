# Pedidos360 infrastructure

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

```bash
cp .env.example .env
chmod +x scripts/*.sh
./scripts/run-local.sh
```

El Compose construye los cuatro componentes desde sus repositorios hermanos. Solo frontend y BFF publican puertos locales; Catalog, Orders y Oracle permanecen en redes privadas.

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
- Los repositorios de servicios construyen sus propias imágenes y verifican sus pruebas de forma independiente.

## AWS

La capa de API Gateway está en `infra/aws/terraform`. BFF, Catalog, Orders y Oracle deben ejecutarse en una VPC privada; API Gateway es la única entrada pública del backend. El despliegue completo requiere configurar VPC, runtime privado, Oracle, secretos, imágenes y el rol OIDC de GitHub antes de ejecutar `apply`.

El contrato público está en `infra/aws/openapi.yaml`.

## Base de datos

La base de datos no se guarda en Git ni en este repositorio. En local se conserva en el volumen Docker `pedidos360_oracle-data`; en AWS se debe usar Oracle privado con migraciones y secretos gestionados.
