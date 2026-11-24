# IMDTravel — Tolerância a Falhas

Projeto da disciplina de Tolerância a Falhas do DIMAp/UFRN: um sistema didático de venda de passagens aéreas, construído para ser derrubado de propósito e medido.

Este repositório corresponde à **Parte 1** do projeto: a versão básica do sistema, instrumentada para ser observada e **sem nenhum mecanismo de tolerância a falhas**. A versão entregue está marcada com a tag `BASELINE`.

## Equipe

| Membro | Responsabilidade |
|---|---|
| Ádisson Téjo | Estrutura inicial do projeto, Exchange e Fidelity |
| Ana Carolina | AirlinesHub |
| Gabriel Ribeiro Barbosa da Silva | IMDTravel e testes |

## Linguagem e framework

- **Java 17**
- **Spring Boot 4.1.2** (Spring Web MVC; `RestClient` nas chamadas do IMDTravel às dependências)
- **Maven** para o build (cada serviço tem seu `pom.xml` e o Maven Wrapper)
- **Docker** e **Docker Compose** para executar os quatro serviços

Escolhemos Java com Spring Boot por ser uma stack conhecida pela equipe, com servidor HTTP embutido, injeção de dependência e serialização JSON prontas, o que deixa o código de cada serviço focado no comportamento pedido. Cada serviço é um projeto Spring Boot independente, empacotado em uma imagem Docker própria com build em dois estágios (Maven → JRE).

## O sistema

O cliente pede uma compra ao IMDTravel, que faz quatro chamadas síncronas, em ordem obrigatória, a três serviços de dependência:

```
cliente ──POST /buyTicket──▶ IMDTravel ──1. GET  /flight ──▶ AirlinesHub   preço e assentos
                                │      ──2. GET  /convert──▶ Exchange      taxa de câmbio
                                │      ──3. POST /sell   ──▶ AirlinesHub   consome o assento
                                │      ──4. POST /bonus  ──▶ Fidelity      credita os pontos
                                ▼
                     registro local de vendas (GET /sales)
```

| Serviço | Papel | Endpoints |
|---|---|---|
| **IMDTravel** | Orquestrador: recebe a compra, faz as quatro chamadas e registra a venda. Única porta exposta ao hospedeiro (`8080`). | `POST /buyTicket`, `GET /sales`, `GET /health` |
| **AirlinesHub** | Mantém o estoque de assentos por voo e dia. | `GET /flight`, `POST /sell`, `GET /health` |
| **Exchange** | Fornece a taxa de câmbio, que varia devagar ao longo do tempo. | `GET /convert`, `GET /health` |
| **Fidelity** | Acumula pontos de fidelidade por usuário. | `POST /bonus`, `GET /points`, `GET /health` |

### Regras de negócio

- **Valor em reais** = preço em dólar × taxa de câmbio (2 casas decimais).
- **Bônus** = parte inteira do preço em dólar.
- **AirlinesHub:** o preço é determinístico (sempre o mesmo para o mesmo voo); `POST /sell` leva de 300 a 900 ms, sorteados por requisição; o decremento do estoque é atômico (`AtomicInteger` com `compareAndSet`), então o estoque nunca fica negativo e o último assento não é vendido duas vezes.
- **Exchange:** a taxa começa em 5,50 e, a cada 10 segundos, varia até ±0,3%, sempre dentro da faixa de 5,00 a 6,00. Entre duas atualizações, todas as chamadas devolvem o mesmo valor e o mesmo `asOf`.
- **IMDTravel:** grava cada compra concluída em `sales.jsonl`, no volume nomeado `imdtravel-sales`, com `correlationId`, `transactionId`, voo, dia, usuário, valores e instante.

### Respostas do `POST /buyTicket`

| Situação | Status | Corpo |
|---|---|---|
| Compra concluída | `201` | `{ transactionId, flight, day, user, valueUSD, valueBRL, rate, rateAsOf, bonus }` |
| Voo ou dia inexistente | `404` | `{ "error": "not-found" }` |
| Voo esgotado | `409` | `{ "error": "sold-out" }` |
| Pedido inválido | `400` | `{ "error": "invalid-request" }` |
| Falha de qualquer dependência | `500` | `{ "error": "dependency-failure" }` |

