package com.shopwise;

import java.security.Principal;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1/merchant")
class MerchantReturns {
    record Application(UUID requestId,String orderId,String lineId,int quantity,String reason) {}
    record Decision(String note,String refundId) {}
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final String shop;
    private final ShopifyRefundReceipts receipts;
    private static final String VIEW="""
        select r.id,r.order_gid as "orderId",r.line_gid as "lineId",r.quantity,r.reason,r.status,
          r.staff_note as "staffNote",r.created_at as "createdAt",r.updated_at as "updatedAt",
          r.refund_id as "refundId",receipt.amount as "refundAmount",receipt.currency as "refundCurrency"
        from merchant_return r left join merchant_refund_receipt receipt on receipt.shop=r.shop and receipt.id=r.refund_id
        """;

    MerchantReturns(JdbcTemplate db,ObjectMapper json,ShopifyRefundReceipts receipts,@Value("${SHOPIFY_PILOT_SHOP:}") String shop) {
        this.db=db; this.json=json; this.receipts=receipts; this.shop=shop;
    }

    @GetMapping("/returns")
    List<Map<String,Object>> own(Principal buyer) {
        return db.queryForList(VIEW+" where r.shop=? and r.buyer=? order by r.created_at desc limit 50",shop,buyer.getName());
    }

    @GetMapping("/staff/returns")
    List<Map<String,Object>> queue(Principal staff) {
        authorize(staff);
        return db.queryForList(VIEW+" where r.shop=? order by r.updated_at desc limit 100",shop);
    }

