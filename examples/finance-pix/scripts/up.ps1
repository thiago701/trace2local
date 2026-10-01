<#
.SYNOPSIS
  Sobe o finance-pix no Windows usando SÓ o Docker Desktop — build, LocalStack e Terraform rodam em containers.

.DESCRIPTION
  1. confere o Docker (e inicia o Docker Desktop se estiver parado) e as portas locais;
  2. compila biblioteca + Station + Lambdas Java 25 num container Linux (Dockerfile.build);
  3. sobe LocalStack 4.9, Postgres 16, parceiros WireMock e o Trace2Local Station (docker compose);
  4. provisiona API Gateway, Lambdas, DynamoDB, SQS e SNS com Terraform (container hashicorp/terraform);
  5. faz uma chamada real ao POST /pix/transfers e abre a UI.
  Tudo publicado só no loopback (127.0.0.1). Log completo em examples\finance-pix\target\up.log.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File examples\finance-pix\scripts\up.ps1
.EXAMPLE
  powershell -ExecutionPolicy Bypass -File examples\finance-pix\scripts\up.ps1 -SkipBuild   # reaproveita os artefatos
.EXAMPLE
  powershell -ExecutionPolicy Bypass -File examples\finance-pix\scripts\up.ps1 -Down        # derruba tudo e limpa o estado
.EXAMPLE
  ... up.ps1 -CaBundle C:\certs\empresa.pem    # proxy corporativo com inspeção TLS (Maven e Terraform confiam na CA)
#>
[CmdletBinding()]
param(
  [switch]$Down,
  [switch]$SkipBuild,
  [switch]$NoBrowser,
  # CA (PEM) do proxy corporativo; também lida de $env:TRACE2LOCAL_CA_BUNDLE
  [string]$CaBundle = $env:TRACE2LOCAL_CA_BUNDLE
)

$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [Text.Encoding]::UTF8 } catch { }

$Demo    = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$Root    = (Resolve-Path (Join-Path $Demo '..\..')).Path
$Target  = Join-Path $Demo 'target'
$Dist    = Join-Path $Target 'dist'
$Log     = Join-Path $Target 'up.log'
$Compose = Join-Path $Demo 'docker-compose.yml'
$TfImage = 'hashicorp/terraform:1.13.3'
$TfVol   = 'finance-pix-terraform'
$Ui      = 'http://localhost:19877/trace2local'
$Api     = 'http://localhost:4568/restapis/pixapi/local/_user_request_'

New-Item -ItemType Directory -Force -Path $Target | Out-Null
Set-Content -Path $Log -Value ("finance-pix up.ps1 - " + (Get-Date -Format s)) -Encoding UTF8

function Say([string]$msg, [string]$color = 'Gray') {
  Write-Host $msg -ForegroundColor $color
  Add-Content -Path $Log -Value $msg -Encoding UTF8
}
function Step([string]$title) { Say ""; Say ">> $title" 'Cyan' }

# Executa um comando nativo, espelha a saída no console e no log, falha com mensagem acionável.
function Run([string]$what, [scriptblock]$cmd) {
  $prev = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  & $cmd 2>&1 | ForEach-Object {
    if ($_ -is [System.Management.Automation.ErrorRecord]) { $line = $_.Exception.Message } else { $line = "$_" }
    Write-Host $line
    Add-Content -Path $Log -Value $line -Encoding UTF8
  }
  $code = $LASTEXITCODE
  $ErrorActionPreference = $prev
  if ($code -ne 0) { throw "$what falhou (exit $code). Detalhes: $Log" }
}

# containers de Lambda que o LocalStack cria fora do compose (prendem a rede pix-demo se ficarem para trás)
function Remove-LambdaContainers {
  $prev = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
  $ids = @(docker ps -aq --filter 'name=finance-pix-localstack-1-lambda-' 2>$null)
  if ($ids.Count -gt 0) { docker rm -f $ids 2>&1 | Out-Null }
  $ErrorActionPreference = $prev
  return $ids.Count
}

function Test-PortBusy([int]$port) {
  $client = New-Object System.Net.Sockets.TcpClient
  try {
    $task = $client.ConnectAsync('127.0.0.1', $port)
    return ($task.Wait(400) -and $client.Connected)
  } catch { return $false } finally { $client.Dispose() }
}

function Test-Docker {
  $prev = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
  docker info --format '{{.ServerVersion}}' 2>&1 | Out-Null
  $ok = ($LASTEXITCODE -eq 0)
  $ErrorActionPreference = $prev
  return $ok
}

