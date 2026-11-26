package com.shopwise;

import static org.springframework.http.HttpStatus.*;

import java.math.BigDecimal;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CommerceService {
  private final JdbcTemplate db;

  CommerceService(JdbcTemplate db) {
    this.db = db;
  }

  static ResponseStatusException error(HttpStatus code, String message) {
    return new ResponseStatusException(code, message);
  }

  static String id(String prefix) {
    return prefix + "-" + UUID.randomUUID();
  }

  Map<String, Object> one(String sql, String missing, Object... args) {
    return db.queryForList(sql, args).stream()
        .findFirst()
        .orElseThrow(() -> error(NOT_FOUND, missing));
  }

  private long lockCart(String buyer) {
    db.update("insert into buyer_carts(buyer) values(?) on conflict do nothing", buyer);
    return db.queryForObject(
        "select revision from buyer_carts where buyer=? for update", Long.class, buyer);
  }

  private void advanceCart(String buyer) {
    db.update("update buyer_carts set revision=revision+1 where buyer=?", buyer);
  }

  private List<Map<String, Object>> cartLines(String buyer) {
    return db.queryForList(
        """
select s.id as "skuId",p.id as "productId",p.image,p.title,s.label,s.price,s.stock,c.quantity
from cart_items c join skus s on s.id=c.sku_id join products p on p.id=s.product_id where buyer=? order by s.id
""",
        buyer);
  }

  private BigDecimal subtotal(List<Map<String, Object>> lines) {
    return lines.stream()
        .map(
            l ->
                ((BigDecimal) l.get("price")).multiply(BigDecimal.valueOf((int) l.get("quantity"))))
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  public Map<String, Object> cart(String buyer) {
    var lines = cartLines(buyer);
    return Map.of("lines", lines, "subtotal", subtotal(lines));
  }

  @Transactional
  public Map<String, Object> setQuantity(String buyer, long sku, Integer quantity) {
    if (quantity == null || quantity < 0 || quantity > 999)
      throw error(BAD_REQUEST, "数量须为 0 至 999");
    lockCart(buyer);
    var variant = one("select stock from skus where id=?", "规格不存在", sku);
    if (quantity > (int) variant.get("stock")) throw error(CONFLICT, "可用库存不足");
    if (quantity == 0) db.update("delete from cart_items where buyer=? and sku_id=?", buyer, sku);
    else
      db.update(
          """
insert into cart_items values(?,?,?) on conflict(buyer,sku_id) do update set quantity=excluded.quantity
""",
          buyer,
          sku,
          quantity);
    advanceCart(buyer);
    return cart(buyer);
  }

  private static void checkKey(String key) {
    if (key != null && (key.isBlank() || key.length() > 128)) throw error(BAD_REQUEST, "请求键无效");
  }

  @Transactional
  public Map<String, Object> addQuantity(String buyer, long sku, Integer delta, String key) {
    if (delta == null || delta < 1 || delta > 999) throw error(BAD_REQUEST, "加购数量须为 1 至 999");
    checkKey(key);
    lockCart(buyer);
    if (key != null) {
      var previous =
          db.queryForList(
              "select sku_id,quantity from cart_additions where buyer=? and request_key=?",
              buyer,
              key);
      if (!previous.isEmpty()) {
        var applied = previous.get(0);
        if (!applied.get("sku_id").equals(sku) || !applied.get("quantity").equals(delta))
          throw error(CONFLICT, "相同请求键已用于其他加购操作");
        return cart(buyer);
      }
    }
    var existing =
        db.queryForList(
            "select quantity from cart_items where buyer=? and sku_id=?",
            Integer.class,
            buyer,
            sku);
    var updated = setQuantity(buyer, sku, delta + (existing.isEmpty() ? 0 : existing.get(0)));
    if (key != null)
      db.update("insert into cart_additions values(?,?,?,?)", buyer, key, sku, delta);
    return updated;
  }

  private List<Map<String, Object>> lines(String quote) {
    return db.queryForList(
        """
select l.sku_id as "skuId",p.id as "productId",p.image,l.title,l.label,l.price,l.quantity
from quote_lines l join skus s on s.id=l.sku_id join products p on p.id=s.product_id where quote_id=? order by sku_id
""",
        quote);
  }

  @Transactional
  public Map<String, Object> quote(String buyer, String key) {
    checkKey(key);
    long revision = lockCart(buyer);
    if (key != null) {
      var existing =
          db.queryForList(
              "select id from quotes where buyer=? and request_key=?", String.class, buyer, key);
      if (!existing.isEmpty()) return getQuote(buyer, existing.get(0));
    }
    var cart = cartLines(buyer);
    if (cart.isEmpty()) throw error(CONFLICT, "购物车为空");
    if (cart.stream().anyMatch(l -> (int) l.get("quantity") > (int) l.get("stock")))
      throw error(CONFLICT, "商品库存不足，请调整购物车");
    String quote = UUID.randomUUID().toString();
    var subtotal = subtotal(cart);
    var shipping =
        subtotal.compareTo(BigDecimal.valueOf(99)) >= 0 ? BigDecimal.ZERO : BigDecimal.valueOf(8);
    db.update(
        "insert into quotes(id,buyer,revision,expires_at,subtotal,shipping,request_key)"
            + " values(?,?,?,now()+interval '10 minutes',?,?,?)",
        quote,
        buyer,
        revision,
        subtotal,
        shipping,
        key);
    for (var line : cart)
      db.update(
          "insert into quote_lines values(?,?,?,?,?,?)",
          quote,
          line.get("skuId"),
          line.get("title"),
          line.get("label"),
          line.get("price"),
          line.get("quantity"));
    return getQuote(buyer, quote);
  }

  public Map<String, Object> getQuote(String buyer, String quote) {
    var result =
        one(
            """
select q.id,q.buyer,q.created_at as "createdAt",q.expires_at as "expiresAt",q.subtotal,q.shipping,q.subtotal+q.shipping as total,o.id as "orderId"
from quotes q left join orders o on o.quote_id=q.id where q.id=? and q.buyer=?
""",
            "报价不存在",
            quote,
            buyer);
    result.put("lines", lines(quote));
    return result;
  }

  @Transactional
  public Map<String, Object> confirm(String buyer, String quote) {
    long revision = lockCart(buyer);
    var snapshot =
        one(
            "select revision, expires_at<=clock_timestamp() as expired from quotes where id=? and"
                + " buyer=? for update",
            "报价不存在",
            quote,
            buyer);
    var existing = db.queryForList("select id from orders where quote_id=?", String.class, quote);
    if (!existing.isEmpty()) return order(buyer, false, existing.get(0));
    if ((boolean) snapshot.get("expired") || revision != (long) snapshot.get("revision"))
      throw error(CONFLICT, "报价已失效，请重新获取报价");
    var locked =
        db.queryForList(
            """
select s.id,s.stock,s.price,l.price as quoted,l.quantity from quote_lines l join skus s on s.id=l.sku_id
where l.quote_id=? order by s.id for update of s
""",
            quote);
    for (var row : locked) {
      if (((BigDecimal) row.get("price")).compareTo((BigDecimal) row.get("quoted")) != 0)
        throw error(CONFLICT, "价格已变更，请重新报价");
      if ((int) row.get("stock") < (int) row.get("quantity")) throw error(CONFLICT, "可用库存不足");
    }
    for (var row : locked)
      db.update("update skus set stock=stock-? where id=?", row.get("quantity"), row.get("id"));
    String order = id("O");
    db.update(
        "insert into orders(id,quote_id,buyer,status,expires_at)"
            + " values(?,?,?,'PENDING_PAYMENT',clock_timestamp()+interval '30 minutes')",
        order,
        quote,
        buyer);
    event(order, "PENDING_PAYMENT");
    db.update("delete from cart_items where buyer=?", buyer);
    advanceCart(buyer);
    return order(buyer, false, order);
  }

  private void event(String order, String status) {
    db.update("insert into order_events(order_id,status) values(?,?)", order, status);
  }

  private static final String ORDER_SQL =
      """
select o.id,o.buyer,o.status,o.quote_id as "quoteId",o.created_at as "createdAt",o.expires_at as "expiresAt",
  o.received_at as "receivedAt",o.tracking,q.subtotal,q.shipping,q.subtotal+q.shipping as total
from orders o join quotes q on q.id=o.quote_id
""";

  private Map<String, Object> hydrateOrder(Map<String, Object> result) {
    result.put("lines", lines((String) result.get("quoteId")));
    result.put(
        "events",
        db.queryForList(
            "select status,at from order_events where order_id=? order by id", result.get("id")));
    return result;
  }

  public Map<String, Object> order(String buyer, boolean staff, String id) {
    return hydrateOrder(
        one(ORDER_SQL + " where o.id=? and (? or o.buyer=?)", "订单不存在", id, staff, buyer));
  }

  public List<Map<String, Object>> orders(String buyer, boolean staff) {
    return db
        .queryForList(ORDER_SQL + " where ? or o.buyer=? order by o.created_at desc", staff, buyer)
        .stream()
        .map(this::hydrateOrder)
        .toList();
  }

  private void release(String quote) {
    var items =
        db.queryForList(
            "select sku_id,quantity from quote_lines where quote_id=? order by sku_id", quote);
    for (var item : items)
      db.update(
          "update skus set stock=stock+? where id=?", item.get("quantity"), item.get("sku_id"));
  }

  private void closeUnpaid(String id, String quote, String status) {
    release(quote);
    db.update("update orders set status=? where id=?", status, id);
    event(id, status);
  }

  @Transactional
  @Scheduled(fixedDelayString = "${SHOPWISE_EXPIRY_POLL_MS:5000}")
  public void expire() {
    var orders =
        db.queryForList(
            """
select id,quote_id from orders where status='PENDING_PAYMENT' and expires_at<=clock_timestamp()
order by expires_at limit 1 for update skip locked
""");
    for (var order : orders)
      closeUnpaid((String) order.get("id"), (String) order.get("quote_id"), "EXPIRED");
  }

  static class Expired extends ResponseStatusException {
    Expired() {
      super(CONFLICT, "订单已超时关闭");
    }
  }

  @Transactional(noRollbackFor = Expired.class)
  public Map<String, Object> act(String buyer, boolean staff, String id, String action) {
    var order =
        one(
            "select status,quote_id,expires_at<=clock_timestamp() as expired from orders where id=?"
                + " and (? or buyer=?) for update",
            "订单不存在",
            id,
            staff,
            buyer);
    String status = (String) order.get("status"), quote = (String) order.get("quote_id");
    if (status.equals("PENDING_PAYMENT") && (boolean) order.get("expired")) {
      closeUnpaid(id, quote, "EXPIRED");
      throw new Expired();
    }
    String previous =
        switch (action) {
          case "pay", "cancel" -> "PENDING_PAYMENT";
          case "ship" -> "PAID";
          case "receive" -> "SHIPPED";
          default -> throw error(NOT_FOUND, "未知操作");
        };
    String next =
        switch (action) {
          case "pay" -> "PAID";
          case "ship" -> "SHIPPED";
          case "receive" -> "COMPLETED";
          default -> "CANCELLED";
        };
    if (status.equals(next)) return order(buyer, staff, id);
    if (!status.equals(previous)) throw error(CONFLICT, "当前订单状态无法执行此操作");
    if (action.equals("cancel")) closeUnpaid(id, quote, next);
    else {
      db.update(
          """
update orders set status=?, tracking=case when ?='SHIPPED' then ? else tracking end,
 received_at=case when ?='COMPLETED' then clock_timestamp() else received_at end where id=?
""",
          next,
          next,
          id("SIM"),
          next,
          id);
      event(id, next);
    }
    return order(buyer, staff, id);
  }

  private static final String RETURN_SQL =
      """
select r.id,r.order_id as "orderId",r.sku_id as "skuId",o.buyer,l.title,l.label,r.quantity,r.reason,r.status,r.amount,r.created_at as "createdAt"
from returns r join orders o on o.id=r.order_id join quote_lines l on l.quote_id=o.quote_id and l.sku_id=r.sku_id
""";

  public List<Map<String, Object>> returns(String buyer, boolean staff) {
    return db.queryForList(
        RETURN_SQL + " where ? or o.buyer=? order by r.created_at desc", staff, buyer);
  }

  record ReturnRequest(Long skuId, Integer quantity, String reason, String requestKey) {}

  @Transactional
  public Map<String, Object> requestReturn(
      String buyer, String orderId, ReturnRequest input, String key) {
    if (input.skuId == null
        || input.quantity == null
        || input.quantity <= 0
        || input.reason == null
        || input.reason.isBlank()
        || input.reason.length() > 1000) throw error(BAD_REQUEST, "请提供规格、有效数量和退货原因");
    if (key == null) key = input.requestKey;
    checkKey(key);
    var order =
        one(
            "select status,quote_id,received_at+interval '7 days'<clock_timestamp() as expired from"
                + " orders where id=? and buyer=? for update",
            "订单不存在",
            orderId,
            buyer);
    if (key != null) {
      var existing =
          db.queryForList(RETURN_SQL + " where r.order_id=? and r.request_key=?", orderId, key);
      if (!existing.isEmpty()) {
        var prev = existing.get(0);
        if (!prev.get("skuId").equals(input.skuId)
            || !prev.get("quantity").equals(input.quantity)
            || !prev.get("reason").equals(input.reason.strip()))
          throw error(CONFLICT, "相同请求键已用于其他退货申请");
        return prev;
      }
    }
    if (!"COMPLETED".equals(order.get("status")) || Boolean.TRUE.equals(order.get("expired")))
      throw error(CONFLICT, "请在确认收货后七天内申请退货");
    var line =
        one(
            "select quantity,price from quote_lines where quote_id=? and sku_id=?",
            "规格不属于此订单",
            order.get("quote_id"),
            input.skuId);
    var used =
        db.queryForObject(
            "select coalesce(sum(quantity),0) from returns where order_id=? and sku_id=? and"
                + " status<>'REJECTED'",
            Long.class,
            orderId,
            input.skuId);
    if (used + input.quantity > (int) line.get("quantity")) throw error(CONFLICT, "超过可退数量");
    String id = id("R");
    db.update(
        "insert into returns(id,order_id,sku_id,quantity,reason,status,amount,request_key)"
            + " values(?,?,?,?,?,'REQUESTED',?,?)",
        id,
        orderId,
        input.skuId,
        input.quantity,
        input.reason.strip(),
        ((BigDecimal) line.get("price")).multiply(BigDecimal.valueOf(input.quantity)),
        key);
    return one(RETURN_SQL + " where r.id=?", "售后单不存在", id);
  }

  @Transactional
  public Map<String, Object> returnAction(String id, String action) {
    var row = one("select status,sku_id,quantity from returns where id=? for update", "售后单不存在", id);
    String next =
        switch (action) {
          case "approve" -> "APPROVED";
          case "reject" -> "REJECTED";
          case "refund" -> "REFUNDED";
          default -> throw error(NOT_FOUND, "未知操作");
        };
    String previous = action.equals("refund") ? "APPROVED" : "REQUESTED";
    if (!next.equals(row.get("status"))) {
      if (!previous.equals(row.get("status"))) throw error(CONFLICT, "当前售后状态无法执行此操作");
      db.update("update returns set status=? where id=?", next, id);
      if (next.equals("REFUNDED"))
        db.update(
            "update skus set stock=stock+? where id=?", row.get("quantity"), row.get("sku_id"));
    }
    return one(RETURN_SQL + " where r.id=?", "售后单不存在", id);
  }
}
