#!/usr/bin/env python3
"""Jornadas de validação do Trace2Local na stack alvo (finance-pix).

Cada jornada dispara uma transferência Pix REAL pelo API Gateway (LocalStack) e confere a
consistência em níveis:

  L1 contrato   — status/corpo HTTP conforme openapi/pix-api.yaml
  L2 árvore     — passos presentes/ausentes, estados, marcação de mock, rótulos
  L3 dados      — o que a árvore diz (Δ) bate com o Postgres e o DynamoDB de verdade
  L4 assíncrono — SQS → liquidação → SNS → notificação na MESMA árvore; reentrega visível
  L5 mocks      — Mock Connect: sugestão, plug, variação sob demanda, falha transitória
  L6 ferramenta — logs correlacionados, laudo/insights, topologia, infra (IaC)

Só biblioteca padrão. Uso: python3 scripts/journeys.py [--report docs/qa/FINANCE-PIX-VALIDACAO.md]
"""
import argparse
import datetime as dt
import json
import os
import re
import secrets
import subprocess
import sys
import time
import urllib.error
import urllib.request

API = os.environ.get("PIX_API", "http://localhost:4568/restapis/pixapi/local/_user_request_")
STATION = os.environ.get("T2L_STATION", "http://localhost:19877/trace2local")
LOCALSTACK = os.environ.get("LOCALSTACK", "http://localhost:4568")
PG = os.environ.get("PG_CONTAINER", "finance-pix-postgres-1")

RESULTS = []
TIMINGS = []


# ------------------------------------------------------------------ HTTP

def http(method, url, body=None, headers=None, timeout=60):
    data = json.dumps(body).encode() if isinstance(body, (dict, list)) else (body.encode() if body else None)
    req = urllib.request.Request(url, data=data, method=method, headers=headers or {})
    if data is not None and "Content-Type" not in (headers or {}):
        req.add_header("Content-Type", "application/json")
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read().decode()
            return r.status, (json.loads(raw) if raw.strip().startswith(("{", "[")) else raw), time.time() - t0
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        return e.code, (json.loads(raw) if raw.strip().startswith(("{", "[")) else raw), time.time() - t0


def station(method, path, body=None):
    h = {"X-Trace2Local": "1"} if method != "GET" else {}
    return http(method, STATION + path, body, h)


def transfer(key, payload, baggage=None, label=""):
    trace = secrets.token_hex(16)
    headers = {"Idempotency-Key": key, "traceparent": f"00-{trace}-{secrets.token_hex(8)}-01"}
    if baggage:
        headers["baggage"] = baggage
    status, body, took = http("POST", API + "/pix/transfers", payload, headers)
    TIMINGS.append((label, took))
    return status, body, trace


# ------------------------------------------------------------------ verificação

def check(journey, level, name, ok, detail=""):
    RESULTS.append({"journey": journey, "level": level, "check": name, "ok": bool(ok), "detail": str(detail)[:240]})
    print(f"  [{'OK' if ok else 'FALHA'}] {level} {name}" + (f" — {detail}" if not ok and detail else ""))
    return ok


def execution_for(trace, need=(), timeout=45):
    """Espera a execução do trace (e, se pedido, até a árvore conter os rótulos `need`)."""
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        _, summaries, _ = station("GET", "/api/executions?limit=100")
        for s in summaries if isinstance(summaries, list) else []:
            if s.get("traceId") == trace:
                _, e, _ = station("GET", "/api/executions/" + s["executionId"])
                last = e
                labels = [n["label"] for n in nodes(e)]
                if all(any(re.search(p, l) for l in labels) for p in need):
                    return e
        time.sleep(1.5)
    return last


def nodes(execution):
    out = []

    def walk(n, path):
        out.append({**n, "_path": path + [n["label"]]})
        for c in n.get("children") or []:
            walk(c, path + [n["label"]])
    for r in (execution or {}).get("roots", []):
        walk(r, [])
    return out


def find(execution, pattern, kind=None):
    return [n for n in nodes(execution) if re.search(pattern, n["label"]) and (kind is None or n["kind"] == kind)]


def sql(query):
    r = subprocess.run(["docker", "exec", PG, "psql", "-U", "pix", "-d", "pix", "-tAc", query],
                       capture_output=True, text=True, timeout=30)
    return r.stdout.strip()


