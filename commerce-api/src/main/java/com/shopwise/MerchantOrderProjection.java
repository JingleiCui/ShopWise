package com.shopwise;

import java.time.OffsetDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class MerchantOrderProjection {
    private final JdbcTemplate db;
    MerchantOrderProjection(JdbcTemplate db) { this.db=db; }
    void store(String shop,String order,String customer,OffsetDateTime updated,String snapshot) {
        db.update("""
            insert into merchant_orders(shop,order_gid,customer_gid,source_updated,snapshot)
            values (?,?,?,?,cast(? as jsonb)) on conflict(shop,order_gid) do update
            set customer_gid=case when excluded.source_updated>merchant_orders.source_updated
                                  then excluded.customer_gid else merchant_orders.customer_gid end,
                source_updated=excluded.source_updated,
                snapshot=case when excluded.source_updated=merchant_orders.source_updated
                          then excluded.snapshot || merchant_orders.snapshot
                          else merchant_orders.snapshot || excluded.snapshot end
            where merchant_orders.source_updated<=excluded.source_updated
            """,shop,order,customer,updated,snapshot);
    }
}
