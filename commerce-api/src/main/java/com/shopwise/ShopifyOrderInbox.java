package com.shopwise;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.OffsetDateTime;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.*;

@RestController
class ShopifyOrderInbox {
    private static final int MAX_BODY=1_048_576;
    private static final Set<String> TOPICS=Set.of("orders/create","orders/updated","refunds/create");
    private final String shop;
    private final String secret;
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final ShopifyRefundReceipts refunds;

    ShopifyOrderInbox(JdbcTemplate db,ObjectMapper json,ShopifyRefundReceipts refunds,@Value("${SHOPIFY_PILOT_SHOP:}") String shop,
                      @Value("${SHOPIFY_WEBHOOK_SECRET:}") String secret) {
        this.db=db; this.json=json; this.refunds=refunds; this.shop=shop; this.secret=secret;
        if ((!shop.isEmpty() || !secret.isEmpty()) &&
            (!shop.matches("[a-z0-9][a-z0-9-]*\\.myshopify\\.com") || secret.length()<24))
            throw new IllegalArgumentException("Configure a valid Shopify pilot shop and webhook secret");
    }

    @PostMapping("/webhooks/shopify")
    @Transactional
    ResponseEntity<Void> receive(HttpServletRequest request,
        @RequestHeader("X-Shopify-Shop-Domain") String from,
        @RequestHeader("X-Shopify-Hmac-Sha256") String signature,
        @RequestHeader("X-Shopify-Topic") String topic,
        @RequestHeader("X-Shopify-Event-Id") String event) throws IOException {
        if (shop.isEmpty() || !shop.equals(from)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        byte[] raw=request.getInputStream().readNBytes(MAX_BODY+1);
        if (raw.length>MAX_BODY) throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE);
        if (!verify(raw,signature,secret)) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (!TOPICS.contains(topic)) return ResponseEntity.noContent().build();
        if (!event.matches("[A-Za-z0-9-]{1,128}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        try {
            var data=json.readTree(raw);
            if ("refunds/create".equals(topic)) {
                refunds.ingest(shop,data);
                return ResponseEntity.noContent().build();
            }
            String order=data.required("admin_graphql_api_id").asString();
            if (!order.matches("gid://shopify/Order/[0-9]+")) throw new IllegalArgumentException();
            String customer=data.path("customer").path("admin_graphql_api_id").asString("");
            if (!customer.matches("gid://shopify/Customer/[0-9]+")) customer=null;
            var snapshot=new LinkedHashMap<String,Object>();
            for(String key:List.of("name","currency","current_total_price","financial_status","fulfillment_status","cancelled_at"))
                snapshot.put(key,data.path(key).asString(null));
            var parcels=new ArrayList<Map<String,String>>();
            for(var item:data.path("fulfillments")) {
                var parcel=new LinkedHashMap<String,String>();
                for(String key:List.of("status","tracking_company","tracking_number"))
                    parcel.put(key,item.path(key).asString(null));
                parcels.add(parcel);
            }
            snapshot.put("fulfillments",parcels);
            if (data.has("line_items") && data.has("fulfillments"))
                snapshot.put("returnableLines",ShopifyRefundReceipts.shippedLines(data));
            db.update("""
              insert into shopify_inbox(shop,event_id,order_gid,customer_gid,topic,source_updated,snapshot)
              values (?,?,?,?,?,?,cast(? as jsonb)) on conflict(shop,event_id) do nothing
              """,shop,event,order,customer,topic,OffsetDateTime.parse(data.required("updated_at").asString()),json.writeValueAsString(snapshot));
        } catch(IllegalArgumentException | java.time.DateTimeException | tools.jackson.core.JacksonException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid order event");
        }
        return ResponseEntity.noContent().build();
    }

    static boolean verify(byte[] body,String provided,String secret) {
        try {
            var mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            return MessageDigest.isEqual(mac.doFinal(body),Base64.getDecoder().decode(provided));
        } catch(IllegalArgumentException | GeneralSecurityException invalid) { return false; }
    }

    @Scheduled(fixedDelay=3000)
    @Transactional
    public void project() {
        if (shop.isEmpty()) return;
        var batch=db.queryForList("""
            select shop,event_id,order_gid,customer_gid,source_updated,snapshot::text
              from shopify_inbox where shop=? and processed_at is null
              order by received_at,event_id limit 30 for update skip locked
            """,shop);
        for(var item:batch) {
            db.update("""
                insert into merchant_orders(shop,order_gid,customer_gid,source_updated,snapshot)
                values (?,?,?,?,cast(? as jsonb)) on conflict(shop,order_gid) do update
                set customer_gid=excluded.customer_gid,source_updated=excluded.source_updated,snapshot=merchant_orders.snapshot || excluded.snapshot
                where merchant_orders.source_updated<excluded.source_updated
                """,item.get("shop"),item.get("order_gid"),item.get("customer_gid"),item.get("source_updated"),item.get("snapshot"));
            db.update("update shopify_inbox set processed_at=now() where shop=? and event_id=?",shop,item.get("event_id"));
        }
    }

    @Scheduled(fixedDelay=3600000)
    void purge() { db.update("delete from shopify_inbox where processed_at<now()-interval '7 days'"); }

    @GetMapping("/api/v1/merchant/orders")
    List<JsonNode> orders(java.security.Principal buyer,HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");
        return db.query("""
          select (o.snapshot || jsonb_build_object('id',o.order_gid))::text from merchant_orders o join merchant_binding b
          on b.shop=o.shop and b.customer_gid=o.customer_gid
          where o.shop=? and b.buyer=? order by o.source_updated desc limit 50
          """,(r,i)->json.readTree(r.getString(1)),shop,buyer.getName());
    }
}
