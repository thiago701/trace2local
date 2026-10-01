#!/usr/bin/env bash
# Sobe o cenário completo: build da lib + Station, pacote das Lambdas, compose e Terraform.
# Requisitos: Docker, JDK 25 (JAVA_HOME), Maven, Terraform ≥ 1.6.
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$(cd ../.. && pwd)"
echo "▸ biblioteca + Station (JDK 21+)"
(cd "$ROOT" && mvn -B -q install -DskipTests -pl trace2local-station -am)
echo "▸ Lambdas Java 25 (JVM)"
./scripts/build.sh
echo "▸ docker compose (LocalStack 4.9, Postgres 16, parceiros WireMock, Station)"
docker compose up -d --build --wait
echo "▸ Terraform → LocalStack"
./scripts/deploy.sh jvm
cat <<MSG

Pronto.
  UI do Trace2Local ....... http://localhost:19877/trace2local
  API Pix (API Gateway) ... http://localhost:4568/restapis/pixapi/local/_user_request_/pix/transfers
  Mock Connect ............ http://localhost:19878   (API: /trace2local/api/mocks)
  Jornadas de validação ... python3 scripts/journeys.py
MSG
