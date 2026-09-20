#!/bin/sh
set -e
until aws --endpoint-url=http://host.docker.internal:4566 dynamodb list-tables >/dev/null 2>&1; do sleep 2; done
aws --endpoint-url=http://host.docker.internal:4566 dynamodb create-table \
  --table-name orders \
  --attribute-definitions AttributeName=pk,AttributeType=S \
  --key-schema AttributeName=pk,KeyType=HASH \
  --billing-mode PAY_PER_REQUEST || true
aws --endpoint-url=http://host.docker.internal:4566 sns create-topic --name order-events || true
aws --endpoint-url=http://host.docker.internal:4566 sqs create-queue --queue-name billing-queue || true
aws --endpoint-url=http://host.docker.internal:4566 sns subscribe \
  --topic-arn arn:aws:sns:us-east-1:000000000000:order-events \
  --protocol sqs \
  --notification-endpoint arn:aws:sqs:us-east-1:000000000000:billing-queue || true
echo PROVISIONADO
