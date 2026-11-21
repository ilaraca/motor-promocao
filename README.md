# Motor de Avaliação de Benefícios Progressivos e Rastreabilidade (POC)

POC em **Java 21 + Spring Boot 3.4 + DynamoDB Single Table + SQS (LocalStack)** estruturada em **Arquitetura Hexagonal (Ports & Adapters)** para validar:

- **Desconto por faixa progressiva** e **Cashback progressivo** via padrão **Strategy / Registry** extensível (Open/Closed).
- **Determinismo absoluto** com `BigDecimal` (`RoundingMode.HALF_EVEN`), ordem fixa de elegibilidade, resolução de conflitos (`EXCLUSIVE` vs. `STACKABLE` com `PRIORITY_RANK` e `MAX_FINANCIAL_BENEFIT`) e desempate total.
- **Versionamento imutável** `(promotionId, ruleVersion)` com Sparse GSI (`GSI1`) no DynamoDB Single Table.
- **Cotação congelada com TTL (`evaluate`) + Checkout idempotente (`commit`)** via `TransactWriteItems` atômico (Lock de Idempotência + Snapshot do Pedido + Cota por Usuário + Orçamento da Campanha + Transactional Outbox).
- **Transactional Outbox** via DynamoDB Streams (`NEW_IMAGE`) publicando `CashbackGrantedEvent` no SQS.
- **Segurança (JWT/RBAC + Ownership Check)** e **Logging Estruturado em JSON** com `correlationId`, `traceId` e trilha completa `candidates[]` (`APPLIED` / `REJECTED` + `reasonCode`).

---

## Pré-requisitos (Custo R$ 0,00 — 100% local)

- **Java 21**
- **Maven 3.9+**
- **Docker** (Docker Desktop, OrbStack ou Colima)

---

## Executar a Suíte Completa de Validação (Unitários + ArchUnit + Integração LocalStack)

```bash
mvn test
```

A suíte executa automaticamente todos os cenários obrigatórios do PRD:

1. **`HexagonalArchitectureTest` (ArchUnit):** garante zero dependência de Spring, AWS SDK ou Jackson nas camadas `domain` e `application`.
2. **`PromotionEvaluationEngineTest` (Unitários puros):**
   - **Cenário 1:** Faixas limite (`R$ 49,99 / 50,00 / 99,99 / 100,00 / 199,99 / 200,00`) e arredondamento `HALF_EVEN`.
   - **Cenário 2:** Conflito `1 EXCLUSIVE + 2 STACKABLE` com `EXCLUSIVE_PROMOTION_CONFLICT`, ambas políticas (`PRIORITY_RANK` e `MAX_FINANCIAL_BENEFIT`) e invariância à ordem de entrada.
   - Reprodução exata do exemplo do PRD (`gross=180.00`, `eligible=160.00` → `discount=16.00`, `cashback=8.00`, `finalPayable=164.00`).
   - Validação *Design-Time* de sobreposição de faixas.
3. **`PromotionPocIntegrationTest` (Testcontainers + LocalStack real — DynamoDB + Streams + SQS):**
   - **Cenário 3:** Atualização de regra `v1 -> v2` durante o checkout honrando `v1` dentro do TTL e retornando `422 EVALUATION_EXPIRED` após expiração.
   - **Cenário 4:** **5 commits simultâneos** (`CyclicBarrier`) com o mesmo `Idempotency-Key`: todas as 5 respostas retornam `200 OK` com snapshot idêntico, `QUOTA` tem `usageCount == 1`, `BUDGET` debitado 1x e exatamente **1 evento `CashbackGrantedEvent`** entregue no SQS via Outbox Relay.
   - **Cenário 5:** Segurança JWT/RBAC (`403`), *ownership check* (`403`), reutilização indevida de `Idempotency-Key` com outro `orderId` (`409`) e auditoria JSON estruturada.

---

## Subir Localmente (Docker Compose + App)

```bash
# 1. Subir LocalStack (DynamoDB + Streams + SQS)
docker compose up -d

# 2. Subir a aplicação (tabela, GSI1, Streams e fila SQS são criados automaticamente)
mvn spring-boot:run

# 3. Em outro terminal, rodar o roteiro completo de curl (gera JWTs, publica regras, testa evaluate/commit/retry/VIP)
./scripts/demo-curl.sh
```

Para gerar apenas um token JWT local para testes manuais ou k6:

```bash
./scripts/generate-jwt.sh usr_98765 "promotions:checkout" "STANDARD"
./scripts/generate-jwt.sh admin_poc "promotions:admin:write" ""
```

Métricas Prometheus ficam disponíveis em `http://localhost:8080/actuator/prometheus`.

### Benchmark de Latência (`p99 < 80 ms`)

Com a aplicação rodando:

```bash
JWT_TOKEN="$(./scripts/generate-jwt.sh usr_98765)" k6 run scripts/k6-evaluate-load.js
```
