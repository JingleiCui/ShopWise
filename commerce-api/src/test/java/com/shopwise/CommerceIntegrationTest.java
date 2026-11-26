package com.shopwise;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "SHOPWISE_DB_TESTS", matches = "true")
class CommerceIntegrationTest {
  @Autowired CommerceService service;
  @Autowired JdbcTemplate db;
  String buyer, other;
  long sku;

  @BeforeEach
  void setup() {
    buyer = UUID.randomUUID().toString();
    other = UUID.randomUUID().toString();
    var pid = db.queryForObject("select min(id) from products", Long.class);
    sku =
        db.queryForObject(
            "insert into skus(product_id,label,price,stock) values(?,?,20,3) returning id",
            Long.class,
            pid,
            "test-" + buyer);
  }

  int stock() {
    return db.queryForObject("select stock from skus where id=?", Integer.class, sku);
  }

  String quote(String owner, int count) {
    service.setQuantity(owner, sku, count);
    return (String) service.quote(owner, null).get("id");
  }

  @Test
  void concurrentConfirmAndReturnLifecycle() throws Exception {
    var quote = quote(buyer, 2);
    String order;
    try (var pool = new CloseablePool()) {
      var a = pool.executor.submit(() -> service.confirm(buyer, quote));
      var b = pool.executor.submit(() -> service.confirm(buyer, quote));
      order = (String) a.get(5, TimeUnit.SECONDS).get("id");
      assertEquals(order, b.get(5, TimeUnit.SECONDS).get("id"));
    }
    assertEquals(1, stock());
    assertEquals(order, service.getQuote(buyer, quote).get("orderId"));
    assertThrows(ResponseStatusException.class, () -> service.order(other, false, order));
    service.act(buyer, false, order, "pay");
    assertThrows(ResponseStatusException.class, () -> service.act(buyer, false, order, "receive"));
    service.act("staff", true, order, "ship");
    service.act(buyer, false, order, "receive");
    var request = new CommerceService.ReturnRequest(sku, 1, "测试退货", null);
    String rid = (String) service.requestReturn(buyer, order, request, "key").get("id");
    assertEquals(rid, service.requestReturn(buyer, order, request, "key").get("id"));
    assertThrows(
        ResponseStatusException.class,
        () ->
            service.requestReturn(
                buyer, order, new CommerceService.ReturnRequest(sku, 2, "整单", null), null));
    service.returnAction(rid, "approve");
    var refunded = service.returnAction(rid, "refund");
    assertEquals("REFUNDED", refunded.get("status"));
    assertEquals(new BigDecimal("20.00"), refunded.get("amount"));
    service.returnAction(rid, "refund");
    assertEquals(2, stock());
  }

  @Test
  void replaysAddAndQuoteAfterChangingCart() {
    service.addQuantity(buyer, sku, 1, "retry");
    var quote = service.quote(buyer, "quote-request");
    service.addQuantity(buyer, sku, 1, "retry");
    assertEquals(quote.get("id"), service.quote(buyer, "quote-request").get("id"));
    var order = service.confirm(buyer, (String) quote.get("id"));
    service.addQuantity(buyer, sku, 1, "retry");
    assertEquals(List.of(), service.cart(buyer).get("lines"));
    assertEquals(new BigDecimal("20.00"), order.get("subtotal"));
    assertThrows(ResponseStatusException.class, () -> service.addQuantity(buyer, sku, 2, "retry"));
  }

  @Test
  void revalidationIsolationAndExpiry() {
    String first = quote(buyer, 3);
    String second = quote(other, 3);
    String order = (String) service.confirm(buyer, first).get("id");
    assertThrows(ResponseStatusException.class, () -> service.confirm(other, second));
    assertEquals(0, stock());
    db.update("update orders set expires_at=now()-interval '1 second' where id=?", order);
    assertThrows(CommerceService.Expired.class, () -> service.act(buyer, false, order, "pay"));
    assertEquals("EXPIRED", service.order(buyer, false, order).get("status"));
    assertEquals(3, stock());
    db.update("update skus set price=21 where id=?", sku);
    assertThrows(ResponseStatusException.class, () -> service.confirm(other, second));
    String stale = quote(other, 1);
    service.setQuantity(other, sku, 2);
    assertThrows(ResponseStatusException.class, () -> service.confirm(other, stale));
    String active = quote(other, 2);
    String cancel = (String) service.confirm(other, active).get("id");
    service.act(other, false, cancel, "cancel");
    service.act(other, false, cancel, "cancel");
    assertEquals(3, stock());
  }

  private static class CloseablePool implements AutoCloseable {
    final ExecutorService executor = Executors.newFixedThreadPool(2);

    public void close() {
      executor.shutdownNow();
    }
  }
}
