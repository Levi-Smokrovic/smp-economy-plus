// Buy orders as a real player: creating (command and "New order" GUI), delivering through the GUI (partial,
// capped over-delivery, pristine rule, own order, creative and spectator blocked), collection chest,
// cancelling, expiry with refund, template (mending book: exactly this enchantment), NPC bulk order (items
// vanish, price cap re-checked), NPC order board (players first, about 9-10 pages, filter, anvil search,
// dynamic price, shift click to deliver all), and sorting.
import {
  connectBot, rcon, runE2E, assert, assertEq, sleep, waitForMessage, expectWindow, windowTitle, clickSlot,
  findSlot, itemLabel, itemLore, topUpTreasury, countItem,
} from './lib.mjs'

const BUYER = 'ordersBot1'
const SELLER = 'ordersBot2'
const SORTER = 'ordersBot3' // sorting only: its saved choice never disturbs the other sections
const SORT_SLOT = 53
const SORTS = ['Price per item ↓', 'Price per item ↑', 'Total payout ↓', 'Demand ↓', 'Newest', 'Name A–Z']

async function info(name) {
  const out = await rcon(`orders admin info ${name}`)
  const m = out.match(/orders-info player=\S+ balance=(\d+) escrow=(\d+) orders=(\d+) claim=(\d+) ids=(\S+)/)
  if (!m) throw new Error(`orders admin info ${name}: ${out}`)
  return {
    balance: Number(m[1]), escrow: Number(m[2]), orders: Number(m[3]), claim: Number(m[4]),
    ids: m[5] === '-' ? [] : m[5].split(',').map(Number),
  }
}

/** Unit price of an order for a player: unit = min(price, PriceGuard cap, conversion cap); -1 = none. */
async function quote(id, name) {
  const out = await rcon(`orders admin quote ${id} ${name}`)
  const m = out.match(/orders-quote id=\d+ player=\S+ unit=(\d+) price=(\d+) guard=(-?\d+) conv=(-?\d+)/)
  if (!m) throw new Error(`orders admin quote ${id} ${name}: ${out}`)
  return { unit: Number(m[1]), price: Number(m[2]), guard: Number(m[3]), conv: Number(m[4]) }
}

/** Without /eco (single test) the starting balance stays. */
async function setBalance(name, cents) {
  const out = await rcon(`eco set ${name} ${(cents / 100).toFixed(2)}`)
  if (/Unknown|Incorrect|expected/i.test(out)) {
    console.log(`[E2E] INFO /eco not available (${out.trim().slice(0, 80)}), using the existing balance`)
    return false
  }
  return true
}

function usd(cents) {
  const s = String(cents).padStart(3, '0')
  return '$' + s.slice(0, -2).replace(/\B(?=(\d{3})+(?!\d))/g, ',') + '.' + s.slice(-2)
}

async function waitUntil(cond, timeoutMs = 5000, stepMs = 200) {
  const end = Date.now() + timeoutMs
  let last
  while (Date.now() < end) {
    last = await cond()
    if (last) return last
    await sleep(stepMs)
  }
  return last
}

async function boardInfo() {
  const out = await rcon('orders admin board')
  const m = out.match(/orders-board count=(\d+) items=(\d+) target=(\d+) eligible=(\d+) enabled=(\S+) paidToday=(\d+) bonusToday=(\d+) unitsToday=(\d+)/)
  if (!m) throw new Error(`orders admin board: ${out}`)
  return {
    count: +m[1], items: +m[2], target: +m[3], eligible: +m[4], enabled: m[5] === 'true', paidToday: +m[6], bonusToday: +m[7],
    unitsToday: +m[8],
  }
}

/**
 * Price of a board order for a player: next piece, reference, demand and factor, pressure, proceeds for 64, the
 * player's /sell price (with trader rank and market day, the base of the daily bonus) and the bonus share of
 * the 64.
 */
async function boardQuote(id, name) {
  const out = await rcon(`orders admin quote ${id} ${name}`)
  const m = out.match(/unit=(\d+) price=\d+ guard=(-?\d+) conv=(-?\d+) ref=(\d+) demand=([\d.]+) factor=([\d.]+) pressure=([\d.]+) bonusLeft=(\d+) stack=(\d+)/)
  const s = out.match(/ sell=(\d+) stackBonus=(\d+)/)
  if (!m || !s) throw new Error(`orders admin quote ${id} ${name}: ${out}`)
  const guard = Number(m[2])
  const conv = Number(m[3])
  return {
    unit: +m[1], guard: Math.min(guard < 0 ? Infinity : guard, conv < 0 ? Infinity : conv), ref: +m[4], demand: +m[5], factor: +m[6],
    pressure: +m[7], bonusLeft: +m[8], stack: +m[9], sell: +s[1], stackBonus: +s[2],
  }
}

// for orders not on page 1
async function openDeliverSearch(bot, id, query) {
  await expectWindow(bot, () => bot.chat('/orders'))
  await expectWindow(bot, () => clickSlot(bot, 52))
  bot._client.write('name_item', { name: query })
  await sleep(400)
  const main = await expectWindow(bot, () => clickSlot(bot, 2))
  const slot = slotOfOrder(main, id)
  if (slot < 0) throw new Error(`Order #${id} not in the search "${query}"`)
  const win = await expectWindow(bot, () => clickSlot(bot, slot))
  assert(new RegExp(`Deliver – Order #${id}`).test(windowTitle(win)), `delivery window #${id}`)
  return win
}

function slotOfOrder(win, id) {
  return findSlot(win, (it) => itemLore(it).some((l) => l.includes(`Order #${id}`)))
}

async function closeWin(bot) {
  if (bot.currentWindow) bot.closeWindow(bot.currentWindow)
  await sleep(300)
}

