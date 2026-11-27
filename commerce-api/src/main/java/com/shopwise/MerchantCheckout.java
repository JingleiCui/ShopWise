package com.shopwise;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.security.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.*;

@RestController
@RequestMapping("/api/v1/merchant")
class MerchantCheckout {
    record Item(String variantId,int quantity) {}
    record Preview(UUID requestId,List<Item> items) {}
    private static final String FIELDS="""
        id checkoutUrl totalQuantity
        cost { subtotalAmount { amount currencyCode } totalAmount { amount currencyCode } totalTaxAmount { amount currencyCode } }
        lines(first:20) { nodes { quantity merchandise { ... on ProductVariant { id title availableForSale } } } }
        """;
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final String shop,token,encryptionKey;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    MerchantCheckout(JdbcTemplate db,ObjectMapper json,@Value("${SHOPIFY_PILOT_SHOP:}") String shop,
                     @Value("${SHOPIFY_STOREFRONT_TOKEN:}") String token,@Value("${SHOPIFY_CART_ENCRYPTION_KEY:}") String encryptionKey) {
        this.db=db; this.json=json; this.shop=shop; this.token=token; this.encryptionKey=encryptionKey;
        if (!token.isBlank() && (!shop.matches("[a-z0-9][a-z0-9-]*\\.myshopify\\.com") || encryptionKey.length()<32))
            throw new IllegalArgumentException("Checkout requires an approved shop and encryption key");
    }

    @GetMapping("/products")
    List<Map<String,String>> products(java.security.Principal principal,HttpServletResponse response) throws IOException,InterruptedException {
        response.setHeader("Cache-Control","no-store");
        requireBinding(principal.getName());
        var data=call("""
          query @inContext(country: CN) {
            products(first: 12) { nodes {
              title variants(first: 8) { nodes { id title availableForSale price { amount currencyCode } } }
            } }
          }
          """,Map.of());
        var products=new ArrayList<Map<String,String>>();
        for (var product:data.path("products").path("nodes")) {
            for (var variant:product.path("variants").path("nodes")) {
                if (!variant.path("availableForSale").asBoolean(false)) continue;
                products.add(Map.of("id",variant.path("id").asString(),"product",product.path("title").asString(),
                    "variant",variant.path("title").asString(),"amount",variant.path("price").path("amount").asString(""),
                    "currency",variant.path("price").path("currencyCode").asString("")));
            }
        }
        return products;
    }

