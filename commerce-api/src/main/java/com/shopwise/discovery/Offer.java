package com.shopwise.discovery;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.Currency;
import java.util.Map;

public record Offer(String provider, String productId, String variantId, String title, String variant,
                    String source, String url, String currency, Long amountMinor, String destination,
                    Boolean available, Instant observedAt, String condition, String gtin,
                    ReferenceRates.Reference referenceCny, String brand, String mpn, Map<String,String> attributes) {
    Offer withReference(ReferenceRates.Reference reference) {
        return new Offer(provider,productId,variantId,title,variant,source,url,currency,amountMinor,destination,available,
            observedAt,condition,gtin,reference,brand,mpn,attributes);
    }
    @com.fasterxml.jackson.annotation.JsonProperty("price")
    public BigDecimal price() {
        if (amountMinor == null || currency == null) return null;
        try {
            int scale = Currency.getInstance(currency).getDefaultFractionDigits();
            return scale < 0 ? null : BigDecimal.valueOf(amountMinor, scale);
        } catch (IllegalArgumentException ignored) { return null; }
    }

    static String merchantUrl(String raw) {
        try {
            URI uri = URI.create(raw);
            if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null) return raw;
        } catch (IllegalArgumentException ignored) { }
        return "";
    }
}
