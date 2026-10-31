# Auction house public API (`AuctionService`)

Class `dev.smpeconomy.features.auction.AuctionService`, static methods only. **Call on the server thread only**
(otherwise `IllegalStateException`). Money is in cents (`long`), prices are always the **total price** of the stack.
Data lives in `<world>/smpeconomy/auctions.json` (items through `ItemCodec` with all components), config in
`config/smpeconomy/auction.json`.

## Types

| Type | Content |
|---|---|
| `record Listing(long id, UUID seller, String sellerName, boolean npc, String source, ItemStack item, long price, long createdAt, long expiresAt, boolean bargain, String note)` | Immutable view of an active listing. `seller == null` for NPC listings. `item()` returns a copy every time. Extras: `isNpc()`, `count()`, `unitPrice()` (rounded down), `remainingMs()`. `source`: `"player"` (`SOURCE_PLAYER`) or the NPC source. `bargain`/`note` for NPC listings only: bargain (`npcBargainFloor` applies on purchase) and an extra display line (empty = none). |
| `record ClaimInfo(long id, UUID owner, ItemStack item, long price, long createdAt, String reason)` | Claim bin entry (`reason`: `expired`, `cancelled`, `admin`). |
| `enum Result` | `OK, STORED, NOT_FOUND, OWN_LISTING, NPC_LISTING, NOT_NPC_LISTING, INSUFFICIENT_FUNDS, NO_SPACE, PRICE_CHANGED, PRICE_ABOVE_CAP, PAYMENT_FAILED, NO_ITEM, INVALID_ITEM, ITEM_TOO_LARGE, NOT_NPC_TRADABLE, INVALID_PRICE, INVALID_DURATION, LIMIT_REACHED, CLAIM_BIN_FULL, CREATIVE, NOT_ALLOWED, ITEM_UNREADABLE, MONEY_ERROR`, with `ok()` (true for `OK` and `STORED`) and `message()` (short player message). |
| `record CreateResult(Result result, Listing listing)` | `listing` only on `OK`, otherwise `null`. `ok()`. |
| `interface NpcPayer { boolean withdraw(long cents); void refund(long cents); }` | Who pays in `buyListingAsNpc`. `withdraw` books exactly `cents` (true) or nothing (false); `refund` only if the purchase fails afterwards. Ready-made: **`NpcPayer.treasury()`** = `Treasury.tryWithdraw(cents, Treasury.R_NPC_BUY)` (hourly cap), refund through `Treasury.deposit`. |
| `interface SoldListener { void onSold(Listing l, UUID buyer, long price, long tax); }` | `buyer == null` means an NPC bought. |
| `enum RemoveReason { EXPIRED, CANCELLED, NPC_REMOVED, ADMIN }`, `interface RemovedListener { void onRemoved(Listing l, RemoveReason r); }` | |
| `interface NpcSaleFloor { long minTotalPrice(UUID buyer, ItemStack stack); default long minBargainTotalPrice(UUID buyer, ItemStack stack) }` | Additional floor (total price) for NPC listings, e.g. an enchantment-dependent market value. `buyer == null` when listing. `minBargainTotalPrice` is the floor for bargains; the default is `minTotalPrice` (no relaxation). |
| `record NpcOffer(boolean bargain, String note)` | Kind of NPC listing for `createNpcListing`; `NpcOffer.NORMAL`. `note` at most `MAX_NOTE_LENGTH` (80) characters, one line. |
| `enum Filter { ALL, PLAYERS, NPC }` | Filter in the browse GUI, `next()`. |
| `enum Sort { NEWEST, PRICE_ASC, PRICE_DESC }` | Sort order for `browse`. |

## Creating listings

- **`CreateResult createNpcListing(ItemStack stack, long price, long durationMs, String sellerName, String source)`**:
  NPC listing (e.g. `sellerName = "§e[Market] Trader Connor"`, `source = "market"`). The stack is copied; a real item only
  comes into being on purchase. Counts toward no player limit, at most `maxNpcListings` (default 150) at once, duration
  `0 < durationMs <= maxNpcDurationHours` (default 48 h). **PriceGuard:** if `price` is below `npcSaleFloor(null, stack)` it is
  raised to that floor; the actual price is in `result.listing().price()`. Expired NPC listings simply disappear
  (`onRemoved(EXPIRED)`, no claim bin).
- **`CreateResult createNpcListing(ItemStack, long price, long durationMs, String sellerName, String source, NpcOffer offer)`**:
  as above; with `offer.bargain()` `price` is only raised to `npcBargainFloor(null, stack)`, and `npcBargainFloor(buyer, stack)`
  applies on purchase. `offer.note()` appears as an extra lore line.
- `CreateResult createPlayerListing(UUID seller, ItemStack stack, long price, long durationMs)`: player listing with item
  handover. **The caller has already removed the stack from the inventory**; on `OK` it belongs to the auction house, on an
  error it still belongs to the caller (give it back). Checks price (`0 < price <= maxPrice`), limit (`maxListings`, default
  10), full claim bin (`maxClaims`, default 45), blocked items, item size. `durationMs <= 0` uses the config duration (48 h),
  longer gives `INVALID_DURATION`.
