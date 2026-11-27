package com.shopwise;

import java.security.Principal;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/merchant")
class MerchantSupport {
    record Request(UUID requestId,String orderId,String reason) {}
    record Answer(String message,boolean close) {}
    private final JdbcTemplate db;
    private final String shop;
    MerchantSupport(JdbcTemplate db,@Value("${SHOPIFY_PILOT_SHOP:}") String shop) { this.db=db; this.shop=shop; }

    @PostMapping("/support")
    Map<String,Object> open(Principal buyer,@RequestBody Request request) {
        String reason=validate(request.reason(),800);
        if (request.requestId()==null || request.orderId()==null || !request.orderId().matches("gid://shopify/Order/[0-9]+"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请提供订单和申请编号");
        db.update("""
            insert into merchant_support(id,shop,order_gid,buyer,reason)
            select ?,o.shop,o.order_gid,b.buyer,? from merchant_orders o join merchant_binding b
            on b.shop=o.shop and b.customer_gid=o.customer_gid
            where o.shop=? and o.order_gid=? and b.buyer=? on conflict(id) do nothing
            """,request.requestId(),reason,shop,request.orderId(),buyer.getName());
        var matches=db.queryForList("""
            select id,status from merchant_support where id=? and shop=? and buyer=? and order_gid=? and reason=?
            """,request.requestId(),shop,buyer.getName(),request.orderId(),reason);
        if (matches.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"未找到可登记的本人订单");
        return matches.get(0);
    }

    @GetMapping("/support")
    List<Map<String,Object>> own(Principal buyer) {
        return db.queryForList("""
            select id,order_gid as "orderId",reason,reply,status,created_at as "createdAt"
            from merchant_support where shop=? and buyer=? order by created_at desc limit 50
            """,shop,buyer.getName());
    }

    @GetMapping("/staff/support")
    List<Map<String,Object>> queue(Principal staff) {
        authorize(staff);
        return db.queryForList("""
            select id,order_gid as "orderId",reason,reply,status,created_at as "createdAt"
            from merchant_support where shop=? order by (status='OPEN') desc,created_at desc limit 100
            """,shop);
    }

    @PostMapping("/staff/support/{id}")
    Map<String,String> answer(Principal staff,@PathVariable UUID id,@RequestBody Answer answer) {
        authorize(staff);
        String status=answer.close()?"CLOSED":"ANSWERED";
        if (db.update("update merchant_support set reply=?,status=?,updated_at=now() where shop=? and id=? and status<>'CLOSED'",
            validate(answer.message(),1200),status,shop,id)!=1)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"工单已关闭或不存在");
        return Map.of("status",status);
    }

    private static String validate(String value,int limit) {
        if(value==null || value.isBlank() || value.strip().length()>limit)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请核对说明字数");
        return value.strip();
    }
    private void authorize(Principal staff) {
        if(db.queryForList("select 1 from merchant_staff where shop=? and username=?",Integer.class,shop,staff.getName()).isEmpty())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"没有此店铺的客服权限");
    }
}
