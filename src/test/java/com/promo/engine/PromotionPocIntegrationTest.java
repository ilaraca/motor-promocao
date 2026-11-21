package com.promo.engine;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promo.engine.adapters.in.web.dto.PromotionWebDtos.CartItemDto;
import com.promo.engine.adapters.in.web.dto.PromotionWebDtos.CommitRequest;
import com.promo.engine.adapters.in.web.dto.PromotionWebDtos.DecisionSnapshotResponse;
import com.promo.engine.adapters.in.web.dto.PromotionWebDtos.EvaluateRequest;
import com.promo.engine.adapters.in.web.dto.PromotionWebDtos.EvaluateResponse;
import com.promo.engine.adapters.in.web.dto.PromotionWebDtos.ProgressiveTierRequest;
import com.promo.engine.adapters.in.web.dto.PromotionWebDtos.PromotionRuleResponse;
import com.promo.engine.adapters.in.web.dto.PromotionWebDtos.PublishRuleRequest;
import com.promo.engine.adapters.out.dynamodb.DynamoDbKeyFactory;
import com.promo.engine.adapters.out.outbox.DynamoDbStreamsOutboxRelay;
import com.promo.engine.adapters.out.outbox.SqsOutboxEventPublisher;
import com.promo.engine.config.MutableClockAdapter;
import com.promo.engine.config.SecurityConfig.LocalJwtTokenFactory;
import com.promo.engine.domain.model.BenefitType;
import com.promo.engine.domain.model.CalculationBase;
import com.promo.engine.domain.model.ConflictPolicy;
import com.promo.engine.domain.model.StackabilityMode;
import com.promo.engine.domain.model.TierBenefitMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.PurgeQueueRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PromotionPocIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> LOCALSTACK = new GenericContainer<>(
            DockerImageName.parse("localstack/localstack:3.8")
    )
            .withExposedPorts(4566)
            .withEnv("SERVICES", "dynamodb,dynamodbstreams,sqs")
            .withEnv("DEFAULT_REGION", "us-east-1")
            .waitingFor(Wait.forHttp("/_localstack/health").forPort(4566));

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("promotions.aws.endpoint",
                () -> "http://" + LOCALSTACK.getHost() + ":" + LOCALSTACK.getMappedPort(4566));
        registry.add("promotions.aws.region", () -> "us-east-1");
        registry.add("promotions.aws.access-key", () -> "test");
        registry.add("promotions.aws.secret-key", () -> "test");
        registry.add("promotions.aws.outbox.relay-enabled", () -> "false"); // polled deterministically in tests
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private LocalJwtTokenFactory jwtTokenFactory;

    @Autowired
    private MutableClockAdapter clock;

    @Autowired
    private DynamoDbClient dynamoDbClient;

    @Autowired
    private SqsClient sqsClient;

    @Autowired
    private SqsOutboxEventPublisher sqsPublisher;

    @Autowired
    private DynamoDbStreamsOutboxRelay outboxRelay;

    @Autowired
    private ObjectMapper objectMapper;

    private String adminToken;

    @BeforeEach
    void setUp() {
        clock.fixAt(Instant.parse("2026-11-20T21:45:00Z"));
        adminToken = jwtTokenFactory.issueToken("admin_user", Set.of(), Set.of("promotions:admin:write"));
    }

    @Test
    @DisplayName("PRD Scenario 3: Rule updated v1 -> v2 mid-checkout honors frozen v1 inside TTL and rejects after TTL")
    void shouldHonorFrozenRuleVersionWithinTtlAndRejectAfterExpiry() {
        String promoId = "promo_mid_checkout_" + System.nanoTime();
        String userId = "usr_scenario3_" + System.nanoTime();
        String userToken = jwtTokenFactory.issueToken(userId, Set.of("STANDARD"), Set.of("promotions:checkout"));

        // 1. Publish rule v1 (10% instant discount)
        ResponseEntity<PromotionRuleResponse> v1 = publishRule(promoId, new BigDecimal("10"),
                BenefitType.INSTANT_DISCOUNT, StackabilityMode.STACKABLE, 1, 1, 5, new BigDecimal("5000.00"), Set.of("STANDARD"));
        assertThat(v1.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        PromotionRuleResponse v1Body = Objects.requireNonNull(v1.getBody());
        assertThat(v1Body.ruleVersion()).isEqualTo(1);

        // 2. Evaluate cart -> freezes ruleVersion: 1 (10% of 160.00 = 16.00)
        EvaluateResponse evalV1 = evaluate(userToken, userId, new BigDecimal("180.00"), new BigDecimal("160.00"), "corr-sc3-1");
        assertThat(evalV1.totalDiscountAmount()).isEqualByComparingTo("16.00");
        assertThat(evalV1.appliedPromotions())
                .anySatisfy(p -> {
                    assertThat(p.promotionId()).isEqualTo(promoId);
                    assertThat(p.ruleVersion()).isEqualTo(1);
                    assertThat(p.calculatedAmount()).isEqualByComparingTo("16.00");
                });

        // 3. Publish rule v2 changing discount to 20% while checkout is still open
        ResponseEntity<PromotionRuleResponse> v2 = publishRule(promoId, new BigDecimal("20"),
                BenefitType.INSTANT_DISCOUNT, StackabilityMode.STACKABLE, 1, 1, 5, new BigDecimal("5000.00"), Set.of("STANDARD"));
        assertThat(v2.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        PromotionRuleResponse v2Body = Objects.requireNonNull(v2.getBody());
        assertThat(v2Body.ruleVersion()).isEqualTo(2);

        // 4. Commit evalV1 inside TTL (advance 2 minutes < 10 min TTL) -> MUST honor ruleVersion: 1!
        clock.advanceBySeconds(120);
        ResponseEntity<DecisionSnapshotResponse> commitResp = commit(
                userToken, "ord_sc3_v1_" + System.nanoTime(), evalV1.evaluationId(), "chk_sc3_v1_" + System.nanoTime());
        assertThat(commitResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        DecisionSnapshotResponse commitBody = Objects.requireNonNull(commitResp.getBody());
        assertThat(commitBody.appliedPromotions())
                .anySatisfy(p -> {
                    assertThat(p.promotionId()).isEqualTo(promoId);
                    assertThat(p.ruleVersion()).isEqualTo(1);
                    assertThat(p.calculatedAmount()).isEqualByComparingTo("16.00");
                });

        // 5. New evaluate after v2 publication immediately picks up ruleVersion: 2 (20% of 160.00 = 32.00)
        EvaluateResponse evalV2 = evaluate(userToken, userId, new BigDecimal("180.00"), new BigDecimal("160.00"), "corr-sc3-2");
        assertThat(evalV2.appliedPromotions())
                .anySatisfy(p -> {
                    assertThat(p.promotionId()).isEqualTo(promoId);
                    assertThat(p.ruleVersion()).isEqualTo(2);
                    assertThat(p.calculatedAmount()).isEqualByComparingTo("32.00");
                });

        // 6. Advance clock beyond 10-min TTL -> commit MUST fail with 422 EVALUATION_EXPIRED
        clock.advanceBySeconds(601);
        ResponseEntity<String> expiredResp = commitRaw(
                userToken, "ord_sc3_exp_" + System.nanoTime(), evalV2.evaluationId(), "chk_sc3_exp_" + System.nanoTime());
        assertThat(expiredResp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(expiredResp.getBody()).contains("EVALUATION_EXPIRED");
    }

    @Test
    @DisplayName("PRD Scenario 4: 5 concurrent commits with same checkoutKey consume quota once, return identical snapshot & emit 1 CashbackGrantedEvent")
    void shouldGuaranteeIdempotencyAndSingleQuotaConsumptionUnder5ConcurrentCommits() throws Exception {
        String seg = "SEG_CONC_" + System.nanoTime();
        String discountPromoId = "promo_conc_disc_" + System.nanoTime();
        String cashbackPromoId = "promo_conc_cb_" + System.nanoTime();
        String userId = "usr_conc_" + System.nanoTime();
        String userToken = jwtTokenFactory.issueToken(userId, Set.of(seg), Set.of("promotions:checkout"));

        // Drain any earlier outbox messages before testing SQS count
        outboxRelay.pollOnce();
        try {
            sqsClient.purgeQueue(PurgeQueueRequest.builder().queueUrl(sqsPublisher.queueUrl()).build());
        } catch (Exception ignored) {}

        // Publish 1 STACKABLE discount + 1 STACKABLE progressive cashback, both capped at maxUsagePerUser = 1
        publishRule(discountPromoId, new BigDecimal("10"),
                BenefitType.INSTANT_DISCOUNT, StackabilityMode.STACKABLE, 1, 1, 1, new BigDecimal("1000.00"), Set.of(seg));
        publishRule(cashbackPromoId, new BigDecimal("5"),
                BenefitType.CASHBACK, StackabilityMode.STACKABLE, 2, 2, 1, new BigDecimal("1000.00"), Set.of(seg));

        EvaluateResponse eval = evaluate(userToken, userId, new BigDecimal("180.00"), new BigDecimal("160.00"), "corr-conc");
        assertThat(eval.totalDiscountAmount()).isEqualByComparingTo("16.00");
        assertThat(eval.totalCashbackAmount()).isEqualByComparingTo("8.00");
        assertThat(eval.finalPayableAmount()).isEqualByComparingTo("164.00");

        String orderId = "ord_conc_" + System.nanoTime();
        String checkoutKey = "chk_conc_" + System.nanoTime();

        // Fire 5 simultaneous commit requests synchronized via CyclicBarrier
        int concurrency = 5;
        CyclicBarrier barrier = new CyclicBarrier(concurrency);
        List<DecisionSnapshotResponse> snapshots = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(concurrency)) {
            List<Callable<ResponseEntity<DecisionSnapshotResponse>>> tasks = new ArrayList<>();

            for (int i = 0; i < concurrency; i++) {
                tasks.add(() -> {
                    barrier.await();
                    return commit(userToken, orderId, eval.evaluationId(), checkoutKey);
                });
            }

            List<Future<ResponseEntity<DecisionSnapshotResponse>>> futures = executor.invokeAll(tasks);

            for (Future<ResponseEntity<DecisionSnapshotResponse>> future : futures) {
                ResponseEntity<DecisionSnapshotResponse> resp = future.get();
                assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
                snapshots.add(Objects.requireNonNull(resp.getBody()));
            }
        }

        // All 5 responses MUST be identical snapshots
        DecisionSnapshotResponse first = snapshots.getFirst();
        assertThat(snapshots).allSatisfy(s -> assertThat(s).isEqualTo(first));
        assertThat(first.orderId()).isEqualTo(orderId);
        assertThat(first.totalDiscountAmount()).isEqualByComparingTo("16.00");
        assertThat(first.totalCashbackAmount()).isEqualByComparingTo("8.00");
        assertThat(first.finalPayableAmount()).isEqualByComparingTo("164.00");

        // Verify DynamoDB QUOTA usageCount == 1 (consumed strictly once!)
        GetItemResponse quotaItem = dynamoDbClient.getItem(GetItemRequest.builder()
                .tableName("promotions_engine")
                .consistentRead(true)
                .key(Map.of(
                        DynamoDbKeyFactory.PK, AttributeValue.fromS(DynamoDbKeyFactory.quotaPk(discountPromoId, userId)),
                        DynamoDbKeyFactory.SK, AttributeValue.fromS(DynamoDbKeyFactory.usageSk())
                ))
                .build());
        assertThat(quotaItem.hasItem()).isTrue();
        assertThat(quotaItem.item().get("usageCount").n()).isEqualTo("1");

        // Verify DynamoDB BUDGET redemptionCount == 1 and spentCents == 1600
        GetItemResponse budgetItem = dynamoDbClient.getItem(GetItemRequest.builder()
                .tableName("promotions_engine")
                .consistentRead(true)
                .key(Map.of(
                        DynamoDbKeyFactory.PK, AttributeValue.fromS(DynamoDbKeyFactory.budgetPk(discountPromoId)),
                        DynamoDbKeyFactory.SK, AttributeValue.fromS(DynamoDbKeyFactory.totalSk())
                ))
                .build());
        assertThat(budgetItem.item().get("redemptionCount").n()).isEqualTo("1");
        assertThat(budgetItem.item().get("spentCents").n()).isEqualTo("1600");

        // Verify Transactional Outbox published exactly 1 CashbackGrantedEvent to SQS
        int relayed = outboxRelay.pollOnce();
        assertThat(relayed).isGreaterThanOrEqualTo(1);

        List<Message> messages = sqsClient.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(sqsPublisher.queueUrl())
                .maxNumberOfMessages(10)
                .waitTimeSeconds(2)
                .build()).messages();

        List<JsonNode> matchingEvents = new ArrayList<>();
        for (Message msg : messages) {
            JsonNode node = objectMapper.readTree(msg.body());
            if (orderId.equals(node.path("orderId").asText())) {
                matchingEvents.add(node);
            }
        }
        assertThat(matchingEvents).hasSize(1);
        assertThat(matchingEvents.getFirst().path("eventType").asText()).isEqualTo("CashbackGrantedEvent");
        assertThat(matchingEvents.getFirst().path("totalCashbackAmount").asText()).isEqualTo("8.00");

        // Verify GET /v1/promotions/orders/{orderId}/snapshot returns the persisted snapshot
        HttpHeaders headers = authHeaders(userToken);
        ResponseEntity<DecisionSnapshotResponse> fetched = restTemplate.exchange(
                "/v1/promotions/orders/" + orderId + "/snapshot",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                DecisionSnapshotResponse.class
        );
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody()).isEqualTo(first);
    }

    @Test
    @DisplayName("Scenario 5: Security (ownership 403, RBAC 403), Idempotency payload conflict (409) & Structured Audit Log")
    void shouldEnforceSecurityOwnershipIdempotencyConflictAndEmitStructuredAuditLogs() {
        Logger auditLogger = (Logger) LoggerFactory.getLogger("PROMOTION_AUDIT");
        ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
        listAppender.start();
        auditLogger.addAppender(listAppender);

        try {
            String seg = "SEG_SEC_" + System.nanoTime();
            String ownerId = "usr_owner_" + System.nanoTime();
            String attackerId = "usr_attacker_" + System.nanoTime();
            String ownerToken = jwtTokenFactory.issueToken(ownerId, Set.of(seg), Set.of("promotions:checkout"));
            String attackerToken = jwtTokenFactory.issueToken(attackerId, Set.of(seg), Set.of("promotions:checkout"));

            // Non-admin cannot publish rules -> 403 Forbidden
            ResponseEntity<String> unauthorizedPublish = restTemplate.exchange(
                    "/v1/admin/promotions",
                    HttpMethod.POST,
                    new HttpEntity<>(rulePayload("promo_unauth", new BigDecimal("10"),
                            BenefitType.INSTANT_DISCOUNT, StackabilityMode.EXCLUSIVE, 1, 1, null, null, Set.of(seg)),
                            authHeaders(ownerToken)),
                    String.class
            );
            assertThat(unauthorizedPublish.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

            // Publish 1 EXCLUSIVE + 1 STACKABLE for this segment to verify structured audit log
            publishRule("promo_sec_excl_" + System.nanoTime(), new BigDecimal("15"),
                    BenefitType.INSTANT_DISCOUNT, StackabilityMode.EXCLUSIVE, 1, 1, null, null, Set.of(seg));
            publishRule("promo_sec_stack_" + System.nanoTime(), new BigDecimal("5"),
                    BenefitType.CASHBACK, StackabilityMode.STACKABLE, 2, 2, null, null, Set.of(seg));

            EvaluateResponse eval = evaluate(ownerToken, ownerId, new BigDecimal("200.00"), new BigDecimal("200.00"), "corr-sec-audit");
            assertThat(eval.discardedEligiblePromotions())
                    .anySatisfy(d -> assertThat(d.reasonCode()).isEqualTo("EXCLUSIVE_PROMOTION_CONFLICT"));

            // Attacker trying to commit owner's evaluationId -> 403 Forbidden
            ResponseEntity<String> stolenCommit = commitRaw(
                    attackerToken, "ord_stolen_" + System.nanoTime(), eval.evaluationId(), "chk_stolen_" + System.nanoTime());
            assertThat(stolenCommit.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(stolenCommit.getBody()).contains("EVALUATION_OWNERSHIP_MISMATCH");

            // Owner commits successfully
            String checkoutKey = "chk_reuse_" + System.nanoTime();
            String orderId1 = "ord_first_" + System.nanoTime();
            assertThat(commit(ownerToken, orderId1, eval.evaluationId(), checkoutKey).getStatusCode())
                    .isEqualTo(HttpStatus.OK);

            // Reusing same checkoutKey with DIFFERENT orderId -> 409 IDEMPOTENCY_KEY_CONFLICT
            ResponseEntity<String> conflictResp = commitRaw(
                    ownerToken, "ord_different_" + System.nanoTime(), eval.evaluationId(), checkoutKey);
            assertThat(conflictResp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(conflictResp.getBody()).contains("IDEMPOTENCY_KEY_CONFLICT");

            // Verify structured audit log emitted with correlationId and EXCLUSIVE_PROMOTION_CONFLICT
            assertThat(listAppender.list)
                    .anySatisfy(event -> {
                        assertThat(event.getFormattedMessage()).contains("PROMOTION_EVALUATION_COMPLETED");
                        assertThat(event.getArgumentArray()).isNotEmpty();
                        String argsStr = java.util.Arrays.toString(event.getArgumentArray());
                        assertThat(argsStr).contains("corr-sec-audit");
                        assertThat(argsStr).contains("EXCLUSIVE_PROMOTION_CONFLICT");
                    });
        } finally {
            auditLogger.detachAppender(listAppender);
        }
    }

    private ResponseEntity<PromotionRuleResponse> publishRule(
            String promoId,
            BigDecimal percent,
            BenefitType benefitType,
            StackabilityMode stackability,
            int priorityRank,
            int precedenceOrder,
            Integer maxUsagePerUser,
            BigDecimal maxCampaignBudget,
            Set<String> segments
    ) {
        PublishRuleRequest req = rulePayload(
                promoId, percent, benefitType, stackability, priorityRank, precedenceOrder, maxUsagePerUser, maxCampaignBudget, segments);
        return restTemplate.exchange(
                "/v1/admin/promotions",
                HttpMethod.POST,
                new HttpEntity<>(req, authHeaders(adminToken)),
                PromotionRuleResponse.class
        );
    }

    private PublishRuleRequest rulePayload(
            String promoId,
            BigDecimal percent,
            BenefitType benefitType,
            StackabilityMode stackability,
            int priorityRank,
            int precedenceOrder,
            Integer maxUsagePerUser,
            BigDecimal maxCampaignBudget,
            Set<String> segments
    ) {
        return new PublishRuleRequest(
                promoId,
                "Campaign " + promoId,
                Instant.parse("2026-11-01T00:00:00Z"),
                Instant.parse("2026-12-31T23:59:59Z"),
                benefitType,
                stackability,
                priorityRank,
                precedenceOrder,
                CalculationBase.ELIGIBLE_GROSS,
                new BigDecimal("50.00"),
                new BigDecimal("200.00"),
                maxUsagePerUser,
                maxCampaignBudget,
                segments,
                Set.of(),
                List.of(
                        new ProgressiveTierRequest("tier_50_99", new BigDecimal("50.00"), new BigDecimal("99.99"),
                                TierBenefitMode.PERCENTAGE, percent.divide(BigDecimal.valueOf(2))),
                        new ProgressiveTierRequest("tier_100_plus", new BigDecimal("100.00"), null,
                                TierBenefitMode.PERCENTAGE, percent)
                )
        );
    }

    private EvaluateResponse evaluate(String token, String userId, BigDecimal gross, BigDecimal eligible, String correlationId) {
        HttpHeaders headers = authHeaders(token);
        headers.set("X-Correlation-Id", correlationId);
        EvaluateRequest req = new EvaluateRequest(
                userId,
                gross,
                eligible,
                List.of(new CartItemDto("ITEM-01", "RESTAURANTS", eligible)),
                ConflictPolicy.PRIORITY_RANK
        );
        ResponseEntity<EvaluateResponse> resp = restTemplate.exchange(
                "/v1/promotions/evaluate",
                HttpMethod.POST,
                new HttpEntity<>(req, headers),
                EvaluateResponse.class
        );
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return Objects.requireNonNull(resp.getBody());
    }

    private ResponseEntity<DecisionSnapshotResponse> commit(String token, String orderId, String evaluationId, String checkoutKey) {
        HttpHeaders headers = authHeaders(token);
        headers.set("Idempotency-Key", checkoutKey);
        return restTemplate.exchange(
                "/v1/promotions/commit",
                HttpMethod.POST,
                new HttpEntity<>(new CommitRequest(orderId, evaluationId), headers),
                DecisionSnapshotResponse.class
        );
    }

    private ResponseEntity<String> commitRaw(String token, String orderId, String evaluationId, String checkoutKey) {
        HttpHeaders headers = authHeaders(token);
        headers.set("Idempotency-Key", checkoutKey);
        return restTemplate.exchange(
                "/v1/promotions/commit",
                HttpMethod.POST,
                new HttpEntity<>(new CommitRequest(orderId, evaluationId), headers),
                String.class
        );
    }

    private HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(Objects.requireNonNull(token));
        return headers;
    }
}
