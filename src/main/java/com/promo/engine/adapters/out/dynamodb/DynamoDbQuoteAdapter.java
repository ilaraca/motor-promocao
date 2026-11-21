package com.promo.engine.adapters.out.dynamodb;

import com.promo.engine.application.port.out.EvaluationQuoteRepository;
import com.promo.engine.domain.model.EvaluationQuote;
import com.promo.engine.domain.model.Money;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class DynamoDbQuoteAdapter implements EvaluationQuoteRepository {

    // Keep item 1h past business TTL for troubleshooting; app strictly enforces expiresAt
    private static final long HOUSEKEEPING_GRACE_SECONDS = 3600L;

    private final DynamoDbClient dynamoDb;
    private final String tableName;
    private final DynamoDbJsonCodec codec;

    public DynamoDbQuoteAdapter(DynamoDbClient dynamoDb, String tableName, DynamoDbJsonCodec codec) {
        this.dynamoDb = dynamoDb;
        this.tableName = tableName;
        this.codec = codec;
    }

    @Override
    public void save(EvaluationQuote quote) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(DynamoDbKeyFactory.PK, AttributeValue.fromS(DynamoDbKeyFactory.evalPk(quote.evaluationId())));
        item.put(DynamoDbKeyFactory.SK, AttributeValue.fromS(DynamoDbKeyFactory.quoteSk()));
        item.put(DynamoDbKeyFactory.ENTITY_TYPE, AttributeValue.fromS("EVALUATION_QUOTE"));
        item.put("evaluationId", AttributeValue.fromS(quote.evaluationId()));
        item.put("userId", AttributeValue.fromS(quote.userId()));
        item.put("evaluatedAt", AttributeValue.fromS(quote.evaluatedAt().toString()));
        item.put("expiresAt", AttributeValue.fromS(quote.expiresAt().toString()));
        item.put(DynamoDbKeyFactory.TTL_ATTR, AttributeValue.fromN(
                Long.toString(quote.expiresAt().getEpochSecond() + HOUSEKEEPING_GRACE_SECONDS)));
        item.put("grossAmount", AttributeValue.fromS(quote.grossAmount().toString()));
        item.put("eligibleAmount", AttributeValue.fromS(quote.eligibleAmount().toString()));
        item.put("totalDiscountAmount", AttributeValue.fromS(quote.totalDiscountAmount().toString()));
        item.put("totalCashbackAmount", AttributeValue.fromS(quote.totalCashbackAmount().toString()));
        item.put("finalPayableAmount", AttributeValue.fromS(quote.finalPayableAmount().toString()));
        item.put("appliedJson", AttributeValue.fromS(codec.writeAppliedPromotions(quote.appliedPromotions())));
        item.put("discardedJson", AttributeValue.fromS(codec.writeDiscardedEligible(quote.discardedEligiblePromotions())));
        item.put("candidatesJson", AttributeValue.fromS(codec.writeCandidates(quote.candidateDecisions())));
        item.put("frozenRefsJson", AttributeValue.fromS(codec.writeFrozenRefs(quote.frozenRuleRefs())));

        dynamoDb.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(item)
                .build());
    }

    @Override
    public Optional<EvaluationQuote> findById(String evaluationId) {
        GetItemResponse response = dynamoDb.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .consistentRead(true)
                .key(Map.of(
                        DynamoDbKeyFactory.PK, AttributeValue.fromS(DynamoDbKeyFactory.evalPk(evaluationId)),
                        DynamoDbKeyFactory.SK, AttributeValue.fromS(DynamoDbKeyFactory.quoteSk())
                ))
                .build());

        if (!response.hasItem() || response.item().isEmpty()) {
            return Optional.empty();
        }
        Map<String, AttributeValue> item = response.item();
        return Optional.of(new EvaluationQuote(
                item.get("evaluationId").s(),
                item.get("userId").s(),
                Instant.parse(item.get("evaluatedAt").s()),
                Instant.parse(item.get("expiresAt").s()),
                Money.of(item.get("grossAmount").s()),
                Money.of(item.get("eligibleAmount").s()),
                Money.of(item.get("totalDiscountAmount").s()),
                Money.of(item.get("totalCashbackAmount").s()),
                Money.of(item.get("finalPayableAmount").s()),
                codec.readAppliedPromotions(item.get("appliedJson").s()),
                codec.readDiscardedEligible(item.get("discardedJson").s()),
                codec.readCandidates(item.get("candidatesJson").s()),
                codec.readFrozenRefs(item.get("frozenRefsJson").s())
        ));
    }
}