### Sem tolerância a falhas (requisito)

Conforme a especificação, esta versão **não** tem timeout configurado nas chamadas de saída (usa o padrão do `RestClient`), retry, fallback, cache, fila nem limite de requisições simultâneas. Qualquer falha de dependência vira `500`. A chamada ao Fidelity está no caminho crítico: se ela falhar, a compra falha, mesmo depois de o assento já ter sido consumido no AirlinesHub.

## Catálogo de voos

O catálogo do AirlinesHub é montado em memória a cada subida do container, a partir das variáveis de ambiente do `docker-compose.yml`:

```yaml
CATALOG_FLIGHTS: "IMD100,IMD200,IMD300"
CATALOG_BASE_PRICES: "350.50,420.50,510.50"
CATALOG_START_DATE: "2026-12-01"
CATALOG_DAYS: "7"
CATALOG_SEATS: "20"
```

| Voo | Preço (USD) | Bônus | Dias | Assentos por dia |
|---|---|---|---|---|
| `IMD100` | 350.50 | 350 | 2026-12-01 a 2026-12-07 | 20 |
| `IMD200` | 420.50 | 420 | 2026-12-01 a 2026-12-07 | 20 |
| `IMD300` | 510.50 | 510 | 2026-12-01 a 2026-12-07 | 20 |

São 3 voos × 7 dias = **21 pares (voo, dia)**, com 20 assentos cada (420 no total). Como o estoque fica em memória, reiniciar o container do AirlinesHub devolve todos os assentos.

A taxa inicial do Exchange também vem do compose: `INITIAL_EXCHANGE_RATE: "5.50"`.

## Instrumentação

- **Correlação:** o IMDTravel lê o `X-Correlation-Id` do cliente (ou gera um UUID, se não vier), devolve esse valor no cabeçalho da resposta e o propaga nas quatro chamadas.
- **Log estruturado:** cada serviço escreve um objeto JSON por linha na saída padrão, com `ts`, `svc`, `cid`, `event` e `ms`. Os logs internos do Spring também saem em JSON (sem banner), então nenhuma linha é texto livre. O campo `ts` está em **UTC** (horário de Brasília + 3 h). Exemplo:
  ```json
  {"ts":"2026-11-24T00:19:09.913Z","svc":"airlineshub","cid":"evidencia-1","event":"venda.processada","ms":329,"flight":"IMD200","day":"2026-12-03","delayMs":329,"result":"SOLD","transactionId":"72c68ed1-...","seatsRemaining":19}
  ```
  Para seguir uma compra pelos serviços: `docker logs <serviço> | grep '"cid":"<id>"'`.
- **Sinal de vida:** `GET /health` nos quatro serviços, devolvendo `{ status, uptimeS }` e o resultado da verificação feita. Cada serviço verifica algo de verdade e responde `503` quando a verificação falha:

  | Serviço | O que o `/health` verifica |
  |---|---|
  | IMDTravel | O diretório de vendas (volume) aceita escrita. |
  | AirlinesHub | O catálogo tem todos os pares (voo, dia) esperados e nenhum estoque está negativo. |
  | Exchange | A taxa está entre 5,00 e 6,00 e foi atualizada há no máximo 30 s (o agendador está vivo). |
  | Fidelity | O armazenamento de pontos aceita escrita, leitura e remoção. |

## Como executar

Requisitos: Docker com Docker Compose.

```bash
docker compose up -d --build --wait   # sobe os quatro serviços e espera ficarem saudáveis
docker compose ps                     # estado e saúde dos containers
docker compose down                   # para tudo
docker compose down -v                # para tudo e apaga o registro de vendas
```

Exemplo de compra:

```bash
curl -i -X POST localhost:8080/buyTicket \
  -H 'Content-Type: application/json' \
  -H 'X-Correlation-Id: minha-compra-1' \
  -d '{"flight":"IMD100","day":"2026-12-01","user":"ana"}'

curl localhost:8080/sales
```