    @PostMapping("/checkout")
    JsonNode preview(@RequestBody Preview input,java.security.Principal principal,HttpServletResponse response) throws IOException,InterruptedException {
        response.setHeader("Cache-Control","no-store");
        String buyer=principal.getName();
        requireBinding(buyer);
        if (input.requestId()==null || input.items()==null || input.items().isEmpty() || input.items().size()>15)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请提供购物车商品");
        var lines=new LinkedHashMap<String,Integer>();
        for (Item item:input.items()) {
            if (item==null || item.variantId()==null || !item.variantId().matches("gid://shopify/ProductVariant/[0-9]+")
                || item.quantity()<1 || item.quantity()>20 || lines.putIfAbsent(item.variantId(),item.quantity())!=null)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"购物车规格或数量无效");
        }
        var variables=Map.of("input",Map.of("buyerIdentity",Map.of("countryCode","CN"),"lines",
            lines.entrySet().stream().map(e->Map.of("merchandiseId",e.getKey(),"quantity",e.getValue())).toList()));
        var saved=saved(input.requestId(),buyer,String.class,"quote::text");
        if (!saved.isEmpty()) {
            JsonNode quote=json.readTree(saved.get(0));
            var expected=json.valueToTree(lines);
            if (!expected.equals(quote.path("requested"))) throw new ResponseStatusException(HttpStatus.CONFLICT,"此请求已用于其他商品");
            return quote;
        }
        var result=call("mutation($input:CartInput!){cartCreate(input:$input){cart{"+FIELDS+"} userErrors{code}}}",variables).path("cartCreate");
        if (!result.path("userErrors").isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT,"商家暂时无法按所选规格备货");
        var cart=result.path("cart");
        var quote=quote(input.requestId(),cart,lines);
        db.update("""
          insert into merchant_checkout(id,shop,buyer,cart_id,quote,expires_at)
          values (?,?,?,?,cast(? as jsonb),now()+interval '5 minutes')
          on conflict(id) do nothing
          """,input.requestId(),shop,buyer,encrypt(cart.required("id").asString()),json.writeValueAsString(quote));
        var winner=saved(input.requestId(),buyer,String.class,"quote::text");
        if (winner.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT,"报价已失效，请重新核价");
        JsonNode existing=json.readTree(winner.get(0));
        if (!json.valueToTree(lines).equals(existing.path("requested"))) throw new ResponseStatusException(HttpStatus.CONFLICT,"此请求已用于其他商品");
        return existing;
    }

    @PostMapping("/checkout/{id}/confirm")
    Map<String,String> confirm(@PathVariable UUID id,java.security.Principal principal,HttpServletResponse response) throws IOException,InterruptedException {
        response.setHeader("Cache-Control","no-store");
        String buyer=principal.getName();
        requireBinding(buyer);
        var quotes=saved(id,buyer,String.class,"quote::text");
        var carts=saved(id,buyer,byte[].class,"cart_id");
        if (quotes.isEmpty() || carts.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT,"报价已失效，请重新核价");
        JsonNode previous=json.readTree(quotes.get(0));
        var requested=new LinkedHashMap<String,Integer>();
        for (var entry:previous.path("requested").properties()) requested.put(entry.getKey(),entry.getValue().intValue());
        var cart=call("query($id:ID!){cart(id:$id){"+FIELDS+"}}",Map.of("id",decrypt(carts.get(0)))).path("cart");
        var current=quote(id,cart,requested);
        if (!current.equals(previous)) throw new ResponseStatusException(HttpStatus.CONFLICT,"商品或金额已变动，请重新核价");
        URI url=URI.create(cart.required("checkoutUrl").asString());
        if (!"https".equals(url.getScheme()) || url.getHost()==null || url.getUserInfo()!=null)
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"商家结账链接无效");
        return Map.of("checkoutUrl",url.toString());
    }

    private void requireBinding(String buyer) {
        if (token.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"商家结账尚未启用");
        if (db.queryForList("select 1 from merchant_binding where shop=? and buyer=?",Integer.class,shop,buyer).isEmpty())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"请先绑定合作店买家身份");
    }
    private <T> List<T> saved(UUID id,String buyer,Class<T> type,String field) {
        return db.queryForList("select "+field+" from merchant_checkout where id=? and shop=? and buyer=? and expires_at>now()",type,id,shop,buyer);
    }
    private JsonNode quote(UUID id,JsonNode cart,Map<String,Integer> requested) {
        var actual=new LinkedHashMap<String,Integer>();
        for (var line:cart.path("lines").path("nodes")) {
            var variant=line.path("merchandise");
            if (!variant.path("availableForSale").asBoolean(false)) throw new ResponseStatusException(HttpStatus.CONFLICT,"商家规格已不可售");
            actual.put(variant.path("id").asString(),line.path("quantity").intValue());
        }
        if (!actual.equals(requested) || !cart.path("cost").hasNonNull("totalAmount"))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"商家已调整购物车，请重新核价");
        return json.valueToTree(Map.of("id",id,"requested",requested,"cost",cart.path("cost"),"shippingStatus","UNKNOWN","finalTotal",false));
    }
    private byte[] encrypt(String cart) {
        byte[] nonce=new byte[12];
        new SecureRandom().nextBytes(nonce);
        byte[] contents=crypt(Cipher.ENCRYPT_MODE,nonce,cart.getBytes(StandardCharsets.UTF_8));
        return java.nio.ByteBuffer.allocate(nonce.length+contents.length).put(nonce).put(contents).array();
    }
    private String decrypt(byte[] encrypted) {
        return new String(crypt(Cipher.DECRYPT_MODE,Arrays.copyOf(encrypted,12),
            Arrays.copyOfRange(encrypted,12,encrypted.length)),StandardCharsets.UTF_8);
    }
    private byte[] crypt(int mode,byte[] nonce,byte[] bytes) {
        try {
            var key=MessageDigest.getInstance("SHA-256").digest(encryptionKey.getBytes(StandardCharsets.UTF_8));
            var cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
            return cipher.doFinal(bytes);
        } catch(GeneralSecurityException e) { throw new IllegalStateException("Cart encryption failed",e); }
    }
    private JsonNode call(String query,Map<String,?> variables) throws IOException,InterruptedException {
        var request=HttpRequest.newBuilder(URI.create("https://"+shop+"/api/2026-10/graphql.json"))
            .timeout(Duration.ofSeconds(7)).header("Content-Type","application/json")
            .header("Shopify-Storefront-Private-Token",token)
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("query",query,"variables",variables)))).build();
        var response=http.send(request,HttpResponse.BodyHandlers.ofString());
        if (response.statusCode()!=200) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"无法连接商家结账");
        var data=json.readTree(response.body());
        if (data.has("errors") || !data.hasNonNull("data")) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"商家结账暂不可用");
        return data.path("data");
    }

    @Scheduled(fixedDelay=3600000)
    void expire() { db.update("delete from merchant_checkout where expires_at<now()-interval '1 day'"); }
}
