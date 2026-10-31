# Commands and permissions

Player nodes are allowed by default. Admin nodes need OP level 2 by default. Console and RCON can run everything. With LuckPerms you can change any of this through fabric-permissions-api.

A module's commands only exist while the module is on. Square brackets mean optional.

Amounts accept shorthand such as `2.5k` and `1m`.

## Economy (`economy`)

| Command | Node |
|---|---|
| `/balance [player]`, `/bal`, `/money` | `smpeconomy.balance`, and `smpeconomy.balance.others` for other players |
| `/pay <player> <amount>` | `smpeconomy.pay` |
| `/paytoggle [on\|off]` | `smpeconomy.pay.toggle` |
| `/baltop [page]` | `smpeconomy.baltop` |
| `/treasury` | `smpeconomy.treasury` |
| `/eco give\|take\|set <player> <amount>`, `/eco reset\|get <player>`, `/eco treasury [show\|deposit\|withdraw]` | `smpeconomy.admin.eco` |

## Selling and shop (`sell`, `shop`)

| Command | Node |
|---|---|
| `/sell [hand\|all]` | `smpeconomy.sell` |
| `/worth [hand\|<item>]` | `smpeconomy.worth` |
| `/shop [search <text>\|<category>]` | `smpeconomy.shop`, and `smpeconomy.shop.sell` to sell in the shop |

## Auction house and orders (`auction`, `orders`)

| Command | Node |
|---|---|
| `/ah`, `/auction`, `/ah search <text>`, `/ah my` | `smpeconomy.ah` |
| `/ah sell [total_price]` | `smpeconomy.ah.sell` |
| `/ah admin info\|expire\|remove\|npc\|clearnpc\|purge` | `smpeconomy.admin.auction` |
| `/orders`, `/orders create <item> <amount> <price>`, `/orders my\|claim`, `/orders cancel <id>` | `smpeconomy.orders` |
| `/orders admin list\|info\|expire\|cancel\|bulk\|rotate\|board\|bonus\|quote` | `smpeconomy.admin.orders` |

## Jobs, tasks and missions (`jobs`, `tasks`, `missions`)

| Command | Node |
|---|---|
| `/jobs`, `/job`, `/jobs join <job>`, `/jobs leave`, `/jobs info [job]` | `smpeconomy.jobs` |
| `/jobs admin set\|level\|cooldown\|info\|flush` | `smpeconomy.admin.jobs` |
| `/tasks` | `smpeconomy.tasks` |
| `/missions [active]` | `smpeconomy.missions` |

## Bank, menu, settings and stats (`bank`, `menu`, `settings`, `hud`, `stats`, `holo`)

| Command | Node |
|---|---|
| `/bank`, `/bank deposit\|withdraw [amount]`, `/bank info` | `smpeconomy.bank` |
| `/menu` | `smpeconomy.menu` |
| `/commands` | `smpeconomy.help` |
| `/settings`, `/settings list`, `/settings <setting> [value]` | `smpeconomy.settings` |
| `/sidebar [on\|off]` | `smpeconomy.sidebar` |
| `/stats [player]` | `smpeconomy.stats`, and `smpeconomy.stats.others` for other players |
| `/playtime [player]` | `smpeconomy.playtime`, and `smpeconomy.stats.others` for other players |
| `/leaderboard [category] [page]` | `smpeconomy.leaderboard` |
| `/holo create [type]`, `/holo remove [id]`, `/holo list\|refresh`, `/holo tp <id>`, `/holo text <id>` | `smpeconomy.admin.holo` |

Player settings: `sidebar`, `mobspawn`, `phantoms`, `pay_receive`, `chatgames`, `announcements`, `notifications`, `job_actionbar`, `death_coords`, `daily.reminder`. `mobspawn` and `phantoms` only work after you enable them in `settings.json`. A setting is hidden while its module is off, so `chatgames` needs `minigames` and `daily.reminder` needs `daily`.

## Casino (`casino`)

All games use `smpeconomy.casino`.

| Command | Notes |
|---|---|
| `/casino stats [player]` | Other players need `smpeconomy.admin.casino` |
| `/casino limit [amount]` | A player's own lower daily loss limit |
| `/casino double <id> [no]` | Double-or-nothing offer |
| `/blackjack [bet]`, `/bj`, `/roulette`, `/slots [bet]`, `/wheel [bet]`, `/hilo [bet]` | |
| `/crash [bet] [auto]`, `/plinko [bet] [risk]`, `/dice [bet] [over\|under]`, `/mines [bet] [bombs]` | |
| `/scratch bronze\|silver\|gold`, `/horserace bet <horse> <amount>` | |
| `/coinflip house [amount]` | Needs `smpeconomy.coinflip` |

Most games also have a `help` subcommand.

## Minigames (`minigames`)

| Command | Node |
|---|---|
| `/coinflip [amount]`, `/coinflip cancel`, `/cf` | `smpeconomy.coinflip` |
| `/chatgame on\|off`, `/chatgame mega rules` | `smpeconomy.chatgame` |
| `/chatgame start [type]\|stop\|info`, `/chatgame mega status\|start\|now\|bonus\|stop\|solution` | `smpeconomy.admin.minigames` |
| `/treasure` | `smpeconomy.treasure` |
| `/treasure start\|stop\|info`, `/treasure place <pos>` | `smpeconomy.admin.minigames`, plus `smpeconomy.treasure` on the root |
| `/minigames status`, `/minigames balance <player>` | `smpeconomy.admin.minigames` |

