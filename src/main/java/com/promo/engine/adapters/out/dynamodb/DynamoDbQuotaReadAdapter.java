package com.promo.engine.adapters.out.dynamodb;

import com.promo.engine.application.port.out.QuotaReadRepository;
import com.promo.engine.domain.model.Money;
import com.promo.engine.domain.model.QuotaSnapshot;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class DynamoDbQuotaReadAdapter implements QuotaReadRepository {

    private static final int MAX_BATCH_KEYS = 100;

    private final DynamoDbClient dynamoDb;
    private final String tableName;

    public DynamoDbQuotaReadAdapter(DynamoDbClient dynamoDb, String tableName) {
        this.dynamoDb = dynamoDb;
        this.tableName = tableName;
    }

    @Override
    public Map<String, QuotaSnapshot> findQuotasAndBudgets(String userId, Set<String> promotionIds) {
        if (promotionIds.isEmpty()) {
            return Map.of();
        }

        List<Map<String, AttributeValue>> allKeys = new ArrayList<>(promotionIds.size() * 2);
        for (String promoId : promotionIds) {
            allKeys.add(Map.of(
                    DynamoDbKeyFactory.PK, AttributeValue.fromS(DynamoDbKeyFactory.quotaPk(promoId, userId)),
                    DynamoDbKeyFactory.SK, AttributeValue.fromS(DynamoDbKeyFactory.usageSk())
            ));
            allKeys.add(Map.of(
                    DynamoDbKeyFactory.PK, AttributeValue.fromS(DynamoDbKeyFactory.budgetPk(promoId)),
                    DynamoDbKeyFactory.SK, AttributeValue.fromS(DynamoDbKeyFactory.totalSk())
            ));
        }

        Map<String, Integer> userUsage = new HashMap<>();
        Map<String, Money> campaignSpent = new HashMap<>();

        for (int i = 0; i < allKeys.size(); i += MAX_BATCH_KEYS) {
            List<Map<String, AttributeValue>> chunk = allKeys.subList(i, Math.min(i + MAX_BATCH_KEYS, allKeys.size()));
            Map<String, KeysAndAttributes> requestItems = Map.of(
                    tableName, KeysAndAttributes.builder().keys(chunk).build()
            );

            while (!requestItems.isEmpty()) {
                BatchGetItemResponse response = dynamoDb.batchGetItem(
                        BatchGetItemRequest.builder().requestItems(requestItems).build()
                );

                List<Map<String, AttributeValue>> items = response.responses().getOrDefault(tableName, List.of());
                for (Map<String, AttributeValue> item : items) {
                    String pk = item.get(DynamoDbKeyFactory.PK).s();
                    if (pk.startsWith("QUOTA#")) {
                        String promoId = pk.substring("QUOTA#".length(), pk.indexOf("#USER#"));
                        int count = item.containsKey("usageCount") ? Integer.parseInt(item.get("usageCount").n()) : 0;
                        userUsage.put(promoId, count);
                    } else if (pk.startsWith("BUDGET#")) {
                        String promoId = pk.substring("BUDGET#".length());
                        long spentCents = item.containsKey("spentCents") ? Long.parseLong(item.get("spentCents").n()) : 0L;
                        campaignSpent.put(promoId, Money.ofCents(spentCents));
                    }
                }
                requestItems = response.hasUnprocessedKeys() ? response.unprocessedKeys() : Map.of();
            }
        }

        Map<String, QuotaSnapshot> result = new HashMap<>();
        for (String promoId : promotionIds) {
            result.put(promoId, new QuotaSnapshot(
                    userUsage.getOrDefault(promoId, 0),
                    campaignSpent.getOrDefault(promoId, Money.ZERO)
            ));
        }
        return result;
    }
}