- `CreateResult listFromHand(ServerPlayer seller, long price)`: `/ah sell <total_price>`, whole stack from the main hand (not in
  creative mode by default), `price` = total price of the stack. Lists immediately; the command's confirmation (see below)
  happens before this.
- **Size limit** (all three): item data (uncompressed NBT including contents) above `maxItemBytes` (config, default 64 KiB) gives
  `ITEM_TOO_LARGE` and nothing happens (e.g. a shulker box full of written books). The same applies to items with more than
  `maxLoreLines` lore lines of their own (config, default 64; vanilla allows 256 and the preview appends lines). GUIs only show
  listings as a light preview: shulker and bundle contents and book pages are removed from the display copy and summarized as
  lore; overlong lore is cut to the vanilla limit in the display, and a preview that can't be shown becomes a barrier, never an
  exception that blocks the auction house for everyone. The real item is always what gets delivered.

## Queries

- `List<Listing> activeListings()`: all active, unexpired, readable listings (player and NPC), newest first.
- `List<Listing> npcListings(String source)`: active NPC listings of this source (`null` = all NPC listings).
- `Optional<Listing> listing(long id)`, `int activeCount(UUID seller)`, `int npcCount()`,
  `List<ClaimInfo> claims(UUID owner)`, `int claimCount(UUID owner)`.
- `List<Listing> browse(String query, Sort sort, Filter filter)`: like the GUI (search by item id, English name, custom name,
  enchantment); `browse(query, sort, boolean playersOnly)` = PLAYERS/ALL.
- `long taxFor(long price)`: tax on player sales (`taxPercent`, default 3 %, rounded down).
- **`long npcBuyCap(Listing l, long unitMarketValue)`**: highest total price an NPC may pay right now.
  1. `unitMarketValue` is capped at `max(Services.marketValue(item)` prorated by remaining durability, highest `NpcSaleFloor` of
     this stack / count`)`. A higher value, e.g. enchantment-dependent, only counts if market has registered a matching floor
     for NPC listings (`addNpcSaleFloor`).
  2. `PriceGuard.maxNpcBuyPrice(seller, item, value, fever ? 1.25 : 1.15) × count` (fever = `Hooks.flag(Hooks.FLAG_AUCTION_FEVER)`).
  3. At most `npcSaleFloor(seller, stack) − 1`: **an NPC always pays less than an NPC listing of the same stack costs**, so
     buying from the system and selling to the system never profits, not even across different market value sources.
  0 for NPC listings and for items with contents (`holdsItems`).
- `boolean holdsItems(ItemStack)`: shulker or chest contents, bundle, loaded crossbow, bees, loot table or custom block or entity
  data. No NPC buys such stacks (the contents would be deleted too), so market should not plan them at all.
- **`long npcSaleFloor(UUID buyer, ItemStack stack)`**: minimum total price of an NPC listing right now:
  `max(PriceGuard.minSystemSalePrice(buyer, item, Services.marketValue(item)) × count, all NpcSaleFloor)`.
- **`long npcBargainFloor(UUID buyer, ItemStack stack)`**: minimum total price of an NPC **bargain**: per item at least the buyer's
  effective /sell price + 1 cent (unmodified stacks only, the only ones /sell takes), and for shop items pivot + 1, plus the
  maximum of all `NpcSaleFloor.minBargainTotalPrice` (market: /sell after a crash ends and through recipes, × 1.15 when listing).
  Without a registered floor (or if one throws) `npcSaleFloor` applies, meaning no bargains. Bargains may be below `npcSaleFloor`:
  reselling to NPC buyers and orders is intended (market limits the amount through a flip budget); `npcBuyCap` for player listings
  stays below `npcSaleFloor`.

## Buying and removing