Os outros serviços não têm porta exposta. Para consultá-los, use `docker exec`:

```bash
docker exec airlineshub curl -s "localhost:8080/flight?flight=IMD100&day=2026-12-01"
docker exec exchange    curl -s localhost:8080/convert
docker exec fidelity    curl -s "localhost:8080/points?user=ana"
```

## Testes realizados

### Testes automatizados — `tests/scripts/testes.sh`

O script faz requisições reais ao `POST /buyTicket` e ao `GET /sales` e confere os resultados contra o estoque do AirlinesHub e os pontos do Fidelity. Requer `docker compose`, `curl` e `jq`.

```bash
tests/scripts/testes.sh                 # testa o sistema que já está no ar
tests/scripts/testes.sh --reiniciar     # derruba tudo, apaga o volume e sobe do zero antes
tests/scripts/testes.sh --com-falhas    # inclui o teste com o Fidelity parado (leva minutos)
```

| Seção | O que verifica |
|---|---|
| 1. `/health` | Os quatro serviços respondem `status: ok` com `uptimeS` numérico. |
| 2. Compra bem-sucedida | `201`; resposta com os 9 campos; `valueBRL = valueUSD × rate`; `bonus` = parte inteira do preço; taxa entre 5,00 e 6,00; o `X-Correlation-Id` devolvido é o enviado; o estoque baixa exatamente 1; `/sales` ganha exatamente 1 linha com o `cid`; o Fidelity credita o bônus com o mesmo `cid`. |
| 3. `cid` gerado | Sem `X-Correlation-Id`, o IMDTravel gera um, devolve no cabeçalho e grava em `/sales`. |
| 4. Determinismo | O mesmo par (voo, dia) sempre tem o mesmo preço, e duas leituras com o mesmo `asOf` têm a mesma taxa. |
| 5. Pedidos inválidos | Voo ou dia inexistente → `404`; campo ausente, usuário vazio ou JSON malformado → `400`; nenhum deles consome assento nem gera venda. |
| 6. Concorrência | 25 compras simultâneas para 20 assentos: exatamente 20 × `201` e 5 × `409`, estoque final 0 e 20 linhas novas em `/sales`; quem recebeu `409` não ganhou bônus. |
| 7. Fidelity parado (`--com-falhas`) | A compra devolve `500`, o assento é consumido mesmo assim e nenhuma venda é registrada. |

Os arquivos de evidência foram gerados pela linha de comando, salvando a saída do script. No Windows (PowerShell, com Git Bash):

```powershell
& "C:\Program Files\Git\bin\bash.exe" -c 'tests/scripts/testes.sh 2>&1 | tee tests/evidence/testes.txt'
```

Em Linux ou macOS:

```bash
tests/scripts/testes.sh 2>&1 | tee tests/evidence/testes.txt
```

### Testes manuais

Os itens que o script não cobre foram verificados manualmente, de forma isolada (sem um fluxo de ponta a ponta). Os arquivos começam com `manual`:

| Arquivo | O que mostra |
|---|---|
| `manual-compra.txt` | Uma compra com `X-Correlation-Id: evidencia-1`, com cabeçalhos e corpo da resposta `201`. |
| `manual-logs-cid.txt` | As linhas de log JSON dessa compra nos quatro serviços, filtradas pelo `cid` e ordenadas por horário: `/buyTicket` → `/flight` → `/convert` → `/sell` → `/bonus`. |
| `manual-health.txt` | A resposta do `/health` dos quatro serviços. |
| `manual-cambio.txt` | Duas leituras do `/convert` com 10 s de intervalo, mostrando a taxa e o `asOf` mudando. |

### Evidências (`tests/evidence/`)

| Arquivo | Execução | Resultado |
|---|---|---|
| `testes.txt` | `testes.sh` com o sistema no ar | 40 passaram, 0 falharam |
| `testes-reiniciar.txt` | `testes.sh --reiniciar` | 40 passaram, 0 falharam |
| `testes-com-falhas.txt` | `testes.sh --com-falhas` | 43 passaram, 0 falharam |
| `manual-*.txt` | testes manuais | ver tabela acima |
