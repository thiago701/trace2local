# finance-pix — o Trace2Local na stack alvo (domínio financeiro)

Um serviço de **transferências Pix** completo, rodando 100 % local, usado para validar o Trace2Local em
condições reais — e para você ver, em 10 minutos, o que a ferramenta mostra num sistema serverless de verdade.

```
cliente ─► API Gateway (OpenAPI) ─► λ pix-api ─┬─► DynamoDB  pix-idempotency · pix-transfers
                                               ├─► DICT (BACEN) · KYC & Limites · Antifraude   ← parceiros HTTP
                                               ├─► Postgres  accounts · ledger_entries          ← RDS na AWS
                                               └─► SQS pix-settlement ─► λ pix-settlement ─┬─► SPI (BACEN)
                                                                                           ├─► Postgres (débito)
                                                                                           └─► SNS pix-events ─► λ pix-notifier ─► Notificações
                          tudo instrumentado ─► Trace2Local Station (UI · Mock Connect · logs CloudWatch)
```

| peça | tecnologia |
|---|---|
| Funções | **Java 25**, runtime `provided.al2023` com laço próprio da Lambda Runtime API — **JVM** (jlink + CDS) ou **nativo** (GraalVM 25 AOT) |
| Infra | **Terraform** (provider AWS ~> 5.100) aplicado no **LocalStack 4.9**; o mesmo código provisiona AWS (`aws.tfvars.example`, RDS em `rds.tf`) |
| API | **API Gateway** a partir de `openapi/pix-api.yaml` (contrato = fonte da verdade, também alimenta a aba API da UI) |
| Dados | **DynamoDB** (estado e idempotência) + **Postgres 16** (contas e ledger; RDS na AWS) |
| Mensageria | **SQS** (liquidação, DLQ após 3 tentativas) + **SNS** (eventos de domínio) |
| Parceiros | 5 APIs externas com contrato OpenAPI em `mocks/contracts`: DICT, Antifraude, SPI e Notificações simulados por WireMock; **KYC ausente de propósito** ("API indisponível no ambiente local") |
| Observabilidade | Trace2Local (OTel + trace W3C/baggage/AWSTraceHeader), logs CloudWatch das 3 funções, **Mock Connect** |

Regras de negócio homologáveis em [`trace2local-business.md`](trace2local-business.md) (a Investigação cruza cada
regra com os passos da árvore).

## Rodando

### Windows — só Docker Desktop

Nenhum JDK, Maven ou Terraform no host: o build roda num container Linux (`Dockerfile.build` — o JRE da Lambda
precisa ser Linux), o Terraform no container `hashicorp/terraform`.

```powershell
examples\finance-pix\scripts\up.cmd                 # duplo clique também funciona
examples\finance-pix\scripts\up.cmd -SkipBuild      # reaproveita os artefatos já gerados
examples\finance-pix\scripts\up.cmd -Down           # derruba containers, rede, volumes e estado do Terraform
examples\finance-pix\scripts\up.cmd -CaBundle C:\certs\empresa.pem   # proxy corporativo com inspeção TLS
```

O `up` inicia o Docker Desktop se estiver parado, confere as portas, faz uma chamada real ao `POST /pix/transfers`
(o 502 do KYC é o esperado — passo 2 do tour) e abre a UI. Log completo em `target/up.log`. 1ª vez: 5–15 min
(imagens e dependências); depois, ~1 min com `-SkipBuild`.

### Linux / macOS

Requisitos: Docker, **JDK 25** (`JAVA_HOME`), Maven, Terraform ≥ 1.6, Python 3 (jornadas). Para o nativo: um
GraalVM 25 (só para o *agent* de treino; a compilação roda no container `native-image-community:25`).

```bash
./scripts/up.sh                     # lib + Station → Lambdas (JVM) → compose → Terraform
python3 scripts/journeys.py         # 12 jornadas reais conferidas em 6 níveis (61 verificações)
```

> O caminho só-Docker também roda aqui: `docker build -f examples/finance-pix/Dockerfile.build --target artifacts
> --output type=local,dest=target/finance-pix-build .` na raiz gera o jar do Station e o `lambda-jvm.zip`.

| endereço | o quê |
|---|---|
| http://localhost:19877/trace2local | UI do Trace2Local (Resonance) |
| http://localhost:4568/restapis/pixapi/local/_user_request_/pix/transfers | API Pix (API Gateway do LocalStack) |
| http://localhost:19878 | servidor de mocks do Mock Connect (gestão em `/trace2local/api/mocks`) |
| `localhost:15432` | Postgres (`pix` / `pix-local-only`, só local) |

