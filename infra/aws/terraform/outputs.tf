output "api_id" {
  description = "Identificador de la HTTP API."
  value       = aws_apigatewayv2_api.pedidos360.id
}

output "api_endpoint" {
  description = "URL pública de API Gateway. Es la única entrada pública al backend."
  value       = aws_apigatewayv2_api.pedidos360.api_endpoint
}

output "bff_vpc_link_id" {
  description = "VPC Link privado utilizado por API Gateway para alcanzar el BFF."
  value       = aws_apigatewayv2_vpc_link.bff.id
}

output "stage_execution_arn" {
  description = "ARN de ejecución de la etapa predeterminada."
  value       = aws_apigatewayv2_stage.default.execution_arn
}

output "route_keys" {
  description = "Rutas públicas autenticadas incluidas en el esqueleto."
  value       = sort(tolist(local.route_keys))
}
