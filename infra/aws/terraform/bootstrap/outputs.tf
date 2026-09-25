output "state_bucket" {
  description = "Bucket S3 del estado remoto de Terraform."
  value       = aws_s3_bucket.state.bucket
}

output "state_bucket_region" {
  description = "Región del bucket de estado."
  value       = var.aws_region
}

output "state_lock_table" {
  description = "Tabla DynamoDB de bloqueo de estado."
  value       = aws_dynamodb_table.state_locks.name
}

output "github_oidc_provider_arn" {
  description = "ARN del proveedor OIDC de GitHub Actions."
  value       = aws_iam_openid_connect_provider.github.arn
}

output "github_deploy_role_arn" {
  description = "ARN del rol que asumen los workflows. Es el valor de la variable AWS_DEPLOY_ROLE_ARN."
  value       = aws_iam_role.deploy.arn
}

output "github_oidc_client_id" {
  description = "Client ID del proveedor OIDC."
  value       = sort(aws_iam_openid_connect_provider.github.client_id_list)[0]
}

output "github_oidc_url" {
  description = "URL del proveedor OIDC."
  value       = aws_iam_openid_connect_provider.github.url
}

output "github_allowed_subjects" {
  description = "Subjects de OIDC aceptados por la política de confianza."
  value       = local.github_subjects
}
