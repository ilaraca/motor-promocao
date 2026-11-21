package com.promo.engine.domain.model;

import java.util.Objects;

public record CartItem(String sku, String category, Money amount) {
    public CartItem {
        Objects.requireNonNull(sku, "sku must not be null");
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
    }
}
