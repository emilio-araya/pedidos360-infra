# ---------------------------------------------------------------------------
# Red privada
#
# Tres capas de subredes por Availability Zone:
#   públicas      -> NAT Gateway (salida a Internet)
#   aplicaciones  -> ECS Fargate, ALB interno y VPC Link de API Gateway
#   datos         -> RDS, cuando está habilitado
#
# Ningún servicio se ejecuta con IP pública: assign_public_ip queda en false y
# los security groups no aceptan 0.0.0.0/0 en puertos de aplicación.
# ---------------------------------------------------------------------------

resource "aws_vpc" "main" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = merge(local.common_tags, { Name = "${local.name}-vpc" })
}

resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id
  tags   = merge(local.common_tags, { Name = "${local.name}-igw" })
}

resource "aws_subnet" "public" {
  for_each = local.public_subnets

  vpc_id                  = aws_vpc.main.id
  cidr_block              = each.value.cidr
  availability_zone       = each.key
  map_public_ip_on_launch = true

  tags = merge(local.common_tags, {
    Name = "${local.name}-public-${each.value.index}"
    Tier = "public"
  })
}

resource "aws_subnet" "app" {
  for_each = local.app_subnets

  vpc_id                  = aws_vpc.main.id
  cidr_block              = each.value.cidr
  availability_zone       = each.key
  map_public_ip_on_launch = false

  tags = merge(local.common_tags, {
    Name = "${local.name}-app-${each.value.index}"
    Tier = "application"
  })
}

resource "aws_subnet" "data" {
  for_each = var.create_rds_oracle ? local.data_subnets : {}

  vpc_id                  = aws_vpc.main.id
  cidr_block              = each.value.cidr
  availability_zone       = each.key
  map_public_ip_on_launch = false

  tags = merge(local.common_tags, {
    Name = "${local.name}-data-${each.value.index}"
    Tier = "data"
  })
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }

  tags = merge(local.common_tags, { Name = "${local.name}-public" })
}

resource "aws_route_table_association" "public" {
  for_each = aws_subnet.public

  subnet_id      = each.value.id
  route_table_id = aws_route_table.public.id
}

resource "aws_route_table" "app" {
  for_each = local.app_subnets

  vpc_id = aws_vpc.main.id

  tags = merge(local.common_tags, { Name = "${local.name}-app-${each.value.index}" })
}

resource "aws_route_table_association" "app" {
  for_each = aws_subnet.app

  subnet_id      = each.value.id
  route_table_id = aws_route_table.app[each.key].id
}

resource "aws_route_table" "data" {
  for_each = local.data_subnet_azs

  vpc_id = aws_vpc.main.id

  tags = merge(local.common_tags, { Name = "${local.name}-data-${local.data_subnets[each.key].index}" })
}

resource "aws_route_table_association" "data" {
  for_each = aws_subnet.data

  subnet_id      = each.value.id
  route_table_id = aws_route_table.data[each.key].id
}

# ---------------------------------------------------------------------------
# Salida a Internet
#
# nat_gateway_count = 1 minimiza el costo y concentration el punto único de
# fallo; con 2 o más, cada Availability Zone tiene el suyo. El módulo se
# rechaza por debajo de 1 para no dejar las tareas sin salida.
# ---------------------------------------------------------------------------

resource "aws_eip" "nat" {
  for_each = local.nat_gateways

  domain = "vpc"

  tags = merge(local.common_tags, { Name = "${local.name}-${each.key}" })

  depends_on = [aws_internet_gateway.main]
}

resource "aws_nat_gateway" "main" {
  for_each = local.nat_gateways

  allocation_id = aws_eip.nat[each.key].id
  subnet_id     = aws_subnet.public[each.value].id

  tags = merge(local.common_tags, { Name = "${local.name}-${each.key}" })

  depends_on = [aws_internet_gateway.main]
}

resource "aws_route" "app_egress" {
  for_each = aws_route_table.app

  route_table_id         = each.value.id
  destination_cidr_block = "0.0.0.0/0"
  nat_gateway_id         = aws_nat_gateway.main[local.nat_gateway_for_az[each.key]].id
}

resource "aws_route" "data_egress" {
  for_each = aws_route_table.data

  route_table_id         = each.value.id
  destination_cidr_block = "0.0.0.0/0"
  nat_gateway_id         = aws_nat_gateway.main[local.nat_gateway_for_az[each.key]].id
}

# ---------------------------------------------------------------------------
# VPC Endpoints
#
# ECR y S3 usan endpoints de tipo gateway; Logs y Secrets Manager son de
# interfaz. Así las tareas descargan imágenes, publican logs y leen secretos sin
# abandonar la VPC.
# ---------------------------------------------------------------------------

resource "aws_vpc_endpoint" "s3" {
  count = var.enable_vpc_endpoints ? 1 : 0

  vpc_id            = aws_vpc.main.id
  service_name      = "com.amazonaws.${var.aws_region}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = concat([for az in local.azs : aws_route_table.app[az].id], values(aws_route_table.data)[*].id)

  tags = merge(local.common_tags, { Name = "${local.name}-s3-endpoint" })
}

resource "aws_vpc_endpoint" "interface" {
  for_each = var.enable_vpc_endpoints ? local.interface_endpoint_services : toset([])

  vpc_id              = aws_vpc.main.id
  service_name        = each.key
  vpc_endpoint_type   = "Interface"
  subnet_ids          = local.app_subnet_ids
  security_group_ids  = [aws_security_group.vpc_endpoints.id]
  private_dns_enabled = true

  tags = merge(local.common_tags, { Name = "${local.name}-${replace(each.key, "/[^a-z0-9-]/", "")}" })
}