    @PostMapping("/returns")
    @Transactional
    Map<String,Object> open(Principal buyer,@RequestBody Application request) {
        String reason=required(request.reason());
        if (request.requestId()==null || !gid(request.orderId(),"Order") || !gid(request.lineId(),"LineItem")
            || request.quantity()<1 || request.quantity()>999)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请核对订单、商品和退货数量");
        var order=db.queryForList("""
          select o.snapshot::text from merchant_orders o join merchant_binding b on b.shop=o.shop and b.customer_gid=o.customer_gid
          where o.shop=? and o.order_gid=? and b.buyer=? for update of o
          """,String.class,shop,request.orderId(),buyer.getName());
        if (order.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"未找到此订单");
        var replay=db.queryForList(VIEW+" where r.shop=? and r.buyer=? and r.id=?",shop,buyer.getName(),request.requestId());
        if (!replay.isEmpty()) {
            var prior=replay.get(0);
            if (request.orderId().equals(prior.get("orderId")) && request.lineId().equals(prior.get("lineId")) &&
                request.quantity()==((Number)prior.get("quantity")).intValue() && reason.equals(prior.get("reason"))) return prior;
            throw new ResponseStatusException(HttpStatus.CONFLICT,"此请求编号已使用");
        }
        var snapshot=json.readTree(order.get(0));
        if (!Set.of("paid","partially_refunded").contains(snapshot.path("financial_status").asString("")) ||
            snapshot.hasNonNull("cancelled_at"))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"当前订单状态无法申请退货");
        int shipped=0;
        for (var line:snapshot.path("returnableLines"))
            if (request.lineId().equals(line.path("id").asString(""))) shipped=line.path("quantity").asInt(0);
        var held=db.queryForObject("""
            select coalesce(sum(quantity),0) from merchant_return
            where shop=? and order_gid=? and line_gid=? and status<>'DECLINED'
            """,Long.class,shop,request.orderId(),request.lineId());
        var external=db.queryForObject("""
            select coalesce(sum(receipt.quantity),0) from merchant_refund_receipt receipt
            where receipt.shop=? and receipt.order_gid=? and receipt.line_gid=?
            and not exists(select 1 from merchant_return r where r.shop=receipt.shop and r.refund_id=receipt.id)
            """,Long.class,shop,request.orderId(),request.lineId());
        if (held+external+request.quantity()>shipped)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"可退数量不足，或商家尚未同步发货明细");
        int inserted=db.update("""
            insert into merchant_return(id,shop,order_gid,buyer,line_gid,quantity,reason)
            values (?,?,?,?,?,?,?) on conflict(id) do nothing
            """,request.requestId(),shop,request.orderId(),buyer.getName(),request.lineId(),request.quantity(),reason);
        if (inserted!=1) throw new ResponseStatusException(HttpStatus.CONFLICT,"此请求编号已使用");
        audit(request.requestId(),"REQUESTED",buyer.getName());
        return view(request.requestId());
    }

    @PostMapping("/staff/returns/{id}/{action:approve|decline|receive|reconcile}")
    @Transactional
    Map<String,Object> decide(Principal staff,@PathVariable UUID id,@PathVariable String action,@RequestBody Decision input) {
        authorize(staff);
        String note=input.note()==null?null:required(input.note());
        var rows=db.queryForList("select status,order_gid,line_gid,quantity,refund_id,created_at from merchant_return where shop=? and id=? for update",shop,id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"退货申请不存在");
        var current=rows.get(0);
        String from=(String)current.get("status");
        String to=switch(action) {
            case "approve" -> "APPROVED";
            case "decline" -> "DECLINED";
            case "receive" -> "RECEIVED";
            default -> "REFUNDED";
        };
        if (from.equals(to) && (!to.equals("REFUNDED") || Objects.equals(current.get("refund_id"),input.refundId()))) return view(id);
        switch(to) {
            case "APPROVED","DECLINED" -> require(from,"REQUESTED");
            case "RECEIVED" -> require(from,"APPROVED");
            case "REFUNDED" -> require(from,"RECEIVED");
            default -> throw new IllegalStateException();
        }
        String receipt=null;
        if ("REFUNDED".equals(to)) {
            receipt=input.refundId();
            if (gid(receipt,"Refund") && !hasReceipt(receipt,current))
                receipts.refresh(shop,receipt,(String)current.get("order_gid"),(String)current.get("line_gid"),
                    ((Number)current.get("quantity")).intValue());
            if (!gid(receipt,"Refund") || !hasReceipt(receipt,current))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"未找到与该商品及数量一致的已成功退款记录");
        }
        try {
            db.update("update merchant_return set status=?,staff_note=coalesce(?,staff_note),refund_id=?,updated_at=now() where shop=? and id=?",
                to,note,receipt,shop,id);
        } catch(DuplicateKeyException conflict) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,"此退款记录已关联其他申请");
        }
        audit(id,to,staff.getName());
        return view(id);
    }

    private boolean hasReceipt(String receipt,Map<String,Object> current) {
        return !db.queryForList("""
              select 1 from merchant_refund_receipt
              where shop=? and id=? and order_gid=? and line_gid=? and quantity=? and source_created_at>=?
              """,Integer.class,shop,receipt,current.get("order_gid"),current.get("line_gid"),current.get("quantity"),current.get("created_at")).isEmpty();
    }

    private void authorize(Principal staff) {
        if (db.queryForList("select 1 from merchant_staff where shop=? and username=?",Integer.class,shop,staff.getName()).isEmpty())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"没有此店铺的客服权限");
    }
    static boolean gid(String value,String resource) {
        return value!=null && value.matches("gid://shopify/"+resource+"/[1-9][0-9]{0,19}");
    }
    private static String required(String value) {
        if (value==null || value.isBlank() || value.strip().length()>800)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请填写 1 至 800 字的说明");
        return value.strip();
    }
    private static void require(String current,String expected) {
        if (!expected.equals(current)) throw new ResponseStatusException(HttpStatus.CONFLICT,"当前申请状态无法执行此操作");
    }
    private void audit(UUID id,String status,String actor) {
        db.update("insert into merchant_return_event(return_id,status,actor) values (?,?,?)",id,status,actor);
    }
    private Map<String,Object> view(UUID id) { return db.queryForMap(VIEW+" where r.id=? and r.shop=?",id,shop); }
}