Tudo é publicado **só no loopback** do host — LocalStack, Postgres e WireMock não têm autenticação.

### Nativo (GraalVM AOT) e benchmark

```bash
JAVA_HOME=<graalvm-25> ./scripts/build-native.sh     # treino com native-image-agent + compilação glibc 2.34 (= AL2023)
TERRAFORM=terraform ./scripts/deploy.sh native
python3 scripts/bench.py                              # cold × warm, JVM × nativo
```

Medido aqui (2 vCPU, Docker; números relativos): cold start **808 ms nativo × 2 191 ms JVM**, POST quente p50
**180 × 228 ms**, pacote **26 × 68 MB** — [BENCH-COLD-START.md](../../docs/qa/finance-pix/BENCH-COLD-START.md).

## Um tour de 10 minutos

1. **Anatomia (1)** — os órgãos do sistema por zona: núcleo (funções e passos de negócio), fronteira local
   (tabelas, fila, tópico, Postgres), fronteira externa (os 5 parceiros) e o que o IaC declara mas nunca foi visto (a DLQ).
2. **J1: KYC indisponível** — `POST /pix/transfers` devolve 502; a árvore mostra o nó do KYC vermelho com
   `ConnectException`, a idempotência liberada (Δ CREATE → DELETE) e **nada** de reserva de saldo.
3. **Mocks (9)** — a sugestão "KYC & Limites indisponível — plugue um mock" já está lá, com evidência e config
   gerada do contrato `kyc.yaml`. **Plugar mock** → repita a chamada: 202, e o passo do KYC ganha o selo **SIM**.
4. **Árvore (2)** de um Pix aprovado — API → DICT → KYC → reserva → antifraude → aceite → **SQS** → liquidação no
   SPI → débito → **SNS** → notificação, tudo na **mesma árvore**, com "⧗ fila" nas arestas assíncronas.
5. **Linha do tempo (3)** — os logs CloudWatch das 3 funções no mesmo eixo, por capítulo.
6. **Variação sob demanda** — na sugestão "Resposta decide o fluxo" do Antifraude, marque *decision ausente*,
   escolha **Sob demanda** e envie `baggage: t2l.mock=<id>` na chamada: a regra "decisão desconhecida nunca aprova"
   manda para revisão (IN_REVIEW). Sem o cabeçalho, a API real responde.
7. **Falha transitória** — "503 só na 1ª chamada" no SPI: a liquidação falha, a mensagem volta para a fila, a 2ª
   tentativa liquida — as duas tentativas na mesma árvore, e o ledger debita **uma única vez**.
8. **Investigação (4)** — homologação: cada regra do glossário com veredito, checklist e laudo.

## O que a validação conferiu

[`scripts/journeys.py`](scripts/journeys.py) roda 12 jornadas e confere cada uma **contra o estado real** (psql, DynamoDB),
não só contra o que a UI mostra — L1 contrato · L2 árvore · L3 dados · L4 assíncrono · L5 mocks · L6 ferramenta.
Resultado: **61/61 em JVM e em nativo** ([JVM](../../docs/qa/finance-pix/VALIDACAO-JVM.md) ·
[nativo](../../docs/qa/finance-pix/VALIDACAO-NATIVO.md)). A usabilidade é aceita pelo loop de personas
(**69/69**, [relatório](../../docs/qa/finance-pix/UX-LOOP-PERSONAS.md)); critérios em [ACEITE.md](../../docs/qa/ACEITE.md).

## Estrutura

```
openapi/pix-api.yaml        contrato da API (API Gateway + aba API da UI)
src/main/java/…/pix/        api · settlement · notify (handlers) · domain · partners · infra · runtime (Runtime API)
runtime/bootstrap-*         bootstrap da Lambda custom runtime (JVM e nativo)
infra/terraform/            API Gateway, Lambdas, DynamoDB, SQS+DLQ, SNS, IAM, RDS (AWS)
mocks/contracts/            OpenAPI dos 5 parceiros (fonte dos stubs do Mock Connect)
mocks/wiremock/             stubs dos parceiros "de verdade" (WireMock)
db/init.sql                 schema e massa do Postgres
events/                     eventos para LocalInvoke e treino do native-image-agent
scripts/                    up (.sh · .ps1/.cmd no Windows) · build · build-native · deploy · journeys · bench
Dockerfile.build            build só-Docker (lib + Station + Lambdas Java 25 com JRE jlink Linux)
```

> Credenciais e chaves deste diretório são de demo, locais (`test`/`test`, `pix-local-only`, `devtoken`). Nunca
> reutilize em ambiente compartilhado.
