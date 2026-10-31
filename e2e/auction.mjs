// Auction house: alias /auction, /ah sell (total price, preview and confirmation), browse GUI (buying with
// confirmation, own listing, sorting, filter, search by command and anvil), NPC listing (proceeds to the
// treasury), My Auctions (cancel, collection box after expiry), tax to the treasury, component round trip
// (name, enchantment, shulker content), "Sell item" button, no GUI items in inventories.
import {
  connectBot, rcon, runE2E, assert, assertEq, sleep, waitForMessage, expectWindow, windowTitle, clickSlot, findSlot,
  itemLabel, itemLore, treasuryFlows, treasuryDelta,
} from './lib.mjs'

const SELLER = 'auctioBot1'
const BUYER = 'auctioBot2'
const SLOT_SORT = 45
const SLOT_FILTER = 46
const SLOT_SEARCH = 47
const SLOT_SELL = 52
const LISTING_SLOTS = 45
const GUI_JUNK = /stained_glass_pane|barrier|hopper|oak_sign|sunflower|player_head|chest|arrow|paper|book|lime_dye|gray_dye|red_dye|emerald_block|name_tag|redstone_torch|gold_nugget/

async function info(name) {
  const out = await rcon(`ah admin info ${name}`)
  const m = out.match(/active=(\d+) claims=(\d+) balance=(\d+)/)
  if (!m) throw new Error(`ah admin info ${name}: ${out}`)
  return { active: Number(m[1]), claims: Number(m[2]), balance: Number(m[3]) }
}

/**
 * Auction tax income between two treasuryFlows() states, adjusted for background NPC purchases: market NPCs buy
 * other listings at any time and also book tax (rounded down per purchase, so at most 3 % of their npc_buy
 * spending). Returns { tax, npcBuy }.
 */
function ahTax(before, after) {
  const tax = treasuryDelta(before, after, 'ah_tax').income
  const npcBuy = treasuryDelta(before, after, 'npc_buy')
  return { tax, npcBuy: npcBuy.expense - npcBuy.income }
}

// with a container open, read its player slots; mineflayer does not update bot.inventory then
function invItems(bot) {
  const w = bot.currentWindow
  return w && w.inventoryStart != null
    ? w.slots.slice(w.inventoryStart, w.inventoryEnd ?? w.slots.length).filter(Boolean)
    : bot.inventory.items()
}

function count(bot, name) {
  return invItems(bot).filter((it) => it.name === name).reduce((sum, it) => sum + it.count, 0)
}

async function waitUntil(fn, timeoutMs = 5000, stepMs = 100) {
  const end = Date.now() + timeoutMs
  while (Date.now() < end) {
    if (await fn()) return true
    await sleep(stepMs)
  }
  return !!(await fn())
}

async function hold(bot, name) {
  await waitUntil(() => count(bot, name) > 0, 5000)
  const item = bot.inventory.items().find((it) => it.name === name)
  if (!item) throw new Error(`${bot.username}: ${name} not in inventory`)
  await bot.equip(item, 'hand')
  await sleep(200)
}

function lore(item) {
  return itemLore(item).join(' | ')
}

function bySeller(name, itemName) {
  return (it) => (!itemName || it.name === itemName) && lore(it).includes(`Seller: ${name}`)
}

function priceOf(item) {
  const m = lore(item).match(/Price: \$([\d,]+\.\d\d)/)
  return m ? Math.round(Number(m[1].replace(/,/g, '')) * 100) : NaN
}

function sellerSlots(win, name) {
  const out = []
  for (let i = 0; i < LISTING_SLOTS; i++) {
    const it = win.slots[i]
    if (it && bySeller(name)(it)) out.push(i)
  }
  return out
}

async function openAh(bot, cmd = '/ah') {
  const win = await expectWindow(bot, () => bot.chat(cmd), 10000)
  assert(/Auction House/.test(windowTitle(win)), `${bot.username}: ${cmd} opens the auction house ("${windowTitle(win)}")`)
  return win
}

// the filter persists per player across sessions
async function setFilter(bot, label) {
  for (let i = 0; i < 4; i++) {
    const win = bot.currentWindow
    if (win && new RegExp(label).test(itemLabel(win.slots[SLOT_FILTER]))) return win
    await clickSlot(bot, SLOT_FILTER)
    await sleep(400)
  }
  const win = bot.currentWindow
  assert(win && new RegExp(label).test(itemLabel(win.slots[SLOT_FILTER])), `filter "${label}" set`)
  return win
}

async function closeWin(bot) {
  if (bot.currentWindow) bot.closeWindow(bot.currentWindow)
  await sleep(300)
}

/**
 * /ah sell <total price>: stacks and dump prices first show a preview with unit price and [Confirm], and list
 * when the command is repeated, like the chat click.
 */
