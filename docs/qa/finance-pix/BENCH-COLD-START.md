# Cold start × warm — JVM 25 (jlink) vs nativo GraalVM 25

> Medido de ponta a ponta pelo cliente (API Gateway do LocalStack → Lambda → DynamoDB, DICT, KYC (mock),
> Postgres, antifraude, SQS). Ambiente: 2 vCPU, Docker; números relativos, não absolutos de AWS.

| modo | pacote (MB) | status | 1ª chamada — cold (ms) | POST quente p50 (ms) | POST quente p95 (ms) | GET quente p50 (ms) |
|---|---|---|---|---|---|---|
| jvm | 68 | 202 | 2191 | 228 | 288 | 78 |
| native | 26 | 202 | 808 | 180 | 211 | 74 |
