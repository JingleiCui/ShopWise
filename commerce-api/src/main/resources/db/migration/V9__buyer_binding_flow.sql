create table merchant_binding_flow (
  state char(43) primary key, buyer text not null references users(username),
  session_id text not null, verifier text not null, expires_at timestamptz not null
);
create index merchant_binding_flow_expiry on merchant_binding_flow(expires_at);
