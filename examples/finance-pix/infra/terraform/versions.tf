terraform {
  required_version = ">= 1.6.0"
  required_providers {
    aws = {
      source = "hashicorp/aws"
      # 5.x: o provider 6.x espera WarmThroughput no DescribeTable do DynamoDB, que o
      # LocalStack Community 4.9 ainda não devolve (o apply fica preso em "waiting for update")
      version = "~> 5.100"
    }
  }
}

# Mesmo código para LocalStack (var.localstack = true) e AWS real (false).
provider "aws" {
  region = var.region

  access_key                  = var.localstack ? "test" : null
  secret_key                  = var.localstack ? "test" : null
  skip_credentials_validation = var.localstack
  skip_metadata_api_check     = var.localstack
  skip_requesting_account_id  = var.localstack
  s3_use_path_style           = var.localstack

  dynamic "endpoints" {
    for_each = var.localstack ? [1] : []
    content {
      apigateway = var.localstack_endpoint
      cloudwatch = var.localstack_endpoint
      dynamodb   = var.localstack_endpoint
      iam        = var.localstack_endpoint
      lambda     = var.localstack_endpoint
      logs       = var.localstack_endpoint
      s3         = var.localstack_endpoint
      sns        = var.localstack_endpoint
      sqs        = var.localstack_endpoint
      sts        = var.localstack_endpoint
    }
  }

  default_tags {
    tags = {
      project     = "trace2local-finance-pix"
      environment = var.environment
      managed-by  = "terraform"
    }
  }
}
