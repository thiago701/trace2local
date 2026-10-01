locals {
  name = "pix-${var.environment}"
  common_env = merge(var.partner_base_urls, {
    PIX_TRANSFERS_TABLE          = aws_dynamodb_table.transfers.name
    PIX_IDEMPOTENCY_TABLE        = aws_dynamodb_table.idempotency.name
    PIX_NOTIFICATIONS_TABLE      = aws_dynamodb_table.notifications.name
    PIX_SETTLEMENT_QUEUE_URL     = aws_sqs_queue.settlement.url
    PIX_EVENTS_TOPIC_ARN         = aws_sns_topic.events.arn
    PIX_DB_URL                   = local.db_url
    PIX_DB_USER                  = var.db_username
    PIX_DB_PASSWORD              = var.localstack ? var.db_password : ""
    AWS_ENDPOINT_URL             = var.localstack ? var.aws_endpoint_url_in_lambda : ""
    TRACE2LOCAL_STATION_ENDPOINT = var.localstack ? var.station_endpoint : ""
    TRACE2LOCAL_STATION_TOKEN    = var.localstack ? var.station_token : ""
    TRACE2LOCAL_MOCKS_ROUTING    = var.localstack ? var.mocks_routing : "off"
    # delta INFERIDO do SQL (intenção do INSERT/UPDATE) no ledger — só local
    TRACE2LOCAL_JDBC_MUTATION_CAPTURE = var.localstack ? "inferred" : ""
  })
  db_url = var.localstack ? var.db_url : try("jdbc:postgresql://${aws_db_instance.ledger[0].endpoint}/pix", var.db_url)
}

# ------------------------------------------------------------------ DynamoDB

resource "aws_dynamodb_table" "transfers" {
  name         = "pix-transfers"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "transferId"

  attribute {
    name = "transferId"
    type = "S"
  }

  point_in_time_recovery {
    enabled = !var.localstack
  }
}

resource "aws_dynamodb_table" "idempotency" {
  name         = "pix-idempotency"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "idempotencyKey"

  attribute {
    name = "idempotencyKey"
    type = "S"
  }

  ttl {
    attribute_name = "expiresAt"
    enabled        = true
  }
}

resource "aws_dynamodb_table" "notifications" {
  name         = "pix-notifications"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "notificationKey"

  attribute {
    name = "notificationKey"
    type = "S"
  }
}

# ------------------------------------------------------------------ SQS (liquidação + DLQ)

resource "aws_sqs_queue" "settlement_dlq" {
  name                      = "pix-settlement-dlq"
  message_retention_seconds = 1209600
}

resource "aws_sqs_queue" "settlement" {
  name                       = "pix-settlement"
  visibility_timeout_seconds = 20 # ≥ timeout da função; reentrega rápida para o demo de retry
  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.settlement_dlq.arn
    maxReceiveCount     = 3
  })
}

# ------------------------------------------------------------------ SNS (eventos de domínio)

resource "aws_sns_topic" "events" {
  name = "pix-events"
}

# ------------------------------------------------------------------ artefato das Lambdas

resource "aws_s3_bucket" "artifacts" {
  bucket        = "${local.name}-lambda-artifacts"
  force_destroy = true
}

resource "aws_s3_object" "lambda_package" {
  bucket = aws_s3_bucket.artifacts.id
  key    = "finance-pix/${filemd5(var.lambda_package)}.zip"
  source = var.lambda_package
  etag   = filemd5(var.lambda_package)
}
