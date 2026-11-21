package com.promo.engine.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promo.engine.adapters.out.dynamodb.DynamoDbCommitTransactionAdapter;
import com.promo.engine.adapters.out.dynamodb.DynamoDbJsonCodec;
import com.promo.engine.adapters.out.dynamodb.DynamoDbPromotionRuleAdapter;
import com.promo.engine.adapters.out.dynamodb.DynamoDbQuotaReadAdapter;
import com.promo.engine.adapters.out.dynamodb.DynamoDbQuoteAdapter;
import com.promo.engine.adapters.out.dynamodb.DynamoDbTableInitializer;
import com.promo.engine.adapters.out.observability.StructuredJsonAuditAdapter;
import com.promo.engine.adapters.out.outbox.DynamoDbStreamsOutboxRelay;
import com.promo.engine.adapters.out.outbox.SqsOutboxEventPublisher;
import com.promo.engine.application.port.out.AuditEventPublisher;
import com.promo.engine.application.port.out.CommitTransactionPort;
import com.promo.engine.application.port.out.EvaluationQuoteRepository;
import com.promo.engine.application.port.out.OutboxEventPublisher;
import com.promo.engine.application.port.out.PromotionRuleRepository;
import com.promo.engine.application.port.out.QuotaReadRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.streams.DynamoDbStreamsClient;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.net.URI;

@Configuration
public class AwsConfig {

    @Bean
    public DynamoDbClient dynamoDbClient(
            @Value("${promotions.aws.region:us-east-1}") String region,
            @Value("${promotions.aws.endpoint:}") String endpoint,
            @Value("${promotions.aws.access-key:test}") String accessKey,
            @Value("${promotions.aws.secret-key:test}") String secretKey
    ) {
        var builder = DynamoDbClient.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)));
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }

    @Bean
    public DynamoDbStreamsClient dynamoDbStreamsClient(
            @Value("${promotions.aws.region:us-east-1}") String region,
            @Value("${promotions.aws.endpoint:}") String endpoint,
            @Value("${promotions.aws.access-key:test}") String accessKey,
            @Value("${promotions.aws.secret-key:test}") String secretKey
    ) {
        var builder = DynamoDbStreamsClient.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)));
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }

    @Bean
    public SqsClient sqsClient(
            @Value("${promotions.aws.region:us-east-1}") String region,
            @Value("${promotions.aws.endpoint:}") String endpoint,
            @Value("${promotions.aws.access-key:test}") String accessKey,
            @Value("${promotions.aws.secret-key:test}") String secretKey
    ) {
        var builder = SqsClient.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)));
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }

    @Bean
    public DynamoDbJsonCodec dynamoDbJsonCodec(ObjectMapper objectMapper) {
        return new DynamoDbJsonCodec(objectMapper);
    }

    @Bean
    public String dynamoDbStreamArn(
            DynamoDbClient dynamoDbClient,
            @Value("${promotions.aws.dynamodb.table-name:promotions_engine}") String tableName,
            @Value("${promotions.aws.dynamodb.auto-create-table:true}") boolean autoCreateTable,
            @Value("${promotions.aws.outbox.relay-enabled:true}") boolean relayEnabled
    ) {
        DynamoDbTableInitializer initializer = new DynamoDbTableInitializer(dynamoDbClient, tableName);
        if (autoCreateTable) {
            String arn = initializer.ensureTableExistsAndReturnStreamArn();
            return arn != null ? arn : "";
        }
        if (!relayEnabled) {
            return "";
        }
        String arn = initializer.findExistingStreamArn();
        return arn != null ? arn : "";
    }

    @Bean
    public PromotionRuleRepository promotionRuleRepository(
            DynamoDbClient dynamoDbClient,
            DynamoDbJsonCodec codec,
            @Value("${promotions.aws.dynamodb.table-name:promotions_engine}") String tableName
    ) {
        return new DynamoDbPromotionRuleAdapter(dynamoDbClient, tableName, codec);
    }

    @Bean
    public QuotaReadRepository quotaReadRepository(
            DynamoDbClient dynamoDbClient,
            @Value("${promotions.aws.dynamodb.table-name:promotions_engine}") String tableName
    ) {
        return new DynamoDbQuotaReadAdapter(dynamoDbClient, tableName);
    }

    @Bean
    public EvaluationQuoteRepository evaluationQuoteRepository(
            DynamoDbClient dynamoDbClient,
            DynamoDbJsonCodec codec,
            @Value("${promotions.aws.dynamodb.table-name:promotions_engine}") String tableName
    ) {
        return new DynamoDbQuoteAdapter(dynamoDbClient, tableName, codec);
    }

    @Bean
    public CommitTransactionPort commitTransactionPort(
            DynamoDbClient dynamoDbClient,
            DynamoDbJsonCodec codec,
            @Value("${promotions.aws.dynamodb.table-name:promotions_engine}") String tableName
    ) {
        return new DynamoDbCommitTransactionAdapter(dynamoDbClient, tableName, codec);
    }

    @Bean
    public SqsOutboxEventPublisher sqsOutboxEventPublisher(
            SqsClient sqsClient,
            DynamoDbJsonCodec codec,
            MeterRegistry meterRegistry,
            @Value("${promotions.aws.sqs.cashback-queue-name:cashback-granted-events}") String queueName,
            @Value("${promotions.aws.sqs.auto-create-queue:true}") boolean autoCreateQueue
    ) {
        return new SqsOutboxEventPublisher(sqsClient, queueName, autoCreateQueue, codec, meterRegistry);
    }

    @Bean
    public DynamoDbStreamsOutboxRelay dynamoDbStreamsOutboxRelay(
            DynamoDbStreamsClient streamsClient,
            String dynamoDbStreamArn,
            OutboxEventPublisher outboxEventPublisher,
            DynamoDbJsonCodec codec,
            MeterRegistry meterRegistry,
            @Value("${promotions.aws.outbox.relay-enabled:true}") boolean relayEnabled
    ) {
        return new DynamoDbStreamsOutboxRelay(
                streamsClient, dynamoDbStreamArn, outboxEventPublisher, codec, meterRegistry, relayEnabled
        );
    }

    @Bean
    public AuditEventPublisher auditEventPublisher(MeterRegistry meterRegistry) {
        return new StructuredJsonAuditAdapter(meterRegistry);
    }
}
