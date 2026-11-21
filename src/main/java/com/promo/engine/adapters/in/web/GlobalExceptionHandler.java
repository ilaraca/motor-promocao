package com.promo.engine.adapters.in.web;

import com.promo.engine.domain.exception.DomainException;
import com.promo.engine.domain.exception.EvaluationExpiredException;
import com.promo.engine.domain.exception.EvaluationNotFoundException;
import com.promo.engine.domain.exception.EvaluationOwnershipException;
import com.promo.engine.domain.exception.IdempotencyConflictException;
import com.promo.engine.domain.exception.InvalidEvaluationRequestException;
import com.promo.engine.domain.exception.InvalidRuleDefinitionException;
import com.promo.engine.domain.exception.QuotaExceededOnCommitException;
import com.promo.engine.domain.exception.RuleVersionConflictException;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.lang.NonNull;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler({InvalidRuleDefinitionException.class, InvalidEvaluationRequestException.class})
    public ProblemDetail handleBadRequest(DomainException ex) {
        return buildProblem(HttpStatus.BAD_REQUEST, ex.errorCode(), ex.getMessage());
    }

    @ExceptionHandler({EvaluationExpiredException.class, EvaluationNotFoundException.class})
    public ProblemDetail handleExpired(DomainException ex) {
        return buildProblem(HttpStatus.UNPROCESSABLE_ENTITY, ex.errorCode(), ex.getMessage());
    }

    @ExceptionHandler(EvaluationOwnershipException.class)
    public ProblemDetail handleForbidden(EvaluationOwnershipException ex) {
        return buildProblem(HttpStatus.FORBIDDEN, ex.errorCode(), ex.getMessage());
    }

    @ExceptionHandler({QuotaExceededOnCommitException.class, IdempotencyConflictException.class, RuleVersionConflictException.class})
    public ProblemDetail handleConflict(DomainException ex) {
        return buildProblem(HttpStatus.CONFLICT, ex.errorCode(), ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        String details = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return buildProblem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", details);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ProblemDetail handleMissingHeader(MissingRequestHeaderException ex) {
        return buildProblem(HttpStatus.BAD_REQUEST, "MISSING_REQUIRED_HEADER", ex.getMessage());
    }

    private ProblemDetail buildProblem(@NonNull HttpStatus status, String errorCode, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(errorCode);
        problem.setProperty("errorCode", errorCode);
        problem.setProperty("correlationId", MDC.get(CorrelationFilter.MDC_CORRELATION_ID));
        problem.setProperty("timestamp", Instant.now().toString());
        return problem;
    }
}