## Lottery and events (`events`)

| Command | Node |
|---|---|
| `/lottery`, `/lottery buy <amount>`, `/lottery tickets\|history\|info` | `smpeconomy.lottery` |
| `/lottery draw [numbers]`, `/lottery status [player]` | `smpeconomy.admin.events` |
| `/event`, `/event info` | `smpeconomy.events` |
| `/event start marketday\|goldrush\|luckyhour\|<event>`, `/event stop`, `/event status [player]`, `/event next <minutes>`, `/event setrank <player> <sales>` | `smpeconomy.admin.events` |
| `/rank [list\|<player>]` | `smpeconomy.rank` |

## Clans (`clans`)

| Command | Node |
|---|---|
| `/clan help`, `create`, `invite`, `accept`, `deny`, `join`, `leave`, `kick`, `promote`, `demote`, `disband`, `info`, `top`, `sethome`, `home`, `bank`, `chat`, `open`, `color` | `smpeconomy.clans` |
| `/cc [message]` | `smpeconomy.clans` |
| `/clan admin disband\|reset\|dump\|who\|sync\|orphan` | `smpeconomy.admin.clans` |

## Other extras (`bounty`, `crates`, `daily`, `afk`, `market`, `news`)

| Command | Node |
|---|---|
| `/bounty [player] [amount]` | `smpeconomy.bounty` |
| `/crates`, `/crates claim`, `/crates preview <crate>` | `smpeconomy.crates` |
| `/crates give <player> <crate> [amount]`, `set`, `info`, `force`, `values` | `smpeconomy.admin.crates` |
| `/daily`, `/daily claim` | `smpeconomy.daily` |
| `/daily admin info\|reset\|shift\|setstreak` | `smpeconomy.admin.daily` |
| `/afkzone` | `smpeconomy.afk` |
| `/afkzone pos1\|pos2 [pos]`, `clear`, `info`, `status <player>`, `set interval\|reward\|keychance\|daylimit\|iplimit` | `smpeconomy.admin.afk` |
| `/market`, `/market value` | `smpeconomy.market` |
| `/market admin status\|fill\|clear\|due\|plans\|offers\|sweep\|budget\|bargain\|recompute\|value\|drift` | `smpeconomy.admin.market` |
| `/news` | `smpeconomy.news` |
| `/news trigger [item]`, `/news action <type> [minutes]`, `/news clear`, `/news auto [state]`, `/news status` | `smpeconomy.admin.news`, and also `smpeconomy.admin.prices` for `/news trigger <item>` |

## /smpeco

Everything under `/smpeco` needs `smpeconomy.admin.core`. Some branches need a second node as well.

| Command | Extra node |
|---|---|
| `/smpeco admin`, `/smpeco module list\|info\|enable\|disable`, `/smpeco config list\|get\|set` | `smpeconomy.admin` |
| `/smpeco reload`, `save`, `status`, `placed (confirm\|<pos>)`, `price <item> [player]`, `treasury [flows]` | |
| `/smpeco notify <player> <text>` | `smpeconomy.admin.notify` |
| `/smpeco pricemod (clear\|<item>)` | `smpeconomy.admin.prices` |
| `/smpeco starterkit`, `/smpeco starter` with `show\|capture\|clear\|bump\|enable\|disable\|onlynew\|give\|reset\|balance` | `smpeconomy.admin.starter` |
| `/smpeco npc list\|reload\|create\|move\|remove\|info\|tp\|equip\|rename\|set` | `smpeconomy.admin.npc` |
| `/smpeco sell balance <player>` | `smpeconomy.admin.sell` |
| `/smpeco shop info\|reset\|deals` | `smpeconomy.admin.shop` |
| `/smpeco tasks info\|preview\|set\|reset\|clock` | `smpeconomy.admin.tasks` |
| `/smpeco missions check\|status\|board\|assign\|expire\|reset\|cooldown\|elite` | `smpeconomy.admin.missions` |
| `/smpeco bank info\|status\|payout\|backdate` | `smpeconomy.admin.bank` |
| `/smpeco stats <player>` | `smpeconomy.admin.stats` |
| `/smpeco hud <player>` | `smpeconomy.admin.hud` |
| `/smpeco settings check\|spawntest\|phantomtest\|<player>` | `smpeconomy.admin.settings` |
| `/smpeco bounty info\|remove\|drop\|check\|unlock\|surplus\|rule` | `smpeconomy.admin.bounty` |
| `/smpeco casino info\|peek\|settle\|resetlimit\|offer\|blackjack\|horserace`, `/smpeco crash info\|peek\|settle\|reset` | `smpeconomy.admin.casino` |
| `/smpeco chatgame start [type]` | `smpeconomy.admin.minigames` |
| `/smpeco selftest [feature]`, `guitest` | Only exist when the server starts with `-Dsmpeconomy.selftest=true`. A self test run blocks the server thread, so keep the flag off live servers. `guitest` opens a demo menu for the e2e tests. |

`/smpeco config set` accepts these keys: `startBalance`, `branding.chatPrefix`, `branding.sidebarTitle`, `currency.symbol`, `currency.symbolAfter`, `currency.nameSingular`, `currency.namePlural`, `multipliers.sell`, `multipliers.jobPay`.
