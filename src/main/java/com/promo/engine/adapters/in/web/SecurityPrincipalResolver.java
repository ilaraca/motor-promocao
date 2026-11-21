package com.promo.engine.adapters.in.web;

import com.promo.engine.domain.exception.EvaluationOwnershipException;
import com.promo.engine.domain.model.AuthenticatedPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class SecurityPrincipalResolver {

    private static final String S2S_SCOPE = "promotions:checkout:s2s";

    public AuthenticatedPrincipal resolve(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new IllegalStateException("Missing authenticated JWT principal");
        }
        String userId = jwt.getSubject();

        Set<String> segments = new HashSet<>();
        List<String> segmentClaim = jwt.getClaimAsStringList("segments");
        if (segmentClaim != null) {
            segments.addAll(segmentClaim);
        }

        Set<String> scopes = new HashSet<>();
        String scopeStr = jwt.getClaimAsString("scope");
        if (scopeStr != null && !scopeStr.isBlank()) {
            scopes.addAll(Arrays.stream(scopeStr.split("\\s+"))
                    .filter(s -> !s.isBlank())
                    .collect(Collectors.toSet()));
        }

        return new AuthenticatedPrincipal(userId, segments, scopes);
    }

    /**
     * Prevents IDOR / userId spoofing: payload.userId must match JWT subject unless caller holds S2S scope.
     */
    public String resolveAuthoritativeTargetUserId(AuthenticatedPrincipal principal, String payloadUserId) {
        if (payloadUserId == null || payloadUserId.isBlank()) {
            return principal.userId();
        }
        if (!principal.hasScope(S2S_SCOPE) && !principal.userId().equals(payloadUserId)) {
            throw new EvaluationOwnershipException("targetUserId=" + payloadUserId);
        }
        return payloadUserId;
    }
}
