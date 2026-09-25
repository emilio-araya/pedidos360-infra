# ---------------------------------------------------------------------------
# ECS Fargate
#
# - cluster en subredes privadas, sin IP pública en las tareas;
# - una definición de tarea por servicio, con IAM de ejecución limitado a su
#   propio secreto;
# - la imagen viene de ECR y se fija por tag: container_image_tag;
# - el BFF se registra en el target group del ALB interno;
# - catalog y orders se registran en Cloud Map para descubrirse por nombre.
#
# El BFF no recibe ninguna credencial de base de datos: no tiene datasource.
# ---------------------------------------------------------------------------

locals {
  # Configuración no secreta común a los tres servicios.
  common_env = {
    ENTRA_ISSUER             = "https://login.microsoftonline.com/${var.entra_tenant_id}/v2.0"
    ENTRA_API_AUDIENCE       = var.entra_api_audience
    COGNITO_ISSUER           = var.cognito_issuer
    COGNITO_API_AUDIENCE     = var.cognito_api_audience
    COGNITO_JWK_SET_URI      = "${var.cognito_issuer}/.well-known/jwks.json"
    COGNITO_USER_POOL_ID     = var.cognito_user_pool_id
    MANAGEMENT_HEALTH_PROBES = "enabled"
  }

  service_definitions = {
    bff = {
      port         = local.service_ports.bff
      profile      = "cloud"
      cpu          = var.bff_task_cpu
      memory       = var.bff_task_memory
      health_check = "wget -q -O /dev/null http://127.0.0.1:8080/actuator/health || exit 1"
      env = {
        SPRING_PROFILES_ACTIVE = "cloud"
        JWT_MODE               = "entra"
        PORT                   = "8080"
        SERVER_PORT            = "8080"
        ORDERS_SERVICE_URL     = "http://${local.service_dns.orders}:${local.service_ports.orders}"
        CATALOG_SERVICE_URL    = "http://${local.service_dns.catalog}:${local.service_ports.catalog}"
        CORS_ALLOWED_ORIGINS   = join(",", local.allowed_origins)
      }
      secret_keys = {}
    }

    catalog = {
      port         = local.service_ports.catalog
      profile      = "cloud"
      cpu          = var.catalog_task_cpu
      memory       = var.catalog_task_memory
      health_check = "bash -c 'exec 3<>/dev/tcp/127.0.0.1/8082' || exit 1"
      env = {
        SPRING_PROFILES_ACTIVE = "cloud"
        PORT                   = "8082"
        SERVER_PORT            = "8082"
        ORACLE_URL             = local.oracle_jdbc_url
      }
      secret_keys = {
        ORACLE_USERNAME = "username"
        ORACLE_PASSWORD = "password"
      }
    }

    orders = {
      port         = local.service_ports.orders
      profile      = "oracle"
      cpu          = var.orders_task_cpu
      memory       = var.orders_task_memory
      health_check = "bash -c 'exec 3<>/dev/tcp/127.0.0.1/8081' || exit 1"
      env = {
        SPRING_PROFILES_ACTIVE = "oracle"
        SERVER_PORT            = "8081"
        ORDERS_DB_URL          = local.oracle_jdbc_url
        CATALOG_SERVICE_URL    = "http://${local.service_dns.catalog}:${local.service_ports.catalog}"
      }
      secret_keys = {
        ORDERS_DB_USERNAME = "username"
        ORDERS_DB_PASSWORD = "password"
      }
    }
  }

  ecr_repository_for = {
    bff     = aws_ecr_repository.services["bff"].repository_url
    catalog = aws_ecr_repository.services["catalog"].repository_url
    orders  = aws_ecr_repository.services["orders"].repository_url
  }
}

resource "aws_ecs_cluster" "main" {
  name = var.cluster_name

  setting {
    name  = "containerInsights"
    value = "enabled"
  }

  tags = merge(local.common_tags, { Name = var.cluster_name })
}

# --- IAM de ejecución -----------------------------------------------------
#
# Rol del agente de ECS: descarga la imagen, escribe logs y lee el secreto
# propio de cada servicio. El permiso de Secrets se acota por ARN.

resource "aws_iam_role" "task_execution" {
  for_each = local.service_sg_ids

  name_prefix        = "${local.name}-${each.key}-exec-"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume_role.json
  description        = "Rol de ejecución de tareas de ${each.key}"

  tags = merge(local.common_tags, { Name = "${local.name}-${each.key}-execution" })
}

data "aws_iam_policy_document" "ecs_assume_role" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role_policy" "task_execution_secrets" {
  for_each = {
    for name, keys in local.service_definitions : name => keys
    if length(keys.secret_keys) > 0
  }

  name = "read-database-secret"
  role = aws_iam_role.task_execution[each.key].id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "ReadOwnDatabaseSecret"
        Effect = "Allow"
        Action = [
          "secretsmanager:GetSecretValue",
        ]
        Resource = [local.database_secret_arns[each.key]]
      },
    ]
  })
}

