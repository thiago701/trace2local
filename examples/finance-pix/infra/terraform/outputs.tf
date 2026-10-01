output "api_base_url" {
  description = "URL base da API Pix (LocalStack: formato _user_request_)"
  value       = var.localstack ? "${var.localstack_endpoint}/restapis/${aws_api_gateway_rest_api.pix.id}/${aws_api_gateway_stage.local.stage_name}/_user_request_" : aws_api_gateway_stage.local.invoke_url
}

output "rest_api_id" {
  value = aws_api_gateway_rest_api.pix.id
}

output "settlement_queue_url" {
  value = aws_sqs_queue.settlement.url
}

output "events_topic_arn" {
  value = aws_sns_topic.events.arn
}

output "functions" {
  value = [for f in aws_lambda_function.fn : f.function_name]
}
