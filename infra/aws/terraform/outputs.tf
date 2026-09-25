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

output "entra_route_keys" {
  description = "Rutas Entra bajo /api."
  value       = sort(tolist(local.entra_route_keys))
}

output "cognito_route_keys" {
  description = "Rutas Cognito bajo /aws/api."
  value       = sort(tolist(local.cognito_route_keys))
}

output "entra_authorizer_id" {
  description = "ID del authorizer JWT de Microsoft Entra."
  value       = aws_apigatewayv2_authorizer.entra_jwt.id
}

output "cognito_authorizer_id" {
  description = "ID del authorizer JWT de Amazon Cognito."
  value       = aws_apigatewayv2_authorizer.cognito_jwt.id
}

output "ecr_repository_urls" {
  description = "URLs de los repositorios ECR para las cuatro imágenes."
  value       = { for name, repository in aws_ecr_repository.services : name => repository.repository_url }
}

output "route_keys" {
  description = "Rutas públicas de ambos proveedores."
  value       = sort(concat(tolist(local.entra_route_keys), tolist(local.cognito_route_keys)))
}
