package com.promo.engine.adapters.in.web;

import com.promo.engine.adapters.in.web.dto.PromotionWebDtos.CommitRequest;
import com.promo.engine.adapters.in.web.dto.PromotionWebDtos.DecisionSnapshotResponse;
import com.promo.engine.adapters.in.web.dto.PromotionWebDtos.EvaluateRequest;
import com.promo.engine.adapters.in.web.dto.PromotionWebDtos.EvaluateResponse;
import com.promo.engine.application.port.in.CommitPromotionsUseCase;
import com.promo.engine.application.port.in.CommitPromotionsUseCase.CommitCommand;
import com.promo.engine.application.port.in.CommitPromotionsUseCase.CommitResult;
import com.promo.engine.application.port.in.EvaluatePromotionsUseCase;
import com.promo.engine.application.port.in.EvaluatePromotionsUseCase.EvaluateCommand;
import com.promo.engine.application.port.in.GetOrderSnapshotUseCase;
import com.promo.engine.domain.model.AuthenticatedPrincipal;
import com.promo.engine.domain.model.CartItem;
import com.promo.engine.domain.model.EvaluationQuote;
import com.promo.engine.domain.model.Money;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/v1/promotions")
public class PromotionController {

    private final EvaluatePromotionsUseCase evaluateUseCase;
    private final CommitPromotionsUseCase commitUseCase;
    private final GetOrderSnapshotUseCase getOrderSnapshotUseCase;
    private final SecurityPrincipalResolver principalResolver;

    public PromotionController(
            EvaluatePromotionsUseCase evaluateUseCase,
            CommitPromotionsUseCase commitUseCase,
            GetOrderSnapshotUseCase getOrderSnapshotUseCase,
            SecurityPrincipalResolver principalResolver
    ) {
        this.evaluateUseCase = evaluateUseCase;
        this.commitUseCase = commitUseCase;
        this.getOrderSnapshotUseCase = getOrderSnapshotUseCase;
        this.principalResolver = principalResolver;
    }

    @PostMapping("/evaluate")
    public ResponseEntity<EvaluateResponse> evaluate(
            @Valid @RequestBody EvaluateRequest request,
            Authentication authentication
    ) {
        AuthenticatedPrincipal principal = principalResolver.resolve(authentication);
        String targetUserId = principalResolver.resolveAuthoritativeTargetUserId(principal, request.userId());

        List<CartItem> items = request.items() == null
                ? List.of()
                : request.items().stream()
                        .map(i -> new CartItem(i.sku(), i.category(), Money.of(i.amount())))
                        .toList();

        EvaluationQuote quote = evaluateUseCase.evaluate(new EvaluateCommand(
                principal,
                targetUserId,
                Money.of(request.grossAmount()),
                Money.of(request.eligibleAmount()),
                items,
                request.conflictPolicy(),
                MDC.get(CorrelationFilter.MDC_CORRELATION_ID),
                MDC.get(CorrelationFilter.MDC_TRACE_ID),
                MDC.get(CorrelationFilter.MDC_CHANNEL)
        ));

        return ResponseEntity.ok(EvaluateResponse.fromDomain(quote));
    }

    @PostMapping("/commit")
    public ResponseEntity<DecisionSnapshotResponse> commit(
            @RequestHeader("Idempotency-Key") String checkoutKey,
            @Valid @RequestBody CommitRequest request,
            Authentication authentication
    ) {
        AuthenticatedPrincipal principal = principalResolver.resolve(authentication);

        CommitResult result = commitUseCase.commit(new CommitCommand(
                principal,
                request.orderId(),
                request.evaluationId(),
                checkoutKey,
                MDC.get(CorrelationFilter.MDC_CORRELATION_ID),
                MDC.get(CorrelationFilter.MDC_TRACE_ID),
                MDC.get(CorrelationFilter.MDC_CHANNEL)
        ));

        return ResponseEntity.ok()
                .header("X-Idempotent-Replay", Boolean.toString(result.idempotentReplay()))
                .body(DecisionSnapshotResponse.fromDomain(result.snapshot()));
    }

    @GetMapping("/orders/{orderId}/snapshot")
    public ResponseEntity<DecisionSnapshotResponse> getOrderSnapshot(@PathVariable String orderId) {
        return getOrderSnapshotUseCase.findByOrderId(orderId)
                .map(s -> ResponseEntity.ok(DecisionSnapshotResponse.fromDomain(s)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
