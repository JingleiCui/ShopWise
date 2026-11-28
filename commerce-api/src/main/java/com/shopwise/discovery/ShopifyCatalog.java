package com.shopwise.discovery;

import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.*;

@Component
@org.springframework.context.annotation.Profile("prod | !load-fixture")
class ShopifyCatalog implements CatalogProvider {
    private final ObjectMapper json;
    private final boolean enabled;
    private final URI endpoint;
    private final String profile;
    private final ShopifyCatalogToken tokens;
    private final HttpClient http = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(6)).build();
    private static final Map<String,String> CONTEXT = Map.of("address_country","CN","currency","CNY");
    private static final Map<String,Object> FILTERS = Map.of("ships_to",Map.of("country","CN"),"available",true);

    ShopifyCatalog(ObjectMapper json, @Value("${SHOPIFY_ENABLED:true}") boolean enabled,
                   @Value("${SHOPIFY_MCP_URL:https://catalog.shopify.com/api/ucp/mcp}") URI endpoint,
                   @Value("${SHOPIFY_AGENT_PROFILE:https://shopify.dev/ucp/agent-profiles/examples/2026-08-25/valid-with-capabilities.json}") String profile,
                   ShopifyCatalogToken tokens) {
        if (!"https".equals(endpoint.getScheme()) || !"catalog.shopify.com".equals(endpoint.getHost()))
            throw new IllegalArgumentException("Shopify catalog endpoint must use the official HTTPS origin");
        this.json=json; this.enabled=enabled; this.endpoint=endpoint; this.profile=profile; this.tokens=tokens;
    }

    public String name() { return "shopify"; }
    public boolean enabled() { return enabled; }
    public List<Offer> search(String query) throws Exception {
        return call("search_catalog", Map.of("query",query,"context",CONTEXT,"filters",FILTERS,"pagination",Map.of("limit",5)));
    }
    public List<Offer> lookup(String id) throws Exception {
        return lookupAll(List.of(id));
    }
    @Override public List<Offer> lookupAll(List<String> ids) throws Exception {
        return call("lookup_catalog", Map.of("ids",ids,"context",CONTEXT,"filters",Map.of("ships_to",Map.of("country","CN"))));
    }
    public List<Offer> details(String id) throws Exception {
        return call("get_product", Map.of("id",id,"context",CONTEXT));
    }
    private List<Offer> call(String tool, Map<String,Object> catalog) throws Exception {
        var input = Map.of("jsonrpc","2.0","id",1,"method","tools/call","params",Map.of("name",tool,
            "arguments",Map.of("catalog",catalog,"meta",Map.of("ucp-agent",Map.of("profile",profile)))));
        var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofMillis(7500))
            .header("Content-Type","application/json").header("Accept","application/json, text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(input)));
        String token=tokens.get();
        if (!token.isBlank()) request.header("Authorization","Bearer " + token);
        var reply=http.send(request.build(),HttpResponse.BodyHandlers.ofString());
        if (reply.statusCode()==401 && !token.isBlank()) tokens.invalidate(token);
        if (reply.statusCode()!=200) throw new java.io.IOException("catalog HTTP " + reply.statusCode());
        String body=reply.body();
        if (reply.headers().firstValue("Content-Type").orElse("").contains("text/event-stream"))
            body=body.lines().filter(l -> l.startsWith("data:")).map(l -> l.substring(5).strip()).findFirst().orElseThrow();
        var envelope=json.readTree(body);
        var result=envelope.path("result");
        if (envelope.has("error") || result.path("isError").asBoolean(false)) throw new java.io.IOException("catalog RPC error");
        var data=result.path("structuredContent");
        if (data.isMissingNode()) {
            for (var block : result.path("content")) if ("text".equals(block.path("type").asString(""))) {
                data=json.readTree(block.path("text").asString()); break;
            }
        }
        if (!data.has("products") && !data.has("product")) throw new java.io.IOException("invalid catalog payload");
        var offers=new ArrayList<Offer>();
        var now=Instant.now();
        if (data.has("product")) append(data.path("product"),now,offers);
        for (var product : data.path("products")) append(product,now,offers);
        return offers;
    }

    private String image(JsonNode variant, JsonNode product) {
        for (var node : List.of(variant, product)) {
            for (var media : node.path("media")) {
                if (!"image".equals(media.path("type").asString(""))) continue;
                String url = Offer.imageUrl(media.path("url").asString(""));
                if (url != null) return url;
            }
        }
        return null;
    }

    private void append(JsonNode product, Instant at, List<Offer> offers) {
        for (var variant : product.path("variants")) {
            var price=variant.path("price");
            String link=Offer.merchantUrl(variant.path("url").asString(""));
            String id=variant.path("id").asString("");
            if (link.isEmpty() || id.isEmpty() || !product.hasNonNull("title")) continue;
            List<String> specs=new ArrayList<>();
            Map<String,String> attributes=new TreeMap<>();
            for (var option : variant.path("options")) {
                String name=option.path("name").asString("");
                String label=option.path("label").asString("");
                specs.add(name+" "+label);
                ProductMatch.attribute(attributes,name,label);
            }
            var metadata=variant.path("metadata");
            var condition=variant.path("condition");
            String grade=condition.isArray() && condition.size()==1?condition.get(0).asString(""):condition.asString("");
            var available=variant.path("availability").path("available");
            offers.add(new Offer(name(),product.path("id").asString(""),id,product.path("title").asString(),
                String.join(" / ",specs),variant.path("seller").path("name").asString("Shopify"),link,
                price.path("currency").asString(null),price.path("amount").isIntegralNumber() ? price.path("amount").longValue() : null,
                "CN",available.isBoolean()?available.booleanValue():null,at,grade,
                ProductMatch.gtin(variant.path("gtin").asString(metadata.path("gtin").asString(null))),null,
                ProductMatch.identifier(metadata.path("brand").asString(null)),
                ProductMatch.identifier(metadata.path("mpn").asString(null)),Map.copyOf(attributes), image(variant, product)));
        }
    }
}
