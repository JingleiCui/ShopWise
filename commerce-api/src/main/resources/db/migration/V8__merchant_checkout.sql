create table merchant_checkout (
  id uuid primary key, shop text not null, buyer text not null references users(username),
  cart_id bytea not null, quote jsonb not null, expires_at timestamptz not null
);
create index merchant_checkout_expiry on merchant_checkout(expires_at);