async function sell(bot, itemName, price, countText) {
  await hold(bot, itemName)
  const first = waitForMessage(bot, /Listed (\d+)x|\[Confirm\]/, 8000).then((m) => m, () => null)
  bot.chat(`/ah sell ${price}`)
  let m = await first
  if (m && !m[1]) {
    await sleep(300)
    const listed = waitForMessage(bot, /Listed (\d+)x/, 8000).then((x) => x, () => null)
    bot.chat(`/ah sell ${price}`)
    m = await listed
  }
  assert(!!m && (!countText || m[1] === String(countText)), `${bot.username}: ${itemName} listed for $${price}${m ? ` (${m[1]}x)` : ''}`)
  return !!m
}

async function buyAt(bot, slot, expectName) {
  const dialog = await expectWindow(bot, () => clickSlot(bot, slot))
  assert(/Confirm Purchase/.test(windowTitle(dialog)), `${bot.username}: confirm dialog ("${windowTitle(dialog)}")`)
  if (expectName) assertEq(dialog.slots[13]?.name, expectName, `preview shows ${expectName}`)
  const bought = waitForMessage(bot, /Bought (\d+)x/, 8000).then((m) => m, () => null)
  // confirm lies on 9-11; if the listing itself is on slot 9 the same-slot debounce (300 ms) would catch it
  const reopened = await expectWindow(bot, () => clickSlot(bot, slot === 9 ? 10 : 9), 10000)
  const m = await bought
  assert(!!m, `${bot.username}: purchase confirmed${m ? ` (${m[1]}x)` : ''}`)
  assert(/Auction House/.test(windowTitle(reopened)), 'back in the auction house after the purchase')
  return reopened
}

async function setup() {
  for (const name of [SELLER, BUYER]) {
    await rcon(`ah admin purge ${name}`)
    await rcon(`clear ${name}`)
    await rcon(`gamemode survival ${name}`)
  }
  await rcon('ah admin clearnpc')
  await rcon(`eco set ${SELLER} 0`)
  await rcon(`eco set ${BUYER} 1000`)
  const s = await info(SELLER)
  const b = await info(BUYER)
  assertEq([s.active, s.claims, s.balance], [0, 0, 0], 'seller starts empty with $0')
  assertEq([b.active, b.claims, b.balance], [0, 0, 100000], 'buyer starts with $1,000 (eco set)')
  if (b.balance !== 100000) throw new Error('starting money missing; is /eco set available?')
}

async function cleanup() {
  for (const name of [SELLER, BUYER]) {
    try {
      await rcon(`ah admin purge ${name}`)
      await rcon(`clear ${name}`)
      await rcon(`eco set ${name} 0`)
    } catch (err) {
      console.log(`[E2E] cleanup ${name}: ${err.message}`)
    }
  }
  try { await rcon('ah admin clearnpc') } catch {}
}

runE2E(async () => {
  const seller = await connectBot(SELLER)
  const buyer = await connectBot(BUYER)
  assert(true, 'bots connected')

  const st = await rcon('smpeco selftest auction')
  const sm = st.match(/SUMMARY passed=(\d+) failed=(\d+)/)
  assert(!!sm && Number(sm[1]) > 0 && Number(sm[2]) === 0, `auction selftests green (${sm ? sm[0] : st.slice(0, 200)})`)

  await setup()
  try {
    await mainFlow(seller, buyer)
  } finally {
    await closeWin(seller)
    await closeWin(buyer)
    await cleanup()
  }
}, { timeoutMs: 300000 })

