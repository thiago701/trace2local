#!/usr/bin/env bash
# Empacota as Lambdas Java 25 para o runtime provided.al2023:
#   target/dist/lambda-jvm.zip    → bootstrap + JRE 25 enxuto (jlink) + finance-pix.jar
#   target/dist/lambda-native.zip → bootstrap nativo GraalVM (gerado por build-native.sh)
# Uso: JAVA_HOME=<jdk 25> [JLINK_HOME=<jdk 25 HotSpot>] ./scripts/build.sh
#   JLINK_HOME: JDK usado para gerar o JRE (prefira HotSpot puro, ex.: Temurin 25 — o jlink
#   do GraalVM embute o compilador JIT Graal, +41 MB que a Lambda não usa).
set -euo pipefail
cd "$(dirname "$0")/.."

: "${JAVA_HOME:?defina JAVA_HOME apontando para um JDK 25 (ex.: GraalVM CE 25)}"
"$JAVA_HOME/bin/java" -version 2>&1 | grep -q 'version "25' || { echo "JAVA_HOME não é um JDK 25" >&2; exit 1; }

echo "▸ build Maven (biblioteca Trace2Local já instalada no ~/.m2: mvn -B install na raiz)"
mvn -B -q -f pom.xml package -DskipTests

DIST=target/dist
STAGE=target/lambda-jvm
rm -rf "$STAGE" && mkdir -p "$STAGE" "$DIST"

# módulos: jdeps + os que só aparecem por reflexão/ServiceLoader (XML do SNS, TLS EC)
MODULES="java.base,java.desktop,java.management,java.naming,java.net.http,java.security.jgss,java.sql,java.xml,jdk.jfr,jdk.net,jdk.unsupported,jdk.crypto.ec"
echo "▸ jlink: JRE 25 com $MODULES"
JLINK_HOME="${JLINK_HOME:-$JAVA_HOME}"
"$JLINK_HOME/bin/jlink" --add-modules "$MODULES" --strip-debug --no-man-pages --no-header-files \
  --compress zip-6 --generate-cds-archive --output "$STAGE/jre"   # CDS padrão do JDK: cold start menor

rm -f "$STAGE/jre/lib/server/classes_nocoops.jsa"   # só serve para heaps > 32 GB
cp target/finance-pix.jar "$STAGE/finance-pix.jar"
cp runtime/bootstrap-jvm "$STAGE/bootstrap"
chmod 755 "$STAGE/bootstrap"

(cd "$STAGE" && rm -f ../dist/lambda-jvm.zip && zip -qr9 ../dist/lambda-jvm.zip bootstrap finance-pix.jar jre)
ls -lh "$DIST"/lambda-jvm.zip
