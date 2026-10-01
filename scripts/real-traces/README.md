# Captura de traces reais (dataset de regressão)

`capture.py` exporta execuções de um Station/app vivo (contrato REST + logs) para
`trace2local-predictive/src/test/resources/real-traces/`, onde o
`RealTraceRegressionTest` as reproduz no pipeline preditivo e no laudo.

```bash
python3 capture.py --base http://127.0.0.1:19877/trace2local --out ../../trace2local-predictive/src/test/resources/real-traces/lambda-sqs/run-1-fresh
python3 capture.py --out …/run-2-reprocess --exclude …/run-1-fresh
```

Payloads e logs já chegam redigidos na origem; ainda assim, **não capture dados
reais de clientes** — use cenários de demonstração (AGENTS.md, dever 4).