async function mainFlow(seller, buyer) {
  for (const alias of ['/auction']) {
    await openAh(buyer, alias)
    await closeWin(buyer)
  }
  const myWin = await expectWindow(seller, () => seller.chat('/auction my'), 10000)
  assert(/My Auctions/.test(windowTitle(myWin)), `/auction my opens "My Auctions" ("${windowTitle(myWin)}")`)
  await closeWin(seller)
  const usage = waitForMessage(seller, /Usage: \/ah sell <total price> .*whole stack.*click Sell item/, 6000).then(() => true, () => false)
  seller.chat('/auction sell')
  assert(await usage, '/auction sell without a price shows usage (with a hint about the button)')
  const searchWin = await expectWindow(buyer, () => buyer.chat('/auction search diamond'), 10000)
  assert(/Auction House.*diamond/.test(windowTitle(searchWin)), `/auction search diamond ("${windowTitle(searchWin)}")`)
  await closeWin(buyer)

  const noItem = waitForMessage(seller, /Hold the item you want to sell in your main hand/, 6000).then(() => true, () => false)
  seller.chat('/ah sell 100')
  assert(await noItem, 'empty hand refused')
  await rcon(`give ${SELLER} minecraft:diamond 16`)
  await hold(seller, 'diamond')
  const badPrice = waitForMessage(seller, /[Ii]nvalid amount/, 6000).then(() => true, () => false)
  seller.chat('/ah sell abc')
  assert(await badPrice, 'invalid price refused')
  assertEq(count(seller, 'diamond'), 16, 'diamonds still there after failed attempts')

  // Price = total price, so a stack first shows a preview: total + [Confirm], unit price (16 for $100 = $6.25),
  // proceeds after 3 % tax, duration, a warning (far below market value) and a hint about the unit price
  // mistake; only the repeat lists it.
  const t0 = await treasuryFlows()
  await hold(seller, 'diamond')
  const preview = waitForMessage(seller, /List 16x .*for \$100\.00 in total\? \[Confirm\]/, 8000).then(() => true, () => false)
  const breakdown = waitForMessage(seller, /\$6\.25 each · you get \$97\.00 \(3% tax\) · expires in 48h/, 8000).then(() => true, () => false)
  const warned = waitForMessage(seller, /⚠ Way below market value: about \$[\d,.]+ each \(\$[\d,.]+ for all\)/, 8000).then(() => true, () => false)
  const meant = waitForMessage(seller, /Did you mean \$100\.00 each\? \/ah sell 1600/, 8000).then(() => true, () => false)
  const hint = waitForMessage(seller, /The price is for the whole stack\. Click Confirm, or repeat \/ah sell 100 within \d+s/, 8000).then(() => true, () => false)
  seller.chat('/ah sell 100')
  assert(await preview, 'stack: first a preview with total price and [Confirm]')
  assert(await breakdown, 'preview: unit price, proceeds after tax, duration')
  assert(await warned, 'preview warns: far below market value')
  assert(await meant, 'preview suggests /ah sell 1600 ($100 each)')
  assert(await hint, 'preview: hint about total price and repeating')
  await sleep(300)
  assertEq(count(seller, 'diamond'), 16, 'preview takes nothing from the hand')
  assertEq((await info(SELLER)).active, 0, 'no listing before the confirmation')
  const confirmed = waitForMessage(seller, /Listed 16x .*for \$100\.00 total \(\$6\.25 each\)\. Expires in 48h, 3% tax on sale\./, 8000)
    .then(() => true, () => false)
  seller.chat('/ah sell 100')
  assert(await confirmed, 'repeat lists it, message with unit price')
  assert(await waitUntil(() => count(seller, 'diamond') === 0), 'diamonds taken from the hand')
  assertEq((await info(SELLER)).active, 1, '1 active offer')

  await openAh(seller)
  let win = await setFilter(seller, 'All')
  let slot = findSlot(win, bySeller(SELLER, 'diamond'), { to: LISTING_SLOTS })
  assert(slot >= 0, `own listing visible (slot ${slot})`)
  if (slot >= 0) {
    assert(/Price: \$100\.00/.test(lore(win.slots[slot])), `lore shows price (${lore(win.slots[slot])})`)
    assert(/Expires in:/.test(lore(win.slots[slot])), 'lore shows time left')
    const own = waitForMessage(seller, /your own listing/, 6000).then(() => true, () => false)
    await clickSlot(seller, slot)
    assert(await own, 'own listing: error instead of purchase')
    assert(/Auction House/.test(windowTitle(seller.currentWindow)), 'no confirm dialog for an own listing')
  }
  await closeWin(seller)

  const soldMsg = waitForMessage(seller, /Sold 16x .*auctioBot2/, 10000).then(() => true, () => false)
  await openAh(buyer)
  win = await setFilter(buyer, 'All')
  slot = findSlot(win, bySeller(SELLER, 'diamond'), { to: LISTING_SLOTS })
  assert(slot >= 0, `buyer sees the listing (slot ${slot})`)
  if (slot < 0) throw new Error('listing not found')
  assertEq(win.slots[slot].count, 16, 'count 16 in the GUI')
  win = await buyAt(buyer, slot, 'diamond')
  assert(await waitUntil(() => count(buyer, 'diamond') === 16), 'buyer has 16 diamonds')
  assertEq(findSlot(win, bySeller(SELLER, 'diamond'), { to: LISTING_SLOTS }), -1, 'listing no longer listed')
  assert(await soldMsg, 'seller notified online')
  assertEq((await info(BUYER)).balance, 90000, 'buyer pays $100')
  assertEq((await info(SELLER)).balance, 9700, 'seller gets $97 (3 % tax)')
  {
    // reason ah_tax instead of the raw balance (NPC orders and buyers book in the background); NPC purchases of
    // other listings in the same period may add at most their own tax
    const { tax, npcBuy } = ahTax(t0, await treasuryFlows())
    if (npcBuy <= 0) assertEq(tax, 300, 'tax $3 in the treasury (ah_tax)')
    else assert(tax >= 300 && tax - 300 <= Math.floor(npcBuy * 3 / 100), `tax $3 in the treasury (ah_tax +${tax}, of which NPC purchases <= ${Math.floor(npcBuy * 3 / 100)})`)
  }
  await closeWin(buyer)

  const npcOut = await rcon('ah admin npc minecraft:emerald 3 500')
  const nm = npcOut.match(/id=(\d+) price=(\d+)/)
  assert(!!nm, `NPC offer created (${npcOut.trim()})`)
  const npcPrice = nm ? Number(nm[2]) : 0
  assert(npcPrice >= 50000, `NPC price at least as given (${npcPrice})`)
  await openAh(buyer)
  win = await setFilter(buyer, 'All')
  const isNpc = (it) => it.name === 'emerald' && /\[Market\] Test Trader/.test(lore(it)) && /NPC listing/.test(lore(it))
  assert(findSlot(win, isNpc, { to: LISTING_SLOTS }) >= 0, 'filter "All": NPC listing visible and marked')
  assert(/All/.test(itemLabel(win.slots[SLOT_FILTER])), `filter button shows "All" (${itemLabel(win.slots[SLOT_FILTER])})`)
  await sleep(200)
  await clickSlot(buyer, SLOT_FILTER)
  await sleep(300)
  win = buyer.currentWindow
  assert(/Players only/.test(itemLabel(win.slots[SLOT_FILTER])), `filter button shows "Players only" (${itemLabel(win.slots[SLOT_FILTER])})`)
  assertEq(findSlot(win, isNpc, { to: LISTING_SLOTS }), -1, 'filter "Players only": NPC listing hidden')
  await sleep(350)
  await clickSlot(buyer, SLOT_FILTER)
  await sleep(300)
  win = buyer.currentWindow
  assert(/Market only/.test(itemLabel(win.slots[SLOT_FILTER])), `filter button shows "Market only" (${itemLabel(win.slots[SLOT_FILTER])})`)
  assertEq(sellerSlots(win, SELLER).length, 0, 'filter "Market only": no player listings')
  slot = findSlot(win, isNpc, { to: LISTING_SLOTS })
  assert(slot >= 0, 'filter "Market only": NPC listing there')
  if (slot >= 0) {
    assertEq(priceOf(win.slots[slot]), npcPrice, 'lore price = NPC price')
    const tBefore = await treasuryFlows()
    const bBefore = (await info(BUYER)).balance
    await buyAt(buyer, slot, 'emerald')
    assert(await waitUntil(() => count(buyer, 'emerald') === 3), 'buyer has 3 emeralds (from the NPC)')
    assertEq((await info(BUYER)).balance, bBefore - npcPrice, 'buyer pays the NPC price')
    assertEq(treasuryDelta(tBefore, await treasuryFlows(), 'npc_sale').income, npcPrice, 'NPC proceeds 100 % to the treasury (npc_sale)')
  }
  await closeWin(buyer)

  await rcon(`give ${SELLER} minecraft:dirt 10`)
  await rcon(`give ${SELLER} minecraft:gold_ingot 5`)
  await sell(seller, 'dirt', 30, 10)
  await sell(seller, 'gold_ingot', 20, 5)
  await rcon(`give ${SELLER} minecraft:iron_ingot 7`)
  await sell(seller, 'iron_ingot', 25, 7)
  await openAh(buyer)
  // many NPC listings (market keeps two pages), so check sorting only among player listings
  win = await setFilter(buyer, 'Players only')
  let order = sellerSlots(win, SELLER).map((s) => win.slots[s].name)
  assertEq(order, ['iron_ingot', 'gold_ingot', 'dirt'], 'sorting "Newest"')
  await clickSlot(buyer, SLOT_SORT)
  await sleep(300)
  win = buyer.currentWindow
  assert(/Price ↑/.test(itemLabel(win.slots[SLOT_SORT])), `sorting shows "Price ↑" (${itemLabel(win.slots[SLOT_SORT])})`)
  let prices = sellerSlots(win, SELLER).map((s) => priceOf(win.slots[s]))
  assertEq(prices, [2000, 2500, 3000], 'sorting "Price ↑"')
  await sleep(200)
  await clickSlot(buyer, SLOT_SORT)
  await sleep(300)
  win = buyer.currentWindow
  prices = sellerSlots(win, SELLER).map((s) => priceOf(win.slots[s]))
  assertEq(prices, [3000, 2500, 2000], 'sorting "Price ↓"')
  await sleep(200)
  await clickSlot(buyer, SLOT_SORT)
  await sleep(300)
  assert(/Newest/.test(itemLabel(buyer.currentWindow.slots[SLOT_SORT])), 'sorting "Newest" again')
  await closeWin(buyer)

  win = await openAh(buyer, '/ah search gold')
  let names = sellerSlots(win, SELLER).map((s) => win.slots[s].name)
  assertEq(names, ['gold_ingot'], '/ah search gold finds only gold')
  const anvil = await expectWindow(buyer, () => clickSlot(buyer, SLOT_SEARCH))
  assert(anvil.type === 'minecraft:anvil' || /anvil/i.test(String(anvil.type)) || /Search/.test(windowTitle(anvil)), `anvil search opened (${anvil.type})`)
  buyer._client.write('name_item', { name: 'dirt' })
  await sleep(400)
  win = await expectWindow(buyer, () => clickSlot(buyer, 2))
  names = sellerSlots(win, SELLER).map((s) => win.slots[s].name)
  assertEq(names, ['dirt'], 'anvil search "dirt" finds dirt')
  assert(/dirt/.test(windowTitle(win)), `title shows the search term ("${windowTitle(win)}")`)
  await sleep(350)
  win = await expectWindow(buyer, () => clickSlot(buyer, SLOT_SEARCH, 1))
  assertEq(sellerSlots(win, SELLER).length, 3, 'right click clears the search')
  await closeWin(buyer)

  win = await expectWindow(seller, () => seller.chat('/ah my'))
  assert(/My Auctions/.test(windowTitle(win)), `/ah my opens "My Auctions" ("${windowTitle(win)}")`)
  slot = findSlot(win, (it) => it.name === 'gold_ingot' && /Active/.test(lore(it)), { to: LISTING_SLOTS })
  assert(slot >= 0, 'gold listing listed as active')
  if (slot >= 0) {
    const dialog = await expectWindow(seller, () => clickSlot(seller, slot))
    assert(/cancel/i.test(windowTitle(dialog)), `cancel dialog ("${windowTitle(dialog)}")`)
    const cancelled = waitForMessage(seller, /Listing cancelled/, 8000).then(() => true, () => false)
    const back = await expectWindow(seller, () => clickSlot(seller, 9))
    assert(await cancelled, 'cancel confirmed')
    assert(/My Auctions/.test(windowTitle(back)), 'back in "My Auctions"')
    assert(await waitUntil(() => count(seller, 'gold_ingot') === 5), 'gold back in the inventory')
  }
  await closeWin(seller)
  win = await openAh(buyer, '/ah search gold')
  assertEq(sellerSlots(win, SELLER).length, 0, 'cancelled listing no longer buyable')
  await closeWin(buyer)

  const expiredMsg = waitForMessage(seller, /Expired: /, 10000).then(() => true, () => false)
  const ex = await rcon(`ah admin expire ${SELLER}`)
  assert(/expired=2/.test(ex), `admin expires 2 listings (${ex.trim()})`)
  assert(await expiredMsg, 'seller notified about expiry')
  let si = await info(SELLER)
  assertEq([si.active, si.claims], [0, 2], '2 entries in the collection box, none active')
  win = await openAh(buyer)
  assertEq(sellerSlots(win, SELLER).length, 0, 'expired listings no longer in browse')
  await closeWin(buyer)
  win = await expectWindow(seller, () => seller.chat('/ah my'))
  slot = findSlot(win, (it) => it.name === 'dirt' && /Expired/.test(lore(it)), { to: LISTING_SLOTS })
  assert(slot >= 0, 'dirt as "Expired" in the collection box')
  if (slot >= 0) {
    const claimed = waitForMessage(seller, /Claimed\./, 8000).then(() => true, () => false)
    await expectWindow(seller, () => clickSlot(seller, slot))
    assert(await claimed, 'collection confirmed')
    assert(await waitUntil(() => count(seller, 'dirt') === 10), '10 dirt back')
  }
  await sleep(350)
  win = seller.currentWindow
  const all = waitForMessage(seller, /Claimed \d+ item stack/, 8000).then(() => true, () => false)
  await clickSlot(seller, 46)
  assert(await all, '"Collect all" takes the rest')
  assert(await waitUntil(() => count(seller, 'iron_ingot') === 7), '7 iron back')
  si = await info(SELLER)
  assertEq([si.active, si.claims], [0, 0], 'pickup box empty')
  await closeWin(seller)

  const gave = await rcon(`give ${SELLER} minecraft:diamond_sword[minecraft:custom_name="Blade",minecraft:enchantments={"minecraft:sharpness":3}] 1`)
  assert(/Gave/i.test(gave) || gave.trim() === '', `sword given (${gave.trim()})`)
  await sell(seller, 'diamond_sword', 15, 1)
  win = await openAh(buyer)
  slot = findSlot(win, bySeller(SELLER, 'diamond_sword'), { to: LISTING_SLOTS })
  assert(slot >= 0 && itemLabel(win.slots[slot]) === 'Blade', `sword in the GUI with name (${slot >= 0 ? itemLabel(win.slots[slot]) : '-'})`)
  if (slot >= 0) {
    await buyAt(buyer, slot, 'diamond_sword')
    assert(await waitUntil(() => count(buyer, 'diamond_sword') === 1), 'buyer has the sword')
    const sword = invItems(buyer).find((it) => it.name === 'diamond_sword')
    assertEq(sword ? itemLabel(sword) : null, 'Blade', 'name kept')
    const inv = await rcon(`data get entity ${BUYER} Inventory`)
    assert(/diamond_sword[^}]*\}?[^]*sharpness"?: ?3/.test(inv) || (/sharpness/.test(inv) && /Blade/.test(inv)), 'enchantment kept (server inventory)')
  }
  await closeWin(buyer)

  // shulker box with content: the GUI shows a light preview (content as lore), the buyer gets the real item
  await rcon(`eco set ${BUYER} 1000`)
  const box = 'minecraft:shulker_box[minecraft:container=[{slot:0,item:{id:"minecraft:diamond",count:64}},'
    + '{slot:1,item:{id:"minecraft:writable_book",count:1,components:{"minecraft:writable_book_content":{pages:["Page one"]}}}}]]'
  const gaveBox = await rcon(`give ${SELLER} ${box} 1`)
  assert(/Gave/i.test(gaveBox) || gaveBox.trim() === '', `shulker given (${gaveBox.trim()})`)
  await sell(seller, 'shulker_box', 50, 1)
  win = await openAh(buyer)
  slot = findSlot(win, bySeller(SELLER, 'shulker_box'), { to: LISTING_SLOTS })
  assert(slot >= 0, `shulker in the GUI (slot ${slot})`)
  if (slot >= 0) {
    const shown = win.slots[slot]
    assert(/Contents: 2 stacks/.test(lore(shown)) && /64x/.test(lore(shown)), `preview sums up the content (${lore(shown)})`)
    assert(!shown.componentMap?.has('container'), 'preview without real container content')
    await buyAt(buyer, slot, 'shulker_box')
    assert(await waitUntil(() => count(buyer, 'shulker_box') === 1), 'buyer has the shulker box')
    const inv = await rcon(`data get entity ${BUYER} Inventory`)
    assert(/shulker_box/.test(inv) && /Page one/.test(inv) && /count: ?64/.test(inv), 'content delivered in full (server inventory)')
  }
  await closeWin(buyer)

  await sellButtonFlow(seller)

  const leaked = [...invItems(seller), ...invItems(buyer)].map((it) => it.name).filter((n) => GUI_JUNK.test(n))
  assertEq(leaked, [], 'no GUI items in the inventories')
  assertEq(count(buyer, 'diamond'), 16, 'diamonds neither lost nor duplicated')
}


