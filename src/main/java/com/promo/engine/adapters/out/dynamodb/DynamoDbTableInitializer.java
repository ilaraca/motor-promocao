package com.promo.engine.adapters.out.dynamodb;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableResponse;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.StreamSpecification;
import software.amazon.awssdk.services.dynamodb.model.StreamViewType;
import software.amazon.awssdk.services.dynamodb.model.TimeToLiveSpecification;
import software.amazon.awssdk.services.dynamodb.model.UpdateTimeToLiveRequest;

public final class DynamoDbTableInitializer {

    private static final Logger log = LoggerFactory.getLogger(DynamoDbTableInitializer.class);

    private final DynamoDbClient dynamoDb;
    private final String tableName;

    public DynamoDbTableInitializer(DynamoDbClient dynamoDb, String tableName) {
        this.dynamoDb = dynamoDb;
        this.tableName = tableName;
    }

    public String ensureTableExistsAndReturnStreamArn() {
        try {
            DescribeTableResponse existing = dynamoDb.describeTable(
                    DescribeTableRequest.builder().tableName(tableName).build()
            );
            log.info("DynamoDB table '{}' already exists", tableName);
            return existing.table().latestStreamArn();
        } catch (ResourceNotFoundException ignored) {
            log.info("Creating DynamoDB Single Table '{}' with GSI1 and Streams (NEW_IMAGE)", tableName);
        }

        dynamoDb.createTable(CreateTableRequest.builder()
                .tableName(tableName)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(
                        AttributeDefinition.builder().attributeName(DynamoDbKeyFactory.PK).attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName(DynamoDbKeyFactory.SK).attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName(DynamoDbKeyFactory.GSI1_PK).attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName(DynamoDbKeyFactory.GSI1_SK).attributeType(ScalarAttributeType.S).build()
                )
                .keySchema(
                        KeySchemaElement.builder().attributeName(DynamoDbKeyFactory.PK).keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName(DynamoDbKeyFactory.SK).keyType(KeyType.RANGE).build()
                )
                .globalSecondaryIndexes(GlobalSecondaryIndex.builder()
                        .indexName(DynamoDbKeyFactory.GSI1_NAME)
                        .keySchema(
                                KeySchemaElement.builder().attributeName(DynamoDbKeyFactory.GSI1_PK).keyType(KeyType.HASH).build(),
                                KeySchemaElement.builder().attributeName(DynamoDbKeyFactory.GSI1_SK).keyType(KeyType.RANGE).build()
                        )
                        .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                        .build())
                .streamSpecification(StreamSpecification.builder()
                        .streamEnabled(true)
                        .streamViewType(StreamViewType.NEW_IMAGE)
                        .build())
                .build());

        dynamoDb.waiter().waitUntilTableExists(DescribeTableRequest.builder().tableName(tableName).build());

        try {
            dynamoDb.updateTimeToLive(UpdateTimeToLiveRequest.builder()
                    .tableName(tableName)
                    .timeToLiveSpecification(TimeToLiveSpecification.builder()
                            .enabled(true)
                            .attributeName(DynamoDbKeyFactory.TTL_ATTR)
                            .build())
                    .build());
        } catch (Exception ex) {
            log.warn("Could not enable DynamoDB TTL on table '{}': {}", tableName, ex.getMessage());
        }

        DescribeTableResponse created = dynamoDb.describeTable(
                DescribeTableRequest.builder().tableName(tableName).build()
        );
        return created.table().latestStreamArn();
    }
}
