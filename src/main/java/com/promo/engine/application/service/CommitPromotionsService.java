package com.promo.engine.application.service;

import com.promo.engine.application.port.in.CommitPromotionsUseCase;
import com.promo.engine.application.port.out.AuditEventPublisher;
import com.promo.engine.application.port.out.ClockPort;
import com.promo.engine.application.port.out.CommitTransactionPort;
import com.promo.engine.application.port.out.CommitTransactionPort.AtomicCommitCommand;
import com.promo.engine.application.port.out.CommitTransactionPort.IdempotentRecord;
import com.promo.engine.application.port.out.CommitTransactionPort.TransactionCommitOutcome;
import com.promo.engine.application.port.out.EvaluationQuoteRepository;
import com.promo.engine.application.port.out.IdGeneratorPort;
import com.promo.engine.domain.exception.DomainException;
import com.promo.engine.domain.exception.EvaluationExpiredException;
import com.promo.engine.domain.exception.EvaluationNotFoundException;
import com.promo.engine.domain.exception.EvaluationOwnershipException;
import com.promo.engine.domain.exception.IdempotencyConflictException;
import com.promo.engine.domain.model.AppliedPromotion;
import com.promo.engine.domain.model.BenefitType;
import com.promo.engine.domain.model.CashbackGrantEvent;
import com.promo.engine.domain.model.DecisionSnapshot;
import com.promo.engine.domain.model.EvaluationQuote;
import com.promo.engine.domain.model.FrozenRuleRef;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

public final class CommitPromotionsService implements CommitPromotionsUseCase {

    private static final String S2S_SCOPE = "promotions:checkout:s2s";

    private final EvaluationQuoteRepository quoteRepository;
    private final CommitTransactionPort commitTransactionPort;
    private final AuditEventPublisher auditEventPublisher;
    private final ClockPort clock;
    private final IdGeneratorPort idGenerator;

    public CommitPromotionsService(
            EvaluationQuoteRepository quoteRepository,
            CommitTransactionPort commitTransactionPort,
            AuditEventPublisher auditEventPublisher,
            ClockPort clock,
            IdGeneratorPort idGenerator
    ) {
        this.quoteRepository = quoteRepository;
        this.commitTransactionPort = commitTransactionPort;
        this.auditEventPublisher = auditEventPublisher;
        this.clock = clock;
        this.idGenerator = idGenerator;
    }

    @Override
    public CommitResult commit(CommitCommand command) {
        long startNanos = System.nanoTime();
        String payloadHash = computePayloadHash(command.orderId(), command.evaluationId());

        try {
            // 1. Fast-path idempotency check (ConsistentRead = true)
            Optional<IdempotentRecord> existing = commitTransactionPort.findIdempotentCommit(command.checkoutKey());
            if (existing.isPresent()) {
                IdempotentRecord record = existing.get();
                if (!record.payloadHash().equals(payloadHash)) {
                    throw new IdempotencyConflictException(command.checkoutKey());
                }
                verifyOwnership(command, record.snapshot().userId(), command.evaluationId());

                long latencyMs = elapsedMs(startNanos);
                auditEventPublisher.recordCommit(
                        command.correlationId(),
                        command.traceId(),
                        command.channel(),
                        record.snapshot(),
                        List.of(),
                        true,
                        false,
                        latencyMs
                );
                return new CommitResult(record.snapshot(), true);
            }

            // 2. Load frozen quote (ConsistentRead = true)
            EvaluationQuote quote = quoteRepository.findById(command.evaluationId())
                    .orElseThrow(() -> new EvaluationNotFoundException(command.evaluationId()));

            // 3. Layer 3 (Write-Time) validations: ownership, strict in-memory TTL, original rule validity
            verifyOwnership(command, quote.userId(), quote.evaluationId());

            Instant now = clock.now();
            if (quote.isExpiredAt(now)) {
                throw EvaluationExpiredException.ttlExpired(quote.evaluationId());
            }

            for (FrozenRuleRef ref : quote.frozenRuleRefs()) {
                if (!ref.isWithinOriginalValidity(now)) {
                    throw EvaluationExpiredException.ruleValidityExpired(ref.promotionId(), ref.ruleVersion());
                }
            }

            // 4. Build immutable DecisionSnapshot honoring frozen ruleVersions
            DecisionSnapshot snapshot = quote.toDecisionSnapshot(command.orderId(), command.checkoutKey(), now);

            CashbackGrantEvent outboxEvent = snapshot.hasCashback()
                    ? buildCashbackOutboxEvent(snapshot, now, command.correlationId(), command.traceId())
                    : null;

            // 5. Execute ACID commit transaction (Idempotency + Snapshot + Quotas + Budgets + Outbox)
            TransactionCommitOutcome outcome = commitTransactionPort.commitAtomically(
                    new AtomicCommitCommand(
                            command.checkoutKey(),
                            payloadHash,
                            snapshot,
                            quote.frozenRuleRefs(),
                            outboxEvent
                    )
            );

            long latencyMs = elapsedMs(startNanos);
            auditEventPublisher.recordCommit(
                    command.correlationId(),
                    command.traceId(),
                    command.channel(),
                    outcome.snapshot(),
                    quote.candidateDecisions(),
                    outcome.idempotentReplay(),
                    outcome.conflictRecovered(),
                    latencyMs
            );

            return new CommitResult(outcome.snapshot(), outcome.idempotentReplay());
        } catch (DomainException ex) {
            auditEventPublisher.recordCommitRejection(
                    command.correlationId(),
                    command.traceId(),
                    command.channel(),
                    command.principal().userId(),
                    command.orderId(),
                    command.evaluationId(),
                    command.checkoutKey(),
                    ex.errorCode(),
                    elapsedMs(startNanos)
            );
            throw ex;
        }
    }

    private void verifyOwnership(CommitCommand command, String quoteUserId, String evaluationId) {
        if (!command.principal().hasScope(S2S_SCOPE)
                && !command.principal().userId().equals(quoteUserId)) {
            throw new EvaluationOwnershipException(evaluationId);
        }
    }

    private CashbackGrantEvent buildCashbackOutboxEvent(
            DecisionSnapshot snapshot,
            Instant occurredAt,
            String correlationId,
            String traceId
    ) {
        List<CashbackGrantEvent.GrantItem> grants = snapshot.appliedPromotions().stream()
                .filter(p -> p.benefitType() == BenefitType.CASHBACK)
                .map(AppliedPromotion::toGrantItem)
                .toList();

        return new CashbackGrantEvent(
                idGenerator.nextEventId(),
                snapshot.orderId(),
                snapshot.userId(),
                snapshot.evaluationId(),
                snapshot.checkoutKey(),
                snapshot.totalCashbackAmount(),
                grants,
                occurredAt,
                correlationId,
                traceId
        );
    }

    private static String computePayloadHash(String orderId, String evaluationId) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((orderId + "|" + evaluationId).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
