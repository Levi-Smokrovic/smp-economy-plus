// Shop: main menu (daily deals, categories, recently bought, favorites, search), buy dialog (amounts, preview,
// balance before and after, double-click guard), selling with right click (the /sell price), favorites with
// shift + right click (persistent over reconnect), quick buy, too little money, full or partly full inventory
// (a click never buys more than the button shows, even if space opened up), search by command and anvil, price
// change between display and click, treasury share, no GUI items in the inventory.
import {
  connectBot, rcon, runE2E, assert, assertEq, sleep, expectWindow, windowTitle, clickSlot, findSlot, itemLabel, itemLore,
  waitForMessage, treasuryFlows,
} from './lib.mjs'

const B1 = 'shopBot1'
const B2 = 'shopBot2'

const MAIN = { info: 4, dealsHeader: 10, dealsFirst: 11, dealsCount: 5, recentFirst: 36, favorites: 47, close: 49, search: 51 }
const LIST = { back: 45, balance: 47, prev: 48, page: 49, next: 50, search: 51, close: 53 }
const DLG = {
  preview: 4, preset: { 1: 11, 8: 12, 16: 13, 32: 14, 64: 15 }, stacks2: 19, minus1: 30, max: 31, plus1: 32,
  space: 38, balance: 40, price: 42, back: 45, mode: 47, action: 49, fav: 51, close: 53,
}

const fmt = (cents) => '$' + (cents / 100).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
// with a window open the inventory slots live in the window; mineflayer updates bot.inventory only on close
const count = (bot, name) => (bot.currentWindow ?? bot.inventory).items().filter((i) => i.name === name).reduce((a, i) => a + i.count, 0)
const loreHas = (item, re) => itemLore(item).some((l) => re.test(l))
const msg = (bot, re, ms = 6000) => waitForMessage(bot, re, ms).then((m) => m, () => null)

async function shopInfo(name) {
  const out = await rcon(`smpeco shop info ${name}`)
  const m = out.match(/balance=(\d+) favorites=(\S*) recent=(\S*)/)
  if (!m) throw new Error(`smpeco shop info ${name}: ${out}`)
  return { balance: Number(m[1]), favorites: m[2] ? m[2].split(',') : [], recent: m[3] ? m[3].split(',') : [] }
}

async function balance(name) {
  return (await shopInfo(name)).balance
}

async function price(item, player) {
  const out = await rcon(`smpeco price ${item} ${player}`)
  const m = out.match(/sell=(-?\d+) buy=(-?\d+) baseSell=(-?\d+) baseBuy=(-?\d+).*discount=([\d.]+) pivot=(-?\d+)/)
  if (!m) throw new Error(`smpeco price ${item}: ${out}`)
  return { sell: Number(m[1]), buy: Number(m[2]), baseSell: Number(m[3]), baseBuy: Number(m[4]), discount: Number(m[5]), pivot: Number(m[6]), raw: out }
}

/**
 * Treasury income from shop purchases since opening, in cents. The 24 h lines lose the oldest hour at the hour
 * change, so they do not work for differences.
 */
async function treasuryShopIncome() {
  return (await treasuryFlows()).flows.shop?.income ?? 0
}

async function setMoney(name, dollars) {
  const out = await rcon(`eco set ${name} ${dollars}`)
  if (/unknown|incomplete/i.test(out)) throw new Error(`"eco set" missing (economy): ${out}`)
  await sleep(200)
}

async function openMain(bot) {
  if (bot.currentWindow) { bot.closeWindow(bot.currentWindow); await sleep(300) }
  return expectWindow(bot, () => bot.chat('/shop'))
}

async function waitInv(bot, name, expected, ms = 3000) {
  for (let t = 0; t < ms && count(bot, name) !== expected; t += 100) await sleep(100)
  return count(bot, name)
}

