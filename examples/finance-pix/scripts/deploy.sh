#!/usr/bin/env bash
# Provisiona (ou atualiza) a infra no LocalStack com Terraform.
# Uso: ./scripts/deploy.sh [jvm|native]      (padrão: jvm)
set -euo pipefail
cd "$(dirname "$0")/.."
MODE="${1:-jvm}"
PKG="../../target/dist/lambda-${MODE}.zip"
[ -f "target/dist/lambda-${MODE}.zip" ] || { echo "pacote target/dist/lambda-${MODE}.zip ausente — rode scripts/build.sh (jvm) ou scripts/build-native.sh (native)" >&2; exit 1; }
TF="${TERRAFORM:-terraform}"
cd infra/terraform
"$TF" init -input=false -no-color >/dev/null
"$TF" apply -auto-approve -input=false -no-color -var-file=localstack.tfvars \
  -var "localstack_endpoint=${LOCALSTACK_ENDPOINT:-http://localhost:4568}" \
  -var "lambda_package=${PKG}" | tail -12
