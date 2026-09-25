variable "aws_region" {
  description = "Región donde se crea toda la infraestructura de Pedidos360."
  type        = string
  default     = "us-east-1"
}

variable "project_name" {
  description = "Prefijo de nombres para red, ECS, observabilidad y frontend."
  type        = string
  default     = "pedidos360"
}

variable "environment_name" {
  description = "Nombre lógico del entorno; se usa en etiquetas y nombres de recursos."
  type        = string
  default     = "aws"
}

variable "api_name" {
  description = "Nombre de la HTTP API pública de Pedidos360 y de los repositorios ECR."
  type        = string
  default     = "pedidos360-api"
}

variable "cognito_user_pool_id" {
  description = "User Pool de Amazon Cognito para las rutas /aws/api."
  type        = string
  default     = "us-east-1_UmEhPRYdI"

  validation {
    condition     = can(regex("^[a-z0-9-]+_[A-Za-z0-9]+$", var.cognito_user_pool_id))
    error_message = "cognito_user_pool_id debe tener el formato region_ID."
  }
}

variable "cognito_region" {
  description = "Región del User Pool de Cognito."
  type        = string
  default     = "us-east-1"
}

variable "cognito_issuer" {
  description = "Issuer exacto de Cognito para el authorizer JWT."
  type        = string
  default     = "https://cognito-idp.us-east-1.amazonaws.com/us-east-1_UmEhPRYdI"

  validation {
    condition     = can(regex("^https://cognito-idp\\.[a-z0-9-]+\\.amazonaws\\.com/[A-Za-z0-9_-]+$", var.cognito_issuer))
    error_message = "cognito_issuer debe ser un issuer HTTPS exacto de Cognito."
  }
}

variable "cognito_api_audience" {
  description = "App Client ID de Cognito usado como audience del authorizer."
  type        = string
  default     = "59be26pgg5ginu2sutr8eetgjg"
}

variable "cognito_authorization_scopes" {
  description = "Scopes OAuth que API Gateway exige en las rutas Cognito."
  type        = set(string)
  default     = ["openid"]

  validation {
    condition     = length(var.cognito_authorization_scopes) > 0 && alltrue([for scope in var.cognito_authorization_scopes : trimspace(scope) != ""])
    error_message = "Debe configurarse al menos un scope Cognito no vacío."
  }
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
  description = <<-EOT
    Orígenes CORS adicionales permitidos además del dominio de CloudFront, que
    siempre se incluye automáticamente. Úsese solo con una justificación
    documentada; el dominio del frontend no necesita declararse aquí.
  EOT
  type        = set(string)
  default     = []

  validation {
    condition = alltrue([
      for origin in var.allowed_origins :
      trimspace(origin) != "*" && can(regex("^https?://", origin))
    ])
    error_message = "Los orígenes adicionales deben ser HTTP(S) exactos; no se admite *."
  }
}

variable "bff_integration_uri" {
  description = <<-EOT
    Opcional. ARN del listener privado (ALB/NLB) que entrega las rutas al BFF.
    Si se deja en null se usa el ALB interno creado por esta misma
    configuración, que es el camino recomendado.
  EOT
  type        = string
  default     = null

  validation {
    condition     = var.bff_integration_uri == null || can(regex("^arn:[a-z0-9-]+:", var.bff_integration_uri))
    error_message = "bff_integration_uri debe ser el ARN de un listener o servicio privado."
  }
}

variable "bff_tls_server_name" {
  description = <<-EOT
    Opcional. Nombre DNS TLS que API Gateway verifica en el listener privado.
    Si se deja en null, la integración privada usa HTTP dentro de la VPC.
  EOT
  type        = string
  default     = null
}

# ---------------------------------------------------------------------------
# Red
# ---------------------------------------------------------------------------

