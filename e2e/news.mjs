// Breaking news and market shocks: admin trigger over RCON, title and subtitle, chat line, boss bar with ticker
// (red or green, progress), prices through PriceGuard (crash only on the sell side, hype, capped shop hype,
// flash sale), /news GUI and paged history with filter, a late joiner sees the boss bar, /news clear as op, NPC
// bulk order with orders (admin cancel and full delivery), and the real automatic schedule.
import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { E2E_SERVER_DIR } from '../tools/server-restart.mjs'
import {
  connectBot, rcon, runE2E, assert, assertEq, sleep, waitForMessage, expectWindow, windowTitle, clickSlot,
  findSlot, itemLabel, itemLore, textOf, topUpTreasury, countItem,
} from './lib.mjs'

const B1 = 'newsBot1'
const B2 = 'newsBot2'
const NEWS_BAR = /CRASH|HYPE|FLASH SALE|BULK ORDER|AUCTION FEVER|returning to normal/
const RED = 2
const GREEN = 3

async function waitFor(fn, timeoutMs, label) {
  const end = Date.now() + timeoutMs
  for (;;) {
    const v = await fn()
    if (v) return v
    if (Date.now() > end) throw new Error(`Timeout (${timeoutMs} ms): ${label}`)
    await sleep(150)
  }
}

// record boss bars and titles from raw packets (texts as NBT, see textOf)
function track(bot) {
  bot.bars = new Map()
  bot.barLog = []
  bot.titles = []
  const onBar = (p) => {
    const id = String(p.entityUUID)
    if (p.action === 0) {
      const bar = { title: textOf(p.title), color: p.color, health: p.health }
      bot.bars.set(id, bar)
      bot.barLog.push({ ...bar, at: Date.now() })
    } else if (p.action === 1) {
      bot.bars.delete(id)
    } else {
      const bar = bot.bars.get(id)
      if (!bar) return
      if (p.action === 2 && p.health !== undefined) bar.health = p.health
      if (p.action === 3 && p.title !== undefined) bar.title = textOf(p.title)
      if (p.action === 4 && p.color !== undefined) bar.color = p.color
      bot.barLog.push({ ...bar, at: Date.now() })
    }
  }
  for (const name of ['boss_bar', 'bossbar']) bot._client.on(name, onBar)
  bot._client.on('set_title_text', (p) => bot.titles.push({ type: 'title', text: textOf(p.text), at: Date.now() }))
  bot._client.on('set_title_subtitle', (p) => bot.titles.push({ type: 'subtitle', text: textOf(p.text), at: Date.now() }))
}

const newsBar = (bot) => [...bot.bars.values()].find((b) => NEWS_BAR.test(b.title))

/** /news clear does not end NPC bulk orders, which orders cannot abort, so wait until none is running. */
async function waitNoBulk(timeoutMs) {
  return waitFor(async () => !/type=BULK_ORDER/.test(await rcon('news status')), timeoutMs, 'bulk order ended')
}

/**
 * Running NPC bulk order according to status (item, amount, order ID in orders, 0 = older message without ID,
 * time left), or null.
 */
function bulkEntry(status) {
  const m = status.match(/type=BULK_ORDER item=(\S+) .*?amount=(\d+)(?: order=(\d+))? remaining=(\d+)s/)
  return m ? { item: m[1], amount: Number(m[2]), order: Number(m[3] ?? 0), remaining: Number(m[4]) } : null
}

