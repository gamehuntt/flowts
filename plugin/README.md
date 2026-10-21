# Flowt plugin (Spigot 1.21.11, Java 21)

## Jar kaise banega (PC par kuch install nahi karna)
1. GitHub repo `flowts` mein `plugin` folder upload karo (Add file -> Upload files, poora folder drag karo).
2. Repo mein "Add file -> Create new file" karo, naam mein likho: `.github/workflows/build-plugin.yml`
   aur `build-plugin.yml` ka content paste karke Commit karo.
3. Repo ke **Actions** tab mein jao -> "Build Flowt plugin" -> **Run workflow**.
4. 1-2 minute baad run khulega -> neeche **Artifacts** mein `Flowt-plugin` download karo (zip) -> andar `Flowt-0.1.0.jar`.

## Server par
1. `Flowt-0.1.0.jar` ko server ke `plugins/` folder mein daalo, server start karo (ek baar), phir band karo.
2. `plugins/Flowt/config.yml` kholo, `api-key` mein apni `flowt_...` key paste karo.
3. Server start karo. Console mein `Connected to the Flowt API` aur `Authenticated as server ...` aana chahiye.
4. Console ya in-game (op) mein `/flowt setup` chalao -> code milega -> Discord mein `/flowt setup code:XXXX`.
5. Chat test karo. Khud ka account link karne ke liye game mein `/discord link`, phir Discord mein `/link code:XXXX`.

## Commands
- `/team` (`/t`, `/teams`): create, invite, join, leave, kick, promote, demote, setowner, disband, info, list, top, description, open, sethome, home, chat
- `/teama` (admin): disband, score, info, list
- `/discord` (`/discordsrv`, `/dsrv`): link
- `/flowt`: setup, status, reload

Agar server par DiscordSRV ya BetterTeams bhi installed hai to commands takra sakte hain. Test ke liye unhe hata do.
