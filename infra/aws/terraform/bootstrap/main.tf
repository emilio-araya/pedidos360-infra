# ---------------------------------------------------------------------------
# Bootstrap
#
# Estos recursos se aplican UNA vez, con estado local, antes que el resto de la
# configuración. Son los que la infraestructura principal necesita para existir:
#
#   1. Bucket S3 con versionado y cifrado para el estado remoto de Terraform.
#   2. Tabla DynamoDB para el bloqueo de estado.
#   3. Proveedor OIDC de GitHub Actions.
#   4. Rol de despliegue que asumen los workflows, sin llaves de acceso.
#
# Justificación de por qué va aparte: un backend remoto no puede crear su propio
# contenedor. Después del apply, el bucket y la tabla se comunican por outputs
# y se usan en las variables de GitHub.
#
# Ningún secreto se escribe aquí: el rol se alcanza con OIDC, no con llaves.
# ---------------------------------------------------------------------------

locals {
  common_tags = merge(var.tags, {
    Project     = var.project_name
    Environment = "bootstrap"
    ManagedBy   = "terraform"
  })

  state_bucket_name = "${var.project_name}-tfstate-${data.aws_caller_identity.current.account_id}-${var.aws_region}"
  lock_table_name   = "${var.project_name}-tfstate-locks"
}

data "aws_caller_identity" "current" {}

data "aws_partition" "current" {}

# --- Estado remoto --------------------------------------------------------

resource "aws_s3_bucket" "state" {
  bucket        = local.state_bucket_name
  force_destroy = false

  tags = merge(local.common_tags, { Name = local.state_bucket_name })
}

