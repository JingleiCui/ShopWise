create table cart_additions (
 buyer text not null references buyer_carts,
 request_key text not null, sku_id bigint not null references skus, quantity integer not null check(quantity>0),
 primary key(buyer, request_key)
);
alter table quotes add column request_key text;
create unique index quotes_request_key on quotes(buyer,request_key);
insert into policies values
 ('dispatch','发货时效','模拟商城预计付款后 1 至 2 个工作日内发货。仓库人员在后台手动发货后生成模拟物流单号，订单时间线记录实际发货时间。','发货 什么时候发货 时效 物流 到货 多久 发货时效 dispatch tracking','ShopWise 演示商城规则','1.0');
