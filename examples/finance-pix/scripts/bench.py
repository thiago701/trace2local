#!/usr/bin/env python3
"""Cold start × warm: JVM 25 (jlink) vs nativo GraalVM, mesma função pix-api, mesmo cenário.

Para cada modo: implanta (Terraform), derruba o container da função (força cold start),
mede a 1ª chamada e 15 chamadas quentes de POST /pix/transfers (fluxo completo com
DynamoDB, DICT, KYC mock, Postgres, antifraude, SQS) e GET de transferência inexistente.
Uso: python3 scripts/bench.py [--modes jvm,native] [--out target/validation/BENCH.md]
"""
import argparse
import json
import os
import secrets
import statistics
import subprocess
import time
import urllib.error
import urllib.request

API = os.environ.get("PIX_API", "http://localhost:4568/restapis/pixapi/local/_user_request_")


def call(method, path, body=None, headers=None):
    req = urllib.request.Request(API + path, data=json.dumps(body).encode() if body else None, method=method,
                                 headers={"Content-Type": "application/json", **(headers or {})})
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            r.read()
            code = r.status
    except urllib.error.HTTPError as e:
        e.read()
        code = e.code
    return code, (time.time() - t0) * 1000


def post():
    return call("POST", "/pix/transfers", {"payerAccountId": "acc-001", "pixKey": "joao@pix.example", "amount": 1.00},
                {"Idempotency-Key": "bench-" + secrets.token_hex(8)})


def kill_function_containers():
    names = subprocess.run(["docker", "ps", "--format", "{{.Names}}"], capture_output=True, text=True).stdout.split()
    for n in names:
        if "lambda-pix-" in n:
            subprocess.run(["docker", "rm", "-f", n], capture_output=True)
    time.sleep(3)


def pct(values, p):
    s = sorted(values)
    return s[min(len(s) - 1, int(round(p / 100 * (len(s) - 1))))]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--modes", default="jvm,native")
    ap.add_argument("--out", default="target/validation/BENCH.md")
    args = ap.parse_args()
    rows = []
    sizes = {}
    for mode in args.modes.split(","):
        subprocess.run(["./scripts/deploy.sh", mode], check=True, capture_output=True,
                       env={**os.environ, "TERRAFORM": os.environ.get("TERRAFORM", "terraform")})
        sizes[mode] = os.path.getsize(f"target/dist/lambda-{mode}.zip") / 1e6
        time.sleep(2)
        kill_function_containers()
        code, cold = post()
        warm = [post()[1] for _ in range(15)]
        gets = [call("GET", "/pix/transfers/pix-inexistente")[1] for _ in range(10)]
        rows.append((mode, code, cold, statistics.median(warm), pct(warm, 95), statistics.median(gets)))
        print(mode, f"cold={cold:.0f}ms warm p50={statistics.median(warm):.0f}ms p95={pct(warm, 95):.0f}ms get p50={statistics.median(gets):.0f}ms")
    lines = ["# Cold start × warm — JVM 25 (jlink) vs nativo GraalVM 25", "",
             "> Medido de ponta a ponta pelo cliente (API Gateway do LocalStack → Lambda → DynamoDB, DICT, KYC (mock),",
             "> Postgres, antifraude, SQS). Ambiente: 2 vCPU, Docker; números relativos, não absolutos de AWS.", "",
             "| modo | pacote (MB) | status | 1ª chamada — cold (ms) | POST quente p50 (ms) | POST quente p95 (ms) | GET quente p50 (ms) |",
             "|---|---|---|---|---|---|---|"]
    for mode, code, cold, p50, p95, g50 in rows:
        lines.append(f"| {mode} | {sizes[mode]:.0f} | {code} | {cold:.0f} | {p50:.0f} | {p95:.0f} | {g50:.0f} |")
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    print("→", args.out)


if __name__ == "__main__":
    main()
