# Flowt plugin (Spigot 1.21.11, Java 21)

## Jar kaise banega (PC par kuch install nahi karna)
1. GitHub repo `flowts` mein `plugin` folder upload karo (Add file -> Upload files, poora folder drag karo).
2. Repo mein "Add file -> Create new file" karo, naam mein likho: `.github/workflows/build-plugin.yml`
   aur `build-plugin.yml` ka content paste karke Commit karo.
3. Repo ke **Actions** tab mein jao -> "Build Flowt plugin" -> **Run workflow**.
4. 1-2 minute baad run khulega -> neeche **Artifacts** mein `Flowt-plugin` download karo (zip) -> andar `Flowt-0.1.0.jar`.

## Server par
1. Discord server mein (Manage Server permission) `/flowt create name:<naam>` chalao. Bot ek **API key** dega (sirf ek baar).
2. `Flowt-0.1.0.jar` ko server ke `plugins/` folder mein daalo, server start karo (ek baar), phir band karo.
3. `plugins/Flowt/config.yml` mein `api-key` mein wo key paste karo. URL kahin nahi daalna, key mein hi hai.
4. Server start karo. Console mein `Connected to the Flowt API` aur `Authenticated as server ...` aana chahiye.
5. Chat us channel se already juda hota hai jahan `/flowt create` chalaya tha.
6. Account link (dono taraf se chalta hai): game mein `/discord link` -> Discord mein `/link code:XXXX`, ya Discord mein `/link` -> game mein `/discord link XXXX`.
7. Server ko dusre channel/Discord se jodna ho: game mein `/flowt setup` -> Discord `/flowt setup code:XXXX`, ya Discord `/flowt setup` -> game `/flowt setup XXXX`.

## Commands
- `/team` (`/t`, `/teams`): create, invite, join, leave, kick, promote, demote, setowner, disband, info, list, top, description, open, sethome, home, chat
- `/teama` (admin): disband, score, info, list
- `/discord` (`/discordsrv`, `/dsrv`): link [code]
- `/flowt`: setup [code], status, reload

Agar server par DiscordSRV ya BetterTeams bhi installed hai to commands takra sakte hain. Test ke liye unhe hata do.
