create table merchant_refund_receipt (
  shop text not null, id text not null, order_gid text not null, line_gid text not null,
  quantity integer not null check (quantity > 0),
  amount numeric(20,4) not null check (amount > 0), currency char(3) not null,
  confirmed_at timestamptz not null default now(), primary key(shop,id)
);
create table merchant_return (
  id uuid primary key, shop text not null, order_gid text not null, buyer text not null references users(username),
  line_gid text not null, quantity integer not null check(quantity > 0), reason varchar(800) not null,
  status text not null default 'REQUESTED' check(status in ('REQUESTED','APPROVED','DECLINED','RECEIVED','REFUNDED')),
  refund_id text, staff_note varchar(800),
  created_at timestamptz not null default now(), updated_at timestamptz not null default now(),
  foreign key(shop,order_gid) references merchant_orders(shop,order_gid),
  foreign key(shop,refund_id) references merchant_refund_receipt(shop,id),
  unique(shop,refund_id)
);
create index merchant_return_line on merchant_return(shop,order_gid,line_gid);
create table merchant_return_event (
  seq bigint generated always as identity primary key, return_id uuid not null references merchant_return(id),
  status text not null, actor text not null, at timestamptz not null default now()
);
