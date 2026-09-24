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

locals {
  route_keys = toset([
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
  ])
}

resource "aws_apigatewayv2_route" "bff" {
  for_each = local.route_keys

  api_id             = aws_apigatewayv2_api.pedidos360.id
  route_key          = each.value
  target             = "integrations/${aws_apigatewayv2_integration.bff.id}"
  authorization_type = "JWT"
  authorizer_id      = aws_apigatewayv2_authorizer.entra_jwt.id
}

resource "aws_apigatewayv2_stage" "default" {
  api_id      = aws_apigatewayv2_api.pedidos360.id
  name        = "$default"
  auto_deploy = true

  depends_on = [aws_apigatewayv2_route.bff]

  default_route_settings {
    detailed_metrics_enabled = true
    throttling_burst_limit   = 100
    throttling_rate_limit    = 50
  }

  tags = {
    Name = "${var.api_name}-default"
  }
}