def account(acc):
    row = sql(f"SELECT balance, held FROM accounts WHERE id = '{acc}'")
    b, h = row.split("|")
    return float(b), float(h)


def dynamo_get(table, key_name, key_value):
    body = {"TableName": table, "Key": {key_name: {"S": key_value}}, "ConsistentRead": True}
    headers = {"X-Amz-Target": "DynamoDB_20120810.GetItem", "Content-Type": "application/x-amz-json-1.0",
               "Authorization": "AWS4-HMAC-SHA256 Credential=test/20260101/us-east-1/dynamodb/aws4_request, "
                                "SignedHeaders=host, Signature=0"}
    _, item, _ = http("POST", LOCALSTACK + "/", json.dumps(body), headers)
    return {k: list(v.values())[0] for k, v in (item.get("Item") or {}).items()} if isinstance(item, dict) else {}


def suggestions(kind=None, api=None, host=None):
    _, s, _ = station("GET", "/api/mocks/suggestions")
    return [x for x in (s if isinstance(s, list) else [])
            if (kind is None or x["kind"] == kind) and (api is None or x["api"] == api)
            and (host is None or x["target"].split(":")[0] == host)]


def reset_fixture():
    """Dados de teste reprodutíveis entre execuções do roteiro (saldos e ledger do seed)."""
    sql("TRUNCATE ledger_entries; UPDATE accounts SET held = 0, balance = CASE id WHEN 'acc-001' THEN 25000 "
        "WHEN 'acc-002' THEN 50 ELSE 5000 END, updated_at = now()")


def reset_station():
    """Roteiro reprodutível: acervo vazio e nenhum mock plugado (o estado do Mock Connect
    persiste entre reinícios do Station e execuções antigas mudariam o que o conselheiro vê)."""
    _, overview, _ = station("GET", "/api/mocks")
    for b in (overview or {}).get("bindings", []):
        delete_binding(b["name"])
    station("DELETE", "/api/executions")
    time.sleep(1)


def delete_binding(name):
    station("DELETE", "/api/mocks/bindings/" + name)


def mock_marker(node):
    return (node.get("attributes") or {}).get("t2l.mock", "")


# ------------------------------------------------------------------ jornadas

def j1_kyc_unavailable():
    j = "J1 KYC indisponível"
    print(j)
    delete_binding("mock-kyc-limites")
    time.sleep(2.5)  # TTL das rotas no cliente
    before = account("acc-001")
    status, body, trace = transfer("j1-" + secrets.token_hex(6), {"payerAccountId": "acc-001", "pixKey": "joao@pix.example", "amount": 150.00}, label="J1 (cold)")
    check(j, "L1", "502 PARTNER_UNAVAILABLE com o parceiro identificado", status == 502 and body.get("partner") == "KYC & Limites", f"{status} {body}")
    e = execution_for(trace)
    check(j, "L2", "execução registrada (raiz = gatilho do API Gateway)", e and e["roots"][0]["label"] == "pix-api · POST /pix/transfers", e and e["roots"][0]["label"])
    kyc = find(e, r"KYC", "HTTP_CLIENT")
    check(j, "L2", "nó KYC vermelho com a exceção de rede", kyc and kyc[0]["status"] == "ERROR" and "Connect" in str(kyc[0].get("error")), kyc and kyc[0].get("error"))
    check(j, "L2", "raiz não é falsamente ÓRFÃ (chamador não instrumentado = API Gateway)", e["roots"][0]["status"] != "ORPHANED", e["roots"][0]["status"])
    check(j, "L2", "sem reserva de saldo nem antifraude após a falha", not find(e, r"Reservar saldo|Avaliar risco"))
    idem = [n for n in find(e, r"pix-idempotency", "DYNAMODB")]
    check(j, "L3", "Δ do DynamoDB: CREATE e depois DELETE da chave (libera nova tentativa)",
          [n["mutation"]["kind"] for n in idem if n.get("mutation")] == ["CREATE", "DELETE"], [n.get("mutation", {}).get("kind") for n in idem])
    check(j, "L3", "saldo/retido inalterados no Postgres", account("acc-001") == before, f"{before} → {account('acc-001')}")
    s = suggestions("UNAVAILABLE_DEPENDENCY", "KYC & Limites")
    check(j, "L5", "conselheiro sugere plugar mock (HIGH) a partir do contrato kyc.yaml",
          s and s[0]["severity"] == "HIGH" and s[0]["bindingConfig"].get("source.spec") == "contracts/kyc.yaml", s and s[0]["bindingConfig"])
    return s[0] if s else None


