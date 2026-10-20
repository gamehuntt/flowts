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
