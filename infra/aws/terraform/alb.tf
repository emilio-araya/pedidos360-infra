# ---------------------------------------------------------------------------
# ALB interno
#
# Único punto de entrada al BFF. El load balancer tiene internal = true: no
# existe ningún listener público y su DNS solo resuelve dentro de la VPC.
# API Gateway lo alcanza por VPC Link usando el ARN del listener.
# ---------------------------------------------------------------------------

resource "aws_lb" "bff" {
  name               = substr("${local.name}-bff", 0, 32)
  internal           = true
  load_balancer_type = "application"
  security_groups    = [aws_security_group.alb.id]
  subnets            = local.app_subnet_ids

  enable_deletion_protection       = var.alb_deletion_protection
  enable_cross_zone_load_balancing = true
  idle_timeout                     = 60

  tags = merge(local.common_tags, { Name = "${local.name}-bff" })
}

resource "aws_lb_target_group" "bff" {
  name        = substr("${local.name}-bff", 0, 32)
  port        = local.service_ports.bff
  protocol    = "HTTP"
  target_type = "ip"
  vpc_id      = aws_vpc.main.id

  deregistration_delay = 30

  health_check {
    enabled             = true
    protocol            = "HTTP"
    path                = "/actuator/health"
    matcher             = "200"
    interval            = 15
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }

  tags = merge(local.common_tags, { Name = "${local.name}-bff" })
}

resource "aws_lb_listener" "bff" {
  load_balancer_arn = aws_lb.bff.arn
  port              = local.alb_listener_port
  protocol          = "HTTP"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.bff.arn
  }
}