def j2_plug_mock(suggestion):
    j = "J2 Plugar mock do KYC"
    print(j)
    status, info, _ = station("POST", f"/api/mocks/suggestions/{suggestion['id']}/apply", {"mode": "exclusive"})
    check(j, "L5", "binding criado e RUNNING", status == 200 and info["status"]["state"] == "RUNNING", info)
    _, routes, _ = http("GET", STATION.replace("/trace2local", "") + "/t2lingest/v1/mock-routes", headers={"Authorization": "Bearer devtoken"})
    check(j, "L5", "rota publicada para kyc.bureau.local:8080", any(r["target"] == "kyc.bureau.local:8080" for r in routes), routes)
    time.sleep(2.5)


def j3_approved():
    j = "J3 Pix aprovado e liquidado"
    print(j)
    before = account("acc-001")
    key = "j3-" + secrets.token_hex(6)
    status, body, trace = transfer(key, {"payerAccountId": "acc-001", "pixKey": "joao@pix.example", "amount": 150.00, "description": "almoço"}, label="J3")
    check(j, "L1", "202 ACCEPTED com transferId e endToEndId de 32 posições",
          status == 202 and body.get("status") == "ACCEPTED" and len(body.get("endToEndId", "")) == 32, f"{status} {body}")
    e = execution_for(trace, need=(r"pix-settlement", r"pix-notifier · SNS pix-events PIX_SETTLED", r"Registrar notificação"), timeout=90)
    tid = body.get("transferId")
    check(j, "L2", "KYC atendido pelo mock e marcado como SIMULADO", any("mock-kyc-limites" in mock_marker(n) for n in find(e, r"KYC", "HTTP_CLIENT")))
    order = [n["label"] for n in nodes(e) if n["kind"] == "BUSINESS"][:7]
    check(j, "L2", "ordem de negócio: idempotência → DICT → KYC → reserva → risco → aceite",
          order[:6] == ["Validar pedido", "Garantir idempotência", "Resolver chave Pix (DICT)", "Verificar KYC e limites", "Reservar saldo", "Avaliar risco (antifraude)"], order)
    sqs = find(e, r"^SQS: pix-settlement", "SQS")
    settle = find(e, r"^pix-settlement · SQS", "LAMBDA")
    check(j, "L4", "liquidação continua a MESMA árvore, como filha do nó SQS",
          sqs and settle and settle[0]["_path"][-2].startswith("SQS: pix-settlement"), settle and settle[0]["_path"])
    notif = find(e, r"^pix-notifier · SNS", "LAMBDA")
    check(j, "L4", "notificação continua a árvore como filha do nó SNS (consumidor do tópico)",
          notif and notif[0]["_path"][-2].startswith("SNS: pix-events"), notif and notif[0]["_path"])
    check(j, "L2", "nenhum nó ÓRFÃO e execução COMPLETED", e["status"] == "COMPLETED" and not [n for n in nodes(e) if n["status"] == "ORPHANED"],
          f"{e['status']} órfãos={[n['label'] for n in nodes(e) if n['status'] == 'ORPHANED']}")
    sql_labels = [n["label"] for n in find(e, r"^SQL", "SQL")]
    check(j, "L2", "SQL rotulado com operação + tabela", "SQL: SELECT accounts" in sql_labels and "SQL: INSERT ledger_entries" in sql_labels, sql_labels)
    item = dynamo_get("pix-transfers", "transferId", tid)
    check(j, "L3", "DynamoDB: transferência SETTLED (estado final real)", item.get("status") == "SETTLED", item)
    upd = [n for n in find(e, r"pix-transfers", "DYNAMODB") if (n.get("mutation") or {}).get("kind") == "UPDATE"]
    after_status = None
    if upd:
        fields = upd[0]["mutation"].get("after") or {}
        after_status = fields.get("status") if isinstance(fields, dict) else None
    check(j, "L3", "Δ UPDATE da árvore = estado no banco (status SETTLED)", after_status == "SETTLED" or (upd and "SETTLED" in json.dumps(upd[0]["mutation"])), upd and upd[0]["mutation"])
    ledger = sql(f"SELECT entry_type, status, amount FROM ledger_entries WHERE transfer_id = '{tid}'")
    check(j, "L3", "ledger: HOLD liquidado (SETTLED) de 150,00", ledger == "HOLD|SETTLED|150.00", ledger)
    after = account("acc-001")
    check(j, "L3", "saldo debitado exatamente uma vez; retido volta ao anterior",
          abs((before[0] - after[0]) - 150.0) < 0.001 and abs(after[1] - before[1]) < 0.001, f"{before} → {after}")
    n = dynamo_get("pix-notifications", "notificationKey", tid + "#PIX_SETTLED")
    check(j, "L3", "notificação registrada como SENT", n.get("status") == "SENT", n)
    _, logs, _ = station("GET", f"/api/executions/{e['executionId']}/logs")
    lines = logs.get("lines") if isinstance(logs, dict) else logs
    check(j, "L6", "logs CloudWatch correlacionados às 3 funções",
          isinstance(lines, list) and {"pix-api", "pix-settlement", "pix-notifier"} <= {str(l.get("function", l.get("logGroup", ""))).split("/")[-1] for l in lines},
          f"{len(lines) if isinstance(lines, list) else lines}")
    return key, tid, e


