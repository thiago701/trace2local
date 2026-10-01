#!/usr/bin/env bash
# Binário NATIVO (GraalVM AOT) das Lambdas para o runtime provided.al2023.
#
#  1) treino: roda as 3 funções FORA da Lambda com o native-image-agent, contra o cenário
#     do compose (LocalStack, Postgres, parceiros, Station), cobrindo os caminhos das
#     jornadas → reflexão/recursos/proxies reais em src/main/resources/META-INF/native-image
#  2) compilação DENTRO de ghcr.io/graalvm/native-image-community:25 (Oracle Linux 9,
#     glibc 2.34 = mesma do Amazon Linux 2023: o binário roda na Lambda)
#  3) target/dist/lambda-native.zip = bootstrap + executável
#
# Uso: JAVA_HOME=<GraalVM 25> ./scripts/build-native.sh [--skip-agent]
set -euo pipefail
cd "$(dirname "$0")/.."
: "${JAVA_HOME:?defina JAVA_HOME apontando para um GraalVM 25 (tem o native-image-agent)}"
IMAGE="${NATIVE_BUILDER_IMAGE:-ghcr.io/graalvm/native-image-community:25}"
META=src/main/resources/META-INF/native-image/tech.neural7.trace2local.examples/finance-pix

mvn -B -q -f pom.xml package -DskipTests

if [ "${1:-}" != "--skip-agent" ]; then
  echo "▸ treino com native-image-agent (cenário do compose precisa estar de pé)"
  mkdir -p "$META"
  TF_OUT=$(cd infra/terraform && ${TERRAFORM:-terraform} output -json)
  export PIX_SETTLEMENT_QUEUE_URL=$(echo "$TF_OUT" | python3 -c 'import json,sys; print(json.load(sys.stdin)["settlement_queue_url"]["value"])')
  export PIX_EVENTS_TOPIC_ARN=$(echo "$TF_OUT" | python3 -c 'import json,sys; print(json.load(sys.stdin)["events_topic_arn"]["value"])')
  export TRACE2LOCAL_STATION_ENDPOINT=http://localhost:19877 TRACE2LOCAL_STATION_TOKEN=devtoken \
         TRACE2LOCAL_MOCKS_ROUTING=on TRACE2LOCAL_JDBC_MUTATION_CAPTURE=inferred \
         AWS_ENDPOINT_URL=http://localhost:4568 AWS_REGION=us-east-1 AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test \
         PIX_DB_URL=jdbc:postgresql://localhost:15432/pix PIX_DB_PASSWORD=pix-local-only \
         DICT_BASE_URL=http://localhost:18080/api/v2 ANTIFRAUDE_BASE_URL=http://localhost:18080 \
         SPI_BASE_URL=http://localhost:18080/spi/v1 NOTIFY_BASE_URL=http://localhost:18080 \
         KYC_BASE_URL=${KYC_BASE_URL:-http://localhost:19878/mock-kyc-limites}
  run() {
    AWS_LAMBDA_FUNCTION_NAME="$1" "$JAVA_HOME/bin/java" \
      -agentlib:native-image-agent=config-merge-dir="$META" \
      -cp target/finance-pix.jar tech.neural7.trace2local.examples.pix.runtime.LocalInvoke "$1" "events/$2" "${3:-1}" >/dev/null
  }
  run pix-api create-approved.json 2
  run pix-api create-review.json
  run pix-api create-denied.json
  run pix-api create-invalid.json
  run pix-api create-replay.json 2
  run pix-api get-transfer.json
  run pix-settlement settlement.json
  run pix-notifier notifier.json
  echo "  configuração em $META"
  # o treino exporta para o Station (exercita o OTLP); limpa o acervo para não misturar
  # execuções de localhost com as do cenário (o conselheiro veria hosts diferentes)
  curl -s -X DELETE -H "X-Trace2Local: 1" http://localhost:19877/trace2local/api/executions >/dev/null || true
  mvn -B -q -f pom.xml package -DskipTests   # o jar passa a carregar a configuração
fi

echo "▸ native-image em $IMAGE (glibc compatível com AL2023)"
mkdir -p target/native
docker run --rm -v "$PWD/target:/work" -w /work --entrypoint native-image "$IMAGE" \
  -jar finance-pix.jar -o native/finance-pix-native \
  --no-fallback -march=compatibility -O2 \
  --enable-url-protocols=http,https \
  -H:+ReportExceptionStackTraces \
  -J-Xmx${NATIVE_XMX:-5g}

STAGE=target/lambda-native
rm -rf "$STAGE" && mkdir -p "$STAGE" target/dist
cp target/native/finance-pix-native "$STAGE/"
cp runtime/bootstrap-native "$STAGE/bootstrap"
chmod 755 "$STAGE/bootstrap" "$STAGE/finance-pix-native"
(cd "$STAGE" && rm -f ../dist/lambda-native.zip && zip -qr9 ../dist/lambda-native.zip bootstrap finance-pix-native)
ls -lh target/dist/lambda-native.zip
