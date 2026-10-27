# Flowt API

## Setup (ek baar)
1. Node.js 20+ install karo (nodejs.org -> LTS).
2. Supabase SQL Editor mein `../supabase/002_snapshot.sql` run karo (schema.sql ke baad).
3. Is folder mein terminal kholo:
   ```
   npm install
   ```
4. `.env.example` ki copy banao aur naam `.env` rakho. Usme apna Supabase URL aur secret key daalo
   (Supabase -> Project Settings -> API).
5. Test server banao:
   ```
   npm run create-server -- "My Test Server" --public
   ```
   Jo `API KEY` print ho, usse copy kar lo.

## Chalana
```
npm run dev
```
Dusre terminal mein test (Minecraft ke bina):
```
npm run fake-plugin -- flowt_xxxxxxxx
```
Phir browser mein kholo: http://localhost:3000/v1/teams/top
Dragons aur Wolves dikhne chahiye.

## Hosting par deploy (Node PC par install kiye bina)
- Build command : `npm install && npm run build`
- Start command : `npm start`
- Root directory: `api`  (agar repo mein ye folder ke andar hai)
- Environment variables host ke dashboard mein daalo: `SUPABASE_URL`, `SUPABASE_SERVICE_ROLE_KEY`
  (`PORT` host khud set karta hai.) `.env` file GitHub par kabhi upload mat karna.

## Discord bot ke liye
- Render Environment mein ek aur variable add karo: `BOT_SECRET` (32+ random characters).
- Optional: `PUBLIC_HOST` (jaise `my-api.onrender.com`). Render par ye khud detect ho jata hai.
- Supabase SQL Editor mein `supabase/004_bot.sql` aur phir `supabase/005_discord_initiated.sql` run karo.
- Bot ke `.env` mein `DISCORD_TOKEN`, `BOT_SECRET` aur `API_URL` (https://tumhari-api-host) daalo. Bot mein koi default URL nahi hai.
- Naya server + API key Discord se: `/flowt create name:<naam>` (Manage Server permission). Key mein API ka address already hota hai, plugin mein URL daalna nahi padta.

## Data
Saara durable data Supabase mein hai (servers, keys, channels, players, links, codes, teams). API aur bot disk par kuch store nahi karte.
`supabase/reset_data.sql` sab data delete kar deta hai (sirf tab chalao jab pakka chahiye).
