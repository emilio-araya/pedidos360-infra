# ---------------------------------------------------------------------------
# Secretos de base de datos
#
# Regla del proyecto: ninguna contraseña se escribe en este repositorio, en un
# archivo .tfvars ni en el estado de forma legible. Por eso los Secrets se
# crean VACÍOS a propósito y el operador carga el valor con la CLI de AWS
# después del primer apply.
#
# Mientras no exista una versión del secreto, ECS no puede iniciar la tarea y
# el error lo dice explícitamente. Ese es el comportamiento correcto: es
# preferible fallar de forma visible a arrancar con credenciales vacías.
#
# Cada microservicio tiene su propio usuario y su propio secreto. Ninguno
# recibe el usuario maestro de Oracle.
# ---------------------------------------------------------------------------

resource "aws_secretsmanager_secret" "catalog_db" {
  name                    = "${local.name}/catalog/db"
  description             = "Credenciales JDBC de catalog. Se carga fuera de Terraform."
  recovery_window_in_days = 0
  kms_key_id              = aws_kms_key.secrets.arn

  tags = merge(local.common_tags, { Name = "${local.name}-catalog-db" })
}

resource "aws_secretsmanager_secret" "orders_db" {
  name                    = "${local.name}/orders/db"
  description             = "Credenciales JDBC de orders. Se carga fuera de Terraform."
  recovery_window_in_days = 0
  kms_key_id              = aws_kms_key.secrets.arn

  tags = merge(local.common_tags, { Name = "${local.name}-orders-db" })
}

resource "aws_kms_key" "secrets" {
  description             = "Cifrado de los secretos de Pedidos360"
  deletion_window_in_days = 7
  enable_key_rotation     = true

  tags = merge(local.common_tags, { Name = "${local.name}-secrets" })
}

resource "aws_kms_alias" "secrets" {
  name          = "alias/${local.name}-secrets"
  target_key_id = aws_kms_key.secrets.key_id
}
