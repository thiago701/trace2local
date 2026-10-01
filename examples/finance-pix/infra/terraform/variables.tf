variable "localstack" {
  description = "true = LocalStack (endpoints locais, RDS substituído pelo Postgres do compose)"
  type        = bool
  default     = true
}

variable "localstack_endpoint" {
  description = "Endpoint do LocalStack visto pelo Terraform"
  type        = string
  default     = "http://localhost:4566"
}

variable "region" {
  type    = string
  default = "us-east-1"
}

variable "environment" {
  type    = string
  default = "local"
}

variable "lambda_package" {
  description = "Pacote das Lambdas: target/dist/lambda-jvm.zip (JRE 25 jlink) ou lambda-native.zip (GraalVM)"
  type        = string
  default     = "../../target/dist/lambda-jvm.zip"
}

variable "lambda_runtime" {
  description = "provided.al2023 (runtime custom: JVM 25 empacotada ou binário nativo). Na AWS real, 'java25' usa o runtime gerenciado."
  type        = string
  default     = "provided.al2023"
}

variable "lambda_architecture" {
  type    = string
  default = "x86_64"
}

variable "lambda_memory_mb" {
  type    = number
  default = 768
}

variable "station_endpoint" {
  description = "Trace2Local Station visto de DENTRO das Lambdas (vazio na AWS real: o Trace2Local se desliga sozinho)"
  type        = string
  default     = "http://trace2local-station:19877"
}

variable "station_token" {
  description = "Bearer do ingest do Station (só local)"
  type        = string
  default     = ""
  sensitive   = true
}

variable "mocks_routing" {
  description = "on = chamadas a parceiros com mock ativo no Mock Connect são desviadas para o mock (só local)"
  type        = string
  default     = "on"
}

variable "aws_endpoint_url_in_lambda" {
  description = "Endpoint AWS visto de dentro das Lambdas (LocalStack no compose); vazio na AWS real"
  type        = string
  default     = "http://localstack:4566"
}

variable "db_url" {
  description = "JDBC do ledger. Local: Postgres do compose. AWS: preenchido a partir do RDS (rds.tf)."
  type        = string
  default     = "jdbc:postgresql://postgres:5432/pix"
}

variable "db_username" {
  type    = string
  default = "pix"
}

variable "db_password" {
  description = "Senha do Postgres LOCAL. Na AWS a senha fica no Secrets Manager gerenciado pelo RDS."
  type        = string
  default     = "pix-local-only"
  sensitive   = true
}

variable "partner_base_urls" {
  description = "URLs base das APIs externas (DICT, KYC, Antifraude, SPI, Notificações)"
  type        = map(string)
  default = {
    DICT_BASE_URL       = "http://dict.bacen.local:8080/api/v2"
    KYC_BASE_URL        = "http://kyc.bureau.local:8080"
    ANTIFRAUDE_BASE_URL = "http://antifraude.partner.local:8080"
    SPI_BASE_URL        = "http://spi.bacen.local:8080/spi/v1"
    NOTIFY_BASE_URL     = "http://notify.partner.local:8080"
  }
}

# ---- RDS (somente AWS real) ----
variable "vpc_id" {
  type    = string
  default = ""
}

variable "private_subnet_ids" {
  type    = list(string)
  default = []
}
