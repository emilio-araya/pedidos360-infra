# Bootstrap

Se aplica **una vez**, con estado local, antes que el resto de la configuración.

## Por qué está separado

La configuración principal usa un backend S3 remoto. Un backend remoto no puede
crear su propio contenedor: si el bucket viviera en la misma configuración que lo
usa, el primer `terraform init` fallaría porque el bucket aún no existe.

Por eso el bootstrap crea, en este orden:

1. Bucket S3 cifrado y versionado para el estado de Terraform.
2. Tabla DynamoDB para el bloqueo de estado.
3. Proveedor OIDC de GitHub Actions.
4. Rol de despliegue que asumen los workflows, sin llaves de acceso.

Después, sus outputs alimentan las variables de GitHub y el `init` con
`-backend-config` de la configuración principal.

## Uso

```bash
cd infra/aws/terraform/bootstrap
cp terraform.tfvars.example terraform.tfvars
# completar github_org y github_repositories

terraform init
terraform fmt -check -recursive
terraform validate
terraform plan -out=bootstrap.tfplan
terraform apply bootstrap.tfplan
```

## Qué anotar

```bash
terraform output state_bucket
terraform output state_lock_table
terraform output github_deploy_role_arn
```

Esos tres valores se usan en el environment `aws-production` de GitHub.

## Sobre el estado local

El estado de esta configuración queda en `terraform.tfstate` dentro del
directorio, y `.gitignore` lo excluye. Contiene nombres de recursos y el ARN del
rol: no contiene secretos, pero trátalo como información sensible, no lo subas
a ningún lado y bórralo si el equipo lo exige.

Si prefieres no conservar estado local, aplícalo y luego borra el archivo:

```bash
rm -f terraform.tfstate terraform.tfstate.backup
```

## La política de confianza

El rol acepta únicamente los subjects de OIDC declarados:

```text
repo:<org>/<repo>:environment:aws-production
repo:<org>/<repo>:ref:refs/heads/main
```

La condición `aud` es `sts.amazonaws.com`. Los workflows que no usen
`environment:` quedan fuera de la política: es intencional, porque el
environment con required reviewers es la que aporta la revisión humana.

Si agregas un repositorio nuevo, actualiza `github_repositories` y vuelve a
aplicar antes de ejecutar su workflow.

## Permisos del rol

La política es explícita para que una revisión note cualquier permiso nuevo. Dos
límites deliberados:

- Los permisos de IAM están limitados al path `pedidos360/*`.
- No hay `iam:PassRole` fuera de ese path, ni permisos de Organizations, ni
  acceso a datos de otras cuentas.

Para producción conviene mover el rol a una cuenta de pipeline dedicada.
