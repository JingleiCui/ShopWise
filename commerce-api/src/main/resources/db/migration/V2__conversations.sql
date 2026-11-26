create table chat_threads (
    id uuid primary key,
    buyer text unique not null,
    taken_over boolean not null default false
);
create table chat_messages (
    id bigint generated always as identity primary key,
    thread_id uuid not null references chat_threads(id),
    role text not null check (role in ('user','assistant','staff')),
    request_id uuid,
    payload jsonb not null,
    created_at timestamptz not null default now(),
    unique(thread_id, role, request_id)
);
create index chat_messages_thread_id on chat_messages(thread_id, id);