variable "vpc_cidr" {
  description = "CIDR de la VPC. Debe ser un /16 o más amplio para alojar tres capas de subredes."
  type        = string
  default     = "10.20.0.0/16"

  validation {
    condition     = can(cidrhost(var.vpc_cidr, 0))
    error_message = "vpc_cidr debe ser un CIDR IPv4 válido."
  }
}

variable "availability_zone_names" {
  description = "Zonas de disponibilidad. Se requieren al menos dos para alta disponibilidad."
  type        = list(string)
  default     = ["us-east-1a", "us-east-1b"]

  validation {
    condition     = length(var.availability_zone_names) >= 2
    error_message = "Se requieren al menos dos zonas de disponibilidad."
  }
}

variable "nat_gateway_count" {
  description = <<-EOT
    Número de NAT Gateways. 1 minimiza el costo y crea un punto único de fallo
    en la salida a Internet; 2 o más distribuye la salida por Availability Zone.
  EOT
  type        = number
  default     = 1

  validation {
    condition     = var.nat_gateway_count >= 1 && var.nat_gateway_count <= length(var.availability_zone_names)
    error_message = "nat_gateway_count debe estar entre 1 y el número de zonas."
  }
}

variable "enable_vpc_endpoints" {
  description = <<-EOT
    Crear VPC Endpoints para ECR, CloudWatch Logs y Secrets Manager. Evita sacar
    ese tráfico a Internet. Cada endpoint de interfaz tiene costo horario.
  EOT
  type        = bool
  default     = true
}

variable "service_discovery_namespace" {
  description = "Namespace privado de AWS Cloud Map para descubrir catalog y orders sin nombres públicos."
  type        = string
  default     = "pedidos360.local"

  validation {
    condition     = can(regex("^[a-z0-9.-]+$", var.service_discovery_namespace))
    error_message = "service_discovery_namespace debe ser un nombre DNS en minúsculas."
  }
}

# ---------------------------------------------------------------------------
# Ejecución de servicios
# ---------------------------------------------------------------------------

variable "container_image_tag" {
  description = "Tag de las imágenes en ECR. Los workflows publican el SHA del commit; se fija aquí para que la infraestructura sea reproducible."
  type        = string
  default     = "latest"
}

variable "cluster_name" {
  description = "Nombre del cluster de ECS."
  type        = string
  default     = "pedidos360"
}

variable "fargate_platform_version" {
  description = "Versión de plataforma de Fargate. 1.4.0 habilita arm64 y ephemeral storage."
  type        = string
  default     = "1.4.0"
}

variable "fargate_cpu_architecture" {
  description = "Arquitectura de CPU de las tareas: X86_64 o ARM64."
  type        = string
  default     = "X86_64"

  validation {
    condition     = contains(["X86_64", "ARM64"], var.fargate_cpu_architecture)
    error_message = "fargate_cpu_architecture debe ser X86_64 o ARM64."
  }
}

variable "bff_task_cpu" {
  description = "Unidades de CPU de la tarea del BFF."
  type        = number
  default     = 512
}

variable "bff_task_memory" {
  description = "Memoria en MiB de la tarea del BFF."
  type        = number
  default     = 1024
}

variable "catalog_task_cpu" {
  description = "Unidades de CPU de la tarea de catalog."
  type        = number
  default     = 512
}

variable "catalog_task_memory" {
  description = "Memoria en MiB de la tarea de catalog."
  type        = number
  default     = 1024
}

variable "orders_task_cpu" {
  description = "Unidades de CPU de la tarea de orders."
  type        = number
  default     = 512
}

variable "orders_task_memory" {
  description = "Memoria en MiB de la tarea de orders."
  type        = number
  default     = 1024
}

variable "service_desired_count" {
  description = "Cantidad de tareas por servicio. El BFF requiere al menos 2 para tolerar la caída de una Availability Zone."
  type        = number
  default     = 2

  validation {
    condition     = var.service_desired_count >= 1
    error_message = "service_desired_count debe ser al menos 1."
  }
}

