variable "aws_region" {
  description = "Región del bucket de estado y del rol de despliegue."
  type        = string
  default     = "us-east-1"
}

variable "project_name" {
  description = "Prefijo de nombres de los recursos de bootstrap."
  type        = string
  default     = "pedidos360"
}

variable "github_org" {
  description = "Organización o usuario de GitHub que aloja los repositorios."
  type        = string
}

variable "github_repositories" {
  description = "Repositorios autorizados a asumir el rol de despliegue con OIDC."
  type        = list(string)
}

variable "github_environment" {
  description = "Nombre del environment de GitHub Actions que exige aprobación para aplicar cambios."
  type        = string
  default     = "aws-production"
}

variable "ecr_repository_prefix" {
  description = "Prefijo de los repositorios ECR a los que el rol puede enviar imágenes."
  type        = string
  default     = "pedidos360-api-"
}

variable "frontend_bucket_prefix" {
  description = "Prefijo de los buckets S3 a los que el rol puede escribir el frontend."
  type        = string
  default     = "pedidos360-frontend-"
}

variable "allowed_branch" {
  description = "Rama autorizada a asumir el rol mediante OIDC además del environment protegido."
  type        = string
  default     = "main"
}

variable "tags" {
  description = "Etiquetas adicionales."
  type        = map(string)
  default     = {}
}
