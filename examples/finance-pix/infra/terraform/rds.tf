# Ledger em RDS Postgres 16 — SÓ na AWS real. O LocalStack Community não emula RDS:
# localmente o mesmo schema roda no Postgres do docker-compose (limitação declarada no README).

resource "aws_security_group" "lambda" {
  count       = var.localstack ? 0 : 1
  name        = "${local.name}-lambda"
  description = "Lambdas do Pix"
  vpc_id      = var.vpc_id

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_security_group" "db" {
  count       = var.localstack ? 0 : 1
  name        = "${local.name}-db"
  description = "Postgres do ledger: só as Lambdas"
  vpc_id      = var.vpc_id

  ingress {
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [aws_security_group.lambda[0].id]
  }
}

resource "aws_db_subnet_group" "ledger" {
  count      = var.localstack ? 0 : 1
  name       = "${local.name}-ledger"
  subnet_ids = var.private_subnet_ids
}

resource "aws_db_instance" "ledger" {
  count                        = var.localstack ? 0 : 1
  identifier                   = "${local.name}-ledger"
  engine                       = "postgres"
  engine_version               = "16"
  instance_class               = "db.t4g.micro"
  allocated_storage            = 20
  storage_encrypted            = true
  db_name                      = "pix"
  username                     = var.db_username
  manage_master_user_password  = true # senha no Secrets Manager, nunca no state em texto
  db_subnet_group_name         = aws_db_subnet_group.ledger[0].name
  vpc_security_group_ids       = [aws_security_group.db[0].id]
  publicly_accessible          = false
  backup_retention_period      = 7
  deletion_protection          = true
  skip_final_snapshot          = false
  final_snapshot_identifier    = "${local.name}-ledger-final"
  performance_insights_enabled = true
}
