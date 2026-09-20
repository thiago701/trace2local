# ADR-007 — Loopback-only, sem autenticação, redaction na origem

- **Status:** Aceita (2026-09-18) — **decisão de produto, não apenas técnica**

## Contexto

O TraceVanta expõe, num navegador, o corpo das requisições, o conteúdo de itens de banco e o resultado de chamadas de infraestrutura da aplicação do desenvolvedor. Em outras palavras: **uma UI que mostra tudo o que o serviço faz com os dados**. Esse poder é o produto — e é também toda a sua superfície de risco.

Somam-se dois agravantes específicos: (a) a UI **dispara requisições** por ordem do navegador, o que é a definição de um vetor de SSRF; (b) a biblioteca vive dentro da aplicação, e a falha mais provável não é um ataque — é ela subir junto com o artefato de produção.

## Decisão

Quatro travas, em camadas:

**1. Local-first absoluto.** Bind em `127.0.0.1`. Nenhum dado sai da máquina: sem telemetria de uso, sem *phone home*, sem CDN, sem fonte remota. Expor fora do loopback exige `tracevanta.allow-non-loopback=true` **e** emite `WARN` a cada boot.

**2. Sem autenticação, por design.** Em loopback, uma senha protegeria contra nada que já não esteja comprometido: quem tem a máquina, tem a UI. Autenticação aqui seria teatro de segurança, com o efeito colateral de sugerir uma proteção que não existe. **A consequência é declarada no README, não escondida.**

**3. Alvo do disparo vem do catálogo, nunca do cliente.** O Request Launcher resolve o destino a partir do `endpointId` interno; não aceita URL arbitrária; não segue redirects; aplica allowlist de host/porta e revalida a resolução DNS (defesa contra rebinding). Sem isso, o TraceVanta seria um SSRF com interface bonita.

**4. Redaction na origem, ligada por padrão.** Aplicada **antes** de o dado entrar no buffer — nunca na UI —, por chave (`password`, `token`, `authorization`, `cpf`, `cnpj`, `card`, `cvv`, `accessKey`…), por padrão de valor (Luhn, JWT `eyJ…`, `AKIA…`, PEM, e-mail) e por tamanho. Substituto literal `[TRACEVANTA_REDACTED]`, com corpus de teste dedicado; falso-negativo em campo óbvio é bug bloqueante.

**5. Bloqueio em produção em três camadas.** Escopo `provided` recomendado; autodesabilitação fora de perfil de desenvolvimento; falha de boot explícita se alguém forçar `enabled=true` fora de dev sem a flag de escape consciente.

## Alternativas descartadas

| Alternativa | Por que não |
| :--- | :--- |
| **Autenticação por token no boot** | Fricção real, proteção ilusória em loopback. Se um dia a UI for exposta a outra máquina, isso volta à mesa — e aí com TLS junto |
| **Redaction opcional (padrão off)** | O padrão é o que 95% usa. Padrão inseguro é decisão de expor dado, não de dar liberdade |
| **Redaction só na UI** | Dado sensível já teria atravessado buffer, memória e qualquer exporter encadeado |
| **Permitir URL livre no launcher** | SSRF direto, num processo que costuma ter credenciais de nuvem no ambiente |
| **Confiar no dev para não empacotar em produção** | Já sabemos como isso termina |

## Consequências

**Boas.** A promessa "nada sai da sua máquina" é verificável (teste que busca referência externa nos assets, ausência de cliente HTTP de saída no núcleo). O produto fica elegível para ambientes corporativos rígidos, incluindo os do próprio autor.

**Ruins, e assumidas.**

1. **Uso remoto (dev em container/VM, navegador em outra máquina)** exige flag explícita e fica, formalmente, sem proteção. Documentado; solução adequada é túnel SSH, não autenticação caseira.
2. **Redaction é mitigação, não garantia** (Risco R-08): campo de negócio com dado pessoal e nome inocente passa. Escrito no README, em letra grande.
3. **Sem compartilhar trace por link.** Compartilhamento é por arquivo exportado (`.tvtrace`), o que também é melhor para privacidade.
