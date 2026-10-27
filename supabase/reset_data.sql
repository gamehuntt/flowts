-- !!! DANGER: deletes ALL Flowt data (servers, API keys, teams, players, Discord links, channel
-- bindings, every code). Tables/schema stay. This cannot be undone.
-- Run in Supabase -> SQL Editor. (Tables that do not exist are skipped.)

do $$
declare t text;
begin
  foreach t in array array[
    'servers', 'server_keys', 'server_channels', 'setup_codes', 'link_codes',
    'discord_setup_codes', 'discord_link_codes', 'players', 'teams', 'team_members'
  ] loop
    if to_regclass('public.' || t) is not null then
      execute format('truncate table public.%I restart identity cascade', t);
    end if;
  end loop;
end $$;
