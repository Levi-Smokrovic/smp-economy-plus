// Market: market values (one source), /market GUI and /market value, NPC buyers for player listings (pays price
// minus auction tax from the treasury, counts toward the per-player budget, no enchantment bonuses or fixed
// collector values), NPC traders in the auction house (50-80 listings = two pages, marked, filterable, proceeds
// to the treasury, drift rises) and bargains (below the normal NPC price, never below /sell, flip budget),
// freely resellable. Needs the economy, auction and market modules.
import {
  connectBot, rcon, assert, assertEq, sleep, runE2E, waitForMessage, clickSlot, findSlot, itemLabel,
  itemLore, windowTitle, expectWindow, treasuryFlows, treasuryDelta, topUpTreasury, dollars,
} from './lib.mjs'

const SAME_SLOT_WAIT_MS = 350 // Gui.SAME_SLOT_DEBOUNCE_MS (300) plus margin
const SELLER = 'marketBot1'
const BUYER = 'marketBot2'

const cents = (s) => Math.round(Number(String(s).replace(/[$,]/g, '')) * 100)
async function num(cmd, key) {
  const out = await rcon(cmd)
  const m = out.match(new RegExp(`${key}=(-?[\\d.]+)`))
  if (!m) throw new Error(`${cmd}: ${key} missing in "${out.trim()}"`)
  return Number(m[1])
}

const status = async () => {
  const out = await rcon('market admin status')
  const get = (k) => Number((out.match(new RegExp(`${k}=(-?\\d+)`)) ?? [])[1])
  return { raw: out, bridge: /bridge=true/.test(out), offers: get('offers'), plans: get('plans'), buys: get('buys'), sales: get('sales'), values: get('values') }
}
const value = (item) => num(`market admin value ${item}`, 'value')
const balance = (name) => num(`ah admin info ${name}`, 'balance')

async function waitFor(fn, timeoutMs = 10000, stepMs = 500) {
  const end = Date.now() + timeoutMs
  for (;;) {
    const v = await fn()
    if (v) return v
    if (Date.now() > end) return v
    await sleep(stepMs)
  }
}

async function equip(bot, itemName) {
  for (let i = 0; i < 30 && !bot.inventory.items().some((it) => it.name === itemName); i++) await sleep(100)
  const item = bot.inventory.items().find((it) => it.name === itemName)
  if (!item) throw new Error(`${itemName} not in the inventory of ${bot.username}`)
  await bot.equip(item, 'hand')
  await sleep(300)
}

const count = (bot, name) => bot.inventory.items().filter((it) => it.name === name).reduce((n, it) => n + it.count, 0)

/**
 * /ah sell <total price>: stacks and dump prices show a preview with [Confirm] first and list on repeat, like
 * the chat click. true = listed.
 */
async function ahSell(bot, amount) {
  const first = waitForMessage(bot, /Listed|\[Confirm\]/, 8000).then((m) => m[0], () => null)
  bot.chat(`/ah sell ${amount}`)
  const res = await first
  if (res !== '[Confirm]') return res === 'Listed'
  await sleep(300)
  const listed = waitForMessage(bot, /Listed/, 8000).then(() => true, () => false)
  bot.chat(`/ah sell ${amount}`)
  return listed
}

async function reset(name) {
  try { await rcon(`ah admin purge ${name}`) } catch {}
  await rcon(`clear ${name}`)
  await rcon(`eco set ${name} 0`)
  // rolling NPC budget per player (1 h / 24 h); a second run would otherwise fail on the budget of the first
  try { await rcon(`market admin budget ${name} reset`) } catch {}
}

let deposited = 0 // dollars deposited in the treasury, taken back at the end

