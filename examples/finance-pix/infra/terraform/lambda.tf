locals {
  functions = {
    "pix-api"        = { timeout = 15, description = "API Pix (API Gateway → aws_proxy)" }
    "pix-settlement" = { timeout = 15, description = "Liquidação no SPI (SQS)" }
    "pix-notifier"   = { timeout = 10, description = "Notificação ao cliente (SNS)" }
  }
  # runtime custom: _HANDLER escolhe a função dentro do mesmo binário;
  # runtime gerenciado java25 (AWS real): handler = classe do RequestHandler
  managed_handlers = {
    "pix-api"        = "tech.neural7.trace2local.examples.pix.api.PixApiHandler::handleRequest"
    "pix-settlement" = "tech.neural7.trace2local.examples.pix.settlement.PixSettlementHandler::handleRequest"
    "pix-notifier"   = "tech.neural7.trace2local.examples.pix.notify.PixNotifierHandler::handleRequest"
  }
}

resource "aws_cloudwatch_log_group" "fn" {
  for_each          = local.functions
  name              = "/aws/lambda/${each.key}"
  retention_in_days = 7
}

resource "aws_lambda_function" "fn" {
  for_each      = local.functions
  function_name = each.key
  description   = each.value.description
  role          = aws_iam_role.lambda.arn
  runtime       = var.lambda_runtime
  handler       = var.lambda_runtime == "java25" ? local.managed_handlers[each.key] : each.key
  architectures = [var.lambda_architecture]
  memory_size   = var.lambda_memory_mb
  timeout       = each.value.timeout

  s3_bucket        = aws_s3_bucket.artifacts.id
  s3_key           = aws_s3_object.lambda_package.key
  source_code_hash = filebase64sha256(var.lambda_package)

  environment {
    variables = { for k, v in local.common_env : k => v if v != "" }
  }

  dynamic "vpc_config" {
    for_each = var.localstack ? [] : [1]
    content {
      subnet_ids         = var.private_subnet_ids
      security_group_ids = [aws_security_group.lambda[0].id]
    }
  }

  depends_on = [aws_cloudwatch_log_group.fn, aws_iam_role_policy.lambda]
}

# SQS → pix-settlement (batch de 1: uma árvore por transferência; falha = reentrega)
resource "aws_lambda_event_source_mapping" "settlement" {
  event_source_arn = aws_sqs_queue.settlement.arn
  function_name    = aws_lambda_function.fn["pix-settlement"].arn
  batch_size       = 1
  enabled          = true
}

# SNS → pix-notifier
resource "aws_sns_topic_subscription" "notifier" {
  topic_arn = aws_sns_topic.events.arn
  protocol  = "lambda"
  endpoint  = aws_lambda_function.fn["pix-notifier"].arn
}

resource "aws_lambda_permission" "sns_notifier" {
  statement_id  = "AllowSnsInvoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.fn["pix-notifier"].function_name
  principal     = "sns.amazonaws.com"
  source_arn    = aws_sns_topic.events.arn
}
