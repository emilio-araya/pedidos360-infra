# ---------------------------------------------------------------------------
# Valores de salida
#
# No se exportan secretos, tokens ni cadenas de conexión con contraseña.
# Los identificadores allows son útiles para evidencia y para configurar los
# workflows y los registros de aplicación de Entra y Cognito.
# ---------------------------------------------------------------------------

output "api_id" {
  description = "Identificador de la HTTP API."
  value       = aws_apigatewayv2_api.pedidos360.id
}

output "api_endpoint" {
  description = "URL pública de API Gateway. Es la única entrada pública al backend."
  value       = aws_apigatewayv2_api.pedidos360.api_endpoint
}

output "stage_execution_arn" {
  description = "ARN de ejecución de la etapa predeterminada."
  value       = aws_apigatewayv2_stage.default.execution_arn
}

output "bff_vpc_link_id" {
  description = "VPC Link privado que conecta API Gateway con el ALB interno."
  value       = aws_apigatewayv2_vpc_link.bff.id
}

output "bff_integration_uri" {
  description = "ARN del listener privado usado por la integración de API Gateway."
  value       = aws_apigatewayv2_integration.bff.integration_uri
}

output "internal_alb_dns" {
  description = "DNS del ALB interno. Solo resuelve dentro de la VPC."
  value       = aws_lb.bff.dns_name
}

output "entra_authorizer_id" {
  description = "ID del authorizer JWT de Microsoft Entra."
  value       = aws_apigatewayv2_authorizer.entra_jwt.id
}

output "cognito_authorizer_id" {
  description = "ID del authorizer JWT de Amazon Cognito."
  value       = aws_apigatewayv2_authorizer.cognito_jwt.id
}

output "entra_route_keys" {
  description = "Rutas Entra bajo /api."
  value       = sort(tolist(local.entra_route_keys))
}

output "cognito_route_keys" {
  description = "Rutas Cognito bajo /aws/api."
  value       = sort(tolist(local.cognito_route_keys))
}

output "route_keys" {
  description = "Rutas públicas de ambos proveedores."
  value       = sort(concat(tolist(local.entra_route_keys), tolist(local.cognito_route_keys)))
}

output "ecr_repository_urls" {
  description = "URLs de los repositorios ECR de los servicios del backend."
  value       = { for name, repository in aws_ecr_repository.services : name => repository.repository_url }
}

output "frontend_bucket" {
  description = "Bucket S3 del frontend estático."
  value       = aws_s3_bucket.frontend.bucket
}

output "frontend_url" {
  description = "URL pública del frontend. Debe registrarse en Entra y en el App Client de Cognito."
  value       = "https://${aws_cloudfront_distribution.frontend.domain_name}"
}

output "frontend_entra_redirect_uris" {
  description = "URIs de redirección que deben registrarse en la app SPA de Microsoft Entra."
  value = [
    "https://${aws_cloudfront_distribution.frontend.domain_name}/login",
  ]
}

output "frontend_cognito_redirect_uris" {
  description = "Callback y logout que deben registrarse en el App Client de Cognito."
  value = {
    callback_urls = ["https://${aws_cloudfront_distribution.frontend.domain_name}/auth/cognito/callback"]
    logout_urls   = ["https://${aws_cloudfront_distribution.frontend.domain_name}/login"]
  }
}

output "api_allowed_origins" {
  description = "Orígenes exactos permitidos por CORS."
  value       = local.allowed_origins
}

output "vpc_id" {
  description = "Identificador de la VPC."
  value       = aws_vpc.main.id
}

output "app_subnet_ids" {
  description = "Subredes privadas de aplicación."
  value       = local.app_subnet_ids
}

output "ecs_cluster_name" {
  description = "Cluster de ECS."
  value       = aws_ecs_cluster.main.name
}

output "ecs_service_names" {
  description = "Servicios de ECS creados."
  value       = sort(keys(aws_ecs_service.service))
}

output "database_secret_arns" {
  description = "Secretos que el operador debe completar con las credenciales de cada aplicación. No contienen valores en este repositorio."
  value       = local.database_secret_arns
}

output "log_group_names" {
  description = "Grupos de logs de CloudWatch."
  value       = { for name, group in aws_cloudwatch_log_group.services : name => group.name }
}

output "oracle_endpoint" {
  description = "Host de Oracle en uso, gestionado por esta configuración o provisto por el operador."
  value       = local.oracle_endpoint
  sensitive   = false
}

output "rds_oracle_enabled" {
  description = "Indica si esta configuración administra la instancia de Oracle."
  value       = var.create_rds_oracle
}