resource "aws_s3_bucket_public_access_block" "state" {
  bucket = aws_s3_bucket.state.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "state" {
  bucket = aws_s3_bucket.state.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "state" {
  bucket = aws_s3_bucket.state.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_versioning" "state" {
  bucket = aws_s3_bucket.state.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "state" {
  bucket = aws_s3_bucket.state.id

  rule {
    id     = "expire-noncurrent-versions"
    status = "Enabled"

    filter {}

    noncurrent_version_expiration {
      noncurrent_days = 90
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }

  depends_on = [aws_s3_bucket_versioning.state]
}

data "aws_iam_policy_document" "state_bucket" {
  statement {
    sid     = "DenyUnencryptedTransport"
    effect  = "Deny"
    actions = ["s3:*"]

    resources = [
      aws_s3_bucket.state.arn,
      "${aws_s3_bucket.state.arn}/*",
    ]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_s3_bucket_policy" "state" {
  bucket = aws_s3_bucket.state.id
  policy = data.aws_iam_policy_document.state_bucket.json
}

resource "aws_dynamodb_table" "state_locks" {
  name         = local.lock_table_name
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "LockID"

  attribute {
    name = "LockID"
    type = "S"
  }

  point_in_time_recovery {
    enabled = true
  }

  tags = merge(local.common_tags, { Name = local.lock_table_name })
}

# --- GitHub Actions con OIDC ---------------------------------------------

resource "aws_iam_openid_connect_provider" "github" {
  url             = "https://token.actions.githubusercontent.com"
  client_id_list  = ["sts.amazonaws.com"]
  thumbprint_list = ["6938fd4d98bab03faadb97b34396831e3780aea1"]

  tags = merge(local.common_tags, { Name = "github-actions-oidc" })
}

locals {
  account_id = data.aws_caller_identity.current.account_id
  region     = var.aws_region
  partition  = data.aws_partition.current.partition

  # Los workflows usan `environment: aws-production`, así que el subject de OIDC
  # es repo:organizacion/repositorio:environment:ENTORNO. Se acepta además la
  # rama principal, útil para pipelines que no dependan del environment.
  github_subjects = concat(
    [for repo in var.github_repositories : "repo:${var.github_org}/${repo}:environment:${var.github_environment}"],
    [for repo in var.github_repositories : "repo:${var.github_org}/${repo}:ref:refs/heads/${var.allowed_branch}"],
  )
}

data "aws_iam_policy_document" "github_assume_role" {
  statement {
    sid     = "GitHubActionsOIDC"
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type = "Federated"
      identifiers = [
        aws_iam_openid_connect_provider.github.arn,
      ]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    condition {
      test     = "StringLike"
      variable = "token.actions.githubusercontent.com:sub"
      values   = local.github_subjects
    }
  }
}

resource "aws_iam_role" "deploy" {
  name_prefix        = "${var.project_name}-deploy-"
  description        = "Rol que asumen los workflows de GitHub Actions mediante OIDC"
  assume_role_policy = data.aws_iam_policy_document.github_assume_role.json

  tags = merge(local.common_tags, { Name = "${var.project_name}-deploy" })
}

data "aws_iam_policy_document" "deploy" {
  # Publicación de imágenes de los tres servicios del backend.
  statement {
    sid    = "EcrAuthorization"
    effect = "Allow"
    actions = [
      "ecr:GetAuthorizationToken",
    ]
    resources = ["*"]
  }

  statement {
    sid    = "EcrPush"
    effect = "Allow"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:CompleteLayerUpload",
      "ecr:InitiateLayerUpload",
      "ecr:PutImage",
      "ecr:UploadLayerPart",
      "ecr:BatchGetImage",
      "ecr:GetDownloadUrlForLayer",
      "ecr:DescribeImages",
    ]
    resources = ["arn:${local.partition}:ecr:${local.region}:${local.account_id}:repository/${var.ecr_repository_prefix}*"]
  }

  # Despliegue del frontend estático.
  statement {
    sid    = "FrontendBucket"
    effect = "Allow"
    actions = [
      "s3:ListBucket",
      "s3:GetBucketLocation",
    ]
    resources = ["arn:${local.partition}:s3:::${var.frontend_bucket_prefix}*"]
  }

  statement {
    sid    = "FrontendObjects"
    effect = "Allow"
    actions = [
      "s3:GetObject",
      "s3:PutObject",
      "s3:DeleteObject",
    ]
    resources = ["arn:${local.partition}:s3:::${var.frontend_bucket_prefix}*/**"]
  }

  # La distribución es pública; invalidar caché es una operación de bajo riesgo.
  statement {
    sid    = "CloudFrontInvalidation"
    effect = "Allow"
    actions = [
      "cloudfront:CreateInvalidation",
      "cloudfront:GetInvalidation",
    ]
    resources = ["*"]
  }

  # Terraform necesita administrar los servicios que esta configuración crea.
  # La lista es explícita para que una revisión note cualquier permiso nuevo.
  statement {
    sid    = "ServiceManagement"
    effect = "Allow"
    actions = [
      "apigateway:GET",
      "apigateway:PUT",
      "apigateway:POST",
      "apigateway:DELETE",
      "apigateway:PATCH",
      "elasticloadbalancing:DescribeLoadBalancers",
      "elasticloadbalancing:DescribeListeners",
      "elasticloadbalancing:DescribeTargetGroups",
      "elasticloadbalancing:DescribeTargetHealth",
      "elasticloadbalancing:DescribeSSLPolicies",
      "elasticloadbalancing:CreateListener",
      "elasticloadbalancing:DeleteListener",
      "elasticloadbalancing:CreateTargetGroup",
      "elasticloadbalancing:DeleteTargetGroup",
      "elasticloadbalancing:RegisterTargets",
      "elasticloadbalancing:DeregisterTargets",
      "elasticloadbalancing:CreateLoadBalancer",
      "elasticloadbalancing:DeleteLoadBalancer",
      "elasticloadbalancing:CreateRule",
      "elasticloadbalancing:DeleteRule",
      "elasticloadbalancing:SetSubnets",
      "elasticloadbalancing:SetSecurityGroups",
      "elasticloadbalancing:AddTags",
      "elasticloadbalancing:RemoveTags",
      "elasticloadbalancing:ModifyListener",
    ]
    resources = ["*"]
  }

  statement {
    sid    = "EcsAndServiceDiscovery"
    effect = "Allow"
    actions = [
      "ecs:RegisterTaskDefinition",
      "ecs:DeregisterTaskDefinition",
      "ecs:DescribeTaskDefinition",
      "ecs:CreateService",
      "ecs:UpdateService",
      "ecs:DeleteService",
      "ecs:DescribeServices",
      "ecs:DescribeClusters",
      "ecs:CreateCluster",
      "ecs:DeleteCluster",
      "ecs:TagResource",
      "ecs:ListTasks",
      "ecs:DescribeTasks",
      "ecs:PutClusterCapacityProviders",
      "servicediscovery:CreateService",
      "servicediscovery:UpdateService",
      "servicediscovery:DeleteService",
      "servicediscovery:CreatePrivateDnsNamespace",
      "servicediscovery:DeleteNamespace",
      "servicediscovery:GetNamespace",
      "servicediscovery:GetService",
      "servicediscovery:ListInstances",
      "servicediscovery:TagResource",
      "application-autoscaling:RegisterScalableTarget",
      "application-autoscaling:DeregisterScalableTarget",
      "application-autoscaling:PutScalingPolicy",
      "application-autoscaling:DeleteScalingPolicy",
      "application-autoscaling:DescribeScalableTargets",
      "application-autoscaling:DescribeScalingPolicies",
    ]
    resources = ["*"]
  }

  statement {
    sid    = "Network"
    effect = "Allow"
    actions = [
      "ec2:CreateVpc",
      "ec2:CreateSubnet",
      "ec2:DeleteSubnet",
      "ec2:DeleteVpc",
      "ec2:CreateInternetGateway",
      "ec2:AttachInternetGateway",
      "ec2:DetachInternetGateway",
      "ec2:DeleteInternetGateway",
      "ec2:CreateRouteTable",
      "ec2:DeleteRouteTable",
      "ec2:CreateRoute",
      "ec2:DeleteRoute",
      "ec2:AssociateRouteTable",
      "ec2:DisassociateRouteTable",
      "ec2:CreateTags",
      "ec2:AllocateAddress",
      "ec2:ReleaseAddress",
      "ec2:AssociateAddress",
      "ec2:DisassociateAddress",
      "ec2:DescribeAddresses",
      "ec2:CreateNatGateway",
      "ec2:DeleteNatGateway",
      "ec2:CreateVpcEndpoint",
      "ec2:DeleteVpcEndpoints",
      "ec2:ModifyVpcEndpoint",
      "ec2:CreateSecurityGroup",
      "ec2:DeleteSecurityGroup",
      "ec2:AuthorizeSecurityGroupIngress",
      "ec2:AuthorizeSecurityGroupEgress",
      "ec2:RevokeSecurityGroupIngress",
      "ec2:RevokeSecurityGroupEgress",
      "ec2:Describe*",
      "ec2:GetCoipPoolUsage",
    ]
    resources = ["*"]
  }

  statement {
    sid    = "SecurityGroupsAsResource"
    effect = "Allow"
    actions = [
      "ec2:CreateSecurityGroup",
      "ec2:DeleteSecurityGroup",
    ]
    resources = ["*"]
  }

  statement {
    sid    = "Secrets"
    effect = "Allow"
    actions = [
      "secretsmanager:CreateSecret",
      "secretsmanager:DescribeSecret",
      "secretsmanager:UpdateSecret",
      "secretsmanager:TagResource",
      "secretsmanager:PutResourcePolicy",
      "secretsmanager:DeleteSecret",
      "secretsmanager:GetResourcePolicy",
      "secretsmanager:RemoveResourcePolicy",
      "kms:CreateKey",
      "kms:DescribeKey",
      "kms:EnableKeyRotation",
      "kms:CreateAlias",
      "kms:ScheduleKeyDeletion",
      "kms:TagResource",
    ]
    resources = ["*"]
  }

  statement {
    sid    = "Database"
    effect = "Allow"
    actions = [
      "rds:CreateDBInstance",
      "rds:ModifyDBInstance",
      "rds:DeleteDBInstance",
      "rds:DescribeDBInstances",
      "rds:CreateDBSubnetGroup",
      "rds:ModifyDBSubnetGroup",
      "rds:DeleteDBSubnetGroup",
      "rds:DescribeDBSubnetGroups",
      "rds:CreateDBParameterGroup",
      "rds:ModifyDBParameterGroup",
      "rds:DeleteDBParameterGroup",
      "rds:DescribeDBParameterGroups",
      "rds:CreateDBSnapshot",
      "rds:DeleteDBSnapshot",
      "rds:CopyDBSnapshot",
      "rds:AddTagsToResource",
      "rds:ListTagsForResource",
    ]
    resources = ["*"]
  }

  # IAM limitado al path del proyecto, para no poder tocar otros roles.
  statement {
    sid    = "ProjectRoles"
    effect = "Allow"
    actions = [
      "iam:CreateRole",
      "iam:DeleteRole",
      "iam:GetRole",
      "iam:UpdateRole",
      "iam:UpdateAssumeRolePolicy",
      "iam:PutRolePolicy",
      "iam:DeleteRolePolicy",
      "iam:GetRolePolicy",
      "iam:ListRolePolicies",
      "iam:ListAttachedRolePolicies",
      "iam:AttachRolePolicy",
      "iam:DetachRolePolicy",
      "iam:TagRole",
      "iam:UntagRole",
      "iam:ListRoles",
      "iam:PassRole",
    ]
    resources = [
      "arn:${local.partition}:iam::${local.account_id}:role/${var.project_name}/*",
    ]
  }

  statement {
    sid    = "LogGroups"
    effect = "Allow"
    actions = [
      "logs:CreateLogGroup",
      "logs:DeleteLogGroup",
      "logs:DescribeLogGroups",
      "logs:PutRetentionPolicy",
      "logs:TagLogGroup",
      "logs:UntagLogGroup",
    ]
    resources = ["*"]
  }

  statement {
    sid    = "Tagging"
    effect = "Allow"
    actions = [
      "tag:GetResources",
      "tag:TagResources",
      "tag:UntagResources",
    ]
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "deploy" {
  name_prefix = "deploy-"
  role        = aws_iam_role.deploy.id
  policy      = data.aws_iam_policy_document.deploy.json
}
