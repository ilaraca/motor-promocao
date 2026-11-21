package com.promo.engine.domain.model;

import java.util.Objects;
import java.util.Set;

public record AuthenticatedPrincipal(
        String userId,
        Set<String> segments,
        Set<String> scopes
) {
    public AuthenticatedPrincipal {
        Objects.requireNonNull(userId, "userId must not be null");
        segments = segments == null ? Set.of() : Set.copyOf(segments);
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
    }

    public boolean hasScope(String scope) {
        return scopes.contains(scope);
    }
}
