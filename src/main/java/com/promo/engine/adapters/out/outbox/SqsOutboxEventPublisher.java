package com.promo.engine.adapters.out.outbox;

import com.promo.engine.adapters.out.dynamodb.DynamoDbJsonCodec;
import com.promo.engine.application.port.out.OutboxEventPublisher;
import com.promo.engine.domain.model.CashbackGrantEvent;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.util.Map;

public final class SqsOutboxEventPublisher implements OutboxEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(SqsOutboxEventPublisher.class);

    private final SqsClient sqsClient;
    private final String queueName;
    private final DynamoDbJsonCodec codec;
    private final MeterRegistry meterRegistry;
    private volatile String queueUrl;

    public SqsOutboxEventPublisher(
            SqsClient sqsClient,
            String queueName,
            boolean autoCreateQueue,
            DynamoDbJsonCodec codec,
            MeterRegistry meterRegistry
    ) {
        this.sqsClient = sqsClient;
        this.queueName = queueName;
        this.codec = codec;
        this.meterRegistry = meterRegistry;
        if (autoCreateQueue) {
            this.queueUrl = ensureQueueExists();
        }
    }

    public String ensureQueueExists() {
        try {
            return sqsClient.getQueueUrl(GetQueueUrlRequest.builder().queueName(queueName).build()).queueUrl();
        } catch (QueueDoesNotExistException ex) {
            log.info("Creating SQS queue '{}' for CashbackGrantedEvent outbox", queueName);
            return sqsClient.createQueue(CreateQueueRequest.builder().queueName(queueName).build()).queueUrl();
        }
    }

    public String queueUrl() {
        if (queueUrl == null) {
            queueUrl = ensureQueueExists();
        }
        return queueUrl;
    }

    @Override
    public void publishCashbackGranted(CashbackGrantEvent event) {
        String body = codec.writeCashbackEvent(event);
        sqsClient.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl())
                .messageBody(body)
                .messageAttributes(Map.of(
                        "eventType", MessageAttributeValue.builder().dataType("String").stringValue("CashbackGrantedEvent").build(),
                        "eventId", MessageAttributeValue.builder().dataType("String").stringValue(event.eventId()).build(),
                        "orderId", MessageAttributeValue.builder().dataType("String").stringValue(event.orderId()).build()
                ))
                .build());
        meterRegistry.counter("promotions.outbox.published.total",
                "eventType", "CashbackGrantedEvent",
                "status", "success").increment();
        log.info("Published CashbackGrantedEvent eventId={} orderId={} amount={} to SQS queue '{}'",
                event.eventId(), event.orderId(), event.totalCashbackAmount(), queueName);
    }
}
