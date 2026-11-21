package com.promo.engine.application.port.out;

import com.promo.engine.domain.model.CashbackGrantEvent;
import com.promo.engine.domain.model.DecisionSnapshot;
import com.promo.engine.domain.model.FrozenRuleRef;

import java.util.List;
import java.util.Optional;

public interface CommitTransactionPort {

    Optional<IdempotentRecord> findIdempotentCommit(String checkoutKey);

    TransactionCommitOutcome commitAtomically(AtomicCommitCommand command);

    Optional<DecisionSnapshot> findOrderSnapshot(String orderId);

    record IdempotentRecord(
            String checkoutKey,
            String orderId,
            String evaluationId,
            String payloadHash,
            DecisionSnapshot snapshot
    ) {}

    record AtomicCommitCommand(
            String checkoutKey,
            String payloadHash,
            DecisionSnapshot snapshot,
            List<FrozenRuleRef> frozenRuleRefs,
            CashbackGrantEvent cashbackOutboxEvent
    ) {}

    record TransactionCommitOutcome(
            DecisionSnapshot snapshot,
            boolean idempotentReplay,
            boolean conflictRecovered
    ) {}
}
