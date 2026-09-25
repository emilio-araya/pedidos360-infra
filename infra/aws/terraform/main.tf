provider "aws" {
  region = var.aws_region

  default_tags {
    tags = var.tags
  }
}

resource "aws_apigatewayv2_api" "pedidos360" {
  name          = var.api_name
  description   = "Entrada pública única de Pedidos360; el backend permanece privado."
  protocol_type = "HTTP"

  cors_configuration {
    allow_origins = var.allowed_origins
    allow_methods = ["GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"]
    allow_headers = ["Authorization", "Content-Type", "X-Request-Id"]
    max_age       = 3600
  }

  tags = {
    Name = var.api_name
  }
}

resource "aws_apigatewayv2_authorizer" "entra_jwt" {
  api_id           = aws_apigatewayv2_api.pedidos360.id
  name             = "entra-jwt"
  authorizer_type  = "JWT"
  identity_sources = ["$request.header.Authorization"]

  jwt_configuration {
    issuer   = "https://login.microsoftonline.com/${var.entra_tenant_id}/v2.0"
    audience = [var.entra_api_audience]
  }
}

resource "aws_ecr_repository" "services" {
  for_each = toset(["frontend", "bff", "catalog", "orders"])

  name                 = "${var.api_name}-${each.value}"
  image_tag_mutability = "MUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "AES256"
  }

  tags = {
    Name = "${var.api_name}-${each.value}"
  }
}

resource "aws_apigatewayv2_authorizer" "cognito_jwt" {
  api_id           = aws_apigatewayv2_api.pedidos360.id
  name             = "cognito-jwt"
  authorizer_type  = "JWT"
  identity_sources = ["$request.header.Authorization"]

  jwt_configuration {
    issuer   = var.cognito_issuer
    audience = [var.cognito_api_audience]
  }
}

# El VPC Link se crea en una VPC ya existente. Las subredes y el security group
# deben permitir tráfico únicamente desde/hacia la infraestructura privada.
resource "aws_apigatewayv2_vpc_link" "bff" {
  name               = "${var.api_name}-bff"
  security_group_ids = var.vpc_security_group_ids
  subnet_ids         = var.vpc_subnet_ids
}

# La URI debe ser el ARN de un listener de ALB/NLB o de un servicio Cloud Map
# dentro de la red privada. HTTP_PROXY + VPC_LINK es obligatorio para HTTP API.
resource "aws_apigatewayv2_integration" "bff" {
  api_id               = aws_apigatewayv2_api.pedidos360.id
  integration_type     = "HTTP_PROXY"
  integration_method   = "ANY"
  connection_type      = "VPC_LINK"
  connection_id        = aws_apigatewayv2_vpc_link.bff.id
  integration_uri      = var.bff_integration_uri
  timeout_milliseconds = 29000

  tls_config {
    server_name_to_verify = var.bff_tls_server_name
  }
}

check "cognito_issuer_consistency" {
  assert {
    condition     = var.cognito_issuer == "https://cognito-idp.${var.cognito_region}.amazonaws.com/${var.cognito_user_pool_id}"
    error_message = "cognito_issuer debe coincidir con cognito_region y cognito_user_pool_id."
  }
}

locals {
  route_keys = [
    "GET /api/orders",
    "POST /api/orders",
    "GET /api/orders/{id}",
    "PUT /api/orders/{id}",
    "DELETE /api/orders/{id}",
    "PATCH /api/orders/{id}/status",
    "GET /api/catalog/products",
    "POST /api/catalog/products",
    "GET /api/catalog/products/{id}",
    "PUT /api/catalog/products/{id}",
    "DELETE /api/catalog/products/{id}",
    "PATCH /api/catalog/products/{id}/stock",
  ]
  entra_route_keys   = toset(local.route_keys)
  cognito_route_keys = toset([for route in local.route_keys : replace(route, "/api/", "/aws/api/")])
}

resource "aws_apigatewayv2_route" "entra" {
  for_each = local.entra_route_keys

  api_id             = aws_apigatewayv2_api.pedidos360.id
  route_key          = each.value
  target             = "integrations/${aws_apigatewayv2_integration.bff.id}"
  authorization_type = "JWT"
  authorizer_id      = aws_apigatewayv2_authorizer.entra_jwt.id
}

resource "aws_apigatewayv2_route" "cognito" {
  for_each = local.cognito_route_keys

  api_id               = aws_apigatewayv2_api.pedidos360.id
  route_key            = each.value
  target               = "integrations/${aws_apigatewayv2_integration.bff.id}"
  authorization_type   = "JWT"
  authorizer_id        = aws_apigatewayv2_authorizer.cognito_jwt.id
  authorization_scopes = var.cognito_authorization_scopes
}

resource "aws_apigatewayv2_stage" "default" {
  api_id      = aws_apigatewayv2_api.pedidos360.id
  name        = "$default"
  auto_deploy = true

  depends_on = [
    aws_apigatewayv2_route.entra,
    aws_apigatewayv2_route.cognito,
  ]

  default_route_settings {
    detailed_metrics_enabled = true
    throttling_burst_limit   = 100
    throttling_rate_limit    = 50
  }

  tags = {
    Name = "${var.api_name}-default"
  }
}