function bulkStart(out) {
  const m = out.match(/Started: Bulk order (\d+)x (minecraft:\S+) \(order #(\d+)\)/)
  return m ? { amount: Number(m[1]), item: m[2], order: Number(m[3]) } : null
}

async function orderLine(id) {
  const m = (await rcon('orders admin list')).match(new RegExp(`#${id} npc=(\\S+) bulk=(\\S+) item=(\\S+) delivered=(\\d+) amount=(\\d+)`))
  return m ? { npc: m[1] === 'true', bulk: m[2] === 'true', item: m[3], delivered: Number(m[4]), amount: Number(m[5]) } : null
}

/**
 * Top up the treasury for bulk orders when other scripts have used up the hourly budget (lib.topUpTreasury).
 * Returns the deposited dollars, to take back at the end.
 */
async function ensureBulkBudget() {
  return topUpTreasury(50_000)
}

/**
 * Delivers the whole bulk order like a player: /orders, click the order, "Deliver all". A click exactly at a
 * price change is repeated. Returns the delivered amount and the time of the completion message.
 */
async function deliverBulk(bot, order) {
  await rcon(`clear ${bot.username}`)
  await rcon(`gamemode survival ${bot.username}`) // creative and spectator do not deliver
  await rcon(`give ${bot.username} ${order.item} ${order.amount}`)
  await waitFor(async () => (await countItem(bot.username, order.item)) >= order.amount, 5000, 'items received for the bulk order')
  let delivered = 0
  let doneAt = 0
  for (let attempt = 0; attempt < 3 && !doneAt; attempt++) {
    const main = await expectWindow(bot, () => bot.chat('/orders'))
    const slot = findSlot(main, (it) => itemLore(it).some((l) => l.includes(`Order #${order.order}`)))
    if (slot < 0) throw new Error(`bulk order #${order.order} not in /orders`)
    const win = await expectWindow(bot, () => clickSlot(bot, slot))
    assert(new RegExp(`Deliver – Order #${order.order}`).test(windowTitle(win)), `delivery window #${order.order}`)
    const msg = waitForMessage(bot, /Delivered: ([\d,]+)×|price has changed/, 8000)
    await clickSlot(bot, 14)
    const m = await msg
    if (m[1]) delivered += Number(m[1].replace(/,/g, ''))
    if (m[1] && /Order complete/.test(m.input)) doneAt = Date.now()
    await sleep(300)
    if (bot.currentWindow) bot.closeWindow(bot.currentWindow)
    await sleep(300)
  }
  return { delivered, doneAt }
}

function onlyBulk(status) {
  const entries = status.split('\n').filter((l) => /news entry/.test(l))
  return /fever=false flash=-/.test(status) && entries.every((l) => /type=BULK_ORDER/.test(l))
}

async function price(item) {
  const out = await rcon(`smpeco price ${item}`)
  const num = (k) => { const m = out.match(new RegExp(`\\b${k}=(-?[\\d.]+)`)); return m ? Number(m[1]) : NaN }
  const r = { sell: num('sell'), buy: num('buy'), baseSell: num('baseSell'), baseBuy: num('baseBuy'), mod: num('mod'),
    sellMult: num('sellMult'), discount: num('discount'), pivot: num('pivot'), buyMod: num('buyMod'), sellNoCrash: num('sellNoCrash'),
    raw: out.trim() }
  if (Number.isNaN(r.sell)) throw new Error(`smpeco price ${item}: ${out}`)
  return r
}

function expectedSell(p) {
  if (p.baseSell <= 0) return -1
  let v = Math.floor(p.baseSell * p.mod * p.sellMult + 1e-6)
  if (p.pivot >= 0) v = Math.min(v, p.pivot)
  return v > 0 ? v : -1
}

const close = (a, b, eps = 0.002) => Math.abs(a - b) <= eps

let autoBefore = null
// original text of news.json (schedule test), written back in the finally block
let newsConfigBefore = null
const NEWS_CONFIG = join(E2E_SERVER_DIR, 'config', 'smpeconomy', 'news.json')

async function restoreNewsConfig() {
  if (newsConfigBefore === null) return
  writeFileSync(NEWS_CONFIG, newsConfigBefore)
  newsConfigBefore = null
  const out = await rcon('smpeco reload')
  console.log(`[E2E] news.json restored: ${out.trim().split('\n')[0]}`)
}
// dollars deposited for bulk orders, taken back at the end
let deposited = 0

runE2E(async () => {
  // pause automatic messages (persisted), or a scheduled one starts mid-test
  const st0 = await rcon('news status')
  const am = st0.match(/\bauto=(on|off)\b/)
  assert(!!am, `status shows auto=on|off (${st0.split('\n')[0]})`)
  assert(/running=\d+\/\d+/.test(st0) && /interval=\d+-\d+m/.test(st0) && /next=/.test(st0), `status shows the schedule (${st0.split('\n')[0]})`)
  autoBefore = am ? am[1] : null
  assert(/villager=\d+/.test(st0), 'villager exceptions computed (crash and flash sale skip all shop items otherwise)')
  try {
    assert(/paused/.test(await rcon('news auto off')), '/news auto off')
    assert(/auto=off/.test(await rcon('news status')), 'status: auto=off')
    await rcon('news clear')
    // A bulk order of an earlier run, or an automatic one: cancel through orders, the message follows at once.
    // Older messages without an order ID: wait 1 min; an older automatic one (30 min) just keeps running.
    let preBulk = 0
    const pre = bulkEntry(await rcon('news status'))
    if (pre && pre.order > 0) {
      console.log(`[E2E] cancelling running bulk order #${pre.order}: ${(await rcon(`orders admin cancel ${pre.order}`)).trim()}`)
      await waitNoBulk(5000)
    } else if (pre) {
      preBulk = pre.remaining
      if (preBulk <= 75) {
        await waitNoBulk(80000)
        preBulk = 0
      }
    }
    await rcon(`deop ${B1}`)
    await rcon(`deop ${B2}`)

    const bot = await connectBot(B1)
    track(bot)
    assert(true, `${B1} connected`)

    const st = await rcon('smpeco selftest news')
    const sm = st.match(/SUMMARY passed=(\d+) failed=(\d+)/)
    assert(!!sm && Number(sm[1]) >= 15, `news selftests ran (${sm ? sm[0] : st.slice(0, 200)})`)
    if (sm) assertEq(Number(sm[2]), 0, `news-Selftests failed=0 ${Number(sm[2]) ? st.slice(0, 600) : ''}`)

    let win = await expectWindow(bot, () => bot.chat('/news'))
    assert(/Breaking News/.test(windowTitle(win)), `/news opens the GUI (${windowTitle(win)})`)
    assert(findSlot(win, /No news right now/) >= 0, 'GUI shows "No news right now"')
    bot.closeWindow(win)
    await sleep(300)
    bot.chat('/news trigger')
    const denied = await waitForMessage(bot, /Incorrect argument|Unknown or incomplete command|unknown/i, 5000).catch(() => null)
    assert(!!denied, 'non-op cannot use /news trigger')

    const base = {
      diamond: await price('minecraft:diamond'),
      block: await price('minecraft:diamond_block'),
      gold: await price('minecraft:gold_ingot'),
      bone: await price('minecraft:bone'),
      boneBlock: await price('minecraft:bone_block'),
      cactus: await price('minecraft:cactus'),
    }
    assert(base.diamond.baseSell > 0 && base.gold.baseSell > 0 && base.bone.pivot > 0, 'base prices present (diamond, gold, bone in the shop)')

    const t0 = Date.now()
    const crash = await rcon('news trigger minecraft:diamond -50 10')
    assert(/Started: minecraft:diamond -50%/.test(crash), `crash started (${crash.trim()})`)
    assert(!/Trader goods/.test(crash), 'crash on market NPC goods without a warning (lowers only the sell side)')
    const title = await waitFor(() => bot.titles.find((t) => t.at >= t0 && t.type === 'title' && /BREAKING NEWS/.test(t.text)), 5000, 'Title')
      .catch(() => null)
    assert(!!title, 'title "BREAKING NEWS" shown')
    const sub = bot.titles.find((t) => t.at >= t0 && t.type === 'subtitle')
    assert(!!sub && /-50%/.test(sub.text), `subtitle with -50% (${sub?.text})`)
    const chat = await waitForMessage(bot, /BREAKING NEWS.*-50%/, 5000, { includePast: true }).catch(() => null)
    assert(!!chat, 'chat line "BREAKING NEWS … -50%"')
    const bar = await waitFor(() => newsBar(bot), 5000, 'Bossbar').catch(() => null)
    const h0 = bar ? bar.health : 0
    assert(!!bar, `boss bar appears (${bar?.title})`)
    if (bar) {
      assert(/CRASH/.test(bar.title) && /-50%/.test(bar.title) && /left/.test(bar.title), `Bossbar-Text (${bar.title})`)
      assertEq(bar.color, RED, 'bossbar red on crash')
      assert(h0 > 0.9 && h0 <= 1.0, `progress = time left (${h0})`)
    }
    const dc = await price('minecraft:diamond')
    assert(close(dc.mod, base.diamond.mod * 0.5), `diamond factor halved (${base.diamond.mod} to ${dc.mod})`)
    assertEq(dc.sell, expectedSell(dc), 'diamond sell price through PriceGuard')
    assert(dc.sell < base.diamond.sell, `diamond cheaper (${base.diamond.sell} to ${dc.sell})`)
    assert(/-50% Breaking News/.test(dc.raw) && /sell(ing)? only/i.test(dc.raw), 'lore line "-50% Breaking News" (sell only)')
    assertEq(dc.sellNoCrash, base.diamond.sell, 'sale without crash = normal price')
    const bc = await price('minecraft:diamond_block')
    assert(close(bc.mod, base.block.mod * 0.5), 'family: diamond block also -50%')
    await sleep(2500)
    const bar2 = newsBar(bot)
    assert(!!bar2 && bar2.health < h0, `progress falls (${h0} to ${bar2?.health})`)

    const t1 = Date.now()
    const hype = await rcon('news trigger minecraft:gold_ingot +150 10')
    assert(/Started: minecraft:gold_ingot \+150%/.test(hype), `hype started (${hype.trim()})`)
    assert(/Trader goods/.test(hype), 'admin warning on a hype on market NPC goods (never automatic)')
    const ec = await price('minecraft:gold_ingot')
    assert(close(ec.mod, base.gold.mod * 2.5), `gold factor ×2.5 (${ec.mod})`)
    assertEq(ec.sell, expectedSell(ec), 'gold sell price through PriceGuard')
    const gb = await price('minecraft:gold_block')
    assert(gb.mod > 2.4, `family: gold block also in the hype (mod=${gb.mod})`)
    const seenHype = await waitFor(() => bot.barLog.find((b) => b.at >= t1 && /HYPE/.test(b.title) && /\+150%/.test(b.title)), 9000, 'Hype in Bossbar')
      .catch(() => null)
    assert(!!seenHype, `ticker shows the hype (${seenHype?.title})`)
    if (seenHype) assertEq(seenHype.color, GREEN, 'boss bar green on hype')
    const t1b = Date.now()
    const seenCrash = await waitFor(() => bot.barLog.find((b) => b.at >= t1b && /CRASH/.test(b.title)), 9000, 'crash back in the boss bar')
      .catch(() => null)
    assert(!!seenCrash && /\[\d\/2\]/.test(seenCrash.title), `ticker rotates back to the crash (${seenCrash?.title})`)

    if (base.cactus.baseBuy > 0 && base.cactus.baseSell > 0) {
      const cc = await rcon('news trigger minecraft:cactus -50 10')
      assert(/Started: minecraft:cactus -\d+%/.test(cc), `crash on cactus (${cc.trim()})`)
      const cp = await price('minecraft:cactus')
      assert(cp.sell < base.cactus.sell, `cactus: sale falls (${base.cactus.sell} to ${cp.sell})`)
      assertEq(cp.buy, base.cactus.buy, `cactus: shop price stays in the crash (${base.cactus.buy})`)
      assert(close(cp.buyMod, base.cactus.buyMod), `cactus: buy modifier without crash (${cp.buyMod})`)
      assertEq(cp.sellNoCrash, base.cactus.sell, 'cactus: sale without crash = normal price')
      assert(/sell(ing)? only/i.test(cp.raw), 'cactus: lore (sell only)')
    }

    const shopHype = await rcon('news trigger minecraft:bone +200 10')
    const sm2 = shopHype.match(/minecraft:bone \+(\d+)%/)
    assert(!!sm2, `shop hype started (${shopHype.trim()})`)
    const bh = await price('minecraft:bone')
    if (sm2) {
      const shown = Number(sm2[1])
      const neutral = Math.floor((Math.min(base.bone.baseSell * base.bone.mod * 3, base.bone.pivot) / Math.min(base.bone.baseSell * base.bone.mod, base.bone.pivot) - 1) * 100 + 1e-6)
      assert(shown < 200 && shown <= neutral, `effective display instead of +200% (${shown}% <= ${neutral}%)`)
      assert(/sell capped/.test(shopHype), 'hint "sell capped"')
    }
    assert(bh.sell <= bh.pivot, `bone sale <= pivot (${bh.sell} <= ${bh.pivot})`)
    assert(bh.buy >= bh.pivot + 1 && bh.buy > bh.sell, `bone shop >= pivot + 1 (${bh.buy})`)
    assert(/capped=true/.test(bh.raw), 'PriceGuard reports sale capped')
    // family of a shop hype: members without a pivot (bone block is not in the shop) get no factor, or their
    // sell and market value would rise uncapped
    const bbh = await price('minecraft:bone_block')
    if (bbh.pivot < 0) {
      assert(close(bbh.mod, base.boneBlock.mod), `bone block (no pivot) not in the shop hype (mod ${base.boneBlock.mod} to ${bbh.mod})`)
      if (/minecraft:bone_block/.test(shopHype)) assert(/Not in the hype.*minecraft:bone_block/.test(shopHype), 'admin hint "Not in the hype"')
    }

    win = await expectWindow(bot, () => bot.chat('/news'))
    assert(/Breaking News/.test(windowTitle(win)), 'GUI open')
    const dSlot = findSlot(win, 'diamond')
    const eSlot = findSlot(win, 'gold_ingot')
    const boneSlot = findSlot(win, 'bone')
    assert(dSlot >= 0 && eSlot >= 0 && boneSlot >= 0, `3 active messages in the GUI (${dSlot}/${eSlot}/${boneSlot})`)
    if (dSlot >= 0) {
      const lore = itemLore(win.slots[dSlot]).join(' | ')
      assert(/CRASH/.test(itemLabel(win.slots[dSlot])) && /-50%/.test(itemLabel(win.slots[dSlot])), `Name ${itemLabel(win.slots[dSlot])}`)
      assert(/Your sell price/.test(lore) && /Time left:/.test(lore) && /Breaking News/.test(lore), `lore with prices and time left (${lore.slice(0, 200)})`)
      await clickSlot(bot, dSlot)
      await clickSlot(bot, dSlot, 1)
    }
    const hist = findSlot(win, /History/)
    assert(hist >= 0, 'history button present')
    win = await expectWindow(bot, () => clickSlot(bot, hist))
    assert(/History/.test(windowTitle(win)), `history opened (${windowTitle(win)})`)
    const hd = findSlot(win, 'diamond')
    assert(hd >= 0 && itemLore(win.slots[hd]).some((l) => /running/.test(l)), 'history: diamond crash "running"')
    if (hd >= 0) assert(itemLore(win.slots[hd]).some((l) => /shop unchanged/.test(l)), 'history: crash "shop unchanged"')
    assert(findSlot(win, /Page 1\//) >= 0, 'history has a page indicator')
    const filterSlot = findSlot(win, /Filter: All/)
    assert(filterSlot >= 0, 'Filter-Button "All"')
    if (filterSlot >= 0) {
      await clickSlot(bot, filterSlot)
      await sleep(400)
      const fwin = bot.currentWindow
      const named = fwin ? fwin.slots.slice(0, 45).filter((x) => x && x.name !== 'gray_dye' && !/glass_pane/.test(x.name)) : []
      assert(fwin && findSlot(fwin, /Filter: Crashes/) >= 0, 'filter moved on: crashes')
      assert(named.length > 0 && named.every((x) => /CRASH/.test(itemLabel(x))), `filter crashes shows only crashes (${named.map(itemLabel).join(', ').slice(0, 160)})`)
      assert(named.every((x) => x.name !== 'gold_ingot'), 'hype (gold) filtered out')
    }
    const back = findSlot(bot.currentWindow ?? win, /Back/)
    win = await expectWindow(bot, () => clickSlot(bot, back))
    assert(/Breaking News/.test(windowTitle(win)) && !/History/.test(windowTitle(win)), 'back to the main view')
    bot.closeWindow(win)
    await sleep(500)
    assert(!bot.inventory.items().some((i) => ['diamond', 'gold_ingot', 'bone', 'book', 'bell'].includes(i.name)), 'no GUI items in the inventory')

    const bot2 = await connectBot(B2, { setup: track })
    const bar3 = await waitFor(() => newsBar(bot2), 5000, 'boss bar for the late joiner').catch(() => null)
    assert(!!bar3, `${B2} sees the boss bar after the join (${bar3?.title})`)
    const hint = await waitForMessage(bot2, /Breaking News/, 13000, { includePast: true }).catch(() => null) // first join: +8 s (JoinGrace)
    assert(!!hint, `${B2} gets the join hint`)

    const flash = await rcon('news action flashsale 5')
    const fm = flash.match(/category=(\S+) (-\d+)% sample=(\S+)/)
    assert(!!fm, `flash sale started (${flash.trim()})`)
    let flashSample = null
    if (fm && fm[3] !== '-') {
      flashSample = await price(fm[3])
      assert(flashSample.discount < 1, `flash sale discount on ${fm[3]} (discount=${flashSample.discount})`)
      assert(flashSample.buy >= flashSample.pivot + 1, `flash sale never below pivot + 1 (${flashSample.buy} >= ${flashSample.pivot + 1})`)
      assert(/Flash Sale/.test(flashSample.raw), 'lore line "Flash Sale"')
      assert(Number(fm[2]) >= -40, `display at most -40% (${fm[2]}%)`)
    }
    const t2 = Date.now()
    const seenFlash = await waitFor(() => [bot, bot2].some((b) => b.barLog.some((x) => x.at >= t2 && /FLASH SALE/.test(x.title))), 12000, 'Flash Sale in Bossbar')
      .catch(() => null)
    assert(!!seenFlash, 'flash sale passes through the boss bar')
    const fever = await rcon('news action fever 5')
    assert(/Started: Auction Fever/.test(fever), `auction fever started (${fever.trim()})`)
    assert(/fever=true/.test(await rcon('news status')), 'status: fever=true')
    assert(!/Started/.test(await rcon('news action fever 5')), 'fever not twice')
    deposited += await ensureBulkBudget()
    const bulk = await rcon('news action bulkorder 1')
    const bulk1 = bulkStart(bulk)
    const bulkStarted = !!bulk1
    if (preBulk > 0) {
      assert(bulkStarted || /already running/.test(bulk), `bulk order started or an older one still running (${bulk.trim()})`)
    } else {
      // news to NpcOrders must not fail silently: treasury is topped up, orders is running
      assert(bulkStarted, `bulk order started, with order ID (${bulk.trim()})`)
    }
    if (bulkStarted) {
      assert(/already running/.test(await rcon('news action bulkorder 1')), 'no second bulk order at the same time')
      const ol = await orderLine(bulk1.order)
      assert(!!ol && ol.npc && ol.bulk && ol.item === bulk1.item, `order #${bulk1.order} in orders (${JSON.stringify(ol)})`)
      if (ol) assertEq(bulk1.amount, ol.amount, 'message shows the amount orders created')
      const be = bulkEntry(await rcon('news status'))
      assertEq(be?.order, bulk1.order, 'status: order ID remembered')
    }

    assert(/has no sell price/.test(await rcon('news trigger minecraft:bedrock -50 5')), 'item without a price refused')
    assert(/Invalid percentage/.test(await rcon('news trigger minecraft:diamond 0 5')), 'percent 0 refused')
    assert(/Invalid percentage/.test(await rcon('news trigger minecraft:diamond abc 5')), 'percent "abc" refused')
    assert(/Unknown action/.test(await rcon('news action nonsense')), 'unknown action refused')
    const status = await rcon('news status')
    assert(/type=CRASH item=minecraft:diamond/.test(status) && /type=HYPE item=minecraft:gold_ingot/.test(status), 'status lists crash and hype')

    await rcon(`op ${B1}`)
    await sleep(500)
    bot.chat('/news clear')
    const cleared = await waitForMessage(bot, /Breaking News ended/, 6000).catch(() => null)
    assert(!!cleared, '/news clear as op')
    const after = { diamond: await price('minecraft:diamond'), gold: await price('minecraft:gold_ingot'), bone: await price('minecraft:bone'),
      cactus: await price('minecraft:cactus') }
    assertEq([after.diamond.sell, after.gold.sell, after.bone.sell, after.cactus.sell], [base.diamond.sell, base.gold.sell, base.bone.sell, base.cactus.sell],
      'prices normal again')
    if (flashSample) {
      const fs = await price(fm[3])
      assert(fs.buy > flashSample.buy, `flash sale ended (${flashSample.buy} to ${fs.buy})`)
    }
    if (bulkStarted) {
      // the bulk order stays visible, since news cannot abort the order; a hint names the cancel in orders
      const kept = await waitForMessage(bot, /Bulk Order.*keeps running in \/orders.*cancel: \/orders admin cancel (\d+)/, 6000, { includePast: true })
        .catch(() => null)
      assert(!!kept, '/news clear reports: bulk order keeps running, abort through orders')
      if (kept) assertEq(Number(kept[1]), bulk1.order, 'hint names the order ID')
      const st = await rcon('news status')
      assert(/active=1 fever=false flash=-/.test(st) && /type=BULK_ORDER/.test(st), `status: only the bulk order active (${st.split('\n')[0]})`)
      const bulkBar = await waitFor(() => /BULK ORDER/.test(newsBar(bot)?.title ?? ''), 5000, 'bulk order in the boss bar').catch(() => null)
      assert(!!bulkBar, 'boss bar shows only the bulk order after clear')
      // admin cancel in orders: message, boss bar and /news entry go at once, not after the run time
      const ended = waitForMessage(bot, /NPC Bulk Order over/, 4000)
      const c0 = Date.now()
      const cancel = await rcon(`orders admin cancel ${bulk1.order}`)
      assert(/cancelled/.test(cancel), `bulk order cancelled in orders (${cancel.trim()})`)
      const barGone = await waitFor(() => !newsBar(bot) && !newsBar(bot2), 2500, 'boss bar gone after cancel').catch(() => null)
      assert(!!barGone, `boss bar gone after the cancel (${Date.now() - c0} ms)`)
      assert(!/type=BULK_ORDER/.test(await rcon('news status')), 'status: bulk order message gone after the cancel')
      assert(await ended.then(() => true, () => false), 'chat: bulk order ended after the cancel')
    }
    if (preBulk > 0) {
      // an older automatic bulk order keeps running, and only it may still be visible
      const only = await waitFor(() => [bot, bot2].every((b) => !newsBar(b) || /BULK ORDER/.test(newsBar(b).title)), 5000, 'only bulk order')
        .catch(() => null)
      assert(!!only, 'boss bar shows only the running bulk order')
      const st = await rcon('news status')
      assert(onlyBulk(st), `status: only the older bulk order active (${st.split('\n')[0]})`)
    } else {
      const gone = await waitFor(() => !newsBar(bot) && !newsBar(bot2), 5000, 'bossbar gone').catch(() => null)
      assert(!!gone, 'boss bar gone for both')
      assert(/active=0 fever=false flash=-/.test(await rcon('news status')), 'status: nothing active')
    }
    win = await expectWindow(bot, () => bot.chat('/news'))
    const h2 = findSlot(win, /History/)
    win = await expectWindow(bot, () => clickSlot(bot, h2))
    const lores = win.slots.filter((s) => s && s.name === 'diamond').flatMap((s) => itemLore(s))
    assert(lores.some((l) => /cancelled/.test(l)), 'history: ended by clear = "cancelled"')
    bot.closeWindow(win)
    await sleep(300)

    if (bulkStarted) {
      // possible again right after the cancel, no waiting for the old run time
      deposited += await ensureBulkBudget()
      const out2 = await rcon('news action bulkorder 5')
      const bulk2 = bulkStart(out2)
      assert(!!bulk2, `bulk order possible again right after the cancel (${out2.trim()})`)
      if (bulk2) {
        assert(bulk2.order !== bulk1.order, `new order #${bulk2.order}`)
        const ol = await orderLine(bulk2.order)
        assert(!!ol && ol.amount === bulk2.amount, `orders: ${bulk2.amount}× ${bulk2.item} (${JSON.stringify(ol)})`)
        const bar2b = await waitFor(() => /BULK ORDER/.test(newsBar(bot)?.title ?? ''), 5000, 'bulk order in the boss bar').catch(() => null)
        assert(!!bar2b, 'boss bar shows the new bulk order')
        win = await expectWindow(bot, () => bot.chat('/news'))
        const gSlot = findSlot(win, bulk2.item.replace('minecraft:', ''))
        assert(gSlot >= 0 && itemLore(win.slots[gSlot]).some((l) => /Deliver via \/orders/.test(l)), '/news GUI: bulk order with "Deliver via /orders"')
        bot.closeWindow(win)
        await sleep(300)

        const ended = waitForMessage(bot, /NPC Bulk Order over/, 15000)
        const res = await deliverBulk(bot, bulk2)
        assertEq(res.delivered, bulk2.amount, `whole amount delivered through /orders (${res.delivered}/${bulk2.amount})`)
        assert(res.doneAt > 0, 'orders: order completed')
        assert(!(await orderLine(bulk2.order)), 'order completed and removed in orders')
        const t0 = res.doneAt || Date.now()
        const barGone = await waitFor(() => !newsBar(bot) && !newsBar(bot2), Math.max(500, t0 + 2500 - Date.now()), 'boss bar gone after delivery')
          .catch(() => null)
        assert(!!barGone, `boss bar gone after the delivery (${Date.now() - t0} ms)`)
        const st = await rcon('news status')
        assert(!/type=BULK_ORDER/.test(st) && /active=0/.test(st), `status: /news entry gone (${st.split('\n')[0]})`)
        assert(await ended.then(() => true, () => false), 'chat: bulk order ended after the delivery')
        win = await expectWindow(bot, () => bot.chat('/news'))
        assert(findSlot(win, /No news right now/) >= 0, '/news GUI: no message left')
        const h3 = findSlot(win, /History/)
        win = await expectWindow(bot, () => clickSlot(bot, h3))
        const bulkHist = win.slots.find((x) => x && x.name === bulk2.item.replace('minecraft:', '') && itemLore(x).some((l) => /Status/.test(l)))
        const histLore = bulkHist ? itemLore(bulkHist).join(' | ') : ''
        assert(/\bended\b/.test(histLore) && !/cancelled|running/.test(histLore), `history: bulk order "ended" (${histLore.slice(0, 160)})`)
        bot.closeWindow(win)
        await sleep(300)
        await rcon(`clear ${B1}`)

        deposited += await ensureBulkBudget()
        const out3 = await rcon('news action bulkorder 1')
        const bulk3 = bulkStart(out3)
        assert(!!bulk3, `bulk order possible again right after the delivery (${out3.trim()})`)
        if (bulk3) {
          await rcon(`orders admin cancel ${bulk3.order}`)
          assert(await waitNoBulk(2500).then(() => true, () => false), 'cancel: message gone at once')
        }
      }
    }

    if (existsSync(NEWS_CONFIG)) {
      await rcon('news clear')
      await waitNoBulk(30000).catch(() => {})
      newsConfigBefore = readFileSync(NEWS_CONFIG, 'utf8')
      const cfg = JSON.parse(newsConfigBefore)
      Object.assign(cfg, { enabled: true, minIntervalMinutes: 1, maxIntervalMinutes: 1, onlineGraceMinutes: 0, quietFromHour: 0, quietToHour: 0,
        maxActive: 3, titleOnStart: true })
      writeFileSync(NEWS_CONFIG, JSON.stringify(cfg, null, 2))
      const rl = await rcon('smpeco reload')
      assert(!/news.*(invalid|error)/i.test(rl), `reload with test schedule (${rl.trim().split('\n')[0]})`)
      const st1 = await rcon('news status')
      const before = Number(st1.match(/autoStarted=(\d+)/)?.[1] ?? -1)
      const progress = Number(st1.match(/progress=(\d+)s/)?.[1] ?? 0)
      assert(before >= 0, 'status: autoStarted')
      const t3 = Date.now()
      await rcon('news auto on')
      const autoTitle = await waitFor(() => bot.titles.find((t) => t.at >= t3 && t.type === 'title' && /BREAKING NEWS/.test(t.text)), 100000,
        'automatic message').catch(() => null)
      assert(!!autoTitle, `automatic message after about 1 min online time (${autoTitle ? Math.round((autoTitle.at - t3) / 1000) + ' s' : 'none'})`)
      if (autoTitle) {
        const minWait = Math.max(0, 60 - progress - 5) * 1000
        assert(autoTitle.at - t3 >= minWait, `not before the interval ends (${Math.round((autoTitle.at - t3) / 1000)} s, counter before ${progress} s)`)
      }
      const st = await rcon('news status')
      const startedNow = Number(st.match(/autoStarted=(\d+)/)?.[1] ?? -1)
      assert(startedNow === before + 1, `status: autoStarted ${before} → ${startedNow}`)
      assert(/running=[1-3]\/3/.test(st), `status: running (${st.split('\n')[0]})`)
      assert(!!newsBar(bot), 'boss bar shows the automatic message')
      await rcon('news auto off')
      const orig = JSON.parse(newsConfigBefore)
      await restoreNewsConfig()
      const want = `interval=${orig.minIntervalMinutes ?? 15}-${orig.maxIntervalMinutes ?? 25}m`
      assert((await rcon('news status')).includes(want), `original schedule active again (${want})`)
    }
  } finally {
    await restoreNewsConfig().catch((e) => console.log(`[E2E] writing back news.json failed: ${e.message}`))
    await rcon('news clear').catch(() => {})
    if (deposited > 0) {
      try { console.log(`[E2E] treasury back: ${(await rcon(`eco treasury withdraw ${deposited}`)).trim()}`) } catch {}
    }
    await rcon(`deop ${B1}`).catch(() => {})
    // restore the earlier state; a test server should keep auto=off permanently
    if (autoBefore) await rcon(`news auto ${autoBefore}`).catch(() => {})
  }
}, { timeoutMs: 480000 })