resource "aws_iam_role_policy_attachment" "task_execution" {
  for_each = local.service_sg_ids

  role       = aws_iam_role.task_execution[each.key].name
  policy_arn = "arn:${data.aws_partition.current.partition}:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

# --- Definiciones de tarea ------------------------------------------------

resource "aws_ecs_task_definition" "service" {
  for_each = local.service_sg_ids

  family                   = "${local.name}-${each.key}"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = local.service_definitions[each.key].cpu
  memory                   = local.service_definitions[each.key].memory
  execution_role_arn       = aws_iam_role.task_execution[each.key].arn
  task_role_arn            = aws_iam_role.task[each.key].arn
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = var.fargate_cpu_architecture
  }

  container_definitions = jsonencode([
    {
      name      = each.key
      image     = "${local.ecr_repository_for[each.key]}:${var.container_image_tag}"
      essential = true

      portMappings = [
        {
          containerPort = local.service_definitions[each.key].port
          hostPort      = local.service_definitions[each.key].port
          protocol      = "tcp"
        }
      ]

      environment = [
        for key, value in merge(local.common_env, local.service_definitions[each.key].env) : {
          name  = key
          value = value
        }
      ]

      secrets = [
        for key, secret_key in local.service_definitions[each.key].secret_keys : {
          name      = key
          valueFrom = "${local.database_secret_arns[each.key]}:${secret_key}::"
        }
      ]

      healthCheck = {
        command     = ["CMD-SHELL", local.service_definitions[each.key].health_check]
        interval    = 30
        timeout     = 5
        retries     = 3
        startPeriod = 60
      }

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.services[each.key].name
          "awslogs-region"        = var.aws_region
          "awslogs-stream-prefix" = each.key
        }
      }

      readonlyRootFilesystem = true

      # El sistema de archivos de la imagen es de solo lectura; la JVM necesita
      # un /tmp escribible para hsperfdata y archivos temporales.
      linuxParameters = {
        tmpfs = [
          {
            containerPath = "/tmp"
            size          = 64
          }
        ]
      }
    }
  ])

  tags = merge(local.common_tags, { Name = "${local.name}-${each.key}" })

  depends_on = [terraform_data.preconditions]
}

# Rol de la propia aplicación. Hoy no necesita permisos de AWS: se mantiene sin
# políticas adjuntas para que cualquier necesidad futura sea una decisión
# explícita y revisable.
resource "aws_iam_role" "task" {
  for_each = local.service_sg_ids

  name_prefix        = "${local.name}-${each.key}-task-"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume_role.json
  description        = "Rol de aplicación de ${each.key}; sin permisos de AWS"

  tags = merge(local.common_tags, { Name = "${local.name}-${each.key}-task" })
}

# --- Servicios ------------------------------------------------------------

resource "aws_ecs_service" "service" {
  for_each = local.service_sg_ids

  name                               = each.key
  cluster                            = aws_ecs_cluster.main.id
  task_definition                    = aws_ecs_task_definition.service[each.key].arn
  desired_count                      = var.service_desired_count
  launch_type                        = "FARGATE"
  platform_version                   = var.fargate_platform_version
  health_check_grace_period_seconds  = 90
  enable_execute_command             = false
  propagate_tags                     = "SERVICE"
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  network_configuration {
    subnets          = local.app_subnet_ids
    security_groups  = [each.value]
    assign_public_ip = false
  }

  dynamic "load_balancer" {
    for_each = each.key == "bff" ? [1] : []

    content {
      target_group_arn = aws_lb_target_group.bff.arn
      container_name   = "bff"
      container_port   = local.service_ports.bff
    }
  }

  dynamic "service_registries" {
    for_each = contains(["catalog", "orders"], each.key) ? [1] : []

    content {
      registry_arn = each.key == "catalog" ? aws_service_discovery_service.catalog.arn : aws_service_discovery_service.orders.arn
    }
  }

  tags = merge(local.common_tags, { Name = each.key })

  depends_on = [aws_lb_listener.bff]
}

# ---------------------------------------------------------------------------
# Autoescalado por CPU
# ---------------------------------------------------------------------------

resource "aws_appautoscaling_target" "service" {
  for_each = var.enable_autoscaling ? local.service_sg_ids : {}

  service_namespace  = "ecs"
  resource_id        = "service/${aws_ecs_cluster.main.name}/${aws_ecs_service.service[each.key].name}"
  scalable_dimension = "ecs:service:DesiredCount"
  min_capacity       = var.service_desired_count
  max_capacity       = max(var.autoscaling_max_capacity, var.service_desired_count)
}

resource "aws_appautoscaling_policy" "service_cpu" {
  for_each = var.enable_autoscaling ? local.service_sg_ids : {}

  name               = "${local.name}-${each.key}-cpu"
  policy_type        = "TargetTrackingScaling"
  resource_id        = aws_appautoscaling_target.service[each.key].resource_id
  scalable_dimension = aws_appautoscaling_target.service[each.key].scalable_dimension
  service_namespace  = aws_appautoscaling_target.service[each.key].service_namespace

  target_tracking_scaling_policy_configuration {
    target_value       = 60
    scale_in_cooldown  = 120
    scale_out_cooldown = 60

    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }
  }
}
