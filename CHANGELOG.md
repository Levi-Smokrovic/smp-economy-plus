# Changelog

Versions follow `<mod version>+<Minecraft version>`.

## 1.0.0

First public release, for Minecraft 1.21.11 (Java 21+), 26.1.2, 26.2 and 26.3 (Java 25+). All four jars have the same features, commands, permissions and configs. Fabric API is required on the server, LuckPerms is optional, and clients need nothing.

Other mods, all optional:

- Common Economy API is bundled, so Universal Shops and similar mods can use player balances
- Placeholder API: `%smpeconomy:balance%`, `balance_short`, `bank`, `job`, `job_level` and `currency`
- `commands.json` disables or renames any root command; a name another mod owns stays with that mod, with a warning in the log
- Configs can name items from any mod regardless of load order; unknown ids in `prices.json`, `shop.json`, `market.json` and `orders.json` are skipped with one warning per file
- Veinminer and Tree Harvester pay per block, and veins stop at Flan and Open Parties and Claims borders
- Styled Player List keeps the tab list unless `tablistWithStyledPlayerList` is set
- The sidebar count leaves out players hidden by Vanish
- Carpet `/player` bots get no job pay, account or start money and stay off `/baltop`, unless `botsEarn` is set in `jobs.json`

On by default:

- Economy: balances, `/pay`, `/paytoggle`, `/baltop`, `/eco` and a treasury with a capped hourly budget
- Starter kit captured from an admin's inventory, and a configurable start balance
- `/sell`, `/worth` and `/shop` with categories, search, favourites and daily deals
- `/ah` auction house and `/orders` buy orders from players and NPC traders
- Jobs: miner, lumberjack, farmer, hunter, fisher and digger, up to level 50, with optional support for Veinminer and RightClickHarvest
- Three daily tasks, a four-tier mission board and a daily elite mission
- Bank with daily interest paid by the treasury
- `/menu`, `/commands`, `/settings`, sidebar and tab list
- `/stats`, `/playtime`, `/leaderboard` and leaderboard holograms
- NPCs that open a module menu or run a command

Off by default: casino, chat games and MEGA challenge, lottery and server events, clans, bounties, crates, daily rewards, AFK zone, market values and breaking news. Crate keys are only given as rewards while `crates` is on.

For admins:

- `/smpeco admin` for modules, start money, branding, currency, multipliers, the starter kit and NPCs. `/smpeco module`, `/smpeco config`, `/smpeco starterkit` and `/smpeco npc` do the same from console and RCON.
- Player commands are allowed by default and admin commands need OP level 2. Nodes are under `smpeconomy.*`.
- The mob-spawn and phantom toggles in `/settings` change gameplay, so they stay off until enabled in `settings.json`.
- The daily reset follows `timezone` in `core.json`, by default the server machine's zone.
- Mod data is saved together with vanilla's saves using a journal. A restore point is written after each start, and each config keeps a last good copy.

Moving from 1.21.11 to 26.x:

- Balances, auctions, orders and other mod data load on 26.x, and Minecraft's data fixer upgrades stored items. Going back to an older version isn't supported.
- `#minecraft:dirt` in tasks, jobs and MEGA challenges still includes grass, podzol, mycelium, moss and mud, even though 26.x split that tag up.
- Villager trades are data-driven on 26.x. The trade-based price checks read them from the game data, so datapacks that change trades are included.

Known limitations are listed in the [README](README.md#known-limitations).
