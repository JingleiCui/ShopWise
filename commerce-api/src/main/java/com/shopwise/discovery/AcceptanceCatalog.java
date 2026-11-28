package com.shopwise.discovery;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("acceptance & load-fixture & !prod")
class AcceptanceCatalog implements CatalogProvider {
    private static final String MERCHANT_URL = "https://merchant.shopwise.test/lamp";

    @Override public String name() { return "shopify"; }
    @Override public boolean enabled() { return true; }

    @Override public List<Offer> search(String query) {
        return List.of(
            lamp("adjustable", "可调光阅读灯", "USD", 2500, "search"),
            lamp("classic", "经典阅读灯", "CNY", 22300, "search"),
            lamp("premium", "高价阅读灯", "USD", 6000, "search"));
    }

    @Override public List<Offer> lookup(String id) {
        if (id.equals("adjustable")) {
            return List.of(lamp(id, "可调光阅读灯", "USD", 2600, "refreshed"));
        }
        return search("").stream().filter(offer -> offer.variantId().equals(id)).toList();
    }

    @Override public List<Offer> lookupAll(List<String> ids) {
        return search("").stream().filter(offer -> ids.contains(offer.variantId())).toList();
    }

    @Override public List<Offer> details(String id) { return lookup(id); }

    private Offer lamp(String id, String title, String currency, long amount, String quote) {
        return new Offer(name(), id, id, title, "标准款", "验收商家", MERCHANT_URL + "?quote=" + quote,
            currency, amount, "CN", true, Instant.now(), "new", null, null, null, null, Map.of());
    }
}
