create table merchant_binding (
  shop text not null, buyer text not null references users(username),
  customer_gid text not null, primary key(shop,buyer), unique(shop,customer_gid)
);
create table shopify_inbox (
  shop text not null, event_id text not null, order_gid text not null,
  customer_gid text, topic text not null, source_updated timestamptz not null,
  snapshot jsonb not null, received_at timestamptz not null default now(),
  processed_at timestamptz, primary key(shop,event_id)
);
create index shopify_inbox_pending on shopify_inbox(received_at) where processed_at is null;
create table merchant_orders (
  shop text not null, order_gid text not null, customer_gid text,
  source_updated timestamptz not null, snapshot jsonb not null,
  primary key(shop,order_gid)
);
create index merchant_orders_customer on merchant_orders(shop,customer_gid,source_updated desc);
