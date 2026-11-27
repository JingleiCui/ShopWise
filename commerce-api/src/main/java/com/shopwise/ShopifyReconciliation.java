package com.shopwise;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.*;

@Component
class ShopifyReconciliation {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final String shop,token;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    ShopifyReconciliation(JdbcTemplate db,ObjectMapper json,@Value("${SHOPIFY_PILOT_SHOP:}") String shop,
                          @Value("${SHOPIFY_ADMIN_TOKEN:}") String token) {
        this.db=db; this.json=json; this.shop=shop; this.token=token;
        if (!token.isBlank() && !shop.matches("[a-z0-9][a-z0-9-]*\\.myshopify\\.com"))
            throw new IllegalArgumentException("Reconciliation requires a verified pilot shop domain");
    }

    @Scheduled(initialDelay=30000,fixedDelay=300000)
    void reconcile() {
        if (token.isBlank()) return;
        var watermark=db.query("select watermark from merchant_sync where shop=?",
            (r,i)->r.getObject(1,OffsetDateTime.class),shop);
        var until=OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1);
        var since=watermark.isEmpty()?until.minusDays(1):watermark.get(0).minusMinutes(2);
        if (!until.isAfter(since)) return;
        String filter="updated_at:>='"+since.toInstant()+"' updated_at:<'"+until.toInstant()+"'";
        try {
            String cursor=null;
            do {
                JsonNode orders=page(filter,cursor);
                for (JsonNode order:orders.path("nodes")) upsert(order);
                cursor=orders.path("pageInfo").path("hasNextPage").asBoolean(false)?
                    orders.path("pageInfo").required("endCursor").asString():null;
            } while (cursor!=null);
            db.update("""
                insert into merchant_sync(shop,watermark,last_success) values (?, ?, now())
                on conflict(shop) do update set watermark=greatest(merchant_sync.watermark,excluded.watermark),
                last_success=now(),last_error=null
                """,shop,until);
        } catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch(IOException | RuntimeException unavailable) {
            db.update("""
                insert into merchant_sync(shop,watermark,last_success,last_error) values (?,?,'-infinity',now())
                on conflict(shop) do update set last_error=now()
                """,shop,since);
        }
    }

    private JsonNode page(String filter,String cursor) throws IOException,InterruptedException {
        String query="""
            query($query:String!,$cursor:String) {
              orders(first:50,query:$query,after:$cursor,sortKey:UPDATED_AT) {
                pageInfo { hasNextPage endCursor }
                nodes {
                  id name updatedAt cancelledAt displayFinancialStatus displayFulfillmentStatus
                  customer { id } currentTotalPriceSet { presentmentMoney { amount currencyCode } }
                  fulfillments(first:20) { status trackingInfo { company number } }
                }
              }
            }
            """;
        Map<String,String> vars=cursor==null?Map.of("query",filter):Map.of("query",filter,"cursor",cursor);
        var request=HttpRequest.newBuilder(URI.create("https://"+shop+"/admin/api/2026-10/graphql.json"))
            .timeout(Duration.ofSeconds(8)).header("Content-Type","application/json").header("X-Shopify-Access-Token",token)
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("query",query,"variables",vars)))).build();
        var response=http.send(request,HttpResponse.BodyHandlers.ofString());
        if (response.statusCode()!=200) throw new IOException("Admin orders unavailable");
        var result=json.readTree(response.body());
        if (result.has("errors") || !result.path("data").hasNonNull("orders")) throw new IOException("Admin orders query failed");
        return result.path("data").path("orders");
    }

    void upsert(JsonNode order) {
        var price=order.path("currentTotalPriceSet").path("presentmentMoney");
        var snapshot=new LinkedHashMap<String,Object>();
        snapshot.put("name",order.path("name").asString(null));
        snapshot.put("currency",price.path("currencyCode").asString(null));
        snapshot.put("current_total_price",price.path("amount").asString(null));
        snapshot.put("financial_status",order.path("displayFinancialStatus").asString("").toLowerCase(Locale.ROOT));
        snapshot.put("fulfillment_status",order.path("displayFulfillmentStatus").asString("").toLowerCase(Locale.ROOT));
        snapshot.put("cancelled_at",order.path("cancelledAt").asString(null));
        var parcels=new ArrayList<Map<String,String>>();
        for(var fulfillment:order.path("fulfillments")) for(var tracking:fulfillment.path("trackingInfo")) {
            var parcel=new LinkedHashMap<String,String>();
            parcel.put("tracking_company",tracking.path("company").asString(null));
            parcel.put("tracking_number",tracking.path("number").asString(null));
            parcels.add(parcel);
        }
        snapshot.put("fulfillments",parcels);
        db.update("""
            insert into merchant_orders(shop,order_gid,customer_gid,source_updated,snapshot)
            values (?,?,?,?,cast(? as jsonb)) on conflict(shop,order_gid) do update
            set customer_gid=excluded.customer_gid,source_updated=excluded.source_updated,snapshot=merchant_orders.snapshot || excluded.snapshot
            where merchant_orders.source_updated<=excluded.source_updated
            """,shop,order.required("id").asString(),order.path("customer").path("id").asString(null),
            OffsetDateTime.parse(order.required("updatedAt").asString()),json.writeValueAsString(snapshot));
    }
}
