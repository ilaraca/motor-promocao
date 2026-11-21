package com.promo.engine.adapters.out.outbox;

import com.promo.engine.adapters.out.dynamodb.DynamoDbJsonCodec;
import com.promo.engine.adapters.out.dynamodb.DynamoDbKeyFactory;
import com.promo.engine.application.port.out.OutboxEventPublisher;
import com.promo.engine.domain.model.CashbackGrantEvent;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DescribeStreamRequest;
import software.amazon.awssdk.services.dynamodb.model.GetRecordsRequest;
import software.amazon.awssdk.services.dynamodb.model.GetRecordsResponse;
import software.amazon.awssdk.services.dynamodb.model.GetShardIteratorRequest;
import software.amazon.awssdk.services.dynamodb.model.OperationType;
import software.amazon.awssdk.services.dynamodb.model.Record;
import software.amazon.awssdk.services.dynamodb.model.Shard;
import software.amazon.awssdk.services.dynamodb.model.ShardIteratorType;
import software.amazon.awssdk.services.dynamodb.streams.DynamoDbStreamsClient;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * POC Transactional Outbox Relay reading DynamoDB Streams (NEW_IMAGE), filtering
 * INSERT events with entityType == OUTBOX_EVENT and forwarding CashbackGrantedEvent
 * to the decoupled OutboxEventPublisher (SQS). In AWS production, EventBridge Pipes
 * replaces this worker with zero polling code.
 */
public class DynamoDbStreamsOutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(DynamoDbStreamsOutboxRelay.class);

    private final DynamoDbStreamsClient streamsClient;
    private final String streamArn;
    private final OutboxEventPublisher publisher;
    private final DynamoDbJsonCodec codec;
    private final MeterRegistry meterRegistry;
    private final boolean enabled;

    private final Map<String, String> shardIterators = new ConcurrentHashMap<>();
    private final Set<String> processedEventIds = ConcurrentHashMap.newKeySet();

    public DynamoDbStreamsOutboxRelay(
            DynamoDbStreamsClient streamsClient,
            String streamArn,
            OutboxEventPublisher publisher,
            DynamoDbJsonCodec codec,
            MeterRegistry meterRegistry,
            boolean enabled
    ) {
        this.streamsClient = streamsClient;
        this.streamArn = streamArn;
        this.publisher = publisher;
        this.codec = codec;
        this.meterRegistry = meterRegistry;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${promotions.aws.outbox.poll-interval-ms:500}")
    public void scheduledPoll() {
        if (!enabled || streamArn == null || streamArn.isBlank()) {
            return;
        }
        pollOnce();
    }

    /**
     * Synchronized single-pass poll (also callable directly from integration tests for determinism).
     */
    public synchronized int pollOnce() {
        if (streamArn == null || streamArn.isBlank()) {
            return 0;
        }
        int publishedCount = 0;
        try {
            var streamDesc = streamsClient.describeStream(
                    DescribeStreamRequest.builder().streamArn(streamArn).build()
            ).streamDescription();

            for (Shard shard : streamDesc.shards()) {
                String iterator = shardIterators.computeIfAbsent(shard.shardId(), shardId ->
                        streamsClient.getShardIterator(GetShardIteratorRequest.builder()
                                .streamArn(streamArn)
                                .shardId(shardId)
                                .shardIteratorType(ShardIteratorType.TRIM_HORIZON)
                                .build()).shardIterator()
                );

                if (iterator == null) {
                    continue;
                }

                GetRecordsResponse recordsResponse = streamsClient.getRecords(
                        GetRecordsRequest.builder().shardIterator(iterator).limit(100).build()
                );
                if (recordsResponse.nextShardIterator() != null) {
                    shardIterators.put(shard.shardId(), recordsResponse.nextShardIterator());
                }

                for (Record record : recordsResponse.records()) {
                    if (record.eventName() != OperationType.INSERT || record.dynamodb() == null) {
                        continue;
                    }
                    Map<String, AttributeValue> newImage = record.dynamodb().newImage();
                    if (newImage == null) {
                        continue;
                    }
                    AttributeValue entityType = newImage.get(DynamoDbKeyFactory.ENTITY_TYPE);
                    if (entityType == null || !DynamoDbKeyFactory.ENTITY_OUTBOX_EVENT.equals(entityType.s())) {
                        continue;
                    }
                    String eventId = newImage.get("eventId").s();
                    if (!processedEventIds.add(eventId)) {
                        continue;
                    }
                    String payloadJson = newImage.get("payloadJson").s();
                    CashbackGrantEvent event = codec.readCashbackEvent(payloadJson);
                    publisher.publishCashbackGranted(event);
                    publishedCount++;
                }
            }
        } catch (Exception ex) {
            meterRegistry.counter("promotions.outbox.published.total",
                    "eventType", "CashbackGrantedEvent",
                    "status", "error").increment();
            log.warn("Error polling DynamoDB Streams outbox: {}", ex.getMessage());
        }
        return publishedCount;
    }
}
