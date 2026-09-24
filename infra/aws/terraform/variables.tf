variable "aws_region" {
  description = "Región donde se crea API Gateway HTTP API."
  type        = string
  default     = "us-east-1"
}

variable "api_name" {
  description = "Nombre de la HTTP API pública de Pedidos360."
  type        = string
  default     = "pedidos360-api"
}

variable "entra_tenant_id" {
  description = "Tenant ID GUID de Microsoft Entra ID."
  type        = string

  validation {
    condition     = can(regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$", var.entra_tenant_id))
    error_message = "entra_tenant_id debe ser un GUID."
  }
}

variable "entra_api_audience" {
  description = "Audiencia esperada: normalmente, el Application (client) ID de pedidos360-api."
  type        = string

  validation {
    condition     = can(regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$", var.entra_api_audience))
    error_message = "entra_api_audience debe ser el Application (client) ID GUID de la API."
  }
}

variable "allowed_origins" {
  description = "Orígenes exactos permitidos por CORS para el frontend."
  type        = set(string)

  validation {
    condition = length(var.allowed_origins) > 0 && alltrue([
      for origin in var.allowed_origins :
      trimspace(origin) != "*" && can(regex("^https?://", origin))
    ])
    error_message = "Debe configurarse al menos un origen HTTP(S) exacto; no se admite *."
  }
}

variable "bff_integration_uri" {
  description = <<-EOT
    ARN privado del listener de ALB/NLB o del servicio Cloud Map que entrega
    las rutas /api al BFF. No se admite una URL HTTP/HTTPS pública.
  EOT
  type        = string

  validation {
    condition     = can(regex("^arn:[a-z0-9-]+:", var.bff_integration_uri))
    error_message = "bff_integration_uri debe ser el ARN de un listener o servicio privado."
  }
}

variable "bff_tls_server_name" {
  description = "Nombre DNS TLS que API Gateway debe verificar en el listener privado del BFF."
  type        = string

  validation {
    condition     = length(trimspace(var.bff_tls_server_name)) > 0
    error_message = "bff_tls_server_name no puede estar vacío."
  }
}

variable "vpc_subnet_ids" {
  description = "Subredes privadas de al menos dos AZ para el VPC Link de API Gateway."
  type        = set(string)

  validation {
    condition     = length(var.vpc_subnet_ids) >= 2
    error_message = "Se requieren al menos dos subredes para el VPC Link."
  }
}

variable "vpc_security_group_ids" {
  description = "Security groups de la infraestructura privada que protegen el listener del BFF."
  type        = set(string)

  validation {
    condition     = length(var.vpc_security_group_ids) > 0
    error_message = "Debe configurarse al menos un security group privado."
  }
}

variable "tags" {
  description = "Etiquetas aplicadas a los recursos de API Gateway."
  type        = map(string)
  default     = {}
}
