package com.shopwise;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
class ShopifyRefundReceipts {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final String adminToken;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    ShopifyRefundReceipts(JdbcTemplate db,ObjectMapper json,@Value("${SHOPIFY_ADMIN_TOKEN:}") String adminToken) {
        this.db=db; this.json=json; this.adminToken=adminToken;
    }

    void refresh(String shop,String receipt,String order,String line,int quantity) {
        if (adminToken.isBlank()) return;
        String query="""
            query($id:ID!) { refund(id:$id) {
              id createdAt order { id } duties { amountSet { shopMoney { amount } } }
              orderAdjustments(first:1) { nodes { id } }
              refundShippingLines(first:1) { nodes { id } }
              refundLineItems(first:2) { nodes { quantity lineItem { id } } }
              transactions(first:20) { pageInfo { hasNextPage } nodes {
                id status kind amountSet { presentmentMoney { amount currencyCode } }
              } }
            } }
            """;
        var req=HttpRequest.newBuilder(URI.create("https://"+shop+"/admin/api/2026-10/graphql.json"))
            .timeout(Duration.ofSeconds(5)).header("Content-Type","application/json").header("X-Shopify-Access-Token",adminToken)
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("query",query,"variables",Map.of("id",receipt))))).build();
        try {
            var res=http.send(req,HttpResponse.BodyHandlers.ofString());
            if (res.statusCode()!=200) throw new java.io.IOException();
            var envelope=json.readTree(res.body());
            if (envelope.has("errors")) throw new java.io.IOException();
            var refund=envelope.path("data").path("refund");
            var lines=refund.path("refundLineItems").path("nodes");
            if (!receipt.equals(refund.path("id").asString("")) || !order.equals(refund.path("order").path("id").asString("")) ||
                lines.size()!=1 || !line.equals(lines.get(0).path("lineItem").path("id").asString("")) ||
                quantity!=lines.get(0).path("quantity").asInt(0) || refund.path("transactions").path("pageInfo").path("hasNextPage").asBoolean(true) ||
                refund.path("refundShippingLines").path("nodes").size()>0 || refund.path("orderAdjustments").path("nodes").size()>0 ||
                refund.path("duties").size()>0) return;
            var data=json.createObjectNode();
            data.put("admin_graphql_api_id",receipt);
            data.put("order_id",numeric(order));
            data.put("created_at",refund.path("createdAt").asString(""));
            data.putArray("refund_line_items").addObject().put("line_item_id",numeric(line)).put("quantity",quantity);
            var transactions=data.putArray("transactions");
            for (var tx:refund.path("transactions").path("nodes")) {
                var money=tx.path("amountSet").path("presentmentMoney");
                transactions.addObject().put("id",numeric(tx.path("id").asString(""))).put("kind",tx.path("kind").asString(""))
                    .put("status",tx.path("status").asString("")).put("currency",money.path("currencyCode").asString(""))
                    .put("amount",money.path("amount").asString(""));
            }
            ingest(shop,data);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"退款核对已中断，请稍后重试");
        } catch (java.io.IOException | tools.jackson.core.JacksonException unavailable) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"暂时无法核对商家退款记录");
        }
    }
    private static String numeric(String id) { return id.substring(id.lastIndexOf('/')+1); }

    void ingest(String shop,JsonNode refund) {
        String id=refund.path("admin_graphql_api_id").asString("");
        String order="gid://shopify/Order/"+refund.path("order_id").asString("");
        if (!MerchantReturns.gid(id,"Refund") || !MerchantReturns.gid(order,"Order")) throw new IllegalArgumentException();
        java.time.OffsetDateTime sourceCreated;
        try { sourceCreated=java.time.OffsetDateTime.parse(refund.path("created_at").asString("")); }
        catch (java.time.DateTimeException invalid) { return; }
        var lines=refund.path("refund_line_items");
        if (!lines.isArray() || lines.size()!=1) return;
        var line=lines.get(0);
        String lineId="gid://shopify/LineItem/"+line.path("line_item_id").asString("");
        int quantity=line.path("quantity").asInt(0);
        if (!MerchantReturns.gid(lineId,"LineItem") || quantity<1) return;
        if (refund.path("order_adjustments").size()>0 || refund.path("refund_shipping_lines").size()>0 || refund.path("duties").size()>0) return;
        BigDecimal amount=BigDecimal.ZERO;
        String currency=null;
        Set<String> transactions=new HashSet<>();
        for (var tx:refund.path("transactions")) {
            if (!"refund".equalsIgnoreCase(tx.path("kind").asString(""))) continue;
            if (!"success".equalsIgnoreCase(tx.path("status").asString(""))) return;
            String txId=tx.path("id").asString("");
            if (!txId.matches("[0-9]+") || !transactions.add(txId)) return;
            String code=tx.path("currency").asString("");
            if (currency==null) currency=code;
            if (!currency.equals(code)) return;
            String value=tx.path("amount").asString("");
            if (!value.matches("[0-9]{1,16}(?:\\.[0-9]{1,4})?")) return;
            BigDecimal paid=new BigDecimal(value);
            if (paid.signum()<=0) return;
            amount=amount.add(paid);
        }
        if (currency==null) return;
        int scale=Currency.getInstance(currency).getDefaultFractionDigits();
        if (scale<0 || amount.stripTrailingZeros().scale()>scale || amount.precision()-amount.scale()>16) return;
        // The order lock serializes a late refund receipt with a new request for the same item.
        db.queryForList("select 1 from merchant_orders where shop=? and order_gid=? for update",Integer.class,shop,order);
        db.update("""
            insert into merchant_refund_receipt(shop,id,order_gid,line_gid,quantity,amount,currency,source_created_at)
            values (?,?,?,?,?,?,?,?) on conflict(shop,id) do nothing
            """,shop,id,order,lineId,quantity,amount,currency,sourceCreated);
    }

    static List<Map<String,Object>> shippedLines(JsonNode order) {
        Map<String,Long> shipped=new HashMap<>();
        for (var shipment:order.path("fulfillments")) {
            if (!"success".equalsIgnoreCase(shipment.path("status").asString(""))) continue;
            for (var line:shipment.path("line_items")) {
                String numeric=line.path("id").asString("");
                int quantity=line.path("quantity").asInt(0);
                if (numeric.matches("[0-9]{1,20}") && quantity>0) shipped.merge(numeric,(long)quantity,Long::sum);
            }
        }
        List<Map<String,Object>> lines=new ArrayList<>();
        for (var line:order.path("line_items")) {
            String numeric=line.path("id").asString("");
            String id="gid://shopify/LineItem/"+numeric;
            long quantity=Math.min(line.path("quantity").asInt(0),shipped.getOrDefault(numeric,0L));
            if (quantity>0 && MerchantReturns.gid(id,"LineItem"))
                lines.add(Map.of("id",id,"quantity",quantity,"title",line.path("name").asString("商品")));
        }
        return lines;
    }
}
