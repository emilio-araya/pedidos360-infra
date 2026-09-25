data "aws_caller_identity" "current" {}

data "aws_partition" "current" {}

data "aws_iam_role" "apigateway_service_linked" {
  # La API de HTTP API escribe access logs con este rol administered. Puede no
  # existir todavía; try() evita abortar el plan en cuentas nuevas.
  name = "AWSServiceRoleForAPIGateway"
}
