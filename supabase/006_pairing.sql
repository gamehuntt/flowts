-- Flowt: key-less pairing (like MC Linker). Run once in Supabase -> SQL Editor (after 005).

create table if not exists public.pair_codes (
  code            text primary key,
  kind            text not null check (kind in ('discord', 'minecraft')),
  guild_id        text,
  channel_id      text,
  discord_user_id text,   -- admin who made the code (discord) or entered it (minecraft)
  server_name     text,   -- name sent by the plugin (kind = minecraft)
  poll_hash       text,   -- sha256 of the plugin's poll token (kind = minecraft)
  expires_at      timestamptz not null,
  created_at      timestamptz not null default now()
);
create index if not exists pair_codes_guild_idx on public.pair_codes (guild_id);
alter table public.pair_codes enable row level security;  -- only the API (service role) may use it

-- replaced by pair_codes
drop table if exists public.discord_setup_codes;