variable "enable_autoscaling" {
  description = "Crear políticas de autoescalado por CPU sobre el objetivo de ECS."
  type        = bool
  default     = true
}

variable "autoscaling_max_capacity" {
  description = "Capacidad máxima por servicio cuando el autoescalado está habilitado."
  type        = number
  default     = 4
}

variable "log_retention_days" {
  description = "Retención de los grupos de logs de CloudWatch."
  type        = number
  default     = 30
}

# ---------------------------------------------------------------------------
# Base de datos
# ---------------------------------------------------------------------------

variable "create_rds_oracle" {
  description = <<-EOT
    Crear una instancia RDS for Oracle. RDS no admite la imagen Oracle Free
    usada en desarrollo: esta opción exige una licencia (License Included o
    bring-your-own-license) y tiene costo. Por defecto está desactivada.
  EOT
  type        = bool
  default     = false
}

variable "oracle_host" {
  description = "Host de Oracle cuando la instancia se gestiona fuera de este Terraform. Obligatorio si create_rds_oracle es false."
  type        = string
  default     = null
}

variable "oracle_port" {
  description = "Puerto del listener de Oracle."
  type        = number
  default     = 1521
}

variable "oracle_service_name" {
  description = "Nombre de servicio (PDB) de Oracle."
  type        = string
  default     = "FREEPDB1"
}

variable "rds_oracle_engine_version" {
  description = "Versión del motor Oracle en RDS."
  type        = string
  default     = "19.0.0.0.ru-2023-10.rur-2023-10.r1"
}

variable "rds_oracle_license_model" {
  description = "Modelo de licencia de RDS for Oracle: license-included o bring-your-own-license."
  type        = string
  default     = "bring-your-own-license"

  validation {
    condition     = contains(["license-included", "bring-your-own-license"], var.rds_oracle_license_model)
    error_message = "rds_oracle_license_model debe ser license-included o bring-your-own-license."
  }
}

variable "rds_oracle_instance_class" {
  description = "Clase de instancia de RDS para Oracle."
  type        = string
  default     = "db.t3.medium"
}

variable "rds_oracle_allocated_storage" {
  description = "Almacenamiento asignado en GiB."
  type        = number
  default     = 100
}

variable "rds_oracle_multi_az" {
  description = "Despliegue multi-AZ de la instancia de base de datos."
  type        = bool
  default     = false
}

variable "rds_oracle_backup_retention_days" {
  description = "Retención de copias de seguridad automáticas."
  type        = number
  default     = 7
}

variable "rds_oracle_deletion_protection" {
  description = "Protección contra eliminación de la instancia."
  type        = bool
  default     = true
}

variable "rds_oracle_master_username" {
  description = "Usuario maestro inicial de la instancia. Su contraseña la administra RDS en Secrets Manager."
  type        = string
  default     = "PEDIDOS360ADMIN"
}

# ---------------------------------------------------------------------------
# Frontend estático
# ---------------------------------------------------------------------------

variable "frontend_bucket_name" {
  description = "Nombre del bucket S3 del frontend. Si es null se genera a partir del nombre del proyecto y la cuenta."
  type        = string
  default     = null
}

variable "cloudfront_price_class" {
  description = "Clase de precio de la distribución de CloudFront."
  type        = string
  default     = "PriceClass_100"

  validation {
    condition     = contains(["PriceClass_All", "PriceClass_200", "PriceClass_100"], var.cloudfront_price_class)
    error_message = "cloudfront_price_class debe ser PriceClass_All, PriceClass_200 o PriceClass_100."
  }
}

variable "alb_deletion_protection" {
  description = "Protección contra eliminación del ALB interno."
  type        = bool
  default     = false
}

variable "tags" {
  description = "Etiquetas adicionales aplicadas a todos los recursos."
  type        = map(string)
  default     = {}
}