def j4_replay(key, tid):
    j = "J4 Reenvio idempotente"
    print(j)
    before = account("acc-001")
    status, body, trace = transfer(key, {"payerAccountId": "acc-001", "pixKey": "joao@pix.example", "amount": 150.00, "description": "almoço"}, label="J4")
    check(j, "L1", "mesma resposta (mesmo transferId)", status == 202 and body.get("transferId") == tid, f"{status} {body}")
    e = execution_for(trace)
    check(j, "L2", "nenhuma reserva, antifraude ou envio para liquidação no reenvio",
          not find(e, r"Reservar saldo|Avaliar risco|SQS: pix-settlement"), [n["label"] for n in nodes(e)])
    check(j, "L2", "a guarda condicional aparece (Conditional check) e a leitura da resposta gravada",
          len(find(e, r"pix-idempotency", "DYNAMODB")) >= 2, [n["label"] for n in find(e, r"pix-idempotency")])
    check(j, "L3", "saldo intacto", account("acc-001") == before)


def j5_conflict(key):
    j = "J5 Conflito de idempotência"
    print(j)
    status, body, _ = transfer(key, {"payerAccountId": "acc-001", "pixKey": "joao@pix.example", "amount": 151.00}, label="J5")
    check(j, "L1", "409 IDEMPOTENCY_CONFLICT", status == 409 and body.get("code") == "IDEMPOTENCY_CONFLICT", f"{status} {body}")


def j6_review():
    j = "J6 Valor atípico → revisão"
    print(j)
    before = account("acc-001")
    status, body, trace = transfer("j6-" + secrets.token_hex(6), {"payerAccountId": "acc-001", "pixKey": "joao@pix.example", "amount": 7000.00}, label="J6")
    check(j, "L1", "202 IN_REVIEW", status == 202 and body.get("status") == "IN_REVIEW", f"{status} {body}")
    e = execution_for(trace, need=(r"pix-notifier · SNS pix-events PIX_REVIEW_REQUIRED",), timeout=60)
    check(j, "L2", "sem liquidação (nada na fila) e evento de revisão no tópico",
          not find(e, r"SQS: pix-settlement") and find(e, r"Enviar para revisão"), [n["label"] for n in find(e, r"Enviar|SQS|SNS")])
    after = account("acc-001")
    check(j, "L3", "saldo retido aumenta 7.000 (reserva mantida até a análise)", abs((after[1] - before[1]) - 7000.0) < 0.001, f"{before} → {after}")
    check(j, "L3", "DynamoDB: IN_REVIEW", dynamo_get("pix-transfers", "transferId", body.get("transferId", "")).get("status") == "IN_REVIEW")


