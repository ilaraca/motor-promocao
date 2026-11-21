package com.promo.engine;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promo.engine.adapters.in.web.dto.PromotionWebDtos.CartItemDto;
import com.promo.engine.adapters.in.web.dto.PromotionWebDtos.CommitRequest;
import com.promo.engine.adapters.in.web.dto.PromotionWebDtos.DecisionSnapshotResponse;
import com.promo.engine.adapters.in.web.dto.PromotionWebDtos.EvaluateRequest;
import com.promo.engine.adapters.in.web.dto.PromotionWebDtos.EvaluateResponse;
import com.promo.engine.adapters.in.web.dto.PromotionWebDtos.ProgressiveTierRequest;
import com.promo.engine.adapters.in.web.dto.PromotionWebDtos.PromotionRuleResponse;
import com.promo.engine.adapters.in.web.dto.PromotionWebDtos.PublishRuleRequest;
import com.promo.engine.application.port.out.CommitTransactionPort;
import com.promo.engine.application.port.out.EvaluationQuoteRepository;
import com.promo.engine.application.port.out.OutboxEventPublisher;
import com.promo.engine.application.port.out.PromotionRuleRepository;
import com.promo.engine.application.port.out.QuotaReadRepository;
import com.promo.engine.config.MutableClockAdapter;
import com.promo.engine.config.SecurityConfig.LocalJwtTokenFactory;
import com.promo.engine.domain.exception.IdempotencyConflictException;
import com.promo.engine.domain.exception.QuotaExceededOnCommitException;
import com.promo.engine.domain.model.AppliedPromotion;
import com.promo.engine.domain.model.BenefitType;
import com.promo.engine.domain.model.CalculationBase;
import com.promo.engine.domain.model.CashbackGrantEvent;
import com.promo.engine.domain.model.ConflictPolicy;
import com.promo.engine.domain.model.DecisionSnapshot;
import com.promo.engine.domain.model.EvaluationQuote;
import com.promo.engine.domain.model.FrozenRuleRef;
import com.promo.engine.domain.model.Money;
import com.promo.engine.domain.model.PromotionRule;
import com.promo.engine.domain.model.QuotaSnapshot;
import com.promo.engine.domain.model.ReasonCode;
import com.promo.engine.domain.model.RuleStatus;
import com.promo.engine.domain.model.StackabilityMode;
import com.promo.engine.domain.model.TierBenefitMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full Spring Boot MVC + Spring Security JWT + Hexagonal Use Cases + 5-Thread Concurrency
 * End-to-End Test using an atomic in-memory adapter (runs fast without Docker).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(PromotionEndToEndWorkflowTest.InMemoryPortsConfig.class)
