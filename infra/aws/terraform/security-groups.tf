# ---------------------------------------------------------------------------
# Security groups
#
# Camino de una request pública:
#   Internet -> API Gateway -> (VPC Link, SG dedicado) -> ALB interno -> BFF
#
# Reglas:
#   - ningún puerto de aplicación acepta 0.0.0.0/0;
#   - el ALB solo acepta tráfico del SG del VPC Link;
#   - el BFF solo acepta tráfico del ALB;
#   - catalog y orders solo aceptan tráfico del BFF y entre sí;
#   - Oracle solo acepta 1521 desde catalog y orders.
# ---------------------------------------------------------------------------

resource "aws_security_group" "vpc_link" {
  name        = "${local.name}-vpc-link"
  description = "ENIs que administra API Gateway para el VPC Link"
  vpc_id      = aws_vpc.main.id

  tags = merge(local.common_tags, { Name = "${local.name}-vpc-link" })
}

resource "aws_security_group" "alb" {
  name        = "${local.name}-alb"
  description = "ALB interno que entrega las rutas /api al BFF"
  vpc_id      = aws_vpc.main.id

  tags = merge(local.common_tags, { Name = "${local.name}-alb" })
}

resource "aws_security_group" "bff" {
  name        = "${local.name}-bff"
  description = "Tareas Fargate del BFF"
  vpc_id      = aws_vpc.main.id

  tags = merge(local.common_tags, { Name = "${local.name}-bff" })
}

resource "aws_security_group" "catalog" {
  name        = "${local.name}-catalog"
  description = "Tareas Fargate de catalog"
  vpc_id      = aws_vpc.main.id

  tags = merge(local.common_tags, { Name = "${local.name}-catalog" })
}

resource "aws_security_group" "orders" {
  name        = "${local.name}-orders"
  description = "Tareas Fargate de orders"
  vpc_id      = aws_vpc.main.id

  tags = merge(local.common_tags, { Name = "${local.name}-orders" })
}

resource "aws_security_group" "vpc_endpoints" {
  name        = "${local.name}-vpc-endpoints"
  description = "VPC Endpoints de interfaz para ECR, Logs y Secrets Manager"
  vpc_id      = aws_vpc.main.id

  tags = merge(local.common_tags, { Name = "${local.name}-vpc-endpoints" })
}

resource "aws_security_group" "rds" {
  count = var.create_rds_oracle ? 1 : 0

  name        = "${local.name}-oracle"
  description = "Acceso a Oracle solo desde catalog y orders"
  vpc_id      = aws_vpc.main.id

  tags = merge(local.common_tags, { Name = "${local.name}-oracle" })
}

# --- VPC Link -> ALB -------------------------------------------------------

resource "aws_vpc_security_group_ingress_rule" "alb_from_vpc_link" {
  security_group_id            = aws_security_group.alb.id
  referenced_security_group_id = aws_security_group.vpc_link.id
  from_port                    = local.alb_listener_port
  to_port                      = local.alb_listener_port
  ip_protocol                  = "tcp"
  description                  = "API Gateway por VPC Link"
}

resource "aws_vpc_security_group_egress_rule" "vpc_link_to_alb" {
  security_group_id            = aws_security_group.vpc_link.id
  referenced_security_group_id = aws_security_group.alb.id
  from_port                    = local.alb_listener_port
  to_port                      = local.alb_listener_port
  ip_protocol                  = "tcp"
  description                  = "Hacia el ALB interno"
}

# --- ALB -> BFF ------------------------------------------------------------

resource "aws_vpc_security_group_egress_rule" "alb_to_bff" {
  security_group_id            = aws_security_group.alb.id
  referenced_security_group_id = aws_security_group.bff.id
  from_port                    = local.service_ports.bff
  to_port                      = local.service_ports.bff
  ip_protocol                  = "tcp"
  description                  = "Reenvío de health checks y tráfico de aplicación"
}

resource "aws_vpc_security_group_ingress_rule" "bff_from_alb" {
  security_group_id            = aws_security_group.bff.id
  referenced_security_group_id = aws_security_group.alb.id
  from_port                    = local.service_ports.bff
  to_port                      = local.service_ports.bff
  ip_protocol                  = "tcp"
  description                  = "Único origen permitido hacia el BFF"
}

# --- BFF -> catalog y orders ----------------------------------------------

resource "aws_vpc_security_group_egress_rule" "bff_to_catalog" {
  security_group_id            = aws_security_group.bff.id
  referenced_security_group_id = aws_security_group.catalog.id
  from_port                    = local.service_ports.catalog
  to_port                      = local.service_ports.catalog
  ip_protocol                  = "tcp"
  description                  = "Lectura y escritura del catálogo"
}

resource "aws_vpc_security_group_egress_rule" "bff_to_orders" {
  security_group_id            = aws_security_group.bff.id
  referenced_security_group_id = aws_security_group.orders.id
  from_port                    = local.service_ports.orders
  to_port                      = local.service_ports.orders
  ip_protocol                  = "tcp"
  description                  = "Gestión de pedidos"
}

