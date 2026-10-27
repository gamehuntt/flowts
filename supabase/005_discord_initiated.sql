-- Flowt: codes created by the Discord bot (Discord -> Minecraft linking), stored in Supabase.
-- Run once in Supabase -> SQL Editor.

create table if not exists public.discord_setup_codes (
  code        text primary key,
  guild_id    text not null,
  channel_id  text not null,
  created_by  text not null,
  expires_at  timestamptz not null,
  created_at  timestamptz not null default now()
);
create index if not exists discord_setup_codes_guild_idx on public.discord_setup_codes (guild_id);

create table if not exists public.discord_link_codes (
  code        text primary key,
  discord_id  text not null unique,
  expires_at  timestamptz not null,
  created_at  timestamptz not null default now()
);

-- Only the API (service role key) may touch these tables: RLS on, no policies.
alter table public.discord_setup_codes enable row level security;
alter table public.discord_link_codes  enable row level security;

-- Which Discord user created a server with /flowt create (used for limits)
alter table public.servers add column if not exists created_by_discord_id text;
create index if not exists servers_created_by_discord_idx on public.servers (created_by_discord_id);