@TestPropertySource(properties = {
        "promotions.aws.dynamodb.auto-create-table=false",
        "promotions.aws.sqs.auto-create-queue=false",
        "promotions.aws.outbox.relay-enabled=false"
})
class PromotionEndToEndWorkflowTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LocalJwtTokenFactory jwtTokenFactory;

    @Autowired
    private MutableClockAdapter clock;

    @Autowired
    private InMemoryStore store;

    private String adminToken;

    @BeforeEach
    void setUp() {
        store.clear();
        clock.fixAt(Instant.parse("2026-11-20T21:45:00Z"));
        adminToken = jwtTokenFactory.issueToken("admin_user", Set.of(), Set.of("promotions:admin:write"));
    }

    @Test
    @DisplayName("PRD Scenario 3 (E2E): Rule updated v1 -> v2 mid-checkout honors frozen v1 inside TTL and rejects expired quote")
    void shouldHonorFrozenRuleVersionWithinTtlAndRejectAfterExpiry() throws Exception {
        String userToken = jwtTokenFactory.issueToken("usr_98765", Set.of("STANDARD"), Set.of("promotions:checkout"));

        // Publish v1 (10% discount)
        PromotionRuleResponse v1 = publishRule("promo_faixa_10", new BigDecimal("10"),
                BenefitType.INSTANT_DISCOUNT, StackabilityMode.STACKABLE, 1, 1, 5, new BigDecimal("1000.00"));
        assertThat(v1.ruleVersion()).isEqualTo(1);
        assertThat(v1.createdBy()).isEqualTo("admin_user");

        // Evaluate -> freezes v1 (10% of 160 = 16.00)
        EvaluateResponse evalV1 = evaluate(userToken, "usr_98765", new BigDecimal("180.00"), new BigDecimal("160.00"), "corr-e2e-3");
        assertThat(evalV1.totalDiscountAmount()).isEqualByComparingTo("16.00");
        assertThat(evalV1.finalPayableAmount()).isEqualByComparingTo("164.00");

        // Publish v2 (20% discount) while checkout is in progress
        PromotionRuleResponse v2 = publishRule("promo_faixa_10", new BigDecimal("20"),
                BenefitType.INSTANT_DISCOUNT, StackabilityMode.STACKABLE, 1, 1, 5, new BigDecimal("1000.00"));
        assertThat(v2.ruleVersion()).isEqualTo(2);

        // Commit evalV1 inside TTL -> MUST honor v1 (16.00)
        clock.advanceBySeconds(180);
        DecisionSnapshotResponse commitSnap = commitOk(userToken, "ord_554433", evalV1.evaluationId(), "chk_v1_01");
        assertThat(commitSnap.appliedPromotions())
                .singleElement()
                .satisfies(p -> {
                    assertThat(p.ruleVersion()).isEqualTo(1);
                    assertThat(p.calculatedAmount()).isEqualByComparingTo("16.00");
                });

        // New evaluate immediately picks up v2 (20% of 160 = 32.00)
        EvaluateResponse evalV2 = evaluate(userToken, "usr_98765", new BigDecimal("180.00"), new BigDecimal("160.00"), "corr-e2e-3b");
        assertThat(evalV2.totalDiscountAmount()).isEqualByComparingTo("32.00");
        assertThat(evalV2.appliedPromotions().getFirst().ruleVersion()).isEqualTo(2);

        // Advance past 10-min TTL -> commit rejected with 422 EVALUATION_EXPIRED
        clock.advanceBySeconds(601);
        MvcResult expired = commitRaw(userToken, "ord_expired", evalV2.evaluationId(), "chk_expired");
        assertThat(expired.getResponse().getStatus()).isEqualTo(422);
        assertThat(expired.getResponse().getContentAsString()).contains("EVALUATION_EXPIRED");
    }

    @Test
    @DisplayName("PRD Scenario 4 (E2E): 5 concurrent commits with same checkoutKey consume quota once, return identical snapshot & publish 1 outbox event")
    void shouldHandle5ConcurrentCommitsIdempotentlyAndConsumeQuotaOnce() throws Exception {
        String userToken = jwtTokenFactory.issueToken("usr_conc", Set.of("STANDARD"), Set.of("promotions:checkout"));

        publishRule("promo_disc", new BigDecimal("10"),
                BenefitType.INSTANT_DISCOUNT, StackabilityMode.STACKABLE, 1, 1, 1, new BigDecimal("1000.00"));
        publishRule("promo_cb", new BigDecimal("5"),
                BenefitType.CASHBACK, StackabilityMode.STACKABLE, 2, 2, 1, new BigDecimal("1000.00"));

        EvaluateResponse eval = evaluate(userToken, "usr_conc", new BigDecimal("180.00"), new BigDecimal("160.00"), "corr-conc-5");
        assertThat(eval.totalDiscountAmount()).isEqualByComparingTo("16.00");
        assertThat(eval.totalCashbackAmount()).isEqualByComparingTo("8.00");
        assertThat(eval.finalPayableAmount()).isEqualByComparingTo("164.00");

        int threads = 5;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<DecisionSnapshotResponse>> calls = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            calls.add(() -> {
                barrier.await();
                return commitOk(userToken, "ord_conc_1", eval.evaluationId(), "chk_same_key_5x");
            });
        }

        List<Future<DecisionSnapshotResponse>> futures = pool.invokeAll(calls);
        pool.shutdown();

        List<DecisionSnapshotResponse> results = new ArrayList<>();
        for (Future<DecisionSnapshotResponse> f : futures) {
            results.add(f.get());
        }

        // All 5 concurrent callers receive byte-for-byte identical snapshot
        DecisionSnapshotResponse expected = results.getFirst();
        assertThat(results).allSatisfy(r -> assertThat(r).isEqualTo(expected));

        // User quota consumed strictly once & campaign budget debited strictly once
        assertThat(store.quotaCount("promo_disc", "usr_conc")).isEqualTo(1);
        assertThat(store.quotaCount("promo_cb", "usr_conc")).isEqualTo(1);
        assertThat(store.budgetSpent("promo_disc")).isEqualTo(Money.of("16.00"));
        assertThat(store.budgetSpent("promo_cb")).isEqualTo(Money.of("8.00"));

        // Exactly 1 CashbackGrantedEvent emitted to outbox
        assertThat(store.outboxEvents())
                .singleElement()
                .satisfies(evt -> {
                    assertThat(evt.orderId()).isEqualTo("ord_conc_1");
                    assertThat(evt.totalCashbackAmount()).isEqualTo(Money.of("8.00"));
                });

        // GET snapshot endpoint matches persisted decision
        MvcResult snapshotResult = mockMvc.perform(get("/v1/promotions/orders/ord_conc_1/snapshot")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isOk())
                .andReturn();
        DecisionSnapshotResponse fetched = objectMapper.readValue(
                snapshotResult.getResponse().getContentAsString(), DecisionSnapshotResponse.class);
        assertThat(fetched).isEqualTo(expected);
    }

    @Test
    @DisplayName("PRD Scenarios 2 & 5 (E2E): Security (RBAC/ownership/anti-spoofing), payload conflict (409) & structured audit logs")
    void shouldEnforceSecurityOwnershipAndEmitStructuredAuditTrail() throws Exception {
        Logger auditLogger = (Logger) LoggerFactory.getLogger("PROMOTION_AUDIT");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        auditLogger.addAppender(appender);

        try {
            String ownerToken = jwtTokenFactory.issueToken("usr_owner", Set.of("STANDARD"), Set.of("promotions:checkout"));
            String attackerToken = jwtTokenFactory.issueToken("usr_attacker", Set.of("STANDARD"), Set.of("promotions:checkout"));

            // Non-admin blocked from publishing rules (403)
            mockMvc.perform(post("/v1/admin/promotions")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRuleReq("promo_forbid", new BigDecimal("10"),
                                    BenefitType.INSTANT_DISCOUNT, StackabilityMode.EXCLUSIVE, 1, 1, null, null))))
                    .andExpect(status().isForbidden());

            // Publish 1 EXCLUSIVE + 2 STACKABLE
            publishRule("promo_excl", new BigDecimal("15"), BenefitType.INSTANT_DISCOUNT, StackabilityMode.EXCLUSIVE, 1, 1, null, null);
            publishRule("promo_stack_a", new BigDecimal("10"), BenefitType.INSTANT_DISCOUNT, StackabilityMode.STACKABLE, 2, 1, null, null);
            publishRule("promo_stack_b", new BigDecimal("5"), BenefitType.CASHBACK, StackabilityMode.STACKABLE, 3, 2, null, null);

            // Spoofing another userId in evaluate body blocked (403)
            mockMvc.perform(post("/v1/promotions/evaluate")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + attackerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new EvaluateRequest(
                                    "usr_owner", new BigDecimal("180.00"), new BigDecimal("160.00"),
                                    List.of(new CartItemDto("ITEM-1", "RESTAURANTS", new BigDecimal("160.00"))),
                                    ConflictPolicy.PRIORITY_RANK))))
                    .andExpect(status().isForbidden());

            // Owner evaluates -> EXCLUSIVE wins, 2 STACKABLE discarded with EXCLUSIVE_PROMOTION_CONFLICT
            EvaluateResponse eval = evaluate(ownerToken, "usr_owner", new BigDecimal("200.00"), new BigDecimal("200.00"), "corr-audit-99");
            assertThat(eval.appliedPromotions()).singleElement().extracting(p -> p.promotionId()).isEqualTo("promo_excl");
            assertThat(eval.discardedEligiblePromotions())
                    .hasSize(2)
                    .allSatisfy(d -> assertThat(d.reasonCode()).isEqualTo("EXCLUSIVE_PROMOTION_CONFLICT"));

            // Attacker cannot commit owner's evaluationId (403)
            MvcResult stolen = commitRaw(attackerToken, "ord_stolen", eval.evaluationId(), "chk_stolen");
            assertThat(stolen.getResponse().getStatus()).isEqualTo(403);
            assertThat(stolen.getResponse().getContentAsString()).contains("EVALUATION_OWNERSHIP_MISMATCH");

            // Owner commits
            commitOk(ownerToken, "ord_valid_1", eval.evaluationId(), "chk_unique_1");

            // Reusing same checkoutKey with different orderId -> 409 IDEMPOTENCY_KEY_CONFLICT
            MvcResult conflict = commitRaw(ownerToken, "ord_different_2", eval.evaluationId(), "chk_unique_1");
            assertThat(conflict.getResponse().getStatus()).isEqualTo(409);
            assertThat(conflict.getResponse().getContentAsString()).contains("IDEMPOTENCY_KEY_CONFLICT");

            // Structured audit log contains correlationId + EXCLUSIVE_PROMOTION_CONFLICT + evaluationId
            assertThat(appender.list).anySatisfy(ev -> {
                String args = Arrays.toString(ev.getArgumentArray());
                assertThat(ev.getFormattedMessage()).contains("PROMOTION_EVALUATION_COMPLETED");
                assertThat(args).contains("corr-audit-99");
                assertThat(args).contains(eval.evaluationId());
                assertThat(args).contains("EXCLUSIVE_PROMOTION_CONFLICT");
            });
        } finally {
            auditLogger.detachAppender(appender);
        }
    }

    private PromotionRuleResponse publishRule(
            String id, BigDecimal pct, BenefitType type, StackabilityMode mode,
            int priorityRank, int precedenceOrder, Integer maxUsage, BigDecimal maxBudget
    ) throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/admin/promotions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildRuleReq(
                                id, pct, type, mode, priorityRank, precedenceOrder, maxUsage, maxBudget))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), PromotionRuleResponse.class);
    }

    private PublishRuleRequest buildRuleReq(
            String id, BigDecimal pct, BenefitType type, StackabilityMode mode,
            int priorityRank, int precedenceOrder, Integer maxUsage, BigDecimal maxBudget
    ) {
        return new PublishRuleRequest(
                id, "Campaign " + id,
                Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-12-31T23:59:59Z"),
                type, mode, priorityRank, precedenceOrder, CalculationBase.ELIGIBLE_GROSS,
                new BigDecimal("50.00"), new BigDecimal("200.00"), maxUsage, maxBudget,
                Set.of("STANDARD"), Set.of(),
                List.of(
                        new ProgressiveTierRequest("t_50_99", new BigDecimal("50.00"), new BigDecimal("99.99"),
                                TierBenefitMode.PERCENTAGE, pct.divide(BigDecimal.valueOf(2))),
                        new ProgressiveTierRequest("t_100_plus", new BigDecimal("100.00"), null,
                                TierBenefitMode.PERCENTAGE, pct)
                )
        );
    }

    private EvaluateResponse evaluate(String token, String userId, BigDecimal gross, BigDecimal eligible, String corrId) throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/promotions/evaluate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("X-Correlation-Id", corrId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new EvaluateRequest(
                                userId, gross, eligible,
                                List.of(new CartItemDto("ITEM-01", "RESTAURANTS", eligible)),
                                ConflictPolicy.PRIORITY_RANK))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), EvaluateResponse.class);
    }

    private DecisionSnapshotResponse commitOk(String token, String orderId, String evalId, String key) throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/promotions/commit")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CommitRequest(orderId, evalId))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), DecisionSnapshotResponse.class);
    }

    private MvcResult commitRaw(String token, String orderId, String evalId, String key) throws Exception {
        return mockMvc.perform(post("/v1/promotions/commit")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CommitRequest(orderId, evalId))))
                .andReturn();
    }

    static class InMemoryStore {
        private final Map<String, List<PromotionRule>> ruleVersions = new ConcurrentHashMap<>();
        private final Map<String, EvaluationQuote> quotes = new ConcurrentHashMap<>();
        private final Map<String, CommitTransactionPort.IdempotentRecord> idempotency = new ConcurrentHashMap<>();
        private final Map<String, DecisionSnapshot> snapshots = new ConcurrentHashMap<>();
        private final Map<String, Integer> userQuotas = new ConcurrentHashMap<>();
        private final Map<String, Money> campaignBudgets = new ConcurrentHashMap<>();
        private final List<CashbackGrantEvent> outboxEvents = new CopyOnWriteArrayList<>();

        void clear() {
            ruleVersions.clear();
            quotes.clear();
            idempotency.clear();
            snapshots.clear();
            userQuotas.clear();
            campaignBudgets.clear();
            outboxEvents.clear();
        }

        int quotaCount(String promoId, String userId) {
            return userQuotas.getOrDefault(promoId + "#" + userId, 0);
        }

        Money budgetSpent(String promoId) {
            return campaignBudgets.getOrDefault(promoId, Money.ZERO);
        }

        List<CashbackGrantEvent> outboxEvents() {
            return List.copyOf(outboxEvents);
        }

        synchronized PromotionRule publishRule(PromotionRule newRule) {
            List<PromotionRule> versions = ruleVersions.computeIfAbsent(newRule.promotionId(), k -> new ArrayList<>());
            for (int i = 0; i < versions.size(); i++) {
                PromotionRule old = versions.get(i);
                if (old.status() == RuleStatus.ACTIVE) {
                    versions.set(i, new PromotionRule(
                            old.promotionId(), old.ruleVersion(), RuleStatus.SUPERSEDED, old.name(),
                            old.validFrom(), old.validTo(), old.benefitType(), old.stackabilityMode(),
                            old.priorityRank(), old.precedenceOrder(), old.calculationBase(),
                            old.minOrderValue(), old.maxBenefitAmount(), old.maxUsagePerUser(),
                            old.maxCampaignBudget(), old.eligibleSegments(), old.eligibleCategories(),
                            old.tiers(), old.createdBy(), old.createdAt()
                    ));
                }
            }
            versions.add(newRule);
            return newRule;
        }

        synchronized List<PromotionRule> activeRules() {
            return ruleVersions.values().stream()
                    .flatMap(List::stream)
                    .filter(r -> r.status() == RuleStatus.ACTIVE)
                    .toList();
        }

        synchronized Optional<Integer> latestVersion(String promoId) {
            List<PromotionRule> versions = ruleVersions.get(promoId);
            if (versions == null || versions.isEmpty()) return Optional.empty();
            return Optional.of(versions.stream().mapToInt(PromotionRule::ruleVersion).max().orElse(0));
        }

        synchronized CommitTransactionPort.TransactionCommitOutcome commitAtomically(
                CommitTransactionPort.AtomicCommitCommand cmd
        ) {
            CommitTransactionPort.IdempotentRecord existing = idempotency.get(cmd.checkoutKey());
            if (existing != null) {
                if (!existing.payloadHash().equals(cmd.payloadHash())) {
                    throw new IdempotencyConflictException(cmd.checkoutKey());
                }
                return new CommitTransactionPort.TransactionCommitOutcome(existing.snapshot(), true, true);
            }

            DecisionSnapshot snapshot = cmd.snapshot();
            if (snapshots.containsKey(snapshot.orderId())) {
                throw new IdempotencyConflictException(cmd.checkoutKey());
            }

            Map<String, FrozenRuleRef> refMap = cmd.frozenRuleRefs().stream()
                    .collect(Collectors.toMap(FrozenRuleRef::promotionId, Function.identity(), (a, b) -> a));

            for (AppliedPromotion p : snapshot.appliedPromotions()) {
                FrozenRuleRef ref = refMap.get(p.promotionId());
                int curUsage = quotaCount(p.promotionId(), snapshot.userId());
                if (ref != null && ref.maxUsagePerUser() != null && curUsage >= ref.maxUsagePerUser()) {
                    throw new QuotaExceededOnCommitException(ReasonCode.USER_CAP_REACHED, "Cap reached");
                }
                Money curSpent = budgetSpent(p.promotionId());
                if (ref != null && ref.maxCampaignBudget() != null
                        && curSpent.add(p.calculatedAmount()).isGreaterThan(ref.maxCampaignBudget())) {
                    throw new QuotaExceededOnCommitException(ReasonCode.CAMPAIGN_BUDGET_EXHAUSTED, "Budget exhausted");
                }
            }

            idempotency.put(cmd.checkoutKey(), new CommitTransactionPort.IdempotentRecord(
                    cmd.checkoutKey(), snapshot.orderId(), snapshot.evaluationId(), cmd.payloadHash(), snapshot));
            snapshots.put(snapshot.orderId(), snapshot);
            for (AppliedPromotion p : snapshot.appliedPromotions()) {
                String qKey = p.promotionId() + "#" + snapshot.userId();
                userQuotas.merge(qKey, 1, Integer::sum);
                campaignBudgets.merge(p.promotionId(), p.calculatedAmount(), Money::add);
            }
            if (cmd.cashbackOutboxEvent() != null) {
                outboxEvents.add(cmd.cashbackOutboxEvent());
            }
            return new CommitTransactionPort.TransactionCommitOutcome(snapshot, false, false);
        }
    }

    @TestConfiguration
    static class InMemoryPortsConfig {

        @Bean
        InMemoryStore inMemoryStore() {
            return new InMemoryStore();
        }

        @Bean
        @Primary
        PromotionRuleRepository testRuleRepo(InMemoryStore store) {
            return new PromotionRuleRepository() {
                @Override public List<PromotionRule> findAllActive() { return store.activeRules(); }
                @Override public Optional<Integer> findLatestVersion(String id) { return store.latestVersion(id); }
                @Override public PromotionRule publishNewVersion(PromotionRule r, Integer prev) { return store.publishRule(r); }
            };
        }

        @Bean
        @Primary
        QuotaReadRepository testQuotaRepo(InMemoryStore store) {
            return (userId, promoIds) -> {
                Map<String, QuotaSnapshot> map = new HashMap<>();
                for (String id : promoIds) {
                    map.put(id, new QuotaSnapshot(store.quotaCount(id, userId), store.budgetSpent(id)));
                }
                return map;
            };
        }

        @Bean
        @Primary
        EvaluationQuoteRepository testQuoteRepo(InMemoryStore store) {
            return new EvaluationQuoteRepository() {
                @Override public void save(EvaluationQuote quote) { store.quotes.put(quote.evaluationId(), quote); }
                @Override public Optional<EvaluationQuote> findById(String id) { return Optional.ofNullable(store.quotes.get(id)); }
            };
        }

        @Bean
        @Primary
        CommitTransactionPort testCommitPort(InMemoryStore store) {
            return new CommitTransactionPort() {
                @Override public Optional<IdempotentRecord> findIdempotentCommit(String key) {
                    return Optional.ofNullable(store.idempotency.get(key));
                }
                @Override public TransactionCommitOutcome commitAtomically(AtomicCommitCommand cmd) {
                    return store.commitAtomically(cmd);
                }
                @Override public Optional<DecisionSnapshot> findOrderSnapshot(String orderId) {
                    return Optional.ofNullable(store.snapshots.get(orderId));
                }
            };
        }

        @Bean
        @Primary
        OutboxEventPublisher testOutboxPublisher() {
            return event -> {};
        }
    }
}
