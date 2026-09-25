# ---------------------------------------------------------------------------
# Descubrimiento de servicios con AWS Cloud Map
#
# catalog y orders se alcanzan por nombre privado (catalog.pedidos360.local),
# nunca por IP ni por nombre público. El namespace no se resuelve desde Internet.
# ---------------------------------------------------------------------------

resource "aws_service_discovery_private_dns_namespace" "main" {
  name        = var.service_discovery_namespace
  vpc         = aws_vpc.main.id
  description = "Namespace privado de Pedidos360"
}

resource "aws_service_discovery_service" "catalog" {
  name = "catalog"

  dns_config {
    namespace_id = aws_service_discovery_private_dns_namespace.main.id

    dns_records {
      ttl  = 10
      type = "A"
    }

    routing_policy = "MULTIVALUE"
  }

  # AWS solo admite failure_threshold = 1, que es el valor por defecto.
  health_check_custom_config {}

  tags = merge(local.common_tags, { Name = "${local.name}-catalog" })
}

resource "aws_service_discovery_service" "orders" {
  name = "orders"

  dns_config {
    namespace_id = aws_service_discovery_private_dns_namespace.main.id

    dns_records {
      ttl  = 10
      type = "A"
    }

    routing_policy = "MULTIVALUE"
  }

  # AWS solo admite failure_threshold = 1, que es el valor por defecto.
  health_check_custom_config {}

  tags = merge(local.common_tags, { Name = "${local.name}-orders" })
}