runE2E(async () => {
  await rcon('smpeco pricemod clear')
  const st = await rcon('smpeco selftest shop')
  assert(/SUMMARY passed=\d+ failed=0/.test(st), `shop selftests green (${st.trim().slice(-200)})`)

  let bot = await connectBot(B1)
  // the bot would pick up drops of other tests lying at spawn, which breaks "Fits in inventory"
  await rcon(`execute at ${B1} run kill @e[type=minecraft:item,distance=..16]`)
  await sleep(300)
  await rcon(`clear ${B1}`)
  await rcon(`smpeco shop reset ${B1}`)
  await setMoney(B1, 1000)
  assertEq(await balance(B1), 100000, 'starting balance $1,000')

  let win = await openMain(bot)
  assert(/Shop/.test(windowTitle(win)), `shop opened (title "${windowTitle(win)}")`)
  assert(/Daily Deals/.test(itemLabel(win.slots[MAIN.dealsHeader])), 'daily deals header')
  const dealItems = []
  for (let i = 0; i < MAIN.dealsCount; i++) {
    const it = win.slots[MAIN.dealsFirst + i]
    if (it && !/glass_pane/.test(it.name)) dealItems.push(it)
  }
  assert(dealItems.length >= 1, `daily deals visible (${dealItems.map((d) => d.name).join(', ')})`)
  assert(dealItems.every((d) => loreHas(d, /DAILY DEAL -\d+%/)), 'deals marked with discount')
  const dealsOut = await rcon('smpeco shop deals')
  assert(dealItems.every((d) => dealsOut.includes(`minecraft:${d.name} `)), 'GUI deals = smpeco shop deals')
  const dp = await price(`minecraft:${dealItems[0].name}`, B1)
  assert(dp.discount < 1 && dp.buy <= dp.baseBuy && dp.buy > dp.pivot, `deal price discounted, but above pivot (${dp.raw.trim().split('\n')[0]})`)
  // shop price = max(PriceGuard, recipe minimum), as "smpeco shop deals" reports it
  const dm = dealsOut.match(new RegExp(`deal minecraft:${dealItems[0].name} .*? buy=(\\d+) guard=(\\d+)`))
  assert(dm && Number(dm[2]) === dp.buy && Number(dm[1]) >= dp.buy, `smpeco shop deals: buy/guard match (${dm && dm[0]})`)
  assert(loreHas(dealItems[0], new RegExp(`Buy: \\${fmt(Number(dm[1]))}`)), 'deal lore shows the effective price')
  assert(/Favorites/.test(itemLabel(win.slots[MAIN.favorites])) && /Search/.test(itemLabel(win.slots[MAIN.search])), 'favorites and search button')
  assert(loreHas(win.slots[MAIN.info], /Balance: \$1,000\.00/), 'balance in info item')

  const catSlot = findSlot(win, /Building Blocks/)
  assert(catSlot >= 0, 'category Building Blocks present')
  win = await expectWindow(bot, () => clickSlot(bot, catSlot))
  assert(/Building Blocks/.test(windowTitle(win)), 'category opened')
  const stoneSlot = findSlot(win, 'stone', { to: 45 })
  assert(stoneSlot >= 0, 'stone in the category')
  const p = await price('minecraft:stone', B1)
  const stoneLore = itemLore(win.slots[stoneSlot])
  assert(stoneLore.some((l) => l.includes(`Buy: ${fmt(p.buy)}`)), `lore: buy price ${fmt(p.buy)}`)
  assert(stoneLore.some((l) => l.includes(`Sell: ${fmt(p.sell)}`)), `lore: sell price ${fmt(p.sell)}`)
  assert(stoneLore.some((l) => /Trend/.test(l)) && stoneLore.some((l) => /Right-click/.test(l)), 'lore: trend and click hints')
  assertEq(win.slots[LIST.page] && itemLabel(win.slots[LIST.page]), 'Page 1/1', 'page indicator')

  let dlg = await expectWindow(bot, () => clickSlot(bot, stoneSlot))
  assert(/Buy/.test(windowTitle(dlg)), `buy dialog (${windowTitle(dlg)})`)
  assert(loreHas(dlg.slots[DLG.preview], /Amount: 1$/), 'preselected 1')
  assert(loreHas(dlg.slots[DLG.preview], /Fits in inventory: 2,304/),
    `Fits in inventory: 2,304 (${itemLore(dlg.slots[DLG.preview]).find((l) => /Fits in inventory/.test(l)) ?? '?'})`)
  const one = msg(bot, /Bought 1× .* for/)
  await bot.clickWindow(DLG.action, 0, 0)
  await bot.clickWindow(DLG.action, 0, 0) // second click at once is debounced
  assert(await one, 'purchase of 1 reported')
  assertEq(await waitInv(bot, 'stone', 1), 1, 'double click buys only once')
  await sleep(400)

  await clickSlot(bot, DLG.preset[64])
  await sleep(250)
  dlg = bot.currentWindow
  const bal1 = await balance(B1)
  assertEq(bal1, 100000 - p.buy, 'balance after 1')
  assert(loreHas(dlg.slots[DLG.preview], /Amount: 64$/), 'amount 64 chosen')
  assert(loreHas(dlg.slots[DLG.preview], new RegExp(`Balance: \\${fmt(bal1)} → \\${fmt(bal1 - 64 * p.buy)}`)), 'balance before and after')
  assert(/Buy: 64×/.test(itemLabel(dlg.slots[DLG.action])), `action button "${itemLabel(dlg.slots[DLG.action])}"`)
  const treasBefore = await treasuryShopIncome()
  const got64 = msg(bot, /Bought 64× .* for \$([\d,.]+)/)
  await clickSlot(bot, DLG.action)
  const m64 = await got64
  assert(!!m64 && m64[1] === fmt(64 * p.buy).slice(1), `64 bought for ${fmt(64 * p.buy)}`)
  assertEq(await waitInv(bot, 'stone', 65), 65, '65 stone in inventory')
  assertEq(await balance(B1), bal1 - 64 * p.buy, 'only the buy price charged')
  const treasAfter = await treasuryShopIncome()
  assertEq(treasAfter - treasBefore, Math.floor(64 * p.buy / 2), 'treasury gets 50 % of the purchase')

  win = await expectWindow(bot, () => clickSlot(bot, DLG.back))
  assert(/Building Blocks/.test(windowTitle(win)), 'back to the category')
  dlg = await expectWindow(bot, () => clickSlot(bot, stoneSlot, 1, 0))
  assert(/Sell/.test(windowTitle(dlg)), `sell dialog through right click (${windowTitle(dlg)})`)
  assert(loreHas(dlg.slots[DLG.preview], /of 65 sellable/), 'stock of 65 sellable')
  const balBeforeSell = await balance(B1)
  const soldMsg = msg(bot, /Sold 65× .* for/)
  await clickSlot(bot, DLG.action)
  assert(await soldMsg, 'sale reported')
  assertEq(await waitInv(bot, 'stone', 0), 0, 'stone sold')
  assertEq(await balance(B1), balBeforeSell + 65 * p.sell, `proceeds = 65 x /sell price (${fmt(p.sell)})`)

  win = await expectWindow(bot, () => clickSlot(bot, DLG.back))
  const favMsg = msg(bot, /added to your favorites/)
  await clickSlot(bot, stoneSlot, 1, 1)
  assert(await favMsg, 'shift+right click saves favorite')
  await sleep(300)
  assert(/★/.test(itemLabel(bot.currentWindow.slots[stoneSlot])), 'star on the entry')
  win = await expectWindow(bot, () => clickSlot(bot, LIST.back))
  assert(/Favorites/.test(itemLabel(win.slots[MAIN.favorites])) && loreHas(win.slots[MAIN.favorites], /1 saved/), 'favorites button counts 1')
  win = await expectWindow(bot, () => clickSlot(bot, MAIN.favorites))
  assert(/Favorites/.test(windowTitle(win)) && findSlot(win, 'stone', { to: 45 }) >= 0, 'favorites list shows stone')
  bot.closeWindow(win)
  bot.quit()
  await sleep(1500)
  bot = await connectBot(B1)
  assertEq((await shopInfo(B1)).favorites, ['minecraft:stone'], 'favorite saved')
  win = await expectWindow(bot, () => bot.chat('/shop favorites'))
  assert(findSlot(win, 'stone', { to: 45 }) >= 0, 'favorite there after reconnect')
  bot.closeWindow(win)
  await sleep(300)
  win = await expectWindow(bot, () => bot.chat('/shop favorites'))
  assert(/Favorites/.test(windowTitle(win)), `/shop favorites (${windowTitle(win)})`)

  win = await openMain(bot)
  const recent = win.slots[MAIN.recentFirst]
  assert(recent?.name === 'stone' && loreHas(recent, /Recently bought/) && loreHas(recent, /Buy 64× again/), 'stone under "Recently bought"')
  const quick = msg(bot, /Bought 64× .* for/)
  await clickSlot(bot, MAIN.recentFirst)
  assert(await quick, 'quick buy done')
  assertEq(await waitInv(bot, 'stone', 64), 64, 'quick buy delivers 64')
  bot.closeWindow(bot.currentWindow)
  await sleep(300)

  await rcon(`clear ${B1}`)
  await setMoney(B1, 0)
  win = await expectWindow(bot, () => bot.chat('/shop building_blocks'))
  assert(/Building Blocks/.test(windowTitle(win)), `/shop building_blocks opens Building Blocks (${windowTitle(win)})`)
  dlg = await expectWindow(bot, () => clickSlot(bot, stoneSlot))
  assert(/Can't buy/.test(itemLabel(dlg.slots[DLG.action])), 'action button shows "Can\'t buy"')
  const poor = msg(bot, /Not enough money/)
  await clickSlot(bot, DLG.action)
  assert(await poor, 'message "Not enough money"')
  await sleep(300)
  assertEq(count(bot, 'stone'), 0, 'no item without money')
  assertEq(await balance(B1), 0, 'balance stays 0')
  // money arrives in the background (AFK zone, /pay): a click on the stale block still must not buy
  await setMoney(B1, 1000)
  assert(/Can't buy/.test(itemLabel(bot.currentWindow.slots[DLG.action])), 'block still shown (stale)')
  await sleep(350)
  const refreshed = msg(bot, /Now possible/)
  await clickSlot(bot, DLG.action)
  assert(await refreshed, 'click on the block only redraws')
  await sleep(300)
  assertEq(count(bot, 'stone'), 0, 'block does not buy, although money is there now')
  assertEq(await balance(B1), 100000, 'nothing charged')
  assert(/Buy: 1×/.test(itemLabel(bot.currentWindow.slots[DLG.action])), `buy button now (${itemLabel(bot.currentWindow.slots[DLG.action])})`)

  bot.closeWindow(bot.currentWindow)
  await setMoney(B1, 1000)
  await rcon(`give ${B1} minecraft:bedrock 2304`)
  await sleep(500)
  win = await expectWindow(bot, () => bot.chat('/shop blocks'))
  dlg = await expectWindow(bot, () => clickSlot(bot, stoneSlot))
  assert(loreHas(dlg.slots[DLG.preview], /Fits in inventory: 0/), 'preview: fits 0')
  const full = msg(bot, /No space in your inventory/)
  await clickSlot(bot, DLG.action)
  assert(await full, 'message "No space in your inventory"')
  assertEq(await balance(B1), 100000, 'full inventory: nothing charged')
  bot.closeWindow(bot.currentWindow)
  await rcon(`clear ${B1} minecraft:bedrock 64`)
  await sleep(500)
  win = await expectWindow(bot, () => bot.chat('/shop blocks'))
  dlg = await expectWindow(bot, () => clickSlot(bot, stoneSlot))
  await clickSlot(bot, DLG.stacks2)
  await sleep(250)
  dlg = bot.currentWindow
  assert(loreHas(dlg.slots[DLG.preview], /Amount: 128$/) && loreHas(dlg.slots[DLG.preview], /Fits in inventory: 64/), 'selected 128, fits 64')
  const partBuy = msg(bot, /Bought 64× .* for/)
  const partNote = msg(bot, /Only 64 of 128 fit/)
  await clickSlot(bot, DLG.action)
  assert(await partBuy, 'partial purchase: 64 delivered')
  assert(await partNote, 'hint: only these paid')
  assertEq(await waitInv(bot, 'stone', 64), 64, '64 stone in the last free slot')
  assertEq(await balance(B1), 100000 - 64 * p.buy, 'only 64 paid')
  // space opens up while the dialog is open: the button promised 64x, so never buy or charge more than shown
  await rcon(`clear ${B1} minecraft:stone`)
  await sleep(400)
  await clickSlot(bot, DLG.stacks2)
  await sleep(250)
  assert(/Buy: 64×/.test(itemLabel(bot.currentWindow.slots[DLG.action])), `button shows 64x (${itemLabel(bot.currentWindow.slots[DLG.action])})`)
  await rcon(`clear ${B1} minecraft:bedrock 640`)
  await sleep(400)
  const pinnedBuy = msg(bot, /Bought (\d+)× .* for/)
  await clickSlot(bot, DLG.action)
  const pinned = await pinnedBuy
  assertEq(pinned && Number(pinned[1]), 64, 'click buys exactly the 64 shown')
  assertEq(await waitInv(bot, 'stone', 64), 64, '64 stone delivered')
  assertEq(await balance(B1), 100000 - 128 * p.buy, 'only the shown amount charged')
  bot.closeWindow(bot.currentWindow)
  await rcon(`clear ${B1}`)
  await sleep(300)

  // search by command and by anvil input
  win = await expectWindow(bot, () => bot.chat('/shop search oak log'))
  assert(/Search/.test(windowTitle(win)) && findSlot(win, 'oak_log', { to: 45 }) >= 0, `search "oak log" finds oak log (${windowTitle(win)})`)
  bot.closeWindow(win)
  await sleep(300)
  win = await expectWindow(bot, () => bot.chat('/shop search oak log'))
  assert(/Search/.test(windowTitle(win)) && findSlot(win, 'oak_log', { to: 45 }) >= 0, `"/shop search oak log" finds oak log (${windowTitle(win)})`)
  bot.closeWindow(win)
  await sleep(300)
  const none = msg(bot, /No results/)
  bot.chat('/shop search zzqqxx')
  assert(await none, 'search without a hit says so')
  const extra = msg(bot, /Unknown category/)
  bot.chat('/shop blocks zzqqxx')
  assert(await extra, '"/shop blocks zzqqxx" reports an unknown category')
  // tab completion lists only English subcommands
  const tab = ((await bot.tabComplete('/shop ', false, false).catch(() => [])) ?? []).map((m) => (typeof m === 'string' ? m : m.match))
  assert(['search', 'deals', 'favorites', 'blocks'].every((n) => tab.includes(n)),
    `tab completion of /shop (${tab.join(', ')})`)
  win = await openMain(bot)
  const anvil = await expectWindow(bot, () => clickSlot(bot, MAIN.search))
  assert(/Search/.test(windowTitle(anvil)), `anvil search opened (${windowTitle(anvil)})`)
  bot._client.write('name_item', { name: 'Pumpkin' })
  await sleep(400)
  win = await expectWindow(bot, () => clickSlot(bot, 2))
  assert(/Search: Pumpkin/.test(windowTitle(win)) && findSlot(win, 'pumpkin_seeds', { to: 45 }) >= 0, 'anvil search "Pumpkin" finds pumpkin seeds')
  // a number key on frame or entry must bring nothing into the inventory
  await clickSlot(bot, LIST.balance, 0, 2)
  await clickSlot(bot, 0, 0, 2)
  bot.closeWindow(win)
  await sleep(500)
  assertEq(bot.inventory.items().map((i) => i.name), [], 'no GUI items in the inventory')

  const bot2 = await connectBot(B2)
  await rcon(`clear ${B2}`)
  await rcon(`smpeco shop reset ${B2}`)
  await setMoney(B2, 1000)
  assertEq((await shopInfo(B2)).favorites, [], 'favorites are per player')
  await expectWindow(bot2, () => bot2.chat('/shop blocks'))
  dlg = await expectWindow(bot2, () => clickSlot(bot2, stoneSlot))
  assert(loreHas(dlg.slots[DLG.preview], new RegExp(`Price: \\${fmt(p.buy)}`)), `dialog shows ${fmt(p.buy)}`)
  await rcon('smpeco pricemod minecraft:stone 3 5')
  const hyped = await price('minecraft:stone', B2)
  assert(hyped.buy > p.buy, `price risen to ${fmt(hyped.buy)}`)
  const changed = msg(bot2, /price just changed/)
  await clickSlot(bot2, DLG.action)
  assert(await changed, 'purchase at the old price refused')
  await sleep(200)
  assertEq(count(bot2, 'stone'), 0, 'nothing bought')
  assertEq(await balance(B2), 100000, 'nothing charged')
  assert(loreHas(bot2.currentWindow.slots[DLG.preview], new RegExp(`Price: \\${fmt(hyped.buy)}`)), 'dialog shows the new price')
  await sleep(350)
  const bought2 = msg(bot2, /Bought 1× .* for/)
  await clickSlot(bot2, DLG.action)
  assert(await bought2, 'second click buys at the new price')
  assertEq(await balance(B2), 100000 - hyped.buy, 'new price paid')
  await rcon('smpeco pricemod clear')
  bot2.closeWindow(bot2.currentWindow)
  await sleep(300)

  await rcon(`clear ${B1}`)
  await rcon(`clear ${B2}`)
  await rcon(`smpeco shop reset ${B1}`)
  await rcon(`smpeco shop reset ${B2}`)
}, { timeoutMs: 240000 })
