package com.shopwise;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(properties={"SHOPIFY_PILOT_SHOP=regression.myshopify.com","SHOPIFY_WEBHOOK_SECRET=regression-webhook-signing-key"})
@EnabledIfEnvironmentVariable(named="SHOPWISE_DB_TESTS",matches="true")
class ShopifyOrderInboxTest {
    @Autowired ShopifyOrderInbox inbox;
    @Autowired JdbcTemplate db;
    @Autowired MerchantSupport support;
    @Autowired MerchantReturns returns;
    private static final String SHOP="regression.myshopify.com";
    private static final String ORDER="gid://shopify/Order/28401";

    private void send(String event,String time,String status,boolean valid) throws Exception {
        byte[] body=("""
            {"admin_graphql_api_id":"%s","updated_at":"%s","financial_status":"%s",
            "customer":{"admin_graphql_api_id":"gid://shopify/Customer/17","email":"private@example.com"}}
            """.formatted(ORDER,time,status)).getBytes(StandardCharsets.UTF_8);
        var mac=Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("regression-webhook-signing-key".getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        var request=new MockHttpServletRequest();request.setContent(body);
        inbox.receive(request,SHOP,valid?Base64.getEncoder().encodeToString(mac.doFinal(body)):"invalid","orders/updated",event);
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void supportRequiresOrderOwnershipAndExplicitStaffMembership() {
        String buyer="buyer-"+java.util.UUID.randomUUID();
        String other="other-"+java.util.UUID.randomUUID();
        String staff="staff-"+java.util.UUID.randomUUID();
        for (String user:java.util.List.of(buyer,other,staff))
            db.update("insert into users(username,password) values (?,'disabled-test-password')",user);
        String order="gid://shopify/Order/72903";
        String customer="gid://shopify/Customer/340";
        db.update("insert into merchant_orders values (?,?,?,now(),'{}')",SHOP,order,customer);
        db.update("insert into merchant_binding values (?,?,?)",SHOP,buyer,customer);
        var request=new MerchantSupport.Request(java.util.UUID.randomUUID(),order,"配送咨询");
        assertThrows(ResponseStatusException.class,()->support.open(()->other,request));
        assertEquals("OPEN",support.open(()->buyer,request).get("status"));
        assertEquals("OPEN",support.open(()->buyer,request).get("status"));
        assertEquals(1,support.own(()->buyer).size());
        assertTrue(support.own(()->other).isEmpty());
        assertThrows(ResponseStatusException.class,()->support.queue(()->staff));
        db.update("insert into merchant_staff values (?,?)",SHOP,staff);
        support.answer(()->staff,request.requestId(),new MerchantSupport.Answer("已核实",true));
        assertEquals("CLOSED",support.own(()->buyer).get(0).get("status"));
    }

    private void refundEvent(String event,String status) throws Exception {
        byte[] body=("""
            {"admin_graphql_api_id":"gid://shopify/Refund/45021","order_id":45020,"created_at":"%s",
             "refund_line_items":[{"line_item_id":19,"quantity":1}],
             "transactions":[{"id":871,"kind":"refund","status":"%s","amount":"19.90","currency":"USD"}]}
            """.formatted(java.time.OffsetDateTime.now().plusSeconds(5),status)).getBytes(StandardCharsets.UTF_8);
        var mac=Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("regression-webhook-signing-key".getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        var request=new MockHttpServletRequest(); request.setContent(body);
        inbox.receive(request,SHOP,Base64.getEncoder().encodeToString(mac.doFinal(body)),"refunds/create",event);
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void refundRequiresOwnershipAllowedQuantityAndSuccessfulMerchantReceipt() throws Exception {
        String buyer="return-test-"+java.util.UUID.randomUUID(), staff=buyer+"-staff";
        for (String user:java.util.List.of(buyer,staff))
            db.update("insert into users(username,password) values (?,'disabled-test-password')",user);
        String order="gid://shopify/Order/45020", customer="gid://shopify/Customer/45020";
        String line="gid://shopify/LineItem/19", refund="gid://shopify/Refund/45021";
        db.update("""
            insert into merchant_orders values (?,?,?,now(),
            '{"financial_status":"paid","returnableLines":[{"id":"gid://shopify/LineItem/19","quantity":2}]}')
            """,SHOP,order,customer);
        db.update("insert into merchant_binding values (?,?,?)",SHOP,buyer,customer);
        db.update("insert into merchant_staff values (?,?)",SHOP,staff);
        var request=new MerchantReturns.Application(java.util.UUID.randomUUID(),order,line,1,"尺码不合");
        var alternate=new MerchantReturns.Application(java.util.UUID.randomUUID(),order,line,1,"尺寸问题");
        var excess=new MerchantReturns.Application(java.util.UUID.randomUUID(),order,line,2,"数量超限");
        var review=new MerchantReturns.Decision(null,null);
        var reconcile=new MerchantReturns.Decision(null,refund);
        assertThrows(ResponseStatusException.class,()->returns.open(()->staff,request));
        assertEquals("REQUESTED",returns.open(()->buyer,request).get("status"));
        assertEquals("REQUESTED",returns.open(()->buyer,request).get("status"));
        assertThrows(ResponseStatusException.class,()->returns.open(()->buyer,excess));
        assertThrows(ResponseStatusException.class,()->returns.decide(()->staff,request.requestId(),"receive",review));
        assertEquals("APPROVED",returns.decide(()->staff,request.requestId(),"approve",review).get("status"));
        assertEquals("RECEIVED",returns.decide(()->staff,request.requestId(),"receive",review).get("status"));
        refundEvent("refund-pending","pending");
        assertThrows(ResponseStatusException.class,()->returns.decide(()->staff,request.requestId(),"reconcile",reconcile));
        refundEvent("refund-success","success");
        refundEvent("refund-success","success");
        var result=returns.decide(()->staff,request.requestId(),"reconcile",reconcile);
        assertEquals("REFUNDED",result.get("status"));
        assertEquals(0,new java.math.BigDecimal("19.90").compareTo((java.math.BigDecimal)result.get("refundAmount")));
        assertEquals("REFUNDED",returns.decide(()->staff,request.requestId(),"reconcile",reconcile).get("status"));
        assertEquals(4,db.queryForObject("select count(*) from merchant_return_event where return_id=?",Integer.class,request.requestId()));
        assertEquals("REQUESTED",returns.open(()->buyer,alternate).get("status"));
        assertEquals("DECLINED",returns.decide(()->staff,alternate.requestId(),"decline",review).get("status"));
    }

    @Test void verifiesDeduplicatesProjectsAndIgnoresOlderUpdates() throws Exception {
        db.update("delete from shopify_inbox where shop=?",SHOP);
        db.update("delete from merchant_orders where shop=?",SHOP);
        assertThrows(ResponseStatusException.class,()->send("invalid","2026-11-15T00:00:00Z","paid",false));
        send("new","2026-11-16T00:00:00Z","paid",true);
        send("new","2026-11-16T00:00:00Z","paid",true);
        send("old","2026-11-15T00:00:00Z","pending",true);
        inbox.project();
        assertEquals(2,db.queryForObject("select count(*) from shopify_inbox where shop=?",Integer.class,SHOP));
        var snapshot=db.queryForObject("select snapshot::text from merchant_orders where shop=? and order_gid=?",String.class,SHOP,ORDER);
        assertTrue(snapshot.contains("paid"));
        assertFalse(snapshot.contains("private@example.com"));
    }
}
