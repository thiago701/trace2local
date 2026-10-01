# API Gateway REST (v1) definido PELO CONTRATO OpenAPI (openapi/pix-api.yaml):
# a mesma fonte alimenta o gateway, o catálogo do Trace2Local e os testes de contrato.
resource "aws_api_gateway_rest_api" "pix" {
  name        = "${local.name}-api"
  description = "Pix Transfers API (OpenAPI → API Gateway)"
  # LocalStack: id fixo ("pixapi") → URL estável para o Station/QA; ignorado pela AWS real
  tags = var.localstack ? { "_custom_id_" = "pixapi" } : {}
  body = templatefile("${path.module}/../../openapi/pix-api.yaml", {
    pix_api_invoke_arn = aws_lambda_function.fn["pix-api"].invoke_arn
  })

  endpoint_configuration {
    types = ["REGIONAL"]
  }
}

resource "aws_api_gateway_deployment" "pix" {
  rest_api_id = aws_api_gateway_rest_api.pix.id
  triggers = {
    redeploy = sha1(aws_api_gateway_rest_api.pix.body)
  }
  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_api_gateway_stage" "local" {
  rest_api_id   = aws_api_gateway_rest_api.pix.id
  deployment_id = aws_api_gateway_deployment.pix.id
  stage_name    = "local"
}

resource "aws_lambda_permission" "apigw" {
  statement_id  = "AllowApiGatewayInvoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.fn["pix-api"].function_name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_api_gateway_rest_api.pix.execution_arn}/*/*"
}
