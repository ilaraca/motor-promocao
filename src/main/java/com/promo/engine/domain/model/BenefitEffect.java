package com.promo.engine.domain.model;

/**
 * Sealed hierarchy distinguishing synchronous price reductions from asynchronous
 * fulfillment grants (e.g., Cashback via Transactional Outbox).
 */
public sealed interface BenefitEffect permits BenefitEffect.InstantPriceReduction, BenefitEffect.AsyncCashbackGrant {

    Money amount();

    record InstantPriceReduction(Money amount) implements BenefitEffect {}

    record AsyncCashbackGrant(Money amount) implements BenefitEffect {}
}
