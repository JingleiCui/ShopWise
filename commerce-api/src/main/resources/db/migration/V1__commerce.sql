create table products (
 id bigint primary key, source text not null, version text not null, title text not null, description text not null,
 category text not null, kind text not null, image text not null, source_image text not null, attributes jsonb not null
);
create table skus (
 id bigint generated always as identity primary key, product_id bigint not null references products, label text not null,
 price numeric(12,2) not null check(price>0), stock integer not null check(stock>=0), unique(product_id,label)
);
create table buyer_carts (buyer text primary key, revision bigint not null default 0);
create table cart_items (buyer text references buyer_carts, sku_id bigint references skus,
 quantity integer not null check(quantity between 1 and 999), primary key(buyer,sku_id));
create table quotes (
 id text primary key, buyer text references buyer_carts, revision bigint not null, created_at timestamptz not null default now(),
 expires_at timestamptz not null, subtotal numeric(14,2) not null, shipping numeric(12,2) not null
);
create table quote_lines (
 quote_id text references quotes, sku_id bigint references skus, title text not null, label text not null,
 price numeric(12,2) not null, quantity integer not null, primary key(quote_id,sku_id)
);
create table orders (
 id text primary key, quote_id text unique not null references quotes, buyer text not null, status text not null,
 created_at timestamptz not null default now(), expires_at timestamptz not null, received_at timestamptz, tracking text
);
create index orders_expiring on orders(expires_at) where status='PENDING_PAYMENT';
create table order_events (
 id bigint generated always as identity primary key, order_id text references orders, status text not null, at timestamptz not null default now()
);
create table returns (
 id text primary key, order_id text not null references orders, sku_id bigint not null references skus, quantity integer not null check(quantity>0),
 reason text not null, status text not null, amount numeric(14,2) not null, request_key text, created_at timestamptz not null default now(),
 unique(order_id, request_key)
);
create table policies(id text primary key, title text not null, content text not null, keywords text not null, source text not null, version text not null);
insert into policies values
 ('shipping','配送与运费','商品小计满 99 元免运费，未满 99 元收取 8 元运费。','配送 运费 包邮 shipping delivery','ShopWise 演示商城规则','1.0'),
 ('payment','支付与取消','订单使用模拟支付。待支付订单可主动取消，30 分钟未付款将自动关闭并释放库存。','支付 付款 取消 库存 过期 payment cancel','ShopWise 演示商城规则','1.0'),
 ('returns','退货与退款','确认收货后 7 天内可按商品规格和数量申请退货。审核通过后模拟退还商品实付金额，运费不退。','退款 退货 售后 退换 不满意 return refund','ShopWise 演示商城规则','1.0'),
 ('quote','报价与确认','报价有效期为 10 分钟。提交订单前重新核对购物车、价格和库存，需买家明确确认。','报价 下单 确认 生效 价格 有效期 quote','ShopWise 演示商城规则','1.0');
