-- Ledger do demo Pix (Postgres 16 — RDS na AWS, container no ambiente local).
-- Saldo disponível = balance - held. Retenção (HOLD) na aceitação; débito na liquidação.

CREATE TABLE IF NOT EXISTS accounts (
    id          VARCHAR(40) PRIMARY KEY,
    owner_name  VARCHAR(80)    NOT NULL,
    ispb        VARCHAR(8)     NOT NULL DEFAULT '00000001',
    balance     NUMERIC(14, 2) NOT NULL CHECK (balance >= 0),
    held        NUMERIC(14, 2) NOT NULL DEFAULT 0 CHECK (held >= 0),
    updated_at  TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS ledger_entries (
    id           VARCHAR(40) PRIMARY KEY,
    account_id   VARCHAR(40)    NOT NULL REFERENCES accounts (id),
    transfer_id  VARCHAR(40)    NOT NULL,
    entry_type   VARCHAR(12)    NOT NULL CHECK (entry_type IN ('HOLD', 'DEBIT')),
    amount       NUMERIC(14, 2) NOT NULL CHECK (amount > 0),
    status       VARCHAR(12)    NOT NULL CHECK (status IN ('PENDING', 'SETTLED', 'RELEASED')),
    created_at   TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_ledger_transfer_type ON ledger_entries (transfer_id, entry_type);

INSERT INTO accounts (id, owner_name, ispb, balance) VALUES
    ('acc-001',       'Maria Pagadora',   '00000001', 25000.00),
    ('acc-002',       'Ana Saldo Curto',  '00000001',    50.00),
    ('acc-bloqueada', 'Conta Sinalizada', '00000001',  5000.00)
ON CONFLICT (id) DO NOTHING;
