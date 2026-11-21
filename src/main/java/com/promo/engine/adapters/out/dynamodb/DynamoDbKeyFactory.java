package com.promo.engine.adapters.out.dynamodb;

public final class DynamoDbKeyFactory {

    public static final String PK = "PK";
    public static final String SK = "SK";
    public static final String GSI1_NAME = "GSI1";
    public static final String GSI1_PK = "GSI1PK";
    public static final String GSI1_SK = "GSI1SK";
    public static final String ENTITY_TYPE = "entityType";
    public static final String TTL_ATTR = "ttlEpoch";

    public static final String ENTITY_OUTBOX_EVENT = "OUTBOX_EVENT";
    public static final String ACTIVE_PROMOS_PARTITION = "ACTIVE_PROMOS";

    private DynamoDbKeyFactory() {}

    public static String promoPk(String promotionId) {
        return "PROMO#" + promotionId;
    }

    public static String metadataSk() {
        return "METADATA";
    }

    public static String versionSk(int ruleVersion) {
        return String.format("VERSION#%06d", ruleVersion);
    }

    public static String activeGsiSk(String promotionId, int ruleVersion) {
        return String.format("PROMO#%s#V%06d", promotionId, ruleVersion);
    }

    public static String evalPk(String evaluationId) {
        return "EVAL#" + evaluationId;
    }

    public static String quoteSk() {
        return "QUOTE";
    }

    public static String idempotencyPk(String checkoutKey) {
        return "IDEMPOTENCY#" + checkoutKey;
    }

    public static String commitSk() {
        return "COMMIT";
    }

    public static String orderPk(String orderId) {
        return "ORDER#" + orderId;
    }

    public static String snapshotSk() {
        return "PROMO_SNAPSHOT";
    }

    public static String quotaPk(String promotionId, String userId) {
        return "QUOTA#" + promotionId + "#USER#" + userId;
    }

    public static String usageSk() {
        return "USAGE";
    }

    public static String budgetPk(String promotionId) {
        return "BUDGET#" + promotionId;
    }

    public static String totalSk() {
        return "TOTAL";
    }

    public static String outboxPk(String eventId) {
        return "OUTBOX#" + eventId;
    }

    public static String cashbackOutboxSk() {
        return "CASHBACK_GRANTED";
    }
}
