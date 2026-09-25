# ---------------------------------------------------------------------------
# Observabilidad mínima: grupos de logs de las tareas y de API Gateway.
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

# El rol administered de API Gateway solo existe después del primer despliegue.
# try() permite trabajar con la cuenta vacía sin abortar el plan.
locals {
  apigateway_slr_arn = try(data.aws_iam_role.apigateway_service_linked.arn, null)
}

resource "aws_iam_role_policy" "apigateway_access_logs" {
  count = local.apigateway_slr_arn == null ? 0 : 1

  name = "${local.name}-api-access-logs"
  role = local.apigateway_slr_arn

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "WriteApiAccessLogs"
        Effect = "Allow"
        Action = [
          "logs:CreateLogStream",
          "logs:PutLogEvents",
          "logs:DescribeLogStreams",
        ]
        Resource = "${aws_cloudwatch_log_group.api_access.arn}:*"
      },
      {
        Sid      = "DescribeLogGroup"
        Effect   = "Allow"
        Action   = "logs:DescribeLogGroups"
        Resource = "*"
      },
    ]
  })
}
