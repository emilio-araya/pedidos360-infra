# ---------------------------------------------------------------------------
# Observabilidad mínima: grupos de logs de las tareas y de API Gateway.
#
# Para access logs de una HTTP API, API Gateway escribe con su rol administrado
# (AWSServiceRoleForAPIGateway), que AWS crea y administer por su cuenta. No
# hace falta concederle permisos a mano.
#
# Antes esta archivo concedía permisos sobre ese rol mediante un data source
# protegido con try(). Eso no funcionaba: un try() no evita el error de lectura
# del data source, porque la lectura ocurre antes de evaluar la expresión. Con un
# rol sin permiso iam:GetRole el plan fallaba entero.
# ---------------------------------------------------------------------------

resource "aws_cloudwatch_log_group" "services" {
  for_each = toset(["bff", "catalog", "orders"])

  name              = "/ecs/${var.cluster_name}/${each.key}"
  retention_in_days = var.log_retention_days

  tags = merge(local.common_tags, { Name = "${local.name}-${each.key}" })
}

resource "aws_cloudwatch_log_group" "api_access" {
  name              = "/aws/apigateway/${var.api_name}"
  retention_in_days = var.log_retention_days

  tags = merge(local.common_tags, { Name = "${local.name}-api-access" })
}
