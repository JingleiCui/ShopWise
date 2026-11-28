package com.shopwise.discovery;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Deterministic upstream latency and records for loopback-only release checks. */
@Component
@Profile("load-fixture & !acceptance & !prod")
class LoadTestCatalog implements CatalogProvider {
    public String name() { return "shopify"; }
    public boolean enabled() { return true; }
    public List<Offer> search(String query) throws InterruptedException {
        Thread.sleep(80);
        return List.of(new Offer(name(),"load-lamp","load-variant","Fixture desk lamp","","Fixture merchant",
            "https://example.com/lamp","CNY",22300L,"CN",true,Instant.now(),"new","036000291452",null,"fixture","lamp-a",Map.of("color","black")));
    }
    public List<Offer> lookup(String id) throws InterruptedException { return search(id); }
    public List<Offer> details(String id) throws InterruptedException { return search(id); }
}

@Component
@Profile("load-fixture & !acceptance & !prod")
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="shopwise.load.both",havingValue="true")
class LoadTestSecondCatalog extends LoadTestCatalog {
    @Override public String name() { return "ebay"; }
}