def j7_denied():
    j = "J7 Conta sinalizada → recusa"
    print(j)
    before = account("acc-bloqueada")
    status, body, trace = transfer("j7-" + secrets.token_hex(6), {"payerAccountId": "acc-bloqueada", "pixKey": "joao@pix.example", "amount": 90.00}, label="J7")
    check(j, "L1", "422 REJECTED_BY_FRAUD", status == 422 and body.get("code") == "REJECTED_BY_FRAUD", f"{status} {body}")
    e = execution_for(trace, need=(r"pix-notifier · SNS pix-events PIX_REJECTED",), timeout=60)
    check(j, "L2", "recusa libera o saldo (passo de compensação presente)", find(e, r"Liberar saldo reservado"), [n["label"] for n in find(e, r"Recusar|Liberar")])
    check(j, "L3", "saldo e retido intactos no Postgres", account("acc-bloqueada") == before, f"{before} → {account('acc-bloqueada')}")
    check(j, "L2", "recusa de negócio não é ERRO na raiz (4xx ≠ 5xx)", e["roots"][0]["status"] == "OK", e["roots"][0]["status"])


def j8_insufficient():
    j = "J8 Saldo insuficiente"
    print(j)
    status, body, trace = transfer("j8-" + secrets.token_hex(6), {"payerAccountId": "acc-002", "pixKey": "joao@pix.example", "amount": 100.00}, label="J8")
    check(j, "L1", "422 INSUFFICIENT_FUNDS", status == 422 and body.get("code") == "INSUFFICIENT_FUNDS", f"{status} {body}")
    e = execution_for(trace)
    check(j, "L2", "antifraude nunca consultado", not find(e, r"Antifraude"), [n["label"] for n in nodes(e)])
    check(j, "L3", "nenhum lançamento para acc-002", sql("SELECT count(*) FROM ledger_entries WHERE account_id = 'acc-002'") == "0")


def j9_invalid_key():
    j = "J9 Chave inexistente"
    print(j)
    status, body, trace = transfer("j9-" + secrets.token_hex(6), {"payerAccountId": "acc-001", "pixKey": "naoexiste@pix.example", "amount": 10.00}, label="J9")
    check(j, "L1", "422 INVALID_KEY", status == 422 and body.get("code") == "INVALID_KEY", f"{status} {body}")
    e = execution_for(trace)
    dict_node = find(e, r"DICT", "HTTP_CLIENT")
    check(j, "L2", "DICT 404 visível; KYC e reserva não acontecem",
          dict_node and (dict_node[0].get("attributes") or {}).get("http.response.status_code") == "404" and not find(e, r"KYC|Reservar"),
          dict_node and dict_node[0].get("attributes"))


def j10_get(tid):
    j = "J10 Consulta"
    print(j)
    status, body, _ = http("GET", f"{API}/pix/transfers/{tid}")
    check(j, "L1", "200 SETTLED com valor 150", status == 200 and body.get("status") == "SETTLED" and float(body.get("amount", 0)) == 150.0, f"{status} {body}")
    status, body, _ = http("GET", f"{API}/pix/transfers/pix-naoexiste")
    check(j, "L1", "404 NOT_FOUND", status == 404 and body.get("code") == "NOT_FOUND", f"{status} {body}")