# --- orders -> catalog (reservas de stock internas) ------------------------

resource "aws_vpc_security_group_egress_rule" "orders_to_catalog" {
  security_group_id            = aws_security_group.orders.id
  referenced_security_group_id = aws_security_group.catalog.id
  from_port                    = local.service_ports.catalog
  to_port                      = local.service_ports.catalog
  ip_protocol                  = "tcp"
  description                  = "Reservas y liberación de stock"
}

resource "aws_vpc_security_group_ingress_rule" "catalog_from_bff" {
  security_group_id            = aws_security_group.catalog.id
  referenced_security_group_id = aws_security_group.bff.id
  from_port                    = local.service_ports.catalog
  to_port                      = local.service_ports.catalog
  ip_protocol                  = "tcp"
  description                  = "Operaciones de catálogo del BFF"
}

resource "aws_vpc_security_group_ingress_rule" "catalog_from_orders" {
  security_group_id            = aws_security_group.catalog.id
  referenced_security_group_id = aws_security_group.orders.id
  from_port                    = local.service_ports.catalog
  to_port                      = local.service_ports.catalog
  ip_protocol                  = "tcp"
  description                  = "Reservas internas de stock"
}

resource "aws_vpc_security_group_ingress_rule" "orders_from_bff" {
  security_group_id            = aws_security_group.orders.id
  referenced_security_group_id = aws_security_group.bff.id
  from_port                    = local.service_ports.orders
  to_port                      = local.service_ports.orders
  ip_protocol                  = "tcp"
  description                  = "Operaciones de pedidos del BFF"
}

# --- Salida a Internet: JWKS de Entra y Cognito ---------------------------
#
# Las tareas validan tokens contra los JWKS de Microsoft Entra ID y de Amazon
# Cognito, y descargan imágenes de ECR. Esa salida viaja por el NAT Gateway y se
# restringe al puerto 443.

resource "aws_vpc_security_group_egress_rule" "services_https_egress" {
  for_each = local.service_sg_ids

  security_group_id = each.value
  cidr_ipv4         = "0.0.0.0/0"
  from_port         = 443
  to_port           = 443
  ip_protocol       = "tcp"
  description       = "JWKS de Entra y Cognito, ECR y CloudWatch Logs"
}

# --- VPC Endpoints --------------------------------------------------------

resource "aws_vpc_security_group_ingress_rule" "endpoints_from_services" {
  for_each = local.service_sg_ids

  security_group_id            = aws_security_group.vpc_endpoints.id
  referenced_security_group_id = each.value
  from_port                    = 443
  to_port                      = 443
  ip_protocol                  = "tcp"
  description                  = "ECR, Logs y Secrets Manager por endpoint privado"
}

resource "aws_vpc_security_group_egress_rule" "endpoints_https_egress" {
  security_group_id = aws_security_group.vpc_endpoints.id
  cidr_ipv4         = "0.0.0.0/0"
  from_port         = 443
  to_port           = 443
  ip_protocol       = "tcp"
  description       = "Hacia las interfaces de red de los endpoints"
}

# --- Oracle ---------------------------------------------------------------

resource "aws_vpc_security_group_ingress_rule" "oracle_from_catalog" {
  count = var.create_rds_oracle ? 1 : 0

  security_group_id            = aws_security_group.rds[0].id
  referenced_security_group_id = aws_security_group.catalog.id
  from_port                    = var.oracle_port
  to_port                      = var.oracle_port
  ip_protocol                  = "tcp"
  description                  = "JDBC de catalog"
}

resource "aws_vpc_security_group_ingress_rule" "oracle_from_orders" {
  count = var.create_rds_oracle ? 1 : 0

  security_group_id            = aws_security_group.rds[0].id
  referenced_security_group_id = aws_security_group.orders.id
  from_port                    = var.oracle_port
  to_port                      = var.oracle_port
  ip_protocol                  = "tcp"
  description                  = "JDBC de orders"
}

resource "aws_vpc_security_group_egress_rule" "catalog_to_oracle" {
  count = var.create_rds_oracle ? 1 : 0

  security_group_id            = aws_security_group.catalog.id
  referenced_security_group_id = aws_security_group.rds[0].id
  from_port                    = var.oracle_port
  to_port                      = var.oracle_port
  ip_protocol                  = "tcp"
  description                  = "JDBC de catalog"
}

resource "aws_vpc_security_group_egress_rule" "orders_to_oracle" {
  count = var.create_rds_oracle ? 1 : 0

  security_group_id            = aws_security_group.orders.id
  referenced_security_group_id = aws_security_group.rds[0].id
  from_port                    = var.oracle_port
  to_port                      = var.oracle_port
  ip_protocol                  = "tcp"
  description                  = "JDBC de orders"
}
