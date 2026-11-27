package com.shopwise.discovery;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class ProductMatchTest {
    private Offer offer(String source,String id,String title,String condition,String gtin,Map<String,String> specs) {
        return new Offer(source,id,id,title,"","seller","https://example.com", "CNY",10000L,"CN",true,
            Instant.EPOCH,condition,gtin,null,"sony","wh-1000",specs);
    }
    @Test void matchesDifferentGtinRepresentationsAcrossPlatformsOnly() {
        var a=offer("shopify","a","Sony headphones","new","036000291452",Map.of("color","black"));
        var b=offer("ebay","b","Sony headphones","1000","00036000291452",Map.of("color","black"));
        assertEquals("GTIN",ProductMatch.crossPlatform(List.of(a,b)).get(0).evidence());
        assertTrue(ProductMatch.crossPlatform(List.of(a)).isEmpty());
    }
    @Test void rejectsConflictsAndDoesNotInferFromTitle() {
        var a=offer("shopify","a","Sony 128GB","new","036000291452",Map.of("color","black"));
        assertNull(ProductMatch.evidence(a,offer("ebay","b","Sony 256GB","new","036000291452",Map.of("color","black"))));
        assertNull(ProductMatch.evidence(a,offer("ebay","b","Sony 128GB","used","036000291452",Map.of("color","black"))));
        assertNull(ProductMatch.evidence(a,offer("ebay","b","Sony 128GB 2-pack","new","036000291452",Map.of("color","black"))));
        assertNull(ProductMatch.evidence(a,offer("ebay","b","Sony 128GB","new","036000291452",Map.of("color","white"))));
        assertNull(ProductMatch.evidence(a,offer("ebay","b","Sony 128GB","new",null,Map.of())));
        assertNull(ProductMatch.gtin("036000291453"));
    }
    @Test void fallbackRequiresStructuredIdentityAndVariant() {
        var a=offer("shopify","a","Sony","new",null,Map.of("color","black","edition","asia"));
        var b=offer("ebay","b","Sony","new",null,Map.of("color","black","edition","asia"));
        assertEquals("BRAND_MPN",ProductMatch.crossPlatform(List.of(a,b)).get(0).evidence());
    }
}
