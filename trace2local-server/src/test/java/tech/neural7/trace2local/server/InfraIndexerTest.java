package tech.neural7.trace2local.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Catálogo de infra: URLs/ARNs/envs/recursos Terraform com fonte e mascaramento de segredos. */
class InfraIndexerTest {

    @Test
    void catalogsTerraformEnvAndUrlsWithSourceLocations(@TempDir Path dir) throws IOException {
        Path tf = dir.resolve("main.tf");
        Files.writeString(tf, """
                resource "aws_dynamodb_table" "payments" {
                  name = "payments"
                }
                locals {
                  endpoint = "http://localhost:4566"
                }
                """);
        Path env = dir.resolve(".env");
        Files.writeString(env, """
                LOCALSTACK_ENDPOINT=http://localhost:4567
                DATABASE_PASSWORD=hunter2
                """);
        Path yml = dir.resolve("application.yml");
        Files.writeString(yml, """
                orders:
                  queue-url: https://sqs.us-east-1.amazonaws.com/000000000000/orders-queue
                """);

        List<InfraIndexer.Entry> entries = new InfraIndexer().scan(List.of(dir));

        assertThat(entries).anySatisfy(e -> {
            assertThat(e.type()).isEqualTo("RECURSO_TERRAFORM");
            assertThat(e.name()).isEqualTo("dynamodb_table");
            assertThat(e.value()).isEqualTo("payments");
            assertThat(e.sources()).anySatisfy(s -> {
                assertThat(s.kind()).isEqualTo("terraform");
                assertThat(s.file()).endsWith("main.tf");
                assertThat(s.line()).isEqualTo(1);
            });
        });
        assertThat(entries).anySatisfy(e -> assertThat(e.value()).isEqualTo("http://localhost:4566"));
        assertThat(entries).anySatisfy(e -> {
            assertThat(e.type()).isEqualTo("ENV");
            assertThat(e.name()).isEqualTo("LOCALSTACK_ENDPOINT");
            assertThat(e.value()).isEqualTo("http://localhost:4567");
            assertThat(e.sources()).anySatisfy(s -> {
                assertThat(s.kind()).isEqualTo("env");
                assertThat(s.line()).isEqualTo(1);
            });
        });
        // segredo mascarado pelo mesmo critério do Redactor (ADR-007)
        assertThat(entries).anySatisfy(e -> {
            assertThat(e.name()).isEqualTo("DATABASE_PASSWORD");
            assertThat(e.value()).isEqualTo("[OCULTO]");
        });
        assertThat(entries).anySatisfy(e -> e.value().contains("orders-queue"));
    }

    @Test
    void terraformUsesTheRealResourceNameNotTheLocalLabel(@TempDir Path dir) throws IOException {
        // achado da validação finance-pix: o rótulo local "transfers" virava um componente
        // "declarado e nunca observado" ao lado do real pix-transfers
        Files.writeString(dir.resolve("main.tf"), """
                resource "aws_dynamodb_table" "transfers" {
                  name         = "pix-transfers"
                  hash_key     = "transferId"
                  attribute {
                    name = "transferId"   # aninhado: não é o nome da tabela
                    type = "S"
                  }
                }
                resource "aws_sqs_queue" "settlement" {
                  attribute_hint = 1
                  name = "pix-settlement"
                }
                resource "aws_lambda_function" "fn" {
                  for_each      = local.functions
                  function_name = each.key
                }
                """);

        List<InfraIndexer.Entry> entries = new InfraIndexer().scan(List.of(dir));

        assertThat(entries).filteredOn(e -> e.type().equals("RECURSO_TERRAFORM"))
                .extracting(InfraIndexer.Entry::value)
                .containsExactlyInAnyOrder("pix-transfers", "pix-settlement", "each.key")
                .doesNotContain("transferId", "transfers", "fn");
    }

    @Test
    void emptyScanProducesNoEntries(@TempDir Path empty) {
        assertThat(new InfraIndexer().scan(List.of(empty))).isEmpty();
    }
}
