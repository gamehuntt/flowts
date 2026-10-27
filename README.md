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

## Setup (ek baar)
1. Supabase SQL Editor mein run karo (is order mein): `supabase/004_bot.sql`, `005_discord_initiated.sql`, `006_pairing.sql`.
2. Render Environment: `BOT_SECRET` (32+ random characters). `PUBLIC_HOST` optional hai, Render par ye khud detect hota hai.
3. GitHub repo ke Settings, Secrets and variables, Actions mein secret banao: `FLOWT_API_HOST` = sirf host, jaise `flowts.onrender.com` (https:// ke bina). Plugin jar build hote waqt ye address jar ke andar chala jata hai, repo mein kahin likha nahi hota.
4. Bot ke `.env` mein `DISCORD_TOKEN`, `BOT_SECRET`, `API_URL` (https://tumhari-api-host).

## Server owners kaise connect karte hain (koi key nahi, koi config nahi)
- Plugin `plugins/` mein daalo, server start karo.
- Discord: `/connect` -> code milta hai -> Minecraft mein `/connect <code>`.
- Ya Minecraft: `/connect` -> code milta hai -> Discord mein `/connect code:<code>`.
- Key API khud banati hai aur plugin `plugins/Flowt/connection.yml` mein khud save karta hai.
- Account link: `/account connect` (Minecraft ya Discord) aur dusri taraf `/account connect <code>`.

## Data
Saara durable data Supabase mein hai (servers, keys, channels, players, links, codes, teams). API aur bot disk par kuch store nahi karte.
`supabase/reset_data.sql` sab data delete kar deta hai (sirf tab chalao jab pakka chahiye).
