locals {
  name = var.project_name

  common_tags = merge(var.tags, {
    Project     = local.name
    Environment = var.environment_name
    ManagedBy   = "terraform"
  })

  azs = var.availability_zone_names

  # Tres capas de subredes /24 derivadas del CIDR principal:
  #   0,1      públicas      -> NAT Gateway
  #   10,11    aplicaciones  -> ECS Fargate, VPC Link y ALB interno
  #   20,21    datos         -> RDS, cuando está habilitado
  public_subnets = {
    for idx, az in local.azs : az => {
      index = idx
      cidr  = cidrsubnet(var.vpc_cidr, 8, idx)
    }
  }

  app_subnets = {
    for idx, az in local.azs : az => {
      index = idx
      cidr  = cidrsubnet(var.vpc_cidr, 8, idx + 10)
    }
  }

  data_subnets = {
    for idx, az in local.azs : az => {
      index = idx
      cidr  = cidrsubnet(var.vpc_cidr, 8, idx + 20)
    }
  }

  # Las subredes de datos solo existen cuando esta configuración crea RDS.
  data_subnet_azs = var.create_rds_oracle ? toset(local.azs) : toset([])

  app_subnet_ids = [for az in local.azs : aws_subnet.app[az].id]
  data_subnet_ids = [
    for az in local.data_subnet_azs : aws_subnet.data[az].id
  ]

  # Un NAT Gateway por el número configurado; el resto de AZ comparte el más cercano.
  nat_gateways = {
    for i in range(var.nat_gateway_count) : "nat-${i}" => local.azs[i]
  }

  nat_gateway_for_az = {
    for idx, az in local.azs : az => "nat-${idx % var.nat_gateway_count}"
  }

  interface_endpoint_services = toset([
    "com.amazonaws.${var.aws_region}.ecr.api",
    "com.amazonaws.${var.aws_region}.ecr.dkr",
    "com.amazonaws.${var.aws_region}.logs",
    "com.amazonaws.${var.aws_region}.secretsmanager",
  ])

  # Puertos que cada servicio expone dentro de la red privada.
  service_ports = {
    bff     = 8080
    catalog = 8082
    orders  = 8081
  }

  # Mapa de security groups por servicio. Se usa como for_each porque las claves
  # son conocidas en el plan aunque los IDs se resuelvan en el apply.
  service_sg_ids = {
    bff     = aws_security_group.bff.id
    catalog = aws_security_group.catalog.id
    orders  = aws_security_group.orders.id
  }

  alb_listener_port = 8080

  # Nombres privados de servicio interno. Solo se resuelven dentro de la VPC.
  service_dns = {
    catalog = "catalog.${var.service_discovery_namespace}"
    orders  = "orders.${var.service_discovery_namespace}"
  }

  # El origen permitido por CORS es siempre el dominio de CloudFront; las
  # variables son adicionales y opcionales.
  frontend_origin = "https://${aws_cloudfront_distribution.frontend.domain_name}"

  allowed_origins = sort(distinct(concat([local.frontend_origin], tolist(var.allowed_origins))))

  oracle_endpoint = var.create_rds_oracle ? aws_db_instance.oracle[0].address : var.oracle_host

  oracle_jdbc_url = "jdbc:oracle:thin:@//${local.oracle_endpoint}:${var.oracle_port}/${var.oracle_service_name}"

  # Un Secret por microservicio: ninguno recibe el rol de administrador de Oracle.
  database_secret_arns = {
    catalog = aws_secretsmanager_secret.catalog_db.arn
    orders  = aws_secretsmanager_secret.orders_db.arn
  }
}

resource "terraform_data" "preconditions" {
  # Si la base de datos no se gestiona aquí, el operador debe indicar el host.
  lifecycle {
    precondition {
      condition     = var.create_rds_oracle || (var.oracle_host != null && trimspace(var.oracle_host) != "")
      error_message = "Define oracle_host o habilita create_rds_oracle para que catalog y orders tengan una base de datos."
    }
  }
}
