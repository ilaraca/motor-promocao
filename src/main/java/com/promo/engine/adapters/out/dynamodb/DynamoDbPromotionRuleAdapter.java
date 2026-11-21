package com.promo.engine.adapters.out.dynamodb;

import com.promo.engine.application.port.out.PromotionRuleRepository;
import com.promo.engine.domain.exception.RuleVersionConflictException;
import com.promo.engine.domain.model.BenefitType;
import com.promo.engine.domain.model.CalculationBase;
import com.promo.engine.domain.model.Money;
import com.promo.engine.domain.model.PromotionRule;
import com.promo.engine.domain.model.RuleStatus;
import com.promo.engine.domain.model.StackabilityMode;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.Update;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class DynamoDbPromotionRuleAdapter implements PromotionRuleRepository {

    private final DynamoDbClient dynamoDb;
    private final String tableName;
    private final DynamoDbJsonCodec codec;

    public DynamoDbPromotionRuleAdapter(DynamoDbClient dynamoDb, String tableName, DynamoDbJsonCodec codec) {
        this.dynamoDb = dynamoDb;
        this.tableName = tableName;
        this.codec = codec;
    }

    @Override
    public List<PromotionRule> findAllActive() {
        List<PromotionRule> rules = new ArrayList<>();
        Map<String, AttributeValue> lastKey = null;

        do {
            QueryRequest.Builder req = QueryRequest.builder()
                    .tableName(tableName)
                    .indexName(DynamoDbKeyFactory.GSI1_NAME)
                    .keyConditionExpression("GSI1PK = :pk")
                    .expressionAttributeValues(Map.of(
                            ":pk", AttributeValue.fromS(DynamoDbKeyFactory.ACTIVE_PROMOS_PARTITION)
                    ));
            if (lastKey != null && !lastKey.isEmpty()) {
                req.exclusiveStartKey(lastKey);
            }
            QueryResponse response = dynamoDb.query(req.build());
            for (Map<String, AttributeValue> item : response.items()) {
                rules.add(fromItem(item));
            }
            lastKey = response.lastEvaluatedKey();
        } while (lastKey != null && !lastKey.isEmpty());

        return rules;
    }

    @Override
    public Optional<Integer> findLatestVersion(String promotionId) {
        GetItemResponse response = dynamoDb.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .consistentRead(true)
                .key(Map.of(
                        DynamoDbKeyFactory.PK, AttributeValue.fromS(DynamoDbKeyFactory.promoPk(promotionId)),
                        DynamoDbKeyFactory.SK, AttributeValue.fromS(DynamoDbKeyFactory.metadataSk())
                ))
                .build());
        if (!response.hasItem() || response.item().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(Integer.parseInt(response.item().get("latestVersion").n()));
    }

    @Override
    public PromotionRule publishNewVersion(PromotionRule newRule, Integer previousVersion) {
        List<TransactWriteItem> txItems = new ArrayList<>();

        // 1. Insert immutable new version (with sparse GSI1 attributes since status == ACTIVE)
        txItems.add(TransactWriteItem.builder()
                .put(Put.builder()
                        .tableName(tableName)
                        .item(toVersionItem(newRule))
                        .conditionExpression("attribute_not_exists(PK)")
                        .build())
                .build());

        if (previousVersion == null) {
            // 2a. First version: create METADATA item
            Map<String, AttributeValue> metadata = Map.of(
                    DynamoDbKeyFactory.PK, AttributeValue.fromS(DynamoDbKeyFactory.promoPk(newRule.promotionId())),
                    DynamoDbKeyFactory.SK, AttributeValue.fromS(DynamoDbKeyFactory.metadataSk()),
                    DynamoDbKeyFactory.ENTITY_TYPE, AttributeValue.fromS("PROMO_METADATA"),
                    "latestVersion", AttributeValue.fromN(Integer.toString(newRule.ruleVersion())),
                    "updatedAt", AttributeValue.fromS(newRule.createdAt().toString()),
                    "updatedBy", AttributeValue.fromS(newRule.createdBy())
            );
            txItems.add(TransactWriteItem.builder()
                    .put(Put.builder()
                            .tableName(tableName)
                            .item(metadata)
                            .conditionExpression("attribute_not_exists(PK)")
                            .build())
                    .build());
        } else {
            // 2b. Optimistic lock update on METADATA
            txItems.add(TransactWriteItem.builder()
                    .update(Update.builder()
                            .tableName(tableName)
                            .key(Map.of(
                                    DynamoDbKeyFactory.PK, AttributeValue.fromS(DynamoDbKeyFactory.promoPk(newRule.promotionId())),
                                    DynamoDbKeyFactory.SK, AttributeValue.fromS(DynamoDbKeyFactory.metadataSk())
                            ))
                            .updateExpression("SET latestVersion = :next, updatedAt = :now, updatedBy = :actor")
                            .conditionExpression("latestVersion = :prev")
                            .expressionAttributeValues(Map.of(
                                    ":next", AttributeValue.fromN(Integer.toString(newRule.ruleVersion())),
                                    ":prev", AttributeValue.fromN(Integer.toString(previousVersion)),
                                    ":now", AttributeValue.fromS(newRule.createdAt().toString()),
                                    ":actor", AttributeValue.fromS(newRule.createdBy())
                            ))
                            .build())
                    .build());

            // 3. Mark previous version SUPERSEDED and remove from sparse GSI1
            txItems.add(TransactWriteItem.builder()
                    .update(Update.builder()
                            .tableName(tableName)
                            .key(Map.of(
                                    DynamoDbKeyFactory.PK, AttributeValue.fromS(DynamoDbKeyFactory.promoPk(newRule.promotionId())),
                                    DynamoDbKeyFactory.SK, AttributeValue.fromS(DynamoDbKeyFactory.versionSk(previousVersion))
                            ))
                            .updateExpression("SET #st = :superseded REMOVE GSI1PK, GSI1SK")
                            .expressionAttributeNames(Map.of("#st", "status"))
                            .expressionAttributeValues(Map.of(
                                    ":superseded", AttributeValue.fromS(RuleStatus.SUPERSEDED.name())
                            ))
                            .build())
                    .build());
        }

        try {
            dynamoDb.transactWriteItems(TransactWriteItemsRequest.builder()
                    .transactItems(txItems)
                    .build());
            return newRule;
        } catch (TransactionCanceledException ex) {
            throw new RuleVersionConflictException(newRule.promotionId(), newRule.ruleVersion());
        }
    }

    private Map<String, AttributeValue> toVersionItem(PromotionRule rule) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(DynamoDbKeyFactory.PK, AttributeValue.fromS(DynamoDbKeyFactory.promoPk(rule.promotionId())));
        item.put(DynamoDbKeyFactory.SK, AttributeValue.fromS(DynamoDbKeyFactory.versionSk(rule.ruleVersion())));
        item.put(DynamoDbKeyFactory.ENTITY_TYPE, AttributeValue.fromS("PROMO_RULE_VERSION"));

        if (rule.status() == RuleStatus.ACTIVE) {
            item.put(DynamoDbKeyFactory.GSI1_PK, AttributeValue.fromS(DynamoDbKeyFactory.ACTIVE_PROMOS_PARTITION));
            item.put(DynamoDbKeyFactory.GSI1_SK, AttributeValue.fromS(
                    DynamoDbKeyFactory.activeGsiSk(rule.promotionId(), rule.ruleVersion())));
        }

        item.put("promotionId", AttributeValue.fromS(rule.promotionId()));
        item.put("ruleVersion", AttributeValue.fromN(Integer.toString(rule.ruleVersion())));
        item.put("status", AttributeValue.fromS(rule.status().name()));
        item.put("name", AttributeValue.fromS(rule.name() != null ? rule.name() : rule.promotionId()));
        item.put("validFrom", AttributeValue.fromS(rule.validFrom().toString()));
        item.put("validTo", AttributeValue.fromS(rule.validTo().toString()));
        item.put("benefitType", AttributeValue.fromS(rule.benefitType().name()));
        item.put("stackabilityMode", AttributeValue.fromS(rule.stackabilityMode().name()));
        item.put("priorityRank", AttributeValue.fromN(Integer.toString(rule.priorityRank())));
        item.put("precedenceOrder", AttributeValue.fromN(Integer.toString(rule.precedenceOrder())));
        item.put("calculationBase", AttributeValue.fromS(rule.calculationBase().name()));
        item.put("minOrderValue", AttributeValue.fromS(rule.minOrderValue().toString()));
        if (rule.maxBenefitAmount() != null) {
            item.put("maxBenefitAmount", AttributeValue.fromS(rule.maxBenefitAmount().toString()));
        }
        if (rule.maxUsagePerUser() != null) {
            item.put("maxUsagePerUser", AttributeValue.fromN(Integer.toString(rule.maxUsagePerUser())));
        }
        if (rule.maxCampaignBudget() != null) {
            item.put("maxCampaignBudget", AttributeValue.fromS(rule.maxCampaignBudget().toString()));
        }
        if (!rule.eligibleSegments().isEmpty()) {
            item.put("eligibleSegments", AttributeValue.fromSs(new ArrayList<>(rule.eligibleSegments())));
        }
        if (!rule.eligibleCategories().isEmpty()) {
            item.put("eligibleCategories", AttributeValue.fromSs(new ArrayList<>(rule.eligibleCategories())));
        }
        item.put("tiersJson", AttributeValue.fromS(codec.writeTiers(rule.tiers())));
        item.put("createdBy", AttributeValue.fromS(rule.createdBy()));
        item.put("createdAt", AttributeValue.fromS(rule.createdAt().toString()));
        return item;
    }

    private PromotionRule fromItem(Map<String, AttributeValue> item) {
        return new PromotionRule(
                item.get("promotionId").s(),
                Integer.parseInt(item.get("ruleVersion").n()),
                RuleStatus.valueOf(item.get("status").s()),
                item.containsKey("name") ? item.get("name").s() : item.get("promotionId").s(),
                Instant.parse(item.get("validFrom").s()),
                Instant.parse(item.get("validTo").s()),
                BenefitType.valueOf(item.get("benefitType").s()),
                StackabilityMode.valueOf(item.get("stackabilityMode").s()),
                Integer.parseInt(item.get("priorityRank").n()),
                Integer.parseInt(item.get("precedenceOrder").n()),
                CalculationBase.valueOf(item.get("calculationBase").s()),
                Money.of(item.get("minOrderValue").s()),
                item.containsKey("maxBenefitAmount") ? Money.of(item.get("maxBenefitAmount").s()) : null,
                item.containsKey("maxUsagePerUser") ? Integer.parseInt(item.get("maxUsagePerUser").n()) : null,
                item.containsKey("maxCampaignBudget") ? Money.of(item.get("maxCampaignBudget").s()) : null,
                item.containsKey("eligibleSegments") ? Set.copyOf(item.get("eligibleSegments").ss()) : Set.of(),
                item.containsKey("eligibleCategories") ? Set.copyOf(item.get("eligibleCategories").ss()) : Set.of(),
                codec.readTiers(item.get("tiersJson").s()),
                item.containsKey("createdBy") ? item.get("createdBy").s() : "system",
                item.containsKey("createdAt") ? Instant.parse(item.get("createdAt").s()) : Instant.EPOCH
        );
    }
}
