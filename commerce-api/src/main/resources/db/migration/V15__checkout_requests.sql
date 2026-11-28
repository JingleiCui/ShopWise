create table merchant_checkout_request (
  shop text not null,
  buyer text not null references users(username),
  id uuid not null,
  requested jsonb not null,
  status text not null check (status in ('PENDING','READY','REJECTED','UNKNOWN')),
  created_at timestamptz not null default now(),
  primary key(shop,buyer,id)
);
create index merchant_checkout_request_created_idx on merchant_checkout_request(created_at);
