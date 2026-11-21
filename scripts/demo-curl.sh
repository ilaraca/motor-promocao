#!/usr/bin/env bash
set -euo pipefail

# Interactive end-to-end curl walkthrough for the Promotion Engine POC.
# Prerequisites:
#   1. docker compose up -d
#   2. mvn spring-boot:run
# Usage:
#   ./scripts/demo-curl.sh

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE_URL="${BASE_URL:-http://localhost:8080}"
RUN_ID="$(date +%s)"

bold() { printf "\n\033[1;36m=== %s ===\033[0m\n" "$1"; }
pretty() {
  if command -v jq >/dev/null 2>&1; then
    jq .
  else
    python3 -m json.tool 2>/dev/null || cat
  fi
}

bold "0. Gerando tokens JWT locais (HS256)"
ADMIN_TOKEN="$("$SCRIPT_DIR/generate-jwt.sh" "admin_poc" "promotions:admin:write" "")"
USER_TOKEN="$("$SCRIPT_DIR/generate-jwt.sh" "usr_98765" "promotions:checkout" "STANDARD")"
VIP_TOKEN="$("$SCRIPT_DIR/generate-jwt.sh" "usr_vip" "promotions:checkout" "STANDARD,VIP")"
echo "ADMIN_TOKEN gerado para admin_poc (scope=promotions:admin:write)"
echo "USER_TOKEN  gerado para usr_98765 (segments=[STANDARD])"
echo "VIP_TOKEN   gerado para usr_vip   (segments=[STANDARD,VIP])"

bold "1. Publicando Campanha 1: Desconto por Faixa Progressiva (promo_faixa_10)"
curl -sS -X POST "$BASE_URL/v1/admin/promotions" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "X-Correlation-Id: demo-rule-1-$RUN_ID" \
  -H "Content-Type: application/json" \
  -d '{
    "promotionId": "promo_faixa_10",
    "name": "Desconto Progressivo por Faixa",
    "validFrom": "2026-01-01T00:00:00Z",
    "validTo": "2027-12-31T23:59:59Z",
    "benefitType": "INSTANT_DISCOUNT",
    "stackabilityMode": "STACKABLE",
    "priorityRank": 2,
    "precedenceOrder": 1,
    "calculationBase": "ELIGIBLE_GROSS",
    "minOrderValue": 50.00,
    "maxBenefitAmount": 100.00,
    "maxUsagePerUser": 10,
    "maxCampaignBudget": 50000.00,
    "eligibleSegments": [],
    "eligibleCategories": [],
    "tiers": [
      { "tierId": "tier_50_99",   "minAmount": 50.00,  "maxAmount": 99.99,  "benefitMode": "PERCENTAGE", "benefitValue": 5 },
      { "tierId": "tier_100_199", "minAmount": 100.00, "maxAmount": 199.99, "benefitMode": "PERCENTAGE", "benefitValue": 10 },
      { "tierId": "tier_200_plus","minAmount": 200.00, "maxAmount": null,   "benefitMode": "PERCENTAGE", "benefitValue": 15 }
    ]
  }' | pretty

bold "2. Publicando Campanha 2: Cashback Progressivo (promo_cb_prog)"
curl -sS -X POST "$BASE_URL/v1/admin/promotions" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "X-Correlation-Id: demo-rule-2-$RUN_ID" \
  -H "Content-Type: application/json" \
  -d '{
    "promotionId": "promo_cb_prog",
    "name": "Cashback Progressivo Restaurantes",
    "validFrom": "2026-01-01T00:00:00Z",
    "validTo": "2027-12-31T23:59:59Z",
    "benefitType": "CASHBACK",
    "stackabilityMode": "STACKABLE",
    "priorityRank": 3,
    "precedenceOrder": 2,
    "calculationBase": "ELIGIBLE_GROSS",
    "minOrderValue": 150.00,
    "maxBenefitAmount": 50.00,
    "maxUsagePerUser": 10,
    "maxCampaignBudget": 50000.00,
    "eligibleSegments": [],
    "eligibleCategories": ["RESTAURANTS"],
    "tiers": [
      { "tierId": "cb_150_plus", "minAmount": 150.00, "maxAmount": null, "benefitMode": "PERCENTAGE", "benefitValue": 5 }
    ]
  }' | pretty

bold "3. Publicando Campanha 3: Exclusiva VIP (promo_vip_excl)"
curl -sS -X POST "$BASE_URL/v1/admin/promotions" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "X-Correlation-Id: demo-rule-3-$RUN_ID" \
  -H "Content-Type: application/json" \
  -d '{
    "promotionId": "promo_vip_excl",
    "name": "VIP Exclusivo 12%",
    "validFrom": "2026-01-01T00:00:00Z",
    "validTo": "2027-12-31T23:59:59Z",
    "benefitType": "INSTANT_DISCOUNT",
    "stackabilityMode": "EXCLUSIVE",
    "priorityRank": 1,
    "precedenceOrder": 1,
    "calculationBase": "ELIGIBLE_GROSS",
    "minOrderValue": 100.00,
    "maxBenefitAmount": 150.00,
    "maxUsagePerUser": 10,
    "maxCampaignBudget": 50000.00,
    "eligibleSegments": ["VIP"],
    "eligibleCategories": [],
    "tiers": [
      { "tierId": "vip_100_plus", "minAmount": 100.00, "maxAmount": null, "benefitMode": "PERCENTAGE", "benefitValue": 12 }
    ]
  }' | pretty

