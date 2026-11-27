create table users (
  username varchar(160) primary key, password varchar(256) not null, enabled boolean not null default true
);
create table authorities (
  username varchar(160) not null references users on delete cascade,
  authority varchar(60) not null,
  primary key(username, authority)
);
create table agent_grants (
  digest char(64) primary key, buyer text not null, session_id text not null,
  request_id uuid not null, expires_at timestamptz not null
);
create index agent_grants_session on agent_grants(session_id);
create index agent_grants_expiry on agent_grants(expires_at);