const cents = (text) => Math.round(Number(String(text).replace(/[$,]/g, '')) * 100)

function invSlotOf(win, name) {
  for (let i = win.inventoryStart; i < (win.inventoryEnd ?? win.slots.length); i++) if (win.slots[i]?.name === name) return i
  return -1
}

// player inventory from inventoryStart: 27 main, then hotbar
const hotbarSlot = (win, h) => win.inventoryStart + 27 + h

async function openPriceInput(bot, typed) {
  const anvil = await expectWindow(bot, () => clickSlot(bot, SLOT_SELL))
  assert(/anvil/i.test(String(anvil.type)), `${bot.username}: "Sell item" opens the anvil (${anvil.type}, "${windowTitle(anvil)}")`)
  if (typed != null) {
    bot._client.write('name_item', { name: typed })
    await sleep(500)
  }
  return bot.currentWindow
}

async function nextToConfirm(bot) {
  const dlg = await expectWindow(bot, () => clickSlot(bot, 2))
  assert(/Confirm Listing/.test(windowTitle(dlg)), `${bot.username}: confirm dialog ("${windowTitle(dlg)}")`)
  return dlg
}

async function sellButtonFlow(seller) {
  await rcon(`clear ${SELLER}`)
  await sleep(300)
  let win = await openAh(seller)
  const btn = win.slots[SLOT_SELL]
  assertEq(btn?.name, 'emerald_block', 'browse: "Sell item" button on slot 52')
  assert(/^Sell item$/.test(itemLabel(btn)), `button is called "Sell item" (${itemLabel(btn)})`)
  assert(/Your main hand is empty/.test(lore(btn)) && /total price/.test(lore(btn)), `button lore explains the steps (${lore(btn)})`)
  const empty = waitForMessage(seller, /Hold the item you want to sell in your main hand/, 6000).then(() => true, () => false)
  await clickSlot(seller, SLOT_SELL)
  assert(await empty, 'empty hand: "Hold the item you want to sell in your main hand"')
  assert(/Auction House/.test(windowTitle(seller.currentWindow)), 'empty hand: auction house stays open')
  await closeWin(seller)

  await rcon(`give ${SELLER} minecraft:diamond 8`)
  await hold(seller, 'diamond')
  const active0 = (await info(SELLER)).active
  win = await openAh(seller)
  assert(/8x Diamond/.test(lore(win.slots[SLOT_SELL])), `button shows the item in hand (${lore(win.slots[SLOT_SELL])})`)
  await sleep(350)
  let anvil = await openPriceInput(seller, null)
  assert(/Total price for all 8/.test(windowTitle(anvil)), `anvil title names the count ("${windowTitle(anvil)}")`)
  assertEq(anvil.slots[0]?.name, 'diamond', 'anvil shows the item')
  const suggested = cents(itemLabel(anvil.slots[0]))
  assert(suggested >= 8 * 5000, `price field prefilled with market value (${itemLabel(anvil.slots[0])} >= 8 x /sell $50)`)
  assert(/^Next: \$/.test(itemLabel(anvil.slots[2])), `next button shows the suggestion (${itemLabel(anvil.slots[2])})`)
  seller._client.write('name_item', { name: 'abc' })
  await sleep(500)
  anvil = seller.currentWindow
  assertEq(anvil.slots[2]?.name, 'gray_dye', `invalid input: button gray (${itemLabel(anvil.slots[2])})`)
  const invalid = waitForMessage(seller, /not a valid price/, 6000).then(() => true, () => false)
  await clickSlot(seller, 2)
  assert(await invalid, 'invalid price: error')
  assert(/anvil/i.test(String(seller.currentWindow?.type)), 'invalid price: anvil stays open')
  await sleep(350)
  seller._client.write('name_item', { name: '2.5k' })
  await sleep(500)
  anvil = seller.currentWindow
  assertEq(itemLabel(anvil.slots[2]), 'Next: $2,500.00', 'live preview: 2.5k = $2,500.00')
  assert(/Each: \$312\.50 × 8/.test(lore(anvil.slots[2])), `live preview: unit price (${lore(anvil.slots[2])})`)
  let dlg = await nextToConfirm(seller)
  assertEq(dlg.slots[13]?.name, 'diamond', 'dialog shows the item')
  assertEq(dlg.slots[13]?.count, 8, 'dialog: 8 pieces')
  const dl = lore(dlg.slots[13])
  assert(/Total price: \$2,500\.00 \(whole stack\)/.test(dl) && /Each: \$312\.50 × 8/.test(dl), `dialog: total and unit price (${dl})`)
  assert(/Tax on sale \(3%\): -\$75\.00/.test(dl) && /You get: \$2,425\.00/.test(dl), 'dialog: tax and proceeds')
  assert(/Expires in: 48h/.test(dl), 'dialog: duration')
  assert(!/⚠/.test(dl) && /glass_pane/.test(dlg.slots[4]?.name ?? ''), 'dialog: above market value, no warning')
  assert(/^Confirm$/.test(itemLabel(dlg.slots[9])) && /^Cancel$/.test(itemLabel(dlg.slots[15])), 'Dialog: Confirm/Cancel')
  assertEq(count(seller, 'diamond'), 8, 'all diamonds still there before confirming')
  const listed = waitForMessage(seller, /Listed 8x .*for \$2,500\.00 total \(\$312\.50 each\)/, 8000).then(() => true, () => false)
  win = await expectWindow(seller, () => clickSlot(seller, 9))
  assert(await listed, 'Confirm: "Listed 8x … for $2,500.00 total ($312.50 each)"')
  assert(/Auction House/.test(windowTitle(win)), 'back in the auction house after listing')
  assert(await waitUntil(() => count(seller, 'diamond') === 0), 'diamonds taken from the hand')
  assertEq((await info(SELLER)).active, active0 + 1, 'exactly one new listing')
  win = await setFilter(seller, 'All')
  const ls = findSlot(win, bySeller(SELLER, 'diamond'), { to: LISTING_SLOTS })
  assert(ls >= 0 && priceOf(win.slots[ls]) === 250000 && win.slots[ls].count === 8, `listing in the auction house: 8 diamonds for $2,500 (slot ${ls})`)
  await closeWin(seller)

  // swap before confirming (by click in the own inventory): abort, nothing listed, nothing lost
  await rcon(`give ${SELLER} minecraft:diamond 6`)
  await rcon(`give ${SELLER} minecraft:coal 5`)
  await hold(seller, 'diamond')
  const active1 = (await info(SELLER)).active
  await openAh(seller)
  await sleep(350)
  anvil = await openPriceInput(seller, '600')
  dlg = await nextToConfirm(seller)
  const hs = hotbarSlot(dlg, seller.quickBarSlot)
  const cs = invSlotOf(dlg, 'coal')
  assert(dlg.slots[hs]?.name === 'diamond' && cs >= 0, `diamonds in hand (slot ${hs}), coal in slot ${cs}`)
  await clickSlot(seller, hs)
  await clickSlot(seller, cs)
  await clickSlot(seller, hs)
  await sleep(300)
  assertEq(seller.currentWindow?.slots[hs]?.name, 'coal', 'hand swapped: coal instead of diamonds')
  const changed = waitForMessage(seller, /main hand changed – nothing was listed/, 8000).then(() => true, () => false)
  const noList = waitForMessage(seller, /Listed /, 3000).then(() => false, () => true)
  win = await expectWindow(seller, () => clickSlot(seller, 9))
  assert(await changed, 'swap: abort with message')
  assert(await noList, 'swap: nothing listed (no "Listed" message)')
  assert(/Auction House/.test(windowTitle(win)), 'swap: back in the auction house')
  assertEq((await info(SELLER)).active, active1, 'swap: no new listing')
  await closeWin(seller)
  assertEq([count(seller, 'diamond'), count(seller, 'coal')], [6, 5], 'swap: diamonds and coal complete in the inventory')

  // amount changes before confirming (item picked up = give): abort, all 7 stay
  await hold(seller, 'diamond')
  await openAh(seller)
  await sleep(350)
  anvil = await openPriceInput(seller, '700')
  dlg = await nextToConfirm(seller)
  await rcon(`give ${SELLER} minecraft:diamond 1`)
  await sleep(300)
  const changed2 = waitForMessage(seller, /main hand changed – nothing was listed/, 8000).then(() => true, () => false)
  win = await expectWindow(seller, () => clickSlot(seller, 10))
  assert(await changed2, 'count changed: abort with message')
  assertEq((await info(SELLER)).active, active1, 'count changed: no new listing')
  await closeWin(seller)
  assertEq(count(seller, 'diamond'), 7, 'count changed: all 7 diamonds in the inventory')

  // dump price: warning + "Did you mean $10.00 each?" gives a new dialog with $70; change price gives a
  // prefilled anvil; ESC in the anvil and cancel in the dialog go back
  await hold(seller, 'diamond')
  await openAh(seller)
  await sleep(350)
  anvil = await openPriceInput(seller, '10')
  assert(/Way below market value/.test(lore(anvil.slots[2])), `live preview warns already in the anvil (${lore(anvil.slots[2])})`)
  dlg = await nextToConfirm(seller)
  assertEq(dlg.slots[4]?.name, 'redstone_torch', 'dialog: warning icon')
  assert(/Way below market value/.test(lore(dlg.slots[4])) && /Way below market value/.test(lore(dlg.slots[13])), 'dialog: warning (icon + item)')
  assert(/Did you mean \$10\.00 each\?/.test(itemLabel(dlg.slots[23])), `Dialog: "Did you mean $10.00 each?" (${itemLabel(dlg.slots[23])})`)
  dlg = await expectWindow(seller, () => clickSlot(seller, 23))
  assert(/Total price: \$70\.00/.test(lore(dlg.slots[13])), `"each" gives $70.00 total (${lore(dlg.slots[13])})`)
  anvil = await expectWindow(seller, () => clickSlot(seller, 22))
  assert(/anvil/i.test(String(anvil.type)) && itemLabel(anvil.slots[0]) === '70', `"Change price" gives an anvil prefilled with $70 (${itemLabel(anvil.slots[0])})`)
  win = await expectWindow(seller, () => seller.closeWindow(anvil))
  assert(/Auction House/.test(windowTitle(win)), 'ESC in the anvil returns to the auction house')
  assertEq((await info(SELLER)).active, active1, 'ESC: nothing listed')
  await sleep(350)
  anvil = await openPriceInput(seller, '70')
  dlg = await nextToConfirm(seller)
  win = await expectWindow(seller, () => clickSlot(seller, 15))
  assert(/Auction House/.test(windowTitle(win)), 'cancel returns to the auction house')
  assertEq((await info(SELLER)).active, active1, 'cancel: nothing listed')
  await closeWin(seller)
  assertEq(count(seller, 'diamond'), 7, 'cancel: diamonds stay')

  await hold(seller, 'diamond')
  win = await expectWindow(seller, () => seller.chat('/ah my'))
  assert(/My Auctions/.test(windowTitle(win)), '/ah my open')
  assertEq(win.slots[SLOT_SELL]?.name, 'emerald_block', '"My Auctions": "Sell item" button on slot 52')
  await sleep(350)
  anvil = await openPriceInput(seller, null)
  const sug = cents(itemLabel(anvil.slots[0]))
  assert(sug > 0, `suggestion ${itemLabel(anvil.slots[0])}`)
  dlg = await nextToConfirm(seller)
  assert(!/⚠/.test(lore(dlg.slots[13])), 'suggestion (market value) raises no warning')
  const listed2 = waitForMessage(seller, /Listed 7x /, 8000).then(() => true, () => false)
  win = await expectWindow(seller, () => clickSlot(seller, 11))
  assert(await listed2, 'suggestion listed')
  assert(/My Auctions/.test(windowTitle(win)), 'back in "My Auctions"')
  const own = findSlot(win, (it) => it.name === 'diamond' && it.count === 7 && /Active/.test(lore(it)), { to: LISTING_SLOTS })
  assert(own >= 0, 'new listing visible in "My Auctions"')
  assertEq((await info(SELLER)).active, active1 + 1, 'one more listing')
  assertEq(count(seller, 'diamond'), 0, 'diamonds listed')
  await closeWin(seller)
}
