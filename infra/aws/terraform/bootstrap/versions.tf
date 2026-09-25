terraform {
  required_version = ">= 1.6.0"

  # El bootstrap tiene estado local a propósito: es lo que crea el bucket que
  # alojará el estado remoto del resto de la configuración.
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 5.0, < 7.0"
    }
  }
}
