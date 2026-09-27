# ---------------------------------------------------------------------------
# Oracle en AWS (opcional)
#
# RDS no acepta la imagen gvenzl/oracle-free que usa el compose de desarrollo.
# Habilitar esta opción implica una decisión de licencia y un costo mensual:
#
#   license-included         -> Oracle Database@AWS con licencia incluida
#   bring-your-own-license   -> el/license de Oracle se aporta por separado
#
# Por eso create_rds_oracle está en false. Con la opción apagada hay que
# proporcionar un oracle_host propio y seguir conectando por Secrets Manager.
#
# Even cuando esta opción crea la instancia, catalog y orders NO reciben el
# usuario maestro: cada uno usa su propio secreto, con un usuario de aplicación
# que se crea después del apply.
# ---------------------------------------------------------------------------

resource "aws_db_subnet_group" "oracle" {
  count = var.create_rds_oracle ? 1 : 0

  name        = "${local.name}-oracle"
  description = "Subredes privadas de datos para Oracle"
  subnet_ids  = local.data_subnet_ids

  tags = merge(local.common_tags, { Name = "${local.name}-oracle" })
}

resource "aws_db_parameter_group" "oracle" {
  count = var.create_rds_oracle ? 1 : 0

  name        = "${local.name}-oracle"
  description = "Parametrizacion de Pedidos360 para Oracle"
  family      = split(".", var.rds_oracle_engine_version)[0]

  parameter {
    name  = "open_cursors"
    value = "1000"
  }

  tags = merge(local.common_tags, { Name = "${local.name}-oracle" })
}

resource "aws_db_instance" "oracle" {
  count = var.create_rds_oracle ? 1 : 0

  identifier     = "${local.name}-oracle"
  engine         = split(".", var.rds_oracle_engine_version)[0]
  engine_version = var.rds_oracle_engine_version
  license_model  = var.rds_oracle_license_model

  instance_class                  = var.rds_oracle_instance_class
  allocated_storage               = var.rds_oracle_allocated_storage
  max_allocated_storage           = var.rds_oracle_allocated_storage * 2
  storage_type                    = "gp3"
  storage_encrypted               = true
  kms_key_id                      = aws_kms_key.secrets.arn
  multi_az                        = var.rds_oracle_multi_az
  publicly_accessible             = false
  db_subnet_group_name            = aws_db_subnet_group.oracle[0].name
  vpc_security_group_ids          = [aws_security_group.rds[0].id]
  parameter_group_name            = aws_db_parameter_group.oracle[0].name
  backup_retention_period         = var.rds_oracle_backup_retention_days
  deletion_protection             = var.rds_oracle_deletion_protection
  skip_final_snapshot             = false
  final_snapshot_identifier       = "${local.name}-oracle-final"
  copy_tags_to_snapshot           = true
  auto_minor_version_upgrade      = true
  performance_insights_enabled    = true
  performance_insights_kms_key_id = aws_kms_key.secrets.arn

  username                    = var.rds_oracle_master_username
  manage_master_user_password = true
  port                        = var.oracle_port

  tags = merge(local.common_tags, { Name = "${local.name}-oracle" })

  lifecycle {
    ignore_changes = [password]
  }
}