def j11_variation_on_demand():
    j = "J11 Variação sob demanda (antifraude)"
    print(j)
    s = suggestions("RESPONSE_DRIVES_FLOW", "Antifraude", host="antifraude.partner.local")
    check(j, "L5", "conselheiro detecta que /decision do antifraude decide o fluxo", s and "/decision" in s[0]["title"], [x["title"] for x in suggestions()])
    if not s:
        return
    ids = {v["title"]: v["id"] for v in s[0]["variations"]}
    absent = ids.get("/decision ausente")
    status, info, _ = station("POST", f"/api/mocks/suggestions/{s[0]['id']}/apply", {"mode": "on-demand", "variations": [absent]})
    check(j, "L5", "variação aplicada em modo sob demanda (repasse + baggage)", status == 200 and info["status"]["state"] == "RUNNING"
          and info["config"].get("source") == "proxy", info.get("config") if isinstance(info, dict) else info)
    time.sleep(2.5)
    st, body, trace = transfer("j11a-" + secrets.token_hex(6), {"payerAccountId": "acc-001", "pixKey": "joao@pix.example", "amount": 20.00},
                              baggage=f"t2l.mock={absent}", label="J11 variação")
    check(j, "L1", "decisão AUSENTE → regra de negócio manda para revisão (nunca aprova o desconhecido)",
          st == 202 and body.get("status") == "IN_REVIEW", f"{st} {body}")
    e = execution_for(trace)
    af = find(e, r"Antifraude", "HTTP_CLIENT")
    check(j, "L2", "nó do antifraude marcado com a variação aplicada", af and f"variation=v-{absent}" in mock_marker(af[0]), af and mock_marker(af[0]))
    st, body, trace = transfer("j11b-" + secrets.token_hex(6), {"payerAccountId": "acc-001", "pixKey": "joao@pix.example", "amount": 20.00}, label="J11 real")
    check(j, "L1", "sem baggage, a API real responde (APPROVED → ACCEPTED)", st == 202 and body.get("status") == "ACCEPTED", f"{st} {body}")
    e = execution_for(trace)
    af = find(e, r"Antifraude", "HTTP_CLIENT")
    check(j, "L2", "repasse sem variação NÃO é marcado como resposta simulada", af and "variation=" not in mock_marker(af[0]), af and mock_marker(af[0]))
    delete_binding(info["name"])
    time.sleep(2.5)


def j12_transient_settlement_failure():
    j = "J12 SPI com falha transitória"
    print(j)
    s = suggestions("HAPPY_PATH_ONLY", "SPI (BACEN)", host="spi.bacen.local")
    check(j, "L5", "conselheiro aponta que só o caminho feliz do SPI foi exercitado", bool(s), [x["title"] for x in suggestions()])
    if not s:
        return
    status, info, _ = station("POST", f"/api/mocks/suggestions/{s[0]['id']}/apply", {"mode": "exclusive", "variations": ["falha-transitoria"]})
    check(j, "L5", "variação '503 só na 1ª chamada' aplicada", status == 200 and info["status"]["state"] == "RUNNING", info)
    time.sleep(2.5)
    before = account("acc-001")
    st, body, trace = transfer("j12-" + secrets.token_hex(6), {"payerAccountId": "acc-001", "pixKey": "joao@pix.example", "amount": 33.00}, label="J12")
    check(j, "L1", "202 ACCEPTED", st == 202, f"{st} {body}")
    e = execution_for(trace, need=(r"pix-settlement · SQS pix-settlement \(tentativa 2\)", r"PIX_SETTLED"), timeout=120)
    attempts = find(e, r"^pix-settlement · SQS", "LAMBDA")
    check(j, "L4", "duas tentativas de liquidação na MESMA árvore: 1ª vermelha, 2ª verde",
          len(attempts) >= 2 and attempts[0]["status"] == "ERROR" and attempts[-1]["status"] == "OK", [(a["label"], a["status"]) for a in attempts])
    tid = body.get("transferId", "")
    check(j, "L3", "liquidação concluída e débito ÚNICO apesar da reentrega",
          dynamo_get("pix-transfers", "transferId", tid).get("status") == "SETTLED" and abs((before[0] - account("acc-001")[0]) - 33.0) < 0.001,
          f"{before} → {account('acc-001')}")
    delete_binding(info["name"])
    time.sleep(2.5)


