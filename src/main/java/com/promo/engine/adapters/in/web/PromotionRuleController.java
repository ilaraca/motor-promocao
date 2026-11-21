package com.promo.engine.adapters.in.web;

import com.promo.engine.adapters.in.web.dto.PromotionWebDtos.PromotionRuleResponse;
import com.promo.engine.adapters.in.web.dto.PromotionWebDtos.PublishRuleRequest;
import com.promo.engine.application.port.in.PublishPromotionRuleUseCase;
import com.promo.engine.application.port.in.PublishPromotionRuleUseCase.PublishRuleCommand;
import com.promo.engine.domain.model.AuthenticatedPrincipal;
import com.promo.engine.domain.model.Money;
import com.promo.engine.domain.model.ProgressiveTier;
import com.promo.engine.domain.model.PromotionRule;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/v1/admin/promotions")
public class PromotionRuleController {

    private final PublishPromotionRuleUseCase publishRuleUseCase;
    private final SecurityPrincipalResolver principalResolver;

    public PromotionRuleController(
            PublishPromotionRuleUseCase publishRuleUseCase,
            SecurityPrincipalResolver principalResolver
    ) {
        this.publishRuleUseCase = publishRuleUseCase;
        this.principalResolver = principalResolver;
    }

    @PostMapping
    public ResponseEntity<PromotionRuleResponse> publishRule(
            @Valid @RequestBody PublishRuleRequest request,
            Authentication authentication
    ) {
        AuthenticatedPrincipal actor = principalResolver.resolve(authentication);

        List<ProgressiveTier> tiers = request.tiers().stream()
                .map(t -> new ProgressiveTier(
                        t.tierId(),
                        Money.of(t.minAmount()),
                        t.maxAmount() != null ? Money.of(t.maxAmount()) : null,
                        t.benefitMode(),
                        t.benefitValue()
                ))
                .toList();

        PromotionRule published = publishRuleUseCase.publish(new PublishRuleCommand(
                request.promotionId(),
                request.name(),
                request.validFrom(),
                request.validTo(),
                request.benefitType(),
                request.stackabilityMode(),
                request.priorityRank(),
                request.precedenceOrder(),
                request.calculationBase(),
                Money.of(request.minOrderValue()),
                request.maxBenefitAmount() != null ? Money.of(request.maxBenefitAmount()) : null,
                request.maxUsagePerUser(),
                request.maxCampaignBudget() != null ? Money.of(request.maxCampaignBudget()) : null,
                request.eligibleSegments(),
                request.eligibleCategories(),
                tiers,
                actor.userId(),
                MDC.get(CorrelationFilter.MDC_CORRELATION_ID),
                MDC.get(CorrelationFilter.MDC_TRACE_ID)
        ));

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(PromotionRuleResponse.fromDomain(published));
    }

    @GetMapping("/active")
    public ResponseEntity<List<PromotionRuleResponse>> listActive() {
        List<PromotionRuleResponse> active = publishRuleUseCase.listActiveRules().stream()
                .map(PromotionRuleResponse::fromDomain)
                .toList();
        return ResponseEntity.ok(active);
    }
}