try {
  if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw "Docker não encontrado. Instale o Docker Desktop (https://www.docker.com/products/docker-desktop/) e rode de novo."
  }

  if ($Down) {
    Step "derrubando o finance-pix (containers, rede, volumes e estado do Terraform)"
    Run "docker compose down" { docker compose -f $Compose down -v --remove-orphans }
    $n = Remove-LambdaContainers
    if ($n -gt 0) { Say "removidos $n containers de Lambda criados pelo LocalStack" }
    $prev = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    docker network rm pix-demo 2>&1 | Out-Null
    docker volume rm $TfVol 2>&1 | Out-Null
    $ErrorActionPreference = $prev
    Say "Pronto: nada do finance-pix ficou rodando." 'Green'
    exit 0
  }

  Step "1/5 Docker"
  if (-not (Test-Docker)) {
    $desktop = Join-Path $env:ProgramFiles 'Docker\Docker\Docker Desktop.exe'
    if (-not (Test-Path $desktop)) { throw "O daemon do Docker não responde e o Docker Desktop não foi encontrado em $desktop." }
    Say "Docker Desktop parado - iniciando (até 3 min)..." 'Yellow'
    Start-Process $desktop | Out-Null
    $deadline = (Get-Date).AddMinutes(3)
    while (-not (Test-Docker)) {
      if ((Get-Date) -gt $deadline) { throw "O Docker Desktop não ficou pronto em 3 min. Abra-o manualmente e rode de novo." }
      Start-Sleep -Seconds 5
    }
  }
  Run "docker version" { docker version --format 'Docker {{.Server.Version}} ({{.Server.Os}}/{{.Server.Arch}})' }

  # portas do compose: livres, ou já ocupadas pelo próprio finance-pix (re-execução)
  $ours = @()
  $prev = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
  $ours = @(docker compose -f $Compose ps -q 2>$null)
  $ErrorActionPreference = $prev
  if ($ours.Count -eq 0) {
    $n = Remove-LambdaContainers   # sobras de uma execução anterior interrompida
    if ($n -gt 0) { Say "removidos $n containers de Lambda órfãos de uma execução anterior" }
    foreach ($port in 4568, 15432, 18080, 19877, 19878) {
      if (Test-PortBusy $port) {
        $who = ''
        if (Get-Command Get-NetTCPConnection -ErrorAction SilentlyContinue) {
          $c = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
          if ($c) { $who = " (processo: $((Get-Process -Id $c.OwningProcess -ErrorAction SilentlyContinue).ProcessName))" }
        }
        throw "A porta $port já está em uso$who. Libere-a ou pare o outro ambiente (ex.: outro compose do Trace2Local)."
      }
    }
  }

  $caArgs = @(); $tfCa = @()
  if ($CaBundle) {
    if (-not (Test-Path $CaBundle)) { throw "CaBundle não encontrado: $CaBundle" }
    $caDir = Join-Path $Target 'ca'
    New-Item -ItemType Directory -Force -Path $caDir | Out-Null
    Copy-Item -Force $CaBundle (Join-Path $caDir 'empresa.pem')
    $caArgs = @('--build-context', "ca=$caDir")
    $tfCa = @('-v', "${caDir}:/ca-extra:ro", '-e', 'SSL_CERT_DIR=/etc/ssl/certs:/ca-extra')
    Say "CA extra (proxy corporativo): $CaBundle"
  }

  $stationJar = Join-Path $Root 'trace2local-station\target\trace2local-station-0.1.0-SNAPSHOT.jar'
  $lambdaZip  = Join-Path $Dist 'lambda-jvm.zip'
  if ($SkipBuild -and (Test-Path $stationJar) -and (Test-Path $lambdaZip)) {
    Step "2/5 build - pulado (-SkipBuild, artefatos já existem)"
  } else {
    Step "2/5 build em container Linux: biblioteca + Station + Lambdas Java 25 (1a vez: ~5-10 min)"
    $out = Join-Path $Root 'target\finance-pix-build'
    if (Test-Path $out) { Remove-Item -Recurse -Force $out }
    $env:DOCKER_BUILDKIT = '1'
    Run "build" {
      docker build --progress=plain @caArgs -f (Join-Path $Demo 'Dockerfile.build') --target artifacts `
        --output "type=local,dest=$out" $Root
    }
    New-Item -ItemType Directory -Force -Path (Split-Path $stationJar), $Dist | Out-Null
    Copy-Item -Force (Join-Path $out 'station\trace2local-station-0.1.0-SNAPSHOT.jar') $stationJar
    Copy-Item -Force (Join-Path $out 'dist\lambda-jvm.zip') $lambdaZip
    Say ("Station: {0:N1} MB  |  Lambdas (JVM 25 + jlink): {1:N1} MB" -f ((Get-Item $stationJar).Length / 1MB), ((Get-Item $lambdaZip).Length / 1MB))
  }

  Step "3/5 docker compose: LocalStack 4.9, Postgres 16, parceiros (WireMock) e Trace2Local Station"
  Run "docker compose up" { docker compose -f $Compose up -d --build --wait }
  # imagem base do runtime provided.al2023: baixada antes, para a 1a invocação não estourar o timeout do gateway
  Run "docker pull (runtime da Lambda)" { docker pull -q public.ecr.aws/lambda/provided:al2023 }

  Step "4/5 Terraform -> LocalStack (API Gateway, 3 Lambdas, DynamoDB, SQS+DLQ, SNS)"
  $tf = @('run', '--rm', '--network', 'pix-demo',
          '-v', "${Demo}:/demo", '-v', "${TfVol}:/tfdata",
          '-e', 'TF_DATA_DIR=/tfdata/.terraform', '-e', 'TF_IN_AUTOMATION=1') + $tfCa + @(
          '-w', '/demo/infra/terraform', $TfImage)
  Run "terraform init" { docker @tf init '-input=false' '-no-color' }
  Run "terraform apply" {
    # argumentos entre aspas: o PowerShell quebra "-opcao=valor.ext" no ponto se ficarem soltos
    docker @tf apply '-auto-approve' '-input=false' '-no-color' '-state=/tfdata/terraform.tfstate' `
      '-var-file=localstack.tfvars' '-var' 'localstack_endpoint=http://localstack:4566' `
      '-var' 'lambda_package=/demo/target/dist/lambda-jvm.zip'
  }

  Step "5/5 chamada real: POST /pix/transfers (1a chamada inclui o cold start da Lambda)"
  $body = '{"payerAccountId":"acc-001","pixKey":"joao@pix.example","amount":150.00,"description":"primeiro Pix"}'
  $status = 0; $text = ''; $ok = $false
  for ($i = 1; $i -le 4 -and -not $ok; $i++) {
    $status = 0; $text = ''
    try {
      $r = Invoke-WebRequest -UseBasicParsing -Method Post -Uri "$Api/pix/transfers" -Body $body `
             -ContentType 'application/json' -Headers @{ 'Idempotency-Key' = "up-$([guid]::NewGuid().ToString('N').Substring(0,10))" } `
             -TimeoutSec 240
      $status = [int]$r.StatusCode; $text = $r.Content
    } catch {
      if ($_.Exception.Response) { $status = [int]$_.Exception.Response.StatusCode }
      if ($_.ErrorDetails -and $_.ErrorDetails.Message) { $text = $_.ErrorDetails.Message }
      elseif (-not $_.Exception.Response) { $text = $_.Exception.Message }
    }
    $text = ($text -replace '\s+', ' ').Trim()
    Say "tentativa ${i}: HTTP $status  $text"
    $ok = ($status -eq 202) -or ($status -eq 502 -and $text -match 'KYC')
    if (-not $ok -and $i -lt 4) { Start-Sleep -Seconds 15 }
  }
  if ($status -eq 202) {
    Say "Pix aceito (há um mock do KYC plugado no Mock Connect)." 'Green'
  } elseif ($ok) {
    Say "Esperado: o KYC não existe no ambiente local. Abra a visão Mocks (tecla 9) e plugue o mock sugerido." 'Green'
  } else {
    throw "A API não respondeu como esperado (HTTP $status). Veja $Log e 'docker compose -f $Compose logs localstack'."
  }

  Say ""
  Say "PRONTO" 'Green'
  Say "  UI do Trace2Local (Resonance) ... $Ui" 'Green'
  Say "  API Pix (API Gateway) ........... $Api/pix/transfers"
  Say "  Mock Connect .................... http://localhost:19878  (gestão: $Ui/api/mocks)"
  Say "  Postgres ........................ localhost:15432  (pix / pix-local-only)"
  Say "  Derrubar tudo ................... powershell -ExecutionPolicy Bypass -File examples\finance-pix\scripts\up.ps1 -Down"
  if (-not $NoBrowser) { Start-Process $Ui | Out-Null }
  exit 0
}
catch {
  Say ""
  Say "FALHOU: $($_.Exception.Message)" 'Red'
  exit 1
}