bold "4. Etapa 1 — Cotação (POST /v1/promotions/evaluate) — Exemplo exato da Seção 6 do PRD"
EVAL_RESPONSE="$(curl -sS -X POST "$BASE_URL/v1/promotions/evaluate" \
  -H "Authorization: Bearer $USER_TOKEN" \
  -H "X-Correlation-Id: demo-eval-1-$RUN_ID" \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "usr_98765",
    "grossAmount": 180.00,
    "eligibleAmount": 160.00,
    "items": [{ "sku": "ITEM-01", "category": "RESTAURANTS", "amount": 160.00 }]
  }')"
echo "$EVAL_RESPONSE" | pretty

EVAL_ID="$(printf '%s' "$EVAL_RESPONSE" | python3 -c 'import json,sys; print(json.load(sys.stdin)["evaluationId"])')"
echo "evaluationId congelado: $EVAL_ID"

bold "5. Atualizando regra promo_faixa_10 para nova versão (10% -> 20%) ANTES do commit"
curl -sS -X POST "$BASE_URL/v1/admin/promotions" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "X-Correlation-Id: demo-rule-v2-$RUN_ID" \
  -H "Content-Type: application/json" \
  -d '{
    "promotionId": "promo_faixa_10",
    "name": "Desconto Progressivo por Faixa (Atualizado 20%)",
    "validFrom": "2026-01-01T00:00:00Z",
    "validTo": "2027-12-31T23:59:59Z",
    "benefitType": "INSTANT_DISCOUNT",
    "stackabilityMode": "STACKABLE",
    "priorityRank": 2,
    "precedenceOrder": 1,
    "calculationBase": "ELIGIBLE_GROSS",
    "minOrderValue": 50.00,
    "maxBenefitAmount": 100.00,
    "maxUsagePerUser": 10,
    "maxCampaignBudget": 50000.00,
    "eligibleSegments": [],
    "eligibleCategories": [],
    "tiers": [
      { "tierId": "tier_50_99",   "minAmount": 50.00,  "maxAmount": 99.99,  "benefitMode": "PERCENTAGE", "benefitValue": 5 },
      { "tierId": "tier_100_199", "minAmount": 100.00, "maxAmount": 199.99, "benefitMode": "PERCENTAGE", "benefitValue": 20 },
      { "tierId": "tier_200_plus","minAmount": 200.00, "maxAmount": null,   "benefitMode": "PERCENTAGE", "benefitValue": 25 }
    ]
  }' | pretty

ORDER_ID="ord_demo_$RUN_ID"
CHECKOUT_KEY="chk_demo_$RUN_ID"

bold "6. Etapa 2 — Commit (POST /v1/promotions/commit) — Honra versão congelada (R\$ 16,00, não R\$ 32,00)"
curl -sS -i -X POST "$BASE_URL/v1/promotions/commit" \
  -H "Authorization: Bearer $USER_TOKEN" \
  -H "X-Correlation-Id: demo-commit-1-$RUN_ID" \
  -H "Idempotency-Key: $CHECKOUT_KEY" \
  -H "Content-Type: application/json" \
  -d "{\"orderId\": \"$ORDER_ID\", \"evaluationId\": \"$EVAL_ID\"}"

bold "7. Retry Idempotente com o MESMO Idempotency-Key (retorna X-Idempotent-Replay: true)"
curl -sS -i -X POST "$BASE_URL/v1/promotions/commit" \
  -H "Authorization: Bearer $USER_TOKEN" \
  -H "X-Correlation-Id: demo-commit-retry-$RUN_ID" \
  -H "Idempotency-Key: $CHECKOUT_KEY" \
  -H "Content-Type: application/json" \
  -d "{\"orderId\": \"$ORDER_ID\", \"evaluationId\": \"$EVAL_ID\"}"

bold "8. Consulta do Snapshot Imutável do Pedido (GET /v1/promotions/orders/$ORDER_ID/snapshot)"
curl -sS -X GET "$BASE_URL/v1/promotions/orders/$ORDER_ID/snapshot" \
  -H "Authorization: Bearer $USER_TOKEN" \
  -H "X-Correlation-Id: demo-snapshot-$RUN_ID" | pretty

bold "9. Nova Cotação (usuário VIP) — Conflito EXCLUSIVE vs STACKABLE (descarta STACKABLE com EXCLUSIVE_PROMOTION_CONFLICT)"
curl -sS -X POST "$BASE_URL/v1/promotions/evaluate" \
  -H "Authorization: Bearer $VIP_TOKEN" \
  -H "X-Correlation-Id: demo-eval-vip-$RUN_ID" \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "usr_vip",
    "grossAmount": 180.00,
    "eligibleAmount": 160.00,
    "items": [{ "sku": "ITEM-01", "category": "RESTAURANTS", "amount": 160.00 }]
  }' | pretty

bold "10. Métricas Prometheus (amostra)"
curl -sS "$BASE_URL/actuator/prometheus" | grep -E "^promotions_" | head -n 20 || true