def l6_tool_views(e):
    j = "L6 Visões da ferramenta"
    print(j)
    eid = e["executionId"]
    st, story, _ = station("GET", f"/api/executions/{eid}/story")
    check(j, "L6", "narrativa de negócio gerada", st == 200 and story, st)
    st, assist, _ = station("GET", f"/api/executions/{eid}/insights")
    check(j, "L6", "laudo/insights gerados para a execução", st == 200 and isinstance(assist, dict), st)
    ex = assist.get("executive", {}) if isinstance(assist, dict) else {}
    tally = ex.get("rulesTally", {})
    check(j, "L6", "homologação de um Pix perfeito: apta, nenhuma regra violada (siglas e caminho de falha não viram violação)",
          ex.get("readiness", {}).get("label") == "apta" and tally.get("violada", 0) == 0, f"{ex.get('headline')} · {tally}")
    st, top, _ = station("GET", "/api/insights?limit=50")
    ids = [i["id"] for i in (top.get("insights", []) if isinstance(top, dict) else [])]
    check(j, "L6", "sem falso positivo das regras preditivas na stack alvo (IDEM-002 por SQL parametrizado, PII já mascarado)",
          "IDEM-002" not in ids and "SEC-PII-001" not in ids, ", ".join(sorted(set(ids))))
    st, topo, _ = station("GET", "/api/topology")
    names = json.dumps(topo)
    check(j, "L6", "topologia mostra os 5 parceiros externos", all(p in names for p in ["dict.bacen.local", "kyc.bureau.local", "antifraude.partner.local", "spi.bacen.local", "notify.partner.local"]), names[:300])
    comps = topo.get("components", []) if isinstance(topo, dict) else []
    declared = sorted(c["id"] for c in comps if c.get("zone") == "declared")
    # tudo o que o Terraform declara foi exercitado pelas jornadas, menos a DLQ: só ela pode
    # aparecer como "declarada e nunca observada" (antes: rótulos locais transfers/fn duplicavam)
    check(j, "L6", "anatomia: só a DLQ fica como declarada (nome real do recurso no IaC, sem duplicatas)",
          declared == ["sqs:pix-settlement-dlq"], "declarados: " + ", ".join(declared))
    st, infra, _ = station("GET", "/api/infra")
    check(j, "L6", "IaC (Terraform) indexado: tabelas, fila, tópico", all(x in json.dumps(infra) for x in ["pix-transfers", "pix-settlement", "pix-events"]), st)


# ------------------------------------------------------------------ relatório

def report(path):
    ok = sum(r["ok"] for r in RESULTS)
    lines = [
        "# Validação do Trace2Local na stack alvo — finance-pix",
        "",
        f"> Gerado por `examples/finance-pix/scripts/journeys.py` em {dt.datetime.now(dt.timezone.utc).strftime('%Y-%m-%d %H:%M UTC')}.",
        "> Stack: API Gateway (OpenAPI) → Lambda Java 25 (runtime provided.al2023) → DynamoDB · Postgres 16 · SQS · SNS · 5 APIs externas.",
        "",
        f"**Resultado: {ok}/{len(RESULTS)} verificações OK.**",
        "",
        "| nível | OK | total |",
        "|---|---|---|",
    ]
    for level in ["L1", "L2", "L3", "L4", "L5", "L6"]:
        rs = [r for r in RESULTS if r["level"] == level]
        if rs:
            lines.append(f"| {level} | {sum(r['ok'] for r in rs)} | {len(rs)} |")
    lines += ["", "## Verificações", "", "| jornada | nível | verificação | resultado |", "|---|---|---|---|"]
    for r in RESULTS:
        lines.append(f"| {r['journey']} | {r['level']} | {r['check']} | {'✅' if r['ok'] else '❌ ' + r['detail'].replace('|', '/')} |")
    lines += ["", "## Latência observada (cliente → API Gateway → Lambda)", "", "| requisição | s |", "|---|---|"]
    lines += [f"| {k} | {v:.3f} |" for k, v in TIMINGS]
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    with open(os.path.splitext(path)[0] + ".json", "w", encoding="utf-8") as f:
        json.dump({"results": RESULTS, "timings": TIMINGS}, f, ensure_ascii=False, indent=1)
    print(f"\n{ok}/{len(RESULTS)} OK — relatório em {path}")
    return ok == len(RESULTS)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--report", default="target/validation/FINANCE-PIX-VALIDACAO.md")
    args = ap.parse_args()
    reset_fixture()
    reset_station()
    s = j1_kyc_unavailable()
    if s:
        j2_plug_mock(s)
    key, tid, e3 = j3_approved()
    j4_replay(key, tid)
    j5_conflict(key)
    j6_review()
    j7_denied()
    j8_insufficient()
    j9_invalid_key()
    j10_get(tid)
    time.sleep(6)  # quiescência: o conselheiro analisa as execuções concluídas
    j11_variation_on_demand()
    j12_transient_settlement_failure()
    l6_tool_views(e3)
    sys.exit(0 if report(args.report) else 1)


if __name__ == "__main__":
    main()
