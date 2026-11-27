create table merchant_support (
  id uuid primary key, shop text not null, order_gid text not null, buyer text not null references users(username),
  reason varchar(800) not null, reply varchar(1200), status text not null default 'OPEN' check(status in ('OPEN','ANSWERED','CLOSED')),
  created_at timestamptz not null default now(), updated_at timestamptz not null default now(),
  foreign key(shop,order_gid) references merchant_orders(shop,order_gid)
);
create index merchant_support_owner on merchant_support(shop,buyer,created_at desc);
create table merchant_staff (
  shop text not null, username text not null references users(username), primary key(shop,username)
);
