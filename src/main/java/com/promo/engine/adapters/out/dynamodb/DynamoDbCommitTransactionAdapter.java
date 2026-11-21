package com.promo.engine.adapters.out.dynamodb;

import com.promo.engine.application.port.out.CommitTransactionPort;
import com.promo.engine.domain.exception.IdempotencyConflictException;
import com.promo.engine.domain.exception.QuotaExceededOnCommitException;
import com.promo.engine.domain.model.AppliedPromotion;
import com.promo.engine.domain.model.CashbackGrantEvent;
import com.promo.engine.domain.model.DecisionSnapshot;
import com.promo.engine.domain.model.FrozenRuleRef;
import com.promo.engine.domain.model.ReasonCode;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.Update;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class DynamoDbCommitTransactionAdapter implements CommitTransactionPort {

    private static final long IDEMPOTENCY_TTL_SECONDS = Duration.ofDays(30).toSeconds();
    private static final long OUTBOX_TTL_SECONDS = Duration.ofDays(7).toSeconds();
    private static final int MAX_CONFLICT_RETRIES = 4;

    private final DynamoDbClient dynamoDb;
    private final String tableName;
    private final DynamoDbJsonCodec codec;

    public DynamoDbCommitTransactionAdapter(DynamoDbClient dynamoDb, String tableName, DynamoDbJsonCodec codec) {
        this.dynamoDb = dynamoDb;
        this.tableName = tableName;
        this.codec = codec;
    }

    @Override
    public Optional<IdempotentRecord> findIdempotentCommit(String checkoutKey) {
        GetItemResponse response = dynamoDb.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .consistentRead(true)
                .key(Map.of(
                        DynamoDbKeyFactory.PK, AttributeValue.fromS(DynamoDbKeyFactory.idempotencyPk(checkoutKey)),
                        DynamoDbKeyFactory.SK, AttributeValue.fromS(DynamoDbKeyFactory.commitSk())
                ))
                .build());

        if (!response.hasItem() || response.item().isEmpty()) {
            return Optional.empty();
        }
        Map<String, AttributeValue> item = response.item();
        return Optional.of(new IdempotentRecord(
                checkoutKey,
                item.get("orderId").s(),
                item.get("evaluationId").s(),
                item.get("payloadHash").s(),
                codec.readSnapshot(item.get("snapshotJson").s())
        ));
    }

    @Override
    public Optional<DecisionSnapshot> findOrderSnapshot(String orderId) {
        GetItemResponse response = dynamoDb.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .consistentRead(true)
                .key(Map.of(
                        DynamoDbKeyFactory.PK, AttributeValue.fromS(DynamoDbKeyFactory.orderPk(orderId)),
                        DynamoDbKeyFactory.SK, AttributeValue.fromS(DynamoDbKeyFactory.snapshotSk())
                ))
                .build());

        if (!response.hasItem() || response.item().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(codec.readSnapshot(response.item().get("snapshotJson").s()));
    }

    @Override
    public TransactionCommitOutcome commitAtomically(AtomicCommitCommand command) {
        DecisionSnapshot snapshot = command.snapshot();
        String snapshotJson = codec.writeSnapshot(snapshot);

        Map<String, FrozenRuleRef> frozenByPromoId = command.frozenRuleRefs().stream()
                .collect(Collectors.toMap(FrozenRuleRef::promotionId, Function.identity(), (a, b) -> a));

        List<TransactWriteItem> txItems = new ArrayList<>();
        List<TxSlot> slots = new ArrayList<>();

        // Slot 0: Idempotency Lock
        Map<String, AttributeValue> idempItem = Map.of(
                DynamoDbKeyFactory.PK, AttributeValue.fromS(DynamoDbKeyFactory.idempotencyPk(command.checkoutKey())),
                DynamoDbKeyFactory.SK, AttributeValue.fromS(DynamoDbKeyFactory.commitSk()),
                DynamoDbKeyFactory.ENTITY_TYPE, AttributeValue.fromS("IDEMPOTENCY_COMMIT"),
                "orderId", AttributeValue.fromS(snapshot.orderId()),
                "evaluationId", AttributeValue.fromS(snapshot.evaluationId()),
                "payloadHash", AttributeValue.fromS(command.payloadHash()),
                "committedAt", AttributeValue.fromS(snapshot.committedAt().toString()),
                "snapshotJson", AttributeValue.fromS(snapshotJson),
                DynamoDbKeyFactory.TTL_ATTR, AttributeValue.fromN(
                        Long.toString(snapshot.committedAt().getEpochSecond() + IDEMPOTENCY_TTL_SECONDS))
        );
        txItems.add(TransactWriteItem.builder()
                .put(Put.builder()
                        .tableName(tableName)
                        .item(idempItem)
                        .conditionExpression("attribute_not_exists(PK)")
                        .build())
                .build());
        slots.add(new TxSlot(SlotType.IDEMPOTENCY, command.checkoutKey()));

        // Slot 1: Immutable Order Snapshot
        Map<String, AttributeValue> snapshotItem = Map.of(
                DynamoDbKeyFactory.PK, AttributeValue.fromS(DynamoDbKeyFactory.orderPk(snapshot.orderId())),
                DynamoDbKeyFactory.SK, AttributeValue.fromS(DynamoDbKeyFactory.snapshotSk()),
                DynamoDbKeyFactory.ENTITY_TYPE, AttributeValue.fromS("ORDER_PROMO_SNAPSHOT"),
                "orderId", AttributeValue.fromS(snapshot.orderId()),
                "userId", AttributeValue.fromS(snapshot.userId()),
                "evaluationId", AttributeValue.fromS(snapshot.evaluationId()),
                "checkoutKey", AttributeValue.fromS(snapshot.checkoutKey()),
                "committedAt", AttributeValue.fromS(snapshot.committedAt().toString()),
                "snapshotJson", AttributeValue.fromS(snapshotJson)
        );
        txItems.add(TransactWriteItem.builder()
                .put(Put.builder()
                        .tableName(tableName)
                        .item(snapshotItem)
                        .conditionExpression("attribute_not_exists(PK)")
                        .build())
                .build());
        slots.add(new TxSlot(SlotType.ORDER_SNAPSHOT, snapshot.orderId()));

        // Slots 2..N: Atomic User Quota and Campaign Budget updates for each applied promotion
        for (AppliedPromotion applied : snapshot.appliedPromotions()) {
            FrozenRuleRef ref = frozenByPromoId.get(applied.promotionId());
            long benefitCents = applied.calculatedAmount().toCents();

            // User Quota update
            Update.Builder quotaUpdate = Update.builder()
                    .tableName(tableName)
                    .key(Map.of(
                            DynamoDbKeyFactory.PK, AttributeValue.fromS(
                                    DynamoDbKeyFactory.quotaPk(applied.promotionId(), snapshot.userId())),
                            DynamoDbKeyFactory.SK, AttributeValue.fromS(DynamoDbKeyFactory.usageSk())
                    ))
                    .updateExpression(
                            "SET usageCount = if_not_exists(usageCount, :zero) + :one, "
                                    + "totalBenefitUsedCents = if_not_exists(totalBenefitUsedCents, :zero) + :benefit, "
                                    + "updatedAt = :now, "
                                    + "entityType = :etype");

            Map<String, AttributeValue> quotaValues = new HashMap<>();
            quotaValues.put(":zero", AttributeValue.fromN("0"));
            quotaValues.put(":one", AttributeValue.fromN("1"));
            quotaValues.put(":benefit", AttributeValue.fromN(Long.toString(benefitCents)));
            quotaValues.put(":now", AttributeValue.fromS(snapshot.committedAt().toString()));
            quotaValues.put(":etype", AttributeValue.fromS("USER_QUOTA"));

            if (ref != null && ref.maxUsagePerUser() != null) {
                quotaUpdate.conditionExpression("attribute_not_exists(usageCount) OR usageCount < :maxUsage");
                quotaValues.put(":maxUsage", AttributeValue.fromN(Integer.toString(ref.maxUsagePerUser())));
            }
            quotaUpdate.expressionAttributeValues(quotaValues);
            txItems.add(TransactWriteItem.builder().update(quotaUpdate.build()).build());
            slots.add(new TxSlot(SlotType.USER_QUOTA, applied.promotionId()));

            // Campaign Budget update
            Update.Builder budgetUpdate = Update.builder()
                    .tableName(tableName)
                    .key(Map.of(
                            DynamoDbKeyFactory.PK, AttributeValue.fromS(DynamoDbKeyFactory.budgetPk(applied.promotionId())),
                            DynamoDbKeyFactory.SK, AttributeValue.fromS(DynamoDbKeyFactory.totalSk())
                    ))
                    .updateExpression(
                            "SET spentCents = if_not_exists(spentCents, :zero) + :benefit, "
                                    + "redemptionCount = if_not_exists(redemptionCount, :zero) + :one, "
                                    + "updatedAt = :now, "
                                    + "entityType = :etype");

            Map<String, AttributeValue> budgetValues = new HashMap<>();
            budgetValues.put(":zero", AttributeValue.fromN("0"));
            budgetValues.put(":one", AttributeValue.fromN("1"));
            budgetValues.put(":benefit", AttributeValue.fromN(Long.toString(benefitCents)));
            budgetValues.put(":now", AttributeValue.fromS(snapshot.committedAt().toString()));
            budgetValues.put(":etype", AttributeValue.fromS("CAMPAIGN_BUDGET"));

            if (ref != null && ref.maxCampaignBudget() != null) {
                long maxAllowedBefore = ref.maxCampaignBudget().toCents() - benefitCents;
                if (maxAllowedBefore < 0) {
                    throw new QuotaExceededOnCommitException(
                            ReasonCode.CAMPAIGN_BUDGET_EXHAUSTED,
                            "Campaign budget insufficient for promotion " + applied.promotionId());
                }
                budgetUpdate.conditionExpression("attribute_not_exists(spentCents) OR spentCents <= :maxBefore");
                budgetValues.put(":maxBefore", AttributeValue.fromN(Long.toString(maxAllowedBefore)));
            }
            budgetUpdate.expressionAttributeValues(budgetValues);
            txItems.add(TransactWriteItem.builder().update(budgetUpdate.build()).build());
            slots.add(new TxSlot(SlotType.CAMPAIGN_BUDGET, applied.promotionId()));
        }

        // Optional final slot: Transactional Outbox event when order grants CASHBACK
        if (command.cashbackOutboxEvent() != null) {
            CashbackGrantEvent event = command.cashbackOutboxEvent();
            Map<String, AttributeValue> outboxItem = Map.of(
                    DynamoDbKeyFactory.PK, AttributeValue.fromS(DynamoDbKeyFactory.outboxPk(event.eventId())),
                    DynamoDbKeyFactory.SK, AttributeValue.fromS(DynamoDbKeyFactory.cashbackOutboxSk()),
                    DynamoDbKeyFactory.ENTITY_TYPE, AttributeValue.fromS(DynamoDbKeyFactory.ENTITY_OUTBOX_EVENT),
                    "eventId", AttributeValue.fromS(event.eventId()),
                    "eventType", AttributeValue.fromS("CashbackGrantedEvent"),
                    "orderId", AttributeValue.fromS(event.orderId()),
                    "userId", AttributeValue.fromS(event.userId()),
                    "payloadJson", AttributeValue.fromS(codec.writeCashbackEvent(event)),
                    "createdAt", AttributeValue.fromS(event.occurredAt().toString()),
                    DynamoDbKeyFactory.TTL_ATTR, AttributeValue.fromN(
                            Long.toString(event.occurredAt().getEpochSecond() + OUTBOX_TTL_SECONDS))
            );
            txItems.add(TransactWriteItem.builder()
                    .put(Put.builder()
                            .tableName(tableName)
                            .item(outboxItem)
                            .conditionExpression("attribute_not_exists(PK)")
                            .build())
                    .build());
            slots.add(new TxSlot(SlotType.OUTBOX, event.eventId()));
        }

        return executeWithConflictRecovery(command, txItems, slots);
    }

    private TransactionCommitOutcome executeWithConflictRecovery(
            AtomicCommitCommand command,
            List<TransactWriteItem> txItems,
            List<TxSlot> slots
    ) {
        for (int attempt = 1; attempt <= MAX_CONFLICT_RETRIES; attempt++) {
            try {
                dynamoDb.transactWriteItems(TransactWriteItemsRequest.builder()
                        .transactItems(txItems)
                        .build());
                return new TransactionCommitOutcome(command.snapshot(), false, false);
            } catch (TransactionCanceledException ex) {
                List<CancellationReason> reasons = ex.cancellationReasons();

                // 1. Check if Idempotency lock already exists (concurrent duplicate checkoutKey won!)
                if (hasReason(reasons, 0, "ConditionalCheckFailed")) {
                    return recoverIdempotentRecord(command);
                }

                // 2. Check for transient TransactionConflict (simultaneous in-flight write on same key)
                boolean hasConflict = reasons.stream()
                        .anyMatch(r -> "TransactionConflict".equals(r.code()));
                if (hasConflict) {
                    backoffJitter(attempt);
                    Optional<IdempotentRecord> existing = findIdempotentCommit(command.checkoutKey());
                    if (existing.isPresent()) {
                        return validateAndReturnIdempotent(command, existing.get());
                    }
                    if (attempt < MAX_CONFLICT_RETRIES) {
                        continue;
                    }
                }

                // 3. Check specific quota / budget / order snapshot failures
                for (int i = 0; i < Math.min(reasons.size(), slots.size()); i++) {
                    if ("ConditionalCheckFailed".equals(reasons.get(i).code())) {
                        TxSlot slot = slots.get(i);
                        switch (slot.type()) {
                            case USER_QUOTA -> throw new QuotaExceededOnCommitException(
                                    ReasonCode.USER_CAP_REACHED,
                                    "User usage cap reached on commit for promotion " + slot.refId());
                            case CAMPAIGN_BUDGET -> throw new QuotaExceededOnCommitException(
                                    ReasonCode.CAMPAIGN_BUDGET_EXHAUSTED,
                                    "Campaign budget exhausted on commit for promotion " + slot.refId());
                            case ORDER_SNAPSHOT -> throw new IdempotencyConflictException(command.checkoutKey());
                            default -> {}
                        }
                    }
                }

                // Last-chance check in case LocalStack reported generic cancellation on concurrent duplicate
                Optional<IdempotentRecord> fallback = findIdempotentCommit(command.checkoutKey());
                if (fallback.isPresent()) {
                    return validateAndReturnIdempotent(command, fallback.get());
                }
                throw ex;
            }
        }
        return recoverIdempotentRecord(command);
    }

    private TransactionCommitOutcome recoverIdempotentRecord(AtomicCommitCommand command) {
        for (int i = 0; i < 3; i++) {
            Optional<IdempotentRecord> existing = findIdempotentCommit(command.checkoutKey());
            if (existing.isPresent()) {
                return validateAndReturnIdempotent(command, existing.get());
            }
            backoffJitter(i + 1);
        }
        throw new IllegalStateException(
                "Idempotency lock failed ConditionalCheckFailed but record was not found for " + command.checkoutKey());
    }

    private TransactionCommitOutcome validateAndReturnIdempotent(
            AtomicCommitCommand command,
            IdempotentRecord record
    ) {
        if (!record.payloadHash().equals(command.payloadHash())) {
            throw new IdempotencyConflictException(command.checkoutKey());
        }
        return new TransactionCommitOutcome(record.snapshot(), true, true);
    }

    private boolean hasReason(List<CancellationReason> reasons, int idx, String code) {
        return idx < reasons.size() && code.equals(reasons.get(idx).code());
    }

    private void backoffJitter(int attempt) {
        try {
            long delayMs = ThreadLocalRandom.current().nextLong(15L * attempt, 45L * attempt);
            Thread.sleep(delayMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private enum SlotType {
        IDEMPOTENCY,
        ORDER_SNAPSHOT,
        USER_QUOTA,
        CAMPAIGN_BUDGET,
        OUTBOX
    }

    private record TxSlot(SlotType type, String refId) {}
}
