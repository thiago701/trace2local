# Infra do payment-service (demo do catálogo INFRA & DEVOPS do Trace2Local)
# O catálogo da UI lê este arquivo e mostra ONDE cada recurso vive (arquivo:linha).
resource "aws_dynamodb_table" "payments" {
  name         = "payments"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "pk"

  attribute {
    name = "pk"
    type = "S"
  }
}

resource "aws_sqs_queue" "pix_notifications" {
  name = "pix-notifications"
}

variable "localstack_endpoint" {
  default = "http://localhost:4566"
}

locals {
  # o app aponta para o LocalStack no ambiente local
  endpoint_override = "http://localhost:4567"
}
