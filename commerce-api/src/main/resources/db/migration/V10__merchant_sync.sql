create table merchant_sync (
    shop text primary key, watermark timestamptz not null,
    last_success timestamptz not null, last_error timestamptz
);