async function createByCommand(bot, item, amount, price) {
  const dialog = await expectWindow(bot, () => bot.chat(`/orders create ${item} ${amount} ${price}`))
  assert(/Create Order/.test(windowTitle(dialog)), `confirmation for ${amount}x ${item} (${windowTitle(dialog)})`)
  const created = waitForMessage(bot, /Order #(\d+) created/, 8000)
  await clickSlot(bot, 9)
  const m = await created
  return Number(m[1])
}

async function openDeliver(bot, id) {
  const main = await expectWindow(bot, () => bot.chat('/orders'))
  assert(/^Orders/.test(windowTitle(main)), `overview open (${windowTitle(main)})`)
  const slot = slotOfOrder(main, id)
  if (slot < 0) throw new Error(`Order #${id} not in the overview`)
  const win = await expectWindow(bot, () => clickSlot(bot, slot))
  assert(new RegExp(`Deliver – Order #${id}`).test(windowTitle(win)), `delivery window #${id}`)
  return win
}

function toCents(text) {
  return Math.round(Number(text.replace(/[$,]/g, '')) * 100)
}

/** As OrderSearch.normalize: lower case, everything but a-z and 0-9 becomes one space. */
function normalizeName(s) {
  return s.toLowerCase()
    .replace(/[^a-z0-9]/g, ' ').trim().replace(/\s+/g, ' ')
}

function sortState(win) {
  const it = win?.slots[SORT_SLOT]
  const label = itemLabel(it)
  const sort = SORTS.findIndex((s) => label === `Sort: ${s}`)
  const lore = itemLore(it)
  const pf = lore.some((l) => /Players first: on/.test(l)) ? true : lore.some((l) => /Players first: off/.test(l)) ? false : null
  return { sort, playersFirst: pf, label }
}

/**
 * Page 1 of the overview: heads and orders with kind (player/bulk/board), number, item, unit price (as in the
 * lore: "For you" on capped bulk orders, 0 on "Not accepting right now") and total payout ("Open: ... ($...)").
 */
function pageRows(win) {
  const rows = []
  for (let i = 0; i < 45 && win; i++) {
    const it = win.slots[i]
    if (!it) continue
    const label = itemLabel(it)
    const lore = itemLore(it)
    const idLine = lore.find((l) => /Order #\d+/.test(l))
    if (!idLine) {
      if (/Player Orders|NPC Orders/.test(label)) rows.push({ slot: i, header: /Player Orders/.test(label) ? 'players' : 'npc', lore })
      continue
    }
    const money = (re) => { const m = lore.map((l) => l.match(re)).find(Boolean); return m ? toCents(m[1]) : null }
    const kind = lore.some((l) => /Buyer: .*\(NPC\)/.test(l)) ? 'board' : lore.some((l) => /Buyer: Treasury/.test(l)) ? 'bulk' : 'player'
    const dead = lore.some((l) => /Not accepting anything right now/.test(l))
    const unit = dead ? 0 : money(/For you: (\$[\d,]+\.\d\d)/) ?? money(/Price per item: (\$[\d,]+\.\d\d)/) ?? 0
    const total = money(/Remaining: [\d,]+ \((?:≈ )?(\$[\d,]+\.\d\d)\)/) ?? 0
    rows.push({ slot: i, id: Number(idLine.match(/Order #(\d+)/)[1]), kind, item: it.name, unit, total })
  }
  return rows
}

/** Group as in OrderSort.group: with "players first" players, then bulk orders, then board; otherwise one list. */
function groupOf(row, playersFirst) {
  return !playersFirst ? 0 : row.kind === 'player' ? 0 : row.kind === 'bulk' ? 1 : 2
}

/**
 * Checks page 1 pairwise within its group against {@code key} (negative = a before b). Returns the number of
 * checked pairs (0 = nothing to check) and the first violation.
 */
function checkOrder(rows, playersFirst, cmp) {
  const orders = rows.filter((r) => !r.header)
  let pairs = 0
  for (let i = 1; i < orders.length; i++) {
    const a = orders[i - 1]
    const b = orders[i]
    const ga = groupOf(a, playersFirst)
    const gb = groupOf(b, playersFirst)
    if (ga !== gb) {
      if (ga > gb) return { pairs, bad: `group: #${a.id} (${a.kind}) before #${b.id} (${b.kind})` }
      continue
    }
    if (cmp(a, b) > 0) return { pairs, bad: `#${a.id} ${a.item} (${a.unit}/${a.total}) before #${b.id} ${b.item} (${b.unit}/${b.total})` }
    pairs++
  }
  return { pairs, bad: null }
}

runE2E(async () => {
  const buyer = await connectBot(BUYER)
  const seller = await connectBot(SELLER)
  assert(true, 'bots connected')

  for (const name of [BUYER, SELLER]) {
    for (const id of (await info(name)).ids) await rcon(`orders admin cancel ${id}`)
    await rcon(`clear ${name}`)
    await rcon(`gamemode survival ${name}`)
  }
  await rcon('smpeco pricemod clear')
  const ecoOk = (await setBalance(BUYER, 1_000_00)) && (await setBalance(SELLER, 0))
  if (!ecoOk) assert((await info(BUYER)).balance >= 200_00, 'buyer has enough starting balance (without /eco)')
  if ((await info(BUYER)).claim > 0) { buyer.chat('/orders claim'); await sleep(1000); await rcon(`clear ${BUYER}`) }

  let b0 = await info(BUYER)
  const id1 = await createByCommand(buyer, 'minecraft:diamond', 10, '5')
  let b1 = await info(BUYER)
  assertEq(b0.balance - b1.balance, 50_00, 'escrow: 10 × $5 charged')
  assertEq(b1.escrow - b0.escrow, 50_00, 'escrow in the order')
  assert(b1.ids.includes(id1), `order #${id1} active`)

  await rcon(`give ${SELLER} minecraft:diamond 4`)
  let s0 = await info(SELLER)
  let win = await openDeliver(seller, id1)
  assert(/Deliver all/.test(itemLabel(win.slots[14])), `Button "Deliver all" (${itemLabel(win.slots[14])})`)
  assert(/\(4\)/.test(itemLabel(win.slots[14])), 'All = 4 matching')
  let msg = waitForMessage(seller, /Delivered: (\d+)× .* for \$(\d[\d,]*\.\d{2})(?: \(fee \$(\d[\d,]*\.\d{2})\))?/, 8000)
  await clickSlot(seller, 14)
  let m = await msg
  assertEq(Number(m[1]), 4, '4 delivered')
  const net = Math.round(Number(m[2].replace(/,/g, '')) * 100)
  const fee = m[3] ? Math.round(Number(m[3].replace(/,/g, '')) * 100) : 0
  assertEq(net + fee, 4 * 5_00, 'net + fee = 4 x $5')
  let s1 = await info(SELLER)
  assertEq(s1.balance - s0.balance, net, 'supplier paid at once')
  assertEq(await countItem(SELLER, 'minecraft:diamond'), 0, 'diamonds taken from the inventory')
  b1 = await info(BUYER)
  assertEq(b1.claim - b0.claim, 4, '4 diamonds in the collection chest')
  assertEq(b1.escrow - b0.escrow, 30_00, 'Rest-Escrow 6 × $5')
  await sleep(300)
  assert(/No matching/.test(itemLabel(seller.currentWindow?.slots[13])), 'afterwards: no matching items left')
  await closeWin(seller)

  // creative and spectator do not deliver: no money for conjured items
  await rcon(`give ${SELLER} minecraft:diamond 2`)
  const sC = await info(SELLER)
  const bC = await info(BUYER)
  for (const mode of ['creative', 'spectator']) {
    await rcon(`gamemode ${mode} ${SELLER}`)
    try {
      await sleep(300)
      win = await openDeliver(seller, id1)
      assert(/Creative or Spectator mode/.test(itemLabel(win.slots[13])), `${mode}: delivery blocked (${itemLabel(win.slots[13])})`)
      assert(!/deliver/i.test(itemLabel(win.slots[14])), `${mode}: no "Deliver all" (${itemLabel(win.slots[14])})`)
      await closeWin(seller)
    } finally {
      await rcon(`gamemode survival ${SELLER}`)
    }
  }
  assertEq(await countItem(SELLER, 'minecraft:diamond'), 2, 'creative/spectator: diamonds stay')
  assertEq((await info(SELLER)).balance, sC.balance, 'creative/spectator: no money')
  const bC2 = await info(BUYER)
  assertEq(bC2.escrow, bC.escrow, 'creative/spectator: escrow untouched')
  assertEq(bC2.claim, bC.claim, 'creative/spectator: nothing in the collection chest')
  await rcon(`clear ${SELLER} minecraft:diamond`)

  await rcon(`give ${SELLER} minecraft:diamond 20`)
  const named = await rcon(`give ${SELLER} minecraft:diamond[custom_name="Fake"] 1`)
  assert(/Gave/.test(named), `renamed diamond given (${named.trim().slice(0, 60)})`)
  win = await openDeliver(seller, id1)
  assert(/\(6\)/.test(itemLabel(win.slots[14])), `all = 6 open (${itemLabel(win.slots[14])})`)
  msg = waitForMessage(seller, /Delivered: (\d+)×.*Order complete/, 8000)
  const backToMain = expectWindow(seller, () => clickSlot(seller, 14))
  m = await msg
  assertEq(Number(m[1]), 6, 'only the open 6 taken')
  const mainAgain = await backToMain
  assert(/^Orders/.test(windowTitle(mainAgain)), 'finished order returns to the overview')
  assertEq(slotOfOrder(mainAgain, id1), -1, 'finished order no longer listed')
  await closeWin(seller)
  assertEq(await countItem(SELLER, 'minecraft:diamond'), 15, '14 + renamed diamond stay')
  b1 = await info(BUYER)
  assert(!b1.ids.includes(id1), 'order finished')
  assertEq(b1.claim - b0.claim, 10, '10 diamonds in the collection chest')
  assertEq(b1.escrow, b0.escrow, 'escrow used up')

  win = await expectWindow(buyer, () => buyer.chat('/orders'))
  const claimWin = await expectWindow(buyer, () => clickSlot(buyer, 46))
  assert(/Claim Chest/.test(windowTitle(claimWin)), 'collection chest open')
  assert(findSlot(claimWin, 'diamond', { to: 45 }) >= 0, 'diamonds visible in the chest')
  msg = waitForMessage(buyer, /Claimed: (\d+) items/, 8000)
  await clickSlot(buyer, 47)
  m = await msg
  assert(Number(m[1]) >= 10, `claimed (${m[1]})`)
  await closeWin(buyer)
  assertEq(await countItem(BUYER, 'minecraft:diamond'), 10 + Math.max(0, b0.claim), 'diamonds in the buyer inventory')
  assertEq((await info(BUYER)).claim, 0, 'chest empty')
  await rcon(`clear ${BUYER}`)

  b0 = await info(BUYER)
  const id2 = await createByCommand(buyer, 'minecraft:emerald', 2, '1')
  win = await openDeliver(buyer, id2)
  assert(/own order/.test(itemLabel(win.slots[13])), 'own order cannot be supplied')
  await closeWin(buyer)
  await rcon(`give ${SELLER} minecraft:emerald[custom_name="Fake"] 3`)
  win = await openDeliver(seller, id2)
  assert(/No matching/.test(itemLabel(win.slots[13])), 'renamed emeralds do not count (pristine rule)')
  await closeWin(seller)
  assertEq(await countItem(SELLER, 'minecraft:emerald'), 3, 'renamed emeralds stay')
  const mine = await expectWindow(buyer, () => buyer.chat('/orders my'))
  assert(/My Orders/.test(windowTitle(mine)), 'my orders open')
  const mySlot = slotOfOrder(mine, id2)
  assert(mySlot >= 0, `#${id2} in my orders`)
  const cancelDialog = await expectWindow(buyer, () => clickSlot(buyer, mySlot))
  assert(/Cancel Order/.test(windowTitle(cancelDialog)), 'cancel confirmation')
  msg = waitForMessage(buyer, /cancelled\. Refunded: \$(\d[\d,]*\.\d{2})/, 8000)
  const mineAgain = expectWindow(buyer, () => clickSlot(buyer, 9))
  m = await msg
  assertEq(m[1], '2.00', 'cancel refunds $2.00')
  await mineAgain
  await closeWin(buyer)
  let bNow = await info(BUYER)
  assertEq(bNow.balance, b0.balance, 'balance after cancel as before')
  assert(!bNow.ids.includes(id2), 'cancelled order gone')

  b0 = await info(BUYER)
  const id3 = await createByCommand(buyer, 'minecraft:wheat', 5, '0.50')
  assertEq(b0.balance - (await info(BUYER)).balance, 2_50, 'Escrow 5 × $0.50')
  msg = waitForMessage(buyer, new RegExp(`order #${id3} has expired`), 8000)
  const exp = await rcon(`orders admin expire ${id3}`)
  assert(/expired/.test(exp), `admin expiry (${exp.trim()})`)
  await msg.then(() => assert(true, 'expiry message received'), () => assert(false, 'expiry message received'))
  bNow = await info(BUYER)
  assertEq(bNow.balance, b0.balance, 'rest refunded after expiry')
  assert(!bNow.ids.includes(id3), 'expired order gone')

  await rcon(`give ${BUYER} minecraft:cobblestone 1`)
  await waitUntil(() => buyer.inventory.items().some((it) => it.name === 'cobblestone'))
  await buyer.equip(buyer.inventory.items().find((it) => it.name === 'cobblestone'), 'hand')
  await sleep(300)
  b0 = await info(BUYER)
  win = await expectWindow(buyer, () => buyer.chat('/orders'))
  const draft = await expectWindow(buyer, () => clickSlot(buyer, 47))
  assert(/New Order/.test(windowTitle(draft)), 'new order menu open')
  assert(/Amount: 64/.test(itemLabel(draft.slots[13])), `start amount 64 (${itemLabel(draft.slots[13])})`)
  await clickSlot(buyer, 16)
  await sleep(300)
  assert(/Amount: 128/.test(itemLabel(buyer.currentWindow?.slots[13])), 'amount +64 = 128')
  const priceLabel = itemLabel(buyer.currentWindow?.slots[21])
  const pm = priceLabel.match(/\$(\d[\d,]*\.\d{2})/)
  const unit = pm ? Math.round(Number(pm[1].replace(/,/g, '')) * 100) : -1
  assert(unit > 0, `starting price set (${priceLabel})`)
  const confirm = await expectWindow(buyer, () => clickSlot(buyer, 31))
  assert(/Create Order/.test(windowTitle(confirm)), 'confirmation from the menu')
  msg = waitForMessage(buyer, /Order #(\d+) created: 128×/, 8000)
  await clickSlot(buyer, 9)
  m = await msg
  const id4 = Number(m[1])
  assertEq(b0.balance - (await info(BUYER)).balance, 128 * unit, 'GUI order reserves 128 x price')
  msg = waitForMessage(buyer, new RegExp(`Order #${id4} cancelled`), 8000)
  buyer.chat(`/orders cancel ${id4}`)
  await msg
  assertEq((await info(BUYER)).balance, b0.balance, 'cancel by command refunds')
  msg = waitForMessage(seller, /not your order|doesn't exist anymore/, 8000)
  seller.chat(`/orders cancel ${id4}`)
  assert(await msg.then(() => true, () => false), 'foreign or old order cannot be cancelled')

  const MENDING = 'minecraft:enchanted_book[stored_enchantments={"minecraft:mending":1}]'
  const BANE = 'minecraft:enchanted_book[stored_enchantments={"minecraft:bane_of_arthropods":1}]'
  msg = waitForMessage(buyer, /only exists with properties/, 8000)
  buyer.chat('/orders create minecraft:enchanted_book 1 30')
  assert(await msg.then(() => true, () => false), 'enchanted book without template refused')
  await sleep(500)
  assert(!buyer.currentWindow || !/Create Order/.test(windowTitle(buyer.currentWindow)), 'no confirm dialog without template')
  await rcon(`give ${BUYER} ${MENDING} 1`)
  await waitUntil(() => buyer.inventory.items().some((it) => it.name === 'enchanted_book'))
  await buyer.equip(buyer.inventory.items().find((it) => it.name === 'enchanted_book'), 'hand')
  await sleep(300)
  await expectWindow(buyer, () => buyer.chat('/orders'))
  const bookDraft = await expectWindow(buyer, () => clickSlot(buyer, 47))
  assert(/New Order/.test(windowTitle(bookDraft)), 'new order with a book in hand')
  assert(/Exactly like template/.test(itemLabel(bookDraft.slots[25])), `acceptance: exactly as the template (${itemLabel(bookDraft.slots[25])})`)
  const bookConfirm = await expectWindow(buyer, () => clickSlot(buyer, 31))
  assert(/Create Order/.test(windowTitle(bookConfirm)), 'confirmation of the book order')
  msg = waitForMessage(buyer, /Order #(\d+) created: 1×.*exactly like template/, 8000)
  await clickSlot(buyer, 9)
  m = await msg
  const idBook = Number(m[1])
  assert(/template=true/.test(await rcon('orders admin list')), 'order with template saved')
  await rcon(`clear ${SELLER}`)
  await rcon(`give ${SELLER} ${BANE} 1`)
  await sleep(300)
  win = await openDeliver(seller, idBook)
  assert(/No matching items/.test(itemLabel(win.slots[13])), `smite book does not fit (${itemLabel(win.slots[13])})`)
  await closeWin(seller)
  await rcon(`give ${SELLER} ${MENDING} 1`)
  await sleep(300)
  win = await openDeliver(seller, idBook)
  msg = waitForMessage(seller, /Delivered: (\d+)×/, 8000)
  const afterBook = expectWindow(seller, () => clickSlot(seller, 14))
  m = await msg
  assertEq(Number(m[1]), 1, 'mending book delivered')
  await afterBook.catch(() => {})
  assertEq(await countItem(SELLER, MENDING), 0, 'mending book gone')
  assertEq(await countItem(SELLER, BANE), 1, 'smite book stays')
  await closeWin(seller)
  assertEq((await info(BUYER)).claim, 1, 'mending book in the collection chest')
  buyer.chat('/orders claim')
  assertEq(await waitUntil(async () => (await countItem(BUYER, MENDING)) === 2 ? 2 : 0), 2, 'mending book collected (exactly this book)')
  await rcon(`clear ${BUYER} minecraft:enchanted_book`)

  // Other scripts may have used up the treasury's hourly NPC budget: top up as needed and take it back at the
  // end.
  const deposited = await topUpTreasury(20_000)
  const bulk = await rcon('orders admin bulk minecraft:wheat 64 30')
  const bm = bulk.match(/#(\d+) npc=true bulk=true item=minecraft:wheat delivered=0 amount=(\d+) price=(\d+)/)
  assert(!!bm, `bulk order created (${bulk.trim().slice(0, 160)})`)
  if (bm) {
    const npcId = Number(bm[1])
    const amount = Number(bm[2])
    const half = Math.floor(amount / 2)
    const capOf = async () => Number((await rcon(`smpeco price minecraft:wheat ${SELLER}`)).match(/npcOrderMax=(\d+)/)?.[1] ?? -1)
    const cap1 = await capOf()
    assert(Number(bm[3]) > 0 && cap1 > 0, `price ${bm[3]} / cap ${cap1}`)
    const q1 = await quote(npcId, SELLER)
    assert(q1.unit > 0 && q1.unit <= Math.min(Number(bm[3]), cap1), `unit price ${q1.unit} <= min(price ${bm[3]}, cap ${cap1})`)
    assertEq(q1.unit, Math.min(q1.price, q1.guard, q1.conv < 0 ? Infinity : q1.conv), 'unit price = min(price, cap, conversion)')
    await rcon(`clear ${SELLER}`)
    await rcon(`give ${SELLER} minecraft:wheat ${half}`)
    s0 = await info(SELLER)
    win = await openDeliver(seller, npcId)
    msg = waitForMessage(seller, /Delivered: (\d+)×/, 8000)
    await clickSlot(seller, 14)
    m = await msg
    assertEq(Number(m[1]), half, `${half} wheat delivered to the NPC`)
    s1 = await info(SELLER)
    assertEq(s1.balance - s0.balance, half * q1.unit, 'NPC pays the current unit price, no fee')
    assertEq(await countItem(SELLER, 'minecraft:wheat'), 0, 'wheat gone')
    assertEq((await info(SELLER)).claim, 0, 'no collection chest for NPCs (items vanish)')
    await closeWin(seller)

    // Crash while the delivery window is open: the click at the shown old price delivers nothing and the window
    // then shows the crash price; only the next click delivers, at the new capped price.
    const rest = amount - half
    await rcon(`give ${SELLER} minecraft:wheat ${rest + 5}`)
    await sleep(300)
    win = await openDeliver(seller, npcId)
    const lore1 = itemLore(win.slots[14])
    assert(lore1.some((l) => l.includes(`You get: ${usd(rest * q1.unit)}`)), `button shows ${usd(rest * q1.unit)} (${lore1.join(' | ')})`)
    await rcon('smpeco pricemod minecraft:wheat 0.5 5')
    const cap2 = await capOf()
    assert(cap2 > 0 && cap2 < cap1, `cap after crash ${cap1} to ${cap2}`)
    const q2 = await quote(npcId, SELLER)
    assert(q2.unit > 0 && q2.unit <= cap2 && q2.unit <= q1.unit, `unit price after crash ${q1.unit} to ${q2.unit} (cap ${cap2})`)
    s0 = await info(SELLER)
    if (q2.unit < q1.unit) {
      msg = waitForMessage(seller, /price has changed/, 8000)
      await clickSlot(seller, 14)
      assert(await msg.then(() => true, () => false), 'click at the old price: "price has changed"')
      assertEq((await info(SELLER)).balance, s0.balance, 'no money paid at the old price')
      assertEq(await countItem(SELLER, 'minecraft:wheat'), rest + 5, 'wheat stays on price change')
      const updated = await waitUntil(() => itemLore(seller.currentWindow?.slots[14]).some((l) => l.includes(`You get: ${usd(rest * q2.unit)}`)))
      assert(!!updated, `window then shows ${usd(rest * q2.unit)} (${itemLore(seller.currentWindow?.slots[14]).join(' | ')})`)
      await sleep(300)
    } else {
      console.log(`[E2E] INFO unit price unchanged after crash (${q1.unit}, conversion cap), price change click skipped`)
    }
    msg = waitForMessage(seller, /Delivered: (\d+)×/, 8000)
    const back = expectWindow(seller, () => clickSlot(seller, 14))
    m = await msg
    assertEq(Number(m[1]), amount - half, 'rest delivered, surplus stays')
    await back.catch(() => {})
    s1 = await info(SELLER)
    assertEq(s1.balance - s0.balance, (amount - half) * q2.unit, 'capped again on delivery (crash price)')
    assertEq(await countItem(SELLER, 'minecraft:wheat'), 5, '5 wheat left')
    await rcon('smpeco pricemod clear')
    const list = await rcon('orders admin list')
    assert(!new RegExp(`#${npcId} `).test(list), 'bulk order done and removed')
    await closeWin(seller)
  }

  if (deposited > 0) { try { console.log(`[E2E] treasury back: ${(await rcon(`eco treasury withdraw ${deposited}`)).trim()}`) } catch {} }

  let bi = await boardInfo()
  if (bi.count < 300) { await rcon('orders admin board refill'); bi = await boardInfo() }
  assert(bi.enabled, 'order board active')
  assert(bi.count >= 360, `board full: ${bi.count} NPC orders (${bi.items} items, target ${bi.target}, tradable ${bi.eligible})`)
  await rcon(`clear ${SELLER}`)
  await rcon(`clear ${BUYER}`)
  await rcon('orders admin board reset minecraft:coal')
  await rcon(`orders admin bonus ${SELLER} reset`)
  await rcon('smpeco pricemod clear')
  if (ecoOk) await setBalance(BUYER, 1_000_00)
  const idP = await createByCommand(buyer, 'minecraft:coal', 64, '0.01')
  win = await expectWindow(seller, () => seller.chat('/orders'))
  assert(/Player Orders/.test(itemLabel(win.slots[0])), `slot 0: head "Player Orders" (${itemLabel(win.slots[0])})`)
  const npcHead = findSlot(win, (it) => /NPC Orders/.test(itemLabel(it)), { to: 45 })
  assert(npcHead > 0, 'head "NPC orders" on page 1')
  const pSlot = slotOfOrder(win, idP)
  assert(pSlot > 0 && pSlot < npcHead, `player order (slot ${pSlot}) before the NPC orders (slot ${npcHead}), even at a tiny price`)
  const pageText = [itemLabel(win.slots[49]), ...itemLore(win.slots[49])].join(' ')
  const pages = Number(pageText.match(/Page 1\/(\d+)/)?.[1] ?? 0)
  assert(pages >= 9, `at least 9 pages (${pageText})`)
  const firstBoard = findSlot(win, (it) => itemLore(it).some((l) => /Demand:/.test(l)), { from: npcHead + 1, to: 45 })
  assert(firstBoard > npcHead, 'NPC order shows demand')
  assert(itemLore(win.slots[firstBoard]).some((l) => /Price per item: .*[▲▼■]/.test(l)), 'NPC order shows the trend arrow')
  await clickSlot(seller, 50)
  assert(/Page 2\//.test([itemLabel(seller.currentWindow.slots[49]), ...itemLore(seller.currentWindow.slots[49])].join(' ')), 'paging: page 2')
  assert(itemLore(seller.currentWindow.slots[0]).some((l) => /Order #/.test(l)), 'page 2 full of orders')
  await clickSlot(seller, 51)
  assert(/Filter: Players/.test(itemLabel(seller.currentWindow.slots[51])), `filter players (${itemLabel(seller.currentWindow.slots[51])})`)
  assert(slotOfOrder(seller.currentWindow, idP) >= 0, 'filter players: player order visible')
  assert(findSlot(seller.currentWindow, (it) => /NPC Orders/.test(itemLabel(it)), { to: 45 }) < 0, 'filter players: no NPC orders')
  await sleep(150) // same slot: at least 300 ms apart (debounce)
  await clickSlot(seller, 51)
  assert(/Filter: NPC/.test(itemLabel(seller.currentWindow.slots[51])), 'Filter NPC')
  assert(/NPC Orders/.test(itemLabel(seller.currentWindow.slots[0])), 'filter NPC: starts with the NPC head')
  assert(slotOfOrder(seller.currentWindow, idP) < 0, 'filter NPC: no player order')
  await sleep(150)
  await clickSlot(seller, 51, 1)
  assert(/Filter: Players/.test(itemLabel(seller.currentWindow.slots[51])), 'right click: filter back')
  await sleep(150)
  await clickSlot(seller, 51, 1)
  assert(/Filter: All/.test(itemLabel(seller.currentWindow.slots[51])), 'filter all')
  const sp = await rcon('orders admin board spawn minecraft:coal')
  const idC = Number(sp.match(/spawned #(\d+) /)?.[1] ?? -1)
  assert(idC > 0, `board order coal (${sp.trim().slice(0, 120)})`)
  const q0 = await boardQuote(idC, SELLER)
  assert(q0.unit > 0 && q0.ref > 0, `coal: unit price ${q0.unit}, /sell reference ${q0.ref}, demand ${q0.demand}`)
  assertEq(q0.unit, Math.min(Math.floor(q0.ref * q0.factor + 1e-9), q0.guard), 'unit price = min(reference x demand, price cap)')
  await rcon('smpeco pricemod minecraft:coal 1.5 5')
  const q1 = await boardQuote(idC, SELLER)
  assertEq(q1.ref, Math.floor(q0.ref * 1.5), 'news coal +50 %: reference +50 %')
  assertEq(q1.unit, Math.min(Math.floor(q1.ref * q1.factor + 1e-9), q1.guard), 'news: order follows')
  assert(q1.unit > q0.unit, `coal order rises with the news (${q0.unit} to ${q1.unit})`)
  await rcon(`give ${SELLER} minecraft:coal 64`)
  await waitUntil(async () => (await countItem(SELLER, 'minecraft:coal')) === 64)
  const anvil = await expectWindow(seller, () => clickSlot(seller, 52))
  assert(/Search/.test(windowTitle(anvil)), `search: anvil (${windowTitle(anvil)})`)
  seller._client.write('name_item', { name: 'coal' })
  await sleep(400)
  win = await expectWindow(seller, () => clickSlot(seller, 2))
  assert(/Search: coal/.test(itemLabel(win.slots[52])), `search active (${itemLabel(win.slots[52])})`)
  const entries = win.slots.slice(0, 45).filter((it) => it && itemLore(it).some((l) => /Order #/.test(l)))
  assert(entries.length >= 2 && entries.every((it) => /coal/.test(it.name)), `search: only coal items (${entries.map((it) => it.name).join(',')})`)
  assertEq(slotOfOrder(win, idP), 1, 'search: player order directly below the player head')
  const cSlot = slotOfOrder(win, idC)
  assert(cSlot > 0, `board order #${idC} in the search`)
  const quickLine = itemLore(win.slots[cSlot]).find((l) => /Shift-click: Deliver all/.test(l)) ?? ''
  const quickM = quickLine.match(/\((\d+)\) → \$(\d[\d,]*\.\d{2})/)
  assert(!!quickM && Number(quickM[1]) === 64, `shift-click offer: 64 coal (${quickLine})`)
  const quickShown = quickM ? Math.round(Number(quickM[2].replace(/,/g, '')) * 100) : -1
  const q2 = await boardQuote(idC, SELLER)
  assertEq(quickShown, q2.stack, 'shift click shows the proceeds for 64 (price falls piece by piece)')
  assert(itemLore(win.slots[cSlot]).some((l) => l.includes(`vs. your /sell price ${usd(q2.sell)}`)),
    `lore compares with own /sell price ${q2.sell} (${itemLore(win.slots[cSlot]).join(' | ')})`)
  s0 = await info(SELLER)
  msg = waitForMessage(seller, /Delivered: 64× .* for \$(\d[\d,]*\.\d{2})/, 8000)
  await clickSlot(seller, cSlot, 0, 1)
  m = await msg
  const got = Math.round(Number(m[1].replace(/,/g, '')) * 100)
  assertEq(got, q2.stack, 'shift click: everything delivered at the shown proceeds')
  s1 = await info(SELLER)
  assertEq(s1.balance - s0.balance, got, 'paid at once (new money, like /sell)')
  assertEq(await countItem(SELLER, 'minecraft:coal'), 0, 'coal gone')
  assert(got > 64 * q1.ref, `above the /sell value (${got} > 64 x ${q1.ref})`)
  const q3 = await boardQuote(idC, SELLER)
  assert(q3.unit < q2.unit && q3.pressure > 0, `supply: price lower afterwards (${q2.unit} to ${q3.unit}, pressure ${q3.pressure})`)
  const bonus = (await rcon(`orders admin bonus ${SELLER}`)).match(/used=(\d+) left=(\d+)/)
  assert(!!bonus && Number(bonus[1]) === q2.stackBonus, `daily bonus = share above own /sell price (${bonus?.[0]}, expected ${q2.stackBonus})`)
  assert(got - q2.stackBonus <= 64 * q2.sell, `proceeds <= 64 x /sell + bonus (${got} - ${q2.stackBonus} <= 64 x ${q2.sell})`)
  if (q2.sell === q1.ref) assertEq(q2.stackBonus, got - 64 * q1.ref, 'without personal boosts: bonus = proceeds - 64 x /sell value')
  await sleep(300)
  const refreshed = seller.currentWindow
  assert(!!refreshed && /^Orders/.test(windowTitle(refreshed)), 'overview stays open')
  const lore2 = itemLore(refreshed.slots[slotOfOrder(refreshed, idC)])
  assert(lore2.some((l) => /Supply: .*-\d+(\.\d)?%/.test(l)), `lore shows the supply (${lore2.join(' | ')})`)
  await rcon(`give ${SELLER} minecraft:coal[custom_name="Fake"] 5`)
  await sleep(300)
  win = await expectWindow(seller, () => seller.chat('/orders'))
  await closeWin(seller)
  win = await openDeliverSearch(seller, idC, 'coal')
  assert(/No matching/.test(itemLabel(win.slots[13])), `renamed coal does not count (${itemLabel(win.slots[13])})`)
  await closeWin(seller)
  assertEq(await countItem(SELLER, 'minecraft:coal'), 5, 'renamed coal stays')
  await rcon('smpeco pricemod clear')
  await rcon(`orders admin cancel ${idP}`)
  await rcon('orders admin board reset minecraft:coal')
  await rcon(`orders admin bonus ${SELLER} reset`)
  const after = await boardInfo()
  assert(after.paidToday > 0 && after.unitsToday >= 64, `board statistics (${after.paidToday} cents, ${after.unitsToday} pieces)`)

  let sorter = await connectBot(SORTER)
  await rcon(`clear ${SORTER}`)
  await rcon(`gamemode survival ${SORTER}`)
  // name A-Z sorts by the English item name (server language)
  const nameKey = (row) => normalizeName(sorter.registry.itemsByName[row.item]?.displayName ?? row.item)
  const openMain = async () => {
    await closeWin(sorter)
    const w = await expectWindow(sorter, () => sorter.chat('/orders'))
    assert(/Orders/.test(windowTitle(w)), 'overview open')
    await waitUntil(() => sortState(sorter.currentWindow).sort >= 0 && pageRows(sorter.currentWindow).length > 30, 5000, 100)
    return sorter.currentWindow
  }
  /**
   * Clicks the sort button (0 left, 1 right, 'shift') and waits until the server switched. The next click must
   * wait for that: under load the server handles both in one tick and the debounce drops the second. Returns
   * the updated window.
   */
  const sortClick = async (how) => {
    const before = sortState(sorter.currentWindow)
    const want = how === 'shift' ? { sort: before.sort, playersFirst: !before.playersFirst }
      : { sort: (before.sort + (how === 1 ? SORTS.length - 1 : 1)) % SORTS.length, playersFirst: before.playersFirst }
    if (how === 'shift') await clickSlot(sorter, SORT_SLOT, 0, 1)
    else await clickSlot(sorter, SORT_SLOT, how)
    await waitUntil(() => { const s = sortState(sorter.currentWindow); return s.sort === want.sort && s.playersFirst === want.playersFirst }, 8000, 100)
    await sleep(400) // page comes in the same tick as the button; wait for the rest of the packets (same-slot debounce: at least 300 ms)
    return sorter.currentWindow
  }
  // set the initial state in case an earlier run aborted midway: price down, players first on
  win = await openMain()
  for (let i = 0; i < SORTS.length && sortState(win).sort > 0; i++) win = await sortClick(0)
  if (sortState(win).playersFirst === false) win = await sortClick('shift')
  let st = sortState(win)
  assert(st.sort === 0 && st.playersFirst === true, `sort button in slot ${SORT_SLOT}: "${st.label}", players first on`)
  // three player orders that each sort orders differently: coal 64 x $0.02 ($1.28), cobblestone 10 x $0.05
  // ($0.50), sand 200 x $0.01 ($2.00), created in this order
  if (ecoOk) await setBalance(BUYER, 1_000_00)
  const pCoal = await createByCommand(buyer, 'minecraft:coal', 64, '0.02')
  const pCobble = await createByCommand(buyer, 'minecraft:cobblestone', 10, '0.05')
  const pSand = await createByCommand(buyer, 'minecraft:sand', 200, '0.01')
  // demand of player orders = price / /sell reference (min(base x news factor, pivot))
  const refOf = async (item) => {
    const out = await rcon(`smpeco price ${item}`)
    const num = (k) => Number(out.match(new RegExp(`\\b${k}=(-?[\\d.]+)`))?.[1] ?? NaN)
    const pivot = num('pivot')
    return Math.min(Math.floor(num('baseSell') * num('mod') + 1e-9), pivot >= 0 ? pivot : Infinity)
  }
  const demandP = { [pCoal]: 2 / (await refOf('minecraft:coal')), [pCobble]: 5 / (await refOf('minecraft:cobblestone')),
    [pSand]: 1 / (await refOf('minecraft:sand')) }
  const expectedPlayers = [
    [pCobble, pCoal, pSand],
    [pSand, pCoal, pCobble],
    [pSand, pCoal, pCobble],
    [pCoal, pCobble, pSand].sort((a, b) => demandP[b] - demandP[a]),
    [pSand, pCobble, pCoal],
    [pCoal, pCobble, pSand],
  ]
  const compare = [
    (a, b) => (a.unit > 0) !== (b.unit > 0) ? (a.unit > 0 ? -1 : 1) : b.unit - a.unit,
    (a, b) => (a.unit > 0) !== (b.unit > 0) ? (a.unit > 0 ? -1 : 1) : a.unit - b.unit,
    (a, b) => b.total - a.total,
    null, // demand: board factors over RCON (below)
    (a, b) => b.id - a.id, // order numbers rise with creation
    (a, b) => { const x = nameKey(a); const y = nameKey(b); return x < y ? -1 : x > y ? 1 : 0 },
  ]
  const checkPage = async (w, s, playersFirst, what) => {
    const rows = pageRows(w)
    const orders = rows.filter((r) => !r.header)
    assert(orders.length >= 30, `${what}: page 1 full of orders (${orders.length})`)
    if (playersFirst) {
      assert(rows[0]?.header === 'players', `${what}: slot 0 player head`)
      assert(rows.some((r) => r.header === 'npc'), `${what}: NPC head on page 1`)
      assert(rows[0]?.lore?.some((l) => l.includes(`sorted by: ${SORTS[s]}`)), `${what}: head names the sorting`)
      const mine = orders.filter((r) => [pCoal, pCobble, pSand].includes(r.id)).map((r) => r.id)
      assertEq(mine.join(','), expectedPlayers[s].join(','), `${what}: player orders in the expected order`)
      const firstNpc = orders.findIndex((r) => r.kind !== 'player')
      assert(firstNpc < 0 || orders.slice(firstNpc).every((r) => r.kind !== 'player'), `${what}: player orders first`)
    } else {
      assert(!rows.some((r) => r.header), `${what}: no section heads (mixed)`)
    }
    let cmp = compare[s]
    if (s === 3) {
      // Demand = unit price (lore) / /sell reference, the same for each kind of order. For the board that is
      // what the next piece pays after daily bonus and price cap, not the raw factor. Reference: board by
      // quote, otherwise /smpeco price.
      const ref = {}
      const itemRef = {}
      for (const r of orders) {
        const item = `minecraft:${r.item}`
        ref[r.id] = r.kind === 'board' ? (await boardQuote(r.id, SORTER)).ref : (itemRef[item] ??= await refOf(item))
      }
      const demand = (r) => (r.unit <= 0 ? 0 : Number.isFinite(ref[r.id]) && ref[r.id] > 0 ? r.unit / ref[r.id] : -1)
      cmp = (a, b) => demand(b) - demand(a)
    }
    const res = checkOrder(rows, playersFirst, cmp)
    assert(!res.bad && res.pairs >= 20, `${what}: page 1 sorted (${res.pairs} pairs${res.bad ? `; ${res.bad}` : ''})`)
    return rows
  }
  win = await openMain()
  await checkPage(win, 0, true, 'Price per item ↓')
  for (let s = 1; s < SORTS.length; s++) {
    win = await sortClick(0)
    st = sortState(win)
    assertEq(st.label, `Sort: ${SORTS[s]}`, `left click → ${SORTS[s]}`)
    await checkPage(win, s, true, SORTS[s])
  }
  win = await sortClick(0)
  assertEq(sortState(win).sort, 0, 'left click after name A-Z returns to price per piece down')
  win = await sortClick(1)
  assertEq(sortState(win).sort, 5, 'right click: back to name A-Z')
  win = await sortClick(1)
  assertEq(sortState(win).sort, 4, 'right click: back to newest')
  win = await sortClick(0)
  win = await sortClick('shift')
  st = sortState(win)
  assert(st.sort === 5 && st.playersFirst === false, `shift click: players first off (${st.label})`)
  await checkPage(win, 5, false, 'name A-Z mixed')
  await closeWin(sorter)
  sorter.quit()
  await sleep(1500)
  sorter = await connectBot(SORTER)
  win = await openMain()
  st = sortState(win)
  assert(st.sort === 5 && st.playersFirst === false, `after relog: name A-Z, players first off (${st.label}, ${st.playersFirst})`)
  await checkPage(win, 5, false, 'after relog')
  // mixed price down: the cheap player orders are no longer on top; price up: sand $0.01 on page 1
  win = await sortClick(0)
  assertEq(sortState(win).sort, 0, 'mixed: price per piece down')
  let rows = await checkPage(win, 0, false, 'price down mixed')
  assert(!rows.some((r) => [pCoal, pCobble, pSand].includes(r.id)), 'mixed down: player orders at $0.01-0.05 not on page 1')
  win = await sortClick(0)
  rows = await checkPage(win, 1, false, 'price up mixed')
  assert(rows.some((r) => r.id === pSand), 'mixed up: sand order $0.01 on page 1')
  win = await sortClick(1)
  win = await sortClick('shift')
  st = sortState(win)
  assert(st.sort === 0 && st.playersFirst === true, `back to default (${st.label}, ${st.playersFirst})`)
  await checkPage(win, 0, true, 'default again')
  await closeWin(sorter)
  win = await openMain()
  st = sortState(win)
  assert(st.sort === 0 && st.playersFirst === true, 'default saved')
  await closeWin(sorter)
  for (const id of [pCoal, pCobble, pSand]) await rcon(`orders admin cancel ${id}`)

  for (const name of [BUYER, SELLER]) {
    for (const id of (await info(name)).ids) await rcon(`orders admin cancel ${id}`)
    await rcon(`clear ${name}`)
  }
}, { timeoutMs: 360000 })
