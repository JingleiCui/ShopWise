package com.shopwise.discovery;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.*;

@Component
@org.springframework.context.annotation.Profile("prod | !load-fixture")
class EbayCatalog implements CatalogProvider {
    private final ObjectMapper json;
    private final String clientId, secret, base;
    private final boolean enabled;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private String access="";
    private Instant expires=Instant.MIN;
    EbayCatalog(ObjectMapper json,@Value("${EBAY_ENABLED:false}") boolean enabled,@Value("${EBAY_CLIENT_ID:}") String clientId,
                @Value("${EBAY_CLIENT_SECRET:}") String secret,@Value("${EBAY_SANDBOX:true}") boolean sandbox) {
        this.json=json; this.enabled=enabled; this.clientId=clientId; this.secret=secret;
        base=sandbox?"https://api.sandbox.ebay.com":"https://api.ebay.com";
    }
    public String name() { return "ebay"; }
    public boolean enabled() { return enabled; }
    private synchronized String token() throws Exception {
        if (expires.isAfter(Instant.now())) return access;
        var basic=Base64.getEncoder().encodeToString((clientId+":"+secret).getBytes(StandardCharsets.UTF_8));
        var request=HttpRequest.newBuilder(URI.create(base+"/identity/v1/oauth2/token")).timeout(Duration.ofSeconds(4))
            .header("Authorization","Basic "+basic).header("Content-Type","application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials&scope=https%3A%2F%2Fapi.ebay.com%2Foauth%2Fapi_scope")).build();
        var data=read(request);
        access=data.required("access_token").asString();
        expires=Instant.now().plusSeconds(Math.max(0,data.path("expires_in").asLong(0)-60)); return access;
    }
    private JsonNode read(HttpRequest request) throws Exception {
        var res=http.send(request,HttpResponse.BodyHandlers.ofString());
        if (res.statusCode()!=200) throw new java.io.IOException("eBay HTTP "+res.statusCode());
        return json.readTree(res.body());
    }
    private JsonNode get(String path) throws Exception {
        return read(HttpRequest.newBuilder(URI.create(base+"/buy/browse/v1/"+path)).timeout(Duration.ofSeconds(6))
            .header("Authorization","Bearer "+token()).header("X-EBAY-C-MARKETPLACE-ID","EBAY_US")
            .header("X-EBAY-C-ENDUSERCTX","contextualLocation=country%3DCN").build());
    }
    private static String encode(String v) { return URLEncoder.encode(v,StandardCharsets.UTF_8).replace("+","%20"); }
    public List<Offer> search(String query) throws Exception {
        String searchKey=ProductMatch.gtin(query)!=null?"gtin":"q";
        var data=get("item_summary/search?"+searchKey+"="+encode(query)+"&limit=5&filter=deliveryCountry%3ACN");
        Map<String,JsonNode> summaries=new LinkedHashMap<>();
        for (var item:data.path("itemSummaries")) summaries.put(item.required("itemId").asString(),item);
        Map<String,JsonNode> detail=new HashMap<>();
        if (!summaries.isEmpty()) {
            try {
                String ids=String.join(",",summaries.keySet().stream().map(EbayCatalog::encode).toList());
                for (var item:get("item?item_ids="+ids).path("items")) detail.put(item.required("itemId").asString(),item);
            } catch (java.io.IOException | tools.jackson.core.JacksonException partial) {
                // Search results are still useful without a stronger match signal.
            }
        }
        var offers=new ArrayList<Offer>();
        for (var listing:summaries.entrySet()) add(listing.getValue(),detail.getOrDefault(listing.getKey(),listing.getValue()),offers);
        return offers;
    }
    public List<Offer> lookup(String id) throws Exception { return details(id); }
    public List<Offer> details(String id) throws Exception {
        var item=get("item/"+encode(id));
        var offers=new ArrayList<Offer>(); add(item,item,offers); return offers;
    }
    private void add(JsonNode item,JsonNode details,List<Offer> offers) {
        String link=Offer.merchantUrl(item.path("itemAffiliateWebUrl").asString(item.path("itemWebUrl").asString("")));
        if (link.isEmpty()) return;
        var price=item.path("price");
        String currency=price.path("currency").asString(null);
        Long amount=null;
        if (currency!=null && price.hasNonNull("value")) {
            int digits=Currency.getInstance(currency).getDefaultFractionDigits();
            if (digits>=0) amount=new java.math.BigDecimal(price.path("value").asString()).movePointRight(digits).longValueExact();
        }
        var id=item.required("itemId").asString();
        Map<String,String> specs=new TreeMap<>();
        String brand=details.path("brand").asString(null), mpn=details.path("mpn").asString(null);
        for (var aspect:details.path("localizedAspects")) {
            String name=aspect.path("name").asString("");
            String value=aspect.path("value").asString("");
            if ("brand".equalsIgnoreCase(name) && brand==null) brand=value;
            if ("mpn".equalsIgnoreCase(name) && mpn==null) mpn=value;
            ProductMatch.attribute(specs,name,value);
        }
        boolean itemGroup=item.path("itemGroupType").asString("").equals("SELLER_DEFINED_VARIATIONS")
            && !details.has("localizedAspects");
        String gtin=itemGroup?null:ProductMatch.gtin(details.path("gtin").asString(null));
        offers.add(new Offer(name(),id,id,item.required("title").asString(),"",item.path("seller").path("username").asString("eBay"),
            link,currency,amount,"CN",null,Instant.now(),item.path("conditionId").asString(item.path("condition").asString("")),gtin,null,
            ProductMatch.identifier(brand),ProductMatch.identifier(mpn),Map.copyOf(specs),
            Offer.imageUrl(item.path("image").path("imageUrl").asString(""))));
    }
}
