#!/usr/bin/env python3
"""Captura TRACES REAIS de um Station/app Trace2Local vivo para o dataset de regressão
das Regras Preditivas (trace2local-predictive/src/test/resources/real-traces/).

Cada arquivo = {"execution": <contrato REST>, "logs": [<LogEntry do fio>]}.
Só stdlib; nenhum segredo sai daqui (payloads/logs já chegam redigidos na origem).

  python3 capture.py --base http://127.0.0.1:19877/trace2local --out DIR [--exclude DIR_ANTERIOR]
"""
import argparse, json, os, sys, urllib.request

def get(base, path):
    with urllib.request.urlopen(base + "/api" + path, timeout=15) as r:
        return json.loads(r.read().decode("utf-8"))

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://127.0.0.1:19877/trace2local")
    ap.add_argument("--out", required=True)
    ap.add_argument("--exclude", action="append", default=[], help="diretório de captura anterior (ignora as mesmas execuções)")
    a = ap.parse_args()
    seen = set()
    for d in a.exclude:
        for f in os.listdir(d):
            if f.endswith(".json"):
                seen.add(f[:-5])
    os.makedirs(a.out, exist_ok=True)
    n = 0
    for s in get(a.base, "/executions?limit=100"):
        eid = s["executionId"]
        if eid in seen or s.get("status") == "RUNNING":
            continue
        ex = get(a.base, "/executions/" + eid)
        logs = get(a.base, "/executions/" + eid + "/logs")
        lines = []
        for l in sorted(logs.get("lines", []), key=lambda x: x["index"]):
            lines.append({k: l.get(k) for k in ("timestamp", "level", "logger", "message", "spanId", "requestId", "logGroup", "logStream", "source")}
                         | {"traceId": ex.get("traceId") if l.get("spanId") else None})
        with open(os.path.join(a.out, eid + ".json"), "w", encoding="utf-8") as fh:
            json.dump({"execution": ex, "logs": lines}, fh, ensure_ascii=False, indent=1)
        n += 1
        print("capturada", eid, s.get("status"), s.get("rootLabel"), s.get("nodeCount"), "nós", len(lines), "linhas")
    print(n, "execução(ões) em", a.out)

if __name__ == "__main__":
    sys.exit(main())