- **`Result buyListingAsNpc(long id, NpcPayer payer)`**: an NPC buys a **player** listing (market value =
  `Services.marketValue(item)`, per item type), **unmodified stacks only** (`Items.isPristine`: not damaged, enchanted or named,
  no contents), otherwise `NOT_NPC_TRADABLE`. Checks: listing exists and has not expired, not an NPC listing, item readable,
  `price <= npcBuyCap(...)`. Then `payer.withdraw(price)`; the seller gets `price − tax` (also offline; message at once when
  online, otherwise on join, respecting `Settings.NOTIFICATIONS`), tax goes to `Treasury.deposit(tax, R_AH_TAX)`, the listing is
  gone, **the item is deleted** (the NPC keeps it), `onSold(l, null, price, tax)`. Errors change nothing: `NOT_FOUND`,
  `NPC_LISTING`, `NOT_NPC_TRADABLE`, `PRICE_ABOVE_CAP` (the payer isn't even asked), `PAYMENT_FAILED`, `ITEM_UNREADABLE`,
  `MONEY_ERROR` (the payer gets `refund`).
- `Result buyListingAsNpc(long id, long unitMarketValue, NpcPayer payer)`: as above with a custom market value per item (e.g.
  dependent on enchantments and durability) for any stack except `holdsItems` (`NOT_NPC_TRADABLE`); the cap is recomputed with it
  (`npcBuyCap`, including the value cap and `< npcSaleFloor`). The value must already include durability loss; without a matching
  floor only the prorated item market value counts anyway.
- `Result buyListing(ServerPlayer buyer, long id[, long expectedPrice])`: player purchase (the GUI passes the shown price; another
  price gives `PRICE_CHANGED`). Needs money and room for the whole stack. Player listing: seller gets `price − tax`, tax goes to the
  Treasury. NPC listing: recheck against `npcSaleFloor(buyer, item)` (too low: the price is raised, `PRICE_CHANGED`; bargains against
  `npcBargainFloor(buyer, item)`), then 100 % to the Treasury (`Treasury.collectFrom(buyer, price, R_NPC_SALE)`). The delivered item
  is a perfectly normal item (no marker).
- `boolean removeNpcListing(long id)`: removes NPC listings ONLY (false otherwise or if unknown), `onRemoved(NPC_REMOVED)`.
- `int removeNpcListings(String source)`: all NPC listings of a source.
- `Result cancelListing(ServerPlayer owner, long id)`: cancel your own listing. `OK` = item in inventory, `STORED` = no room, item
  in the claim bin.
- `Result claim(ServerPlayer owner, long claimId)` (only with room), `int[] claimAll(ServerPlayer owner)` returns {claimed, left}.
- `int processExpired()`: runs automatically every 5 s and when `/ah` opens.

## Listeners (return `Hooks.Registration`, `remove()` unregisters)

- `onListingCreated(Consumer<Listing>)`: after every new listing (player AND NPC, check `l.isNpc()`).
- `onListingSold(SoldListener)`: after every purchase (player or NPC).
- `onListingRemoved(RemovedListener)`: expiry, cancellation, NPC removal, admin.
  Every (readable) listing ends with exactly one `onSold` or `onRemoved`.
- `addNpcSaleFloor(NpcSaleFloor)`: additional floor for NPC listings (the maximum of all applies).

Listeners run synchronously on the server thread, **after** the state change; exceptions are logged and ignored. Listeners may call
the API again (e.g. create a new NPC listing right away).

## Example (market)

```java
AuctionService.onListingCreated(l -> { if (!l.isNpc()) planNpcBuy(l.id(), l.price()); });
// later (scheduler):
long mv = Services.marketValue(item).orElse(0);
Optional<Listing> l = AuctionService.listing(id);
if (l.isPresent() && l.get().price() <= AuctionService.npcBuyCap(l.get(), mv) && l.get().price() <= Treasury.maxSpendPerHour())
    AuctionService.buyListingAsNpc(id, mv, AuctionService.NpcPayer.treasury());
CreateResult r = AuctionService.createNpcListing(new ItemStack(Items.DIAMOND, 4), price, 6 * 3_600_000L,
    "§e[Market] Trader Connor", "market");
```

## Commands

`/ah sell <total_price>`: the price applies to the whole stack in the main hand. With more than one item, or if the price is below the
/sell price or below `sellWarnBelowMarketPercent` (default 50 %) of the market value, a preview comes first
(`List 16x Diamond for $100.00 in total? [Confirm]`, then unit price, proceeds after tax, duration, warnings and possibly
`Did you mean $x each? /ah sell …`); `[Confirm]` runs the same command, and the listing happens on a repeat within
`sellConfirmSeconds` (default 10 s) with the same slot, stack and price (`AuctionSellConfirm`). Success message:
`Listed 16x Diamond for $100.00 total ($6.25 each). Expires in 48h, 3% tax on sale.`

**"Sell item" button** (slot 52 in browse and "My Auctions", only with `smpeconomy.ah.sell`; `AuctionSellGui`): click with the item in
the main hand (empty: `Hold the item you want to sell in your main hand.`; precheck = `checkListFromHand` with 1 cent), then an anvil
for the **total price** (prefilled with max(market value, /sell) × count, same formats as `/ah sell`; the next button shows total and
unit price and warnings live), then the `Confirm Listing` dialog (item, total and unit price, tax, proceeds, duration, the same warnings
as `/ah sell`, "Change price", "Did you mean $x each?", Confirm 9-11 / Cancel 15-17). Confirm only lists if the same hotbar slot still
holds exactly the stack from the click (`ItemStack.matches`: item, count, components), otherwise it cancels with a message; the listing
goes through `listFromHand`, which checks everything again. Afterwards, on cancel and on ESC, it returns to the previous menu. The balance
is shown in the "My Auctions" button (51).

`/ah`, `/ah sell <total_price>`, `/ah search <text>`, `/ah my` (smpeconomy.ah, smpeconomy.ah.sell; `/auction` is an alias with the same
tree). Admin (smpeconomy.admin.auction, OP 2, machine-readable): `/ah admin info [player]`
(`auction player=… active=… claims=… balance=<cents>`), `expire <player>`, `remove <id>` (player listing to claim bin),
`npc <item> <count> <price>` (test NPC listing, source `admin`), `clearnpc`, `purge <player>` (deletes listings and claim bin without
returning them; item data as Base64 in the log).