runE2E(async () => {
  const seller = await connectBot(SELLER)
  const buyer = await connectBot(BUYER)
  assert(true, 'bots connected')
  await reset(SELLER)
  await reset(BUYER)

  try {
    const st = await rcon('smpeco selftest market')
    assert(/SUMMARY passed=\d+ failed=0/.test(st), `market selftests green (${st.trim().split('\n').pop()})`)
    const s0 = await status()
    assert(s0.bridge, `auction house connected (${s0.raw.trim()})`)
    assert(s0.values > 300, `many market values (${s0.values})`)

    // one source: market value = Services.marketValue (smpeco price), diamond = sell price x 1.6 (x
    // drift/modifier)
    const dia = await value('minecraft:diamond')
    const price = await rcon('smpeco price minecraft:diamond')
    const market = Number((price.match(/market=(\d+)/) ?? [])[1])
    assertEq(market, dia, 'smpeco price uses the market value')
    assert(dia > 0, `diamond has a market value (${dia})`)
    assert((await value('minecraft:command_block')) === 0, 'command block has no market value')
    assert((await value('minecraft:golden_apple')) > 0, 'golden apple estimated by recipe')

    await rcon(`give ${SELLER} minecraft:diamond 6`)
    await equip(seller, 'diamond')
    const valueMsg = waitForMessage(seller, /Market value of .*\$[\d,.]+/, 8000)
    // /ah sell takes the total price of the stack, so the hint names the cap for all 6 diamonds, not per piece
    const cap = waitForMessage(seller, /NPC buyers pay up to \$([\d,.]+) for your stack of 6 \(total price\)/, 8000)
    const sellHint = waitForMessage(seller, /Sell it: \/ah sell (\d+(?:\.\d\d)?) \(price for the whole stack\)/, 8000)
    seller.chat('/market value')
    const wm = await valueMsg.then((m) => m[0], () => null)
    assert(!!wm, `/market value shows the market value (${wm})`)
    const cm = await cap.then((m) => m, () => null)
    assert(!!cm, '/market value shows the NPC maximum for the whole stack')
    const hm = await sellHint.then((m) => m, () => null)
    assert(!!hm && !!cm && cents(hm[1]) === cents(cm[1]), `/ah sell hint = total price (${hm?.[1]} / ${cm?.[1]})`)
    const npcMax = await num(`smpeco price minecraft:diamond ${SELLER}`, 'npcBuyMax')
    const total = cm ? cents(cm[1]) : 0
    // drift moves toward the middle in real time, so allow a few cents; auction fever raises the cap up to x
    // 1.25/1.15
    assert(npcMax > 0 && total >= npcMax * 6 - 18 && total <= Math.ceil(npcMax * 6 * 1.25 / 1.15) + 18,
      `cap applies to 6 pieces (${total} about 6 x ${npcMax})`)
    assert(wm && wm.includes(dollars(dia).replace(/\B(?=(\d{3})+(?!\d))/g, ',')), 'chat value = market value')

    let win = await expectWindow(seller, () => seller.chat('/market'))
    assert(/Market.*Market Values/.test(windowTitle(win)), `GUI opened ("${windowTitle(win)}")`)
    const dslot = findSlot(win, 'diamond', { to: 45 })
    assert(dslot >= 0, 'diamond in the overview')
    const lore = dslot >= 0 ? itemLore(win.slots[dslot]) : []
    assert(lore.some((l) => /Market value: \$/.test(l)), 'lore: market value')
    assert(lore.some((l) => /NPC buyers pay up to/.test(l)), 'lore: NPC buyer cap')
    assert(lore.some((l) => /NPC Traders sell from/.test(l)), 'lore: NPC trader floor')
    assert(lore.some((l) => /stable|demand|oversupply/.test(l)), 'lore: trend')
    // "Bargains (rare) from ..." names the real lowest bargain price, never the plain floor ($0.01 for books
    // and collector items without a /sell price); for diamonds it is above the /sell price ($50)
    const bargainAb = (l) => cents((l.match(/Bargains \(rare\) from \$([\d,.]+)/) ?? [])[1] ?? '-1')
    const diaAb = lore.map(bargainAb).find((v) => v >= 0)
    assert(diaAb === undefined || diaAb > 5000, `diamond: bargains from $${dollars(diaAb ?? 0)} > /sell $50`)
    const pageLores = (w) => w.slots.slice(0, 45).filter((it) => it && !/glass_pane/.test(it.name)).map((it) => [it.name, itemLore(it)])
    const tooCheap = []
    for (const [name, l] of pageLores(win)) if (l.some((x) => bargainAb(x) >= 0 && bargainAb(x) < 10)) tooCheap.push(name)
    {
      // the pool spans several pages and books come later, so page on
      let page = win
      let found = findSlot(page, 'enchanted_book', { to: 45 }) >= 0
      for (let i = 0; i < 4 && !found && page?.slots[50]?.name === 'arrow'; i++) {
        await clickSlot(seller, 50)
        await sleep(400)
        page = seller.currentWindow
        found = findSlot(page, 'enchanted_book', { to: 45 }) >= 0
        if (page) for (const [name, l] of pageLores(page)) if (l.some((x) => bargainAb(x) >= 0 && bargainAb(x) < 10)) tooCheap.push(name)
      }
      assert(found, 'enchanted books in the overview')
      win = seller.currentWindow
      const bookAb = pageLores(win).filter(([name]) => name === 'enchanted_book').flatMap(([, l]) => l.map(bargainAb)).filter((v) => v >= 0)
      assert(bookAb.length > 0 && bookAb.every((v) => v >= 1000), `books: bargains from at least $10 (${bookAb.slice(0, 5).map(dollars).join(', ')})`)
    }
    assertEq(tooCheap, [], 'no goods with "Bargains (rare) from" below $0.10')
    assertEq(win.slots[53]?.name, 'barrier', 'close button')
    const hand = waitForMessage(seller, /Market value of/, 6000).then(() => true, () => false)
    await clickSlot(seller, 46)
    assert(await hand, 'Button "Value My Item"')
    await clickSlot(seller, dslot >= 0 ? dslot : 0, 0, 2) // number key must pull nothing into the inventory
    await clickSlot(seller, 53)
    await sleep(400)
    assert(!seller.currentWindow, 'GUI closed')
    assertEq(count(seller, 'diamond'), 6, 'no GUI items in the inventory, diamonds unchanged')

    await rcon('market admin clear')
    const qty = 2
    await rcon(`clear ${SELLER}`)
    await rcon(`give ${SELLER} minecraft:diamond ${qty}`)
    await equip(seller, 'diamond')
    const unit = await value('minecraft:diamond')
    const cheap = Math.floor(unit * 0.8) * qty
    // the treasury's hourly budget covers the test; NPC purchases above it are not even planned
    deposited += await topUpTreasury(cheap * 2)
    assert(await ahSell(seller, dollars(cheap)), `diamonds listed for $${dollars(cheap)}`)
    let plans = await rcon('market admin plans')
    assert(new RegExp(`${SELLER} ${qty}x minecraft:diamond price=${cheap}`).test(plans), `NPC purchase planned (${plans.split('\n')[1]?.trim()})`)
    const buysBefore = (await status()).buys
    const driftBefore = await num('market admin drift minecraft:diamond', 'factor')
    const sellerBefore = await balance(SELLER)
    const tBefore = await treasuryFlows()
    const sold = waitForMessage(seller, /Sold .*NPC buyer/, 15000).then(() => true, () => false)
    assert(/market due=\d+/.test(await rcon('market admin due')), 'plan due at once')
    const bought = await waitFor(async () => (await status()).buys > buysBefore, 10000)
    assert(bought, 'NPC bought')
    assert(await sold, 'seller notified')
    const tax = Math.floor(cheap * 3 / 100)
    assertEq(await balance(SELLER), sellerBefore + cheap - tax, 'seller + price - 3 % tax')
    {
      // Treasury through its reasons, not the raw balance (NPC orders book in the background). "market admin
      // due" makes all plans due, also for listings by others, whose purchases may only appear on top.
      const tAfter = await treasuryFlows()
      const buy = treasuryDelta(tBefore, tAfter, 'npc_buy')
      const npcBuy = buy.expense - buy.income
      const taxIn = treasuryDelta(tBefore, tAfter, 'ah_tax').income
      if (npcBuy === cheap) {
        assertEq(taxIn, tax, 'treasury - price + tax (npc_buy, ah_tax)')
      } else {
        const other = npcBuy - cheap
        assert(other > 0 && taxIn >= tax && taxIn - tax <= Math.floor(other * 3 / 100),
          `treasury - price + tax (npc_buy ${npcBuy} = ${cheap} + others ${other}, ah_tax ${taxIn} >= ${tax})`)
      }
    }
    assertEq(await num(`ah admin info ${SELLER}`, 'active'), 0, 'offer gone')
    const driftAfter = await num('market admin drift minecraft:diamond', 'factor')
    // drift values have 3 decimals; an NPC purchase of 2 diamonds lowers by about 0.00125 (earlier runs may
    // have raised the drift)
    assert(driftAfter <= driftBefore && (driftAfter < 1 || driftAfter < driftBefore), `sale to NPC lowers the drift (${driftBefore} to ${driftAfter})`)
    const bud = await rcon(`market admin budget ${SELLER}`)
    assert(new RegExp(`hour=${cheap} day=${cheap} `).test(bud), `NPC purchase counts toward the per-player budget (${bud.trim()})`)
    const hourCap = Number((bud.match(/hourCap=(\d+)/) ?? [])[1])
    assert(hourCap > 0 && hourCap < await num('smpeco treasury', 'balance'), `hourly per-player budget set (${hourCap})`)

    await rcon(`give ${SELLER} minecraft:diamond 1`)
    await equip(seller, 'diamond')
    const dear = unit * 3
    assert(await ahSell(seller, dollars(dear)), 'expensive listing placed')
    plans = await rcon('market admin plans')
    assert(!new RegExp(`price=${dear}`).test(plans), 'expensive listing not planned')
    assert(/rejected: (above the PriceGuard cap|too expensive)/.test(plans), 'refusal with reason')

    // NPC buyers do not buy enchanted books and music discs (freely farmable), not even for $1
    await rcon(`clear ${SELLER}`)
    await rcon(`give ${SELLER} minecraft:enchanted_book[stored_enchantments={"minecraft:efficiency":4}] 1`)
    await equip(seller, 'enchanted_book')
    const bookValue = waitForMessage(seller, /Market value of .*\$[\d,.]+/, 8000).then(() => true, () => false)
    const bookNo = waitForMessage(seller, /NPC buyers don't buy this item/, 8000).then(() => true, () => false)
    seller.chat('/market value')
    assert(await bookValue, 'book has a market value (for players)')
    assert(await bookNo, '/market value: NPC buyers do not buy the book')
    // $1 is far below market value, so /ah sell demands a confirmation (ahSell confirms)
    assert(await ahSell(seller, '1'), 'efficiency IV book listed for $1')
    plans = await rcon('market admin plans')
    assert(!/enchanted_book/.test(plans), 'book not planned for NPC purchase')
    assert(/rejected: players only/.test(plans), `refusal "players only" (${plans.split('\n').slice(1, 3).join(' | ').trim()})`)
    await rcon(`give ${SELLER} minecraft:music_disc_cat 1`)
    await equip(seller, 'music_disc_cat')
    assert(await ahSell(seller, '1'), 'music disc listed for $1')
    plans = await rcon('market admin plans')
    assert(!/music_disc/.test(plans), 'music disc not planned for NPC purchase')

    const fill = await rcon('market admin fill')
    const offers = Number((fill.match(/offers=(\d+)/) ?? [])[1])
    assert(offers >= 50 && offers <= 80, `50-80 NPC listings (${fill.trim()})`)
    // NPC traders never sell emeralds (villager currency), not even from an old market.json
    const offerList = await rcon('market admin offers')
    assert(!/\dx minecraft:(deepslate_)?emerald/.test(offerList), 'no emerald listings by NPC traders')
    await rcon(`eco set ${BUYER} 100000`)
    win = await expectWindow(buyer, () => buyer.chat('/ah'))
    assert(/Auction House/.test(windowTitle(win)), 'auction house opened')
    assertEq(win.slots[50]?.name, 'arrow', 'auction house has a second page (next page)')
    const lorePrice = (it) => cents((itemLore(it).join(' ').match(/Price: \$([\d,.]+)/) ?? [])[1] ?? '0')
    // an affordable unchanged NPC listing (buyer has $1,000; NPC buyers do not buy books and collector items)
    const affordable = (it) => itemLore(it).some((l) => /\[Market\]/.test(l)) && lorePrice(it) > 0 && lorePrice(it) <= 90000
      && /^(diamond|iron_|gold_|ender_pearl|blaze_rod|coal_block|copper_block|lapis_block|redstone_block|obsidian|quartz|amethyst_shard|glowstone_dust|slime_ball|gunpowder|ghast_tear|magma_cream|phantom_membrane|shulker_shell|breeze_rod|rabbit_foot|nautilus_shell|turtle_scute|prismarine_shard|leather|string|golden_apple|golden_carrot)/.test(it.name)
    let npcSlot = findSlot(win, affordable, { to: 45 })
    if (npcSlot < 0) npcSlot = findSlot(win, (it) => itemLore(it).some((l) => /\[Market\]/.test(l)), { to: 45 })
    assert(npcSlot >= 0, 'NPC listing marked [Market]')
    const nlore = npcSlot >= 0 ? itemLore(win.slots[npcSlot]) : []
    assert(nlore.some((l) => /NPC listing/.test(l)), 'lore: NPC listing (proceeds to the treasury)')
    const itemName = win.slots[npcSlot]?.name
    const npcPrice = cents((nlore.join(' ').match(/Price: \$([\d,.]+)/) ?? [])[1] ?? '0')
    assert(npcPrice > 0, `price read ($${dollars(npcPrice)}, ${itemName})`)
    assert(nlore.some((l) => /Market value: \$/.test(l)) || !/^(diamond|iron|gold|ender|blaze)/.test(itemName ?? ''), 'lore: market value for comparison')
    await clickSlot(buyer, 46)
    await sleep(400)
    win = buyer.currentWindow
    assert(findSlot(win, (it) => itemLore(it).some((l) => /\[Market\]/.test(l)), { to: 45 }) < 0, 'filter "Players only" hides NPC listings')
    await sleep(350)
    await clickSlot(buyer, 46)
    await sleep(400)
    win = buyer.currentWindow
    assert(/Market only/.test(itemLabel(win.slots[46])), `Filter "Market only" (${itemLabel(win.slots[46])})`)
    {
      const shown = win.slots.slice(0, 45).filter((it) => it && !/glass_pane/.test(it.name))
      assert(shown.length === 45 && shown.every((it) => itemLore(it).some((l) => /\[Market\]/.test(l))), `"Market only": a full page of NPC listings (${shown.length})`)
    }
    await sleep(350)
    await clickSlot(buyer, 46)
    await sleep(400)
    win = buyer.currentWindow
    assert(/All/.test(itemLabel(win.slots[46])), `filter "All" again (${itemLabel(win.slots[46])})`)
    const slot2 = findSlot(win, (it) => it.name === itemName && lorePrice(it) === npcPrice && itemLore(it).some((l) => /\[Market\]/.test(l)), { to: 45 })
    assert(slot2 >= 0, 'filter "All" shows them again')
    const salesBefore = (await status()).sales
    const tBefore2 = await treasuryFlows()
    const before = count(buyer, itemName)
    const dialog = await expectWindow(buyer, () => clickSlot(buyer, slot2))
    assert(/Confirm Purchase/.test(windowTitle(dialog)), 'buy dialog')
    let got = waitForMessage(buyer, /Bought|price has changed/, 8000).then((m) => m[0], () => null)
    await sleep(SAME_SLOT_WAIT_MS) // slot2 can be 9: the same slot right after would be dropped
    await clickSlot(buyer, 9)
    let res = await got
    if (res && /price has changed/.test(res)) {
      // personal sell bonuses of the buyer can raise the floor; the price was raised then, so buy again
      await sleep(500)
      win = buyer.currentWindow ?? await expectWindow(buyer, () => buyer.chat('/ah'))
      const again = findSlot(win, (it) => it.name === itemName && itemLore(it).some((l) => /\[Market\]/.test(l)), { to: 45 })
      await expectWindow(buyer, () => clickSlot(buyer, again))
      got = waitForMessage(buyer, /Bought/, 8000).then((m) => m[0], () => null)
      await sleep(SAME_SLOT_WAIT_MS)
      await clickSlot(buyer, 9)
      res = await got
    }
    assert(!!res && /Bought/.test(res), 'NPC listing bought')
    await sleep(500)
    if (buyer.currentWindow) buyer.closeWindow(buyer.currentWindow)
    assert(count(buyer, itemName) > before, `item received (${itemName})`)
    const sale = treasuryDelta(tBefore2, await treasuryFlows(), 'npc_sale').income
    assert(sale >= npcPrice, `proceeds (at least the listing price) to the treasury (npc_sale +$${dollars(sale)})`)
    assertEq((await status()).sales, salesBefore + 1, 'trader sale counted')
    await equip(buyer, itemName)
    assert(await ahSell(buyer, '0.01'), 'resale listed')
    plans = await rcon('market admin plans')
      await rcon(`ah admin purge ${BUYER}`)

    await rcon('market admin clear')
    await rcon('market admin bargain reset')
    await rcon(`clear ${BUYER}`)
    await rcon(`eco set ${BUYER} 100000`)
    const bg = await rcon('market admin bargain minecraft:diamond 8')
    const bm = bg.match(/id=(\d+) count=8 price=(\d+) floor=(\d+) bargainFloor=(\d+) sell=(\d+) potential=(\d+)/)
    assert(!!bm, `bargain created (${bg.trim()})`)
    const [bPrice, bFloor, bSell, bPot] = bm ? [Number(bm[2]), Number(bm[3]), Number(bm[5]), Number(bm[6])] : [0, 0, 0, 0]
    assert(bPrice < bFloor, `bargain ($${dollars(bPrice)}) below the normal NPC price ($${dollars(bFloor)})`)
    assert(bPrice > 8 * bSell, `but above /sell ($${dollars(8 * bSell)})`)
    // the trader keeps adding listings in the background, so at least this one is booked
    const bget = (raw, k) => Number((raw.match(new RegExp(`${k}=(\\d+)`)) ?? [])[1])
    let bst = await status()
    const soldBefore = bget(bst.raw, 'bargainsSold')
    assert(bget(bst.raw, 'bargains') >= 1 && bget(bst.raw, 'bargainBudget') >= bPot, `flip budget booked (${bst.raw.match(/bargains=.*$/)?.[0]})`)
    win = await expectWindow(buyer, () => buyer.chat('/ah'))
    assert(/Auction House/.test(windowTitle(win)), '/ah opens the auction house')
    const isBargain = (it) => it?.name === 'diamond' && itemLore(it).some((l) => /★ Bargain/.test(l))
    let bslot = findSlot(win, isBargain, { to: 45 })
    assert(bslot >= 0, 'bargain marked ★ in the auction house')
    if (bslot >= 0) {
      const bl = itemLore(win.slots[bslot])
      assert(bl.some((l) => /Market value: \$/.test(l)), 'lore: market value for comparison')
      assertEq(cents((bl.join(' ').match(/Price: \$([\d,.]+)/) ?? [])[1] ?? '0'), bPrice, 'lore price = bargain price')
      const dlg = await expectWindow(buyer, () => clickSlot(buyer, bslot))
      assert(/Confirm Purchase/.test(windowTitle(dlg)), 'buy dialog (bargain)')
      const gotB = waitForMessage(buyer, /Bought|price has changed/, 8000).then((m) => m[0], () => null)
      await clickSlot(buyer, bslot === 9 ? 10 : 9)
      assert(/Bought/.test((await gotB) ?? ''), 'bargain bought')
      await sleep(500)
      if (buyer.currentWindow) buyer.closeWindow(buyer.currentWindow)
    }
    assert(await waitFor(async () => count(buyer, 'diamond') === 8, 5000, 200), '8 diamonds received')
    assertEq(await balance(BUYER), 100000 * 100 - bPrice, 'bargain price paid')
    bst = await status()
    assert(bget(bst.raw, 'bargainsSold') === soldBefore + 1, `booked as sold (${bst.raw.match(/bargains=.*$/)?.[0]})`)
    // /sell takes the diamonds (no marker) but brings less than the bargain cost (keep 4, sell 4)
    await rcon(`clear ${BUYER} minecraft:diamond 4`)
    await sleep(300)
    await equip(buyer, 'diamond')
    const before8 = await balance(BUYER)
    const buyerSell = await num(`smpeco price minecraft:diamond ${BUYER}`, 'sell')
    buyer.chat('/sell hand')
    assert(await waitFor(async () => (await balance(BUYER)) > before8, 8000, 300), '/sell hand takes the bargain diamonds')
    const sellGain = (await balance(BUYER)) - before8
    assertEq(sellGain, 4 * buyerSell, `/sell: 4 diamonds for $${dollars(sellGain)}`)
    assert(2 * sellGain < bPrice, `/sell brings less than the bargain cost ($${dollars(2 * sellGain)} for 8 < $${dollars(bPrice)})`)
    await rcon(`give ${BUYER} minecraft:diamond 4`)
    await equip(buyer, 'diamond')
    const unitD = await value('minecraft:diamond')
    const flip = Math.floor(unitD * 0.8) * 4
    deposited += await topUpTreasury(flip * 2)
    await rcon(`market admin budget ${BUYER} reset`)
    assert(await ahSell(buyer, dollars(flip)), `resale to NPC buyers listed for $${dollars(flip)}`)
    plans = await rcon('market admin plans')
    assert(new RegExp(`${BUYER} 4x minecraft:diamond price=${flip}`).test(plans), `resale planned (${plans.split('\n')[1]?.trim()})`)
    const buys2 = (await status()).buys
    await rcon('market admin due')
    assert(await waitFor(async () => (await status()).buys > buys2, 10000), 'NPC buyer buys the resale')
    assertEq(await num(`ah admin info ${BUYER}`, 'active'), 0, 'resale completed')
  } finally {
    if (seller.currentWindow) seller.closeWindow(seller.currentWindow)
    if (buyer.currentWindow) buyer.closeWindow(buyer.currentWindow)
    await reset(SELLER)
    await reset(BUYER)
    try { await rcon('market admin clear') } catch {}
    try { await rcon('market admin bargain reset') } catch {}
    if (deposited > 0) { try { console.log(`[E2E] treasury back: ${(await rcon(`eco treasury withdraw ${deposited}`)).trim()}`) } catch {} }
  }
}, { timeoutMs: 240000 })
