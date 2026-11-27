create table discovery_quota(bucket text not null, minute timestamptz not null, turns integer not null,primary key(bucket, minute));
