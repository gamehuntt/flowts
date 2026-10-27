# Flowt plugin (Spigot 1.21.11, Java 21)

## Jar kaise banega (PC par kuch install nahi karna)
1. GitHub repo `flowts` mein `plugin` folder upload karo (Add file -> Upload files, poora folder drag karo).
2. Repo mein "Add file -> Create new file" karo, naam mein likho: `.github/workflows/build-plugin.yml`
   aur `build-plugin.yml` ka content paste karke Commit karo.
3. Repo ke **Actions** tab mein jao -> "Build Flowt plugin" -> **Run workflow**.
4. 1-2 minute baad run khulega -> neeche **Artifacts** mein `Flowt-plugin` download karo (zip) -> andar `Flowt-0.1.0.jar`.

## Server par
1. Jar ko `plugins/` mein daalo aur server start karo. Koi key ya URL config mein nahi daalna.
2. Discord mein (Manage Server permission) `/connect` chalao, code milega. Minecraft console ya game (OP) mein `/connect <code>`.
   Ulta bhi chalta hai: Minecraft mein `/connect`, phir Discord mein `/connect code:<code>`.
3. Console mein `Connected to the Flowt API` aana chahiye. Chat us channel se juda hota hai jahan `/connect` chalaya tha.
4. Connection `plugins/Flowt/connection.yml` mein khud save hota hai (ise kisi ko mat do). Hatana ho toh `/flowt disconnect`.
5. Account link: Minecraft mein `/account connect` -> Discord mein `/account connect code:<code>`, ya Discord mein `/account connect` -> Minecraft mein `/account connect <code>`.

## Commands
- `/team` (`/t`, `/teams`): create, invite, join, leave, kick, promote, demote, setowner, disband, info, list, top, description, open, sethome, home, chat
- `/teama` (admin): disband, score, info, list
- `/connect [code]`: server ko Discord se jodna (OP)
- `/account connect [code]`: apna account link karna
- `/discord` (`/discordsrv`, `/dsrv`): link [code], invite
- `/flowt`: status, reload, disconnect

Agar server par DiscordSRV ya BetterTeams bhi installed hai to commands takra sakte hain. Test ke liye unhe hata do.
