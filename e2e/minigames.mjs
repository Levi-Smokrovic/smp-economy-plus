// Minigames: selftests, chat games (math, fast typing with the setting off, riddle and word through admin info,
// only the first correct answer wins), coinflip (create, announcement above a threshold and at most once per
// cooldown, accept through GUI with confirmation and animation, fee to the treasury, then "double or nothing"
// against the house as one chain), treasure hunt (far left click, explosion, chest opening, stop),
// MEGA-CHALLENGE (rules, bonus question, answer window, races).
import {
  connectBot, rcon, assert, assertEq, sleep, runE2E, waitForMessage, expectWindow, clickSlot, findSlot,
  itemLabel, itemLore, windowTitle, stripColors, Vec3, treasuryFlows, textOf, cents,
} from './lib.mjs'

const A = 'minigaBot1'
const B = 'minigaBot2'
const X = 4600, Y = 230, Z = 4600 // test area for the treasure hunt

async function balance(name) {
  const out = await rcon(`minigames balance ${name}`)
  const m = out.match(/balance=(\d+)/)
  if (!m) throw new Error(`minigames balance ${name}: ${out}`)
  return Number(m[1])
}

/**
 * Treasury income from coinflip fees since opening, in cents: by reason, not balance, to ignore other treasury
 * movements, and since opening, so nothing drops out at the hour change.
 */
async function coinflipFeeIncome() {
  return (await treasuryFlows()).flows.coinflip_fee?.income ?? 0
}

/** Win of a $100 coinflip: pot $200 minus 2 % fee; exactly this amount is offered against the house. */
const DN_STAKE = 19600
const DN_OFFER = /Double or Nothing\? .*\$196\.00/
// treasury level at which the house allows $196 (max bet 2 % of the treasury) and covers $392, plus reserve
const DN_TREASURY_MIN = 1_100_000
let dnTopUp = 0

async function casinoState(name) {
  const line = stripColors(await rcon(`smpeco casino info ${name}`))
  const v = {}
  for (const m of line.matchAll(/(\w+)=(\S+)/g)) v[m[1]] = /^-?\d+$/.test(m[2]) ? Number(m[2]) : m[2]
  if (v.balance === undefined) throw new Error(`smpeco casino info ${name}: ${line}`)
  const f = (await treasuryFlows()).flows.casino ?? { income: 0, expense: 0 }
  v.casinoIn = f.income
  v.casinoOut = f.expense
  return v
}

/**
 * The offer is a chat line with click buttons ("/casino double <id>" or "... no"); the ID is only in the click
 * event, not in the text. Each ID lands in bot.dnOffers.
 */
function trackOffers(bot) {
  bot.dnOffers = []
  bot._client.on('system_chat', (packet) => {
    try {
      const m = JSON.stringify(packet).match(/\/casino double ([0-9a-z]+)/)
      if (m) bot.dnOffers.push(m[1])
    } catch {}
  })
}

async function waitSince(bot, mark, regex, timeoutMs) {
  const end = Date.now() + timeoutMs
  for (;;) {
    const hit = since(bot, mark).find((l) => regex.test(l))
    if (hit) return hit
    if (Date.now() > end) return null
    await sleep(100)
  }
}

async function waitSlot(bot, slot, regex, timeoutMs) {
  const end = Date.now() + timeoutMs
  while (Date.now() < end) {
    const item = bot.currentWindow?.slots?.[slot]
    if (item && regex.test(itemLabel(item))) return item
    await sleep(100)
  }
  return null
}

function houseResult(bot, timeoutMs) {
  return waitForMessage(bot, /Coinflip: (?:Won (\$[\d,]+\.\d\d)|(Lost))/, timeoutMs)
    .then((m) => ({ win: !!m[1], payout: m[1] ? cents(m[1]) : 0 }))
}

/**
 * The house must allow $196, or rightly no offer comes: both bots without an open round and with a full daily
 * limit, treasury large enough (temporarily topped up with /eco treasury, taken back at the end).
 */
async function prepareHouse() {
  for (const n of [A, B]) {
    await rcon(`smpeco casino settle ${n}`)
    await rcon(`smpeco casino resetlimit ${n}`)
  }
  const balance = (await treasuryFlows()).balance
  if (balance < DN_TREASURY_MIN) {
    const amount = DN_TREASURY_MIN - balance
    const out = await rcon(`eco treasury deposit ${(amount / 100).toFixed(2)}`)
    if (/deposited/.test(out)) dnTopUp = amount
    console.log(`[E2E] INFO treasury topped up for "double or nothing": ${out.trim()}`)
  }
  for (const n of [A, B]) {
    const s = await casinoState(n)
    assert(s.maxBet >= DN_STAKE && s.cover >= 2 * DN_STAKE && s.room >= DN_STAKE && s.open === 'none',
      `house allows $196 for ${n} (maxBet=${s.maxBet} cover=${s.cover} room=${s.room} open=${s.open})`)
  }
}

async function restoreHouse() {
  for (const n of [A, B]) {
    await rcon(`smpeco casino settle ${n}`).catch(() => {})
    await rcon(`smpeco casino resetlimit ${n}`).catch(() => {})
  }
  if (dnTopUp > 0) {
    const out = await rcon(`eco treasury withdraw ${(dnTopUp / 100).toFixed(2)}`).catch((e) => String(e))
    console.log(`[E2E] INFO treasury back: ${String(out).trim()}`)
    dnTopUp = 0
  }
}

/**
 * After an accepted $100 coinflip the winner, and only the winner, gets the offer of exactly $196 through
 * CoinflipGui.offerDoubleOrNothing, Services.DoubleOrNothing and CasinoOffers. "/casino double <id>" opens the
 * coinflip against the house, the round settles through the treasury reason "casino", and the offer is used up
 * afterwards.
 */
async function doubleOrNothingChain(a, b, creatorWon, marks) {
  const [winner, loser, wName] = creatorWon ? [a, b, A] : [b, a, B]
  const [markW, markL, offW] = creatorWon ? [marks.a, marks.b, marks.offA] : [marks.b, marks.a, marks.offB]
  const branch = creatorWon ? 'DoN after creator win' : 'DoN after acceptor win'
  // creator: 40 ticks after accepting; acceptor: after the animation (about 63 ticks) + 10 ticks
  const offer = await waitSince(winner, markW, DN_OFFER, 12000)
  assert(!!offer, `${branch}: winner ${wName} gets the offer of $196.00`)
  await sleep(1000)
  assert(!since(loser, markL).some((l) => /Double or Nothing\?/.test(l)), `${branch}: loser gets no offer`)
  const id = offer ? winner.dnOffers.slice(offW).at(-1) : null
  assert(!offer || !!id, `${branch}: offer ID read from the click event (${id})`)
  if (!id) return
  // like a player: close the animation window, then click the chat button
  await closeWindows(winner)
  const before = await casinoState(wName)
  const lost = houseResult(winner, 12000).catch(() => null)
  const win = await expectWindow(winner, () => winner.chat(`/casino double ${id}`))
  assert(/vs\. the House/.test(windowTitle(win)), `${branch}: accepting opens the coinflip against the house ("${windowTitle(win)}")`)
  const cash = await waitSlot(winner, 20, /Cash Out/, 8000)
  let payout = 0
  if (cash) {
    assertEq(cents(itemLabel(cash)), 2 * DN_STAKE, `${branch}: house coinflip won, pot $392.00`)
    const paid = houseResult(winner, 6000).catch(() => null)
    await clickSlot(winner, 20)
    payout = (await paid)?.payout ?? -1
    assertEq(payout, 2 * DN_STAKE, `${branch}: cash out pays $392.00`)
  } else {
    const r = await lost
    assert(!!r && !r.win, `${branch}: house coinflip loss reported`)
  }
  const after = await casinoState(wName)
  assertEq(after.balance, before.balance - DN_STAKE + payout, `${branch}: balance = before - $196 + payout`)
  assertEq([after.casinoIn - before.casinoIn, after.casinoOut - before.casinoOut], [DN_STAKE, payout],
    `${branch}: treasury books stake/payout (reason casino)`)
  assertEq(after.open, 'none', `${branch}: no open casino round`)
  await closeWindows(winner)
  const gone = waitForMessage(winner, /no longer available/, 5000).then(() => true, () => false)
  winner.chat(`/casino double ${id}`)
  assert(await gone, `${branch}: offer can be accepted only once`)
  assertEq((await casinoState(wName)).balance, after.balance, `${branch}: second accept books nothing`)
}

function marksOf(a, b) {
  return { a: a.messages.length, b: b.messages.length, offA: a.dnOffers.length, offB: b.dnOffers.length }
}

async function quickFlip(a, b) {
  await closeWindows(a, b)
  await setMoney(A, 1000)
  await setMoney(B, 1000)
  const created = waitForMessage(a, /Coinflip for \$100\.00 created/, 5000).then(() => true, () => false)
  a.chat('/coinflip 100')
  if (!assert(await created, 'further coinflip created')) return null
  const list = await expectWindow(b, () => b.chat('/coinflip'))
  const slot = findSlot(list, new RegExp(A))
  if (!assert(slot >= 0, 'further flip in the list')) return null
  await expectWindow(b, () => clickSlot(b, slot))
  const marks = marksOf(a, b)
  const creatorMsg = waitForMessage(a, /You (won|lost) your coinflip for \$100\.00/, 10000)
  await expectWindow(b, () => clickSlot(b, 9))
  const creatorWon = (await creatorMsg)[1] === 'won'
  const bal = [await balance(A), await balance(B)]
  assertEq(bal, creatorWon ? [90000 + 19600, 90000] : [90000, 90000 + 19600], `further flip: ${creatorWon ? A : B} wins $196`)
  return { creatorWon, marks }
}

function usd(cents) {
  const t = String(cents).padStart(3, '0')
  return '$' + t.slice(0, -2).replace(/\B(?=(\d{3})+(?!\d))/g, ',') + '.' + t.slice(-2)
}

function reEsc(text) {
  return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

/**
 * Announcements (setting on): B starts a flip above the threshold (announceMinBet, default $5,000) and A sees
 * it; B cancels and recreates at once (free), and within the cooldown no further announcement comes, or anyone
 * could flood everyone's chat. A stays without a cooldown for the $100 test after.
 */
async function announcementTests(a, b) {
  const st = stripColors(await rcon('minigames status'))
  const m = st.match(/announceMinBet=(\d+) announceCooldown=(\d+)s/)
  assert(!!m, `threshold and cooldown in the status (${st.split('\n')[0].trim()})`)
  if (!m) return
  const threshold = Number(m[1])
  const cooldown = Number(m[2])
  assert(threshold > 10000, `announcement threshold above $100 (announceMinBet=${threshold}; old default 0 gets migrated)`)
  assert(cooldown >= 10, `announcement cooldown active (${cooldown} s)`)
  const dollars = Math.ceil(threshold / 100)
  const text = reEsc(usd(dollars * 100))
  await setMoney(B, dollars + 1000)
  const made = waitForMessage(b, new RegExp(`Coinflip for ${text} created`), 5000).then(() => true, () => false)
  const ann = waitForMessage(a, new RegExp(`${B} started a coinflip for ${text}`), 5000).then(() => true, () => false)
  b.chat(`/coinflip ${dollars}`)
  assert(await made, `flip of ${usd(dollars * 100)} created`)
  assert(await ann, `announcement above the threshold (${usd(dollars * 100)}) at the other player`)
  const mark = a.messages.length
  for (let i = 0; i < 2; i++) {
    // slowly, so vanilla's chat spam kick (20 per command, limit 200) does not hit
    await sleep(300)
    const refunded = waitForMessage(b, /refunded/, 5000)
    b.chat('/coinflip cancel')
    await refunded
    await sleep(300)
    const again = waitForMessage(b, new RegExp(`Coinflip for ${text} created`), 5000)
    b.chat(`/coinflip ${dollars}`)
    await again
  }
  await sleep(1000)
  assert(!since(a, mark).some((l) => /started a coinflip for/.test(l)), 'cancel and recreate in the cooldown: no further announcement')
  const refunded = waitForMessage(b, /refunded/, 5000)
  b.chat('/coinflip cancel')
  await refunded
  assertEq(await balance(B), (dollars + 1000) * 100, 'cancel refunds every stake')
  await setMoney(B, 1000)
}

async function setMoney(name, dollars) {
  await rcon(`eco set ${name} ${dollars}`)
  const now = await balance(name)
  if (now !== dollars * 100) throw new Error(`eco set ${name} ${dollars} had no effect (balance=${now}), is economy missing?`)
}

function evalMath(expr) {
  const js = expr.replace(/×/g, '*').trim()
  if (!/^[\d\s+\-*()]+$/.test(js)) throw new Error(`unexpected expression: ${expr}`)
  return Function(`"use strict"; return (${js})`)()
}

function since(bot, from) {
  return bot.messages.slice(from).map(stripColors)
}

async function closeWindows(...bots) {
  for (const bot of bots) {
    if (bot.currentWindow) { try { bot.closeWindow(bot.currentWindow) } catch {} }
  }
  await sleep(300)
}

runE2E(async () => {
  const a = await connectBot(A)
  const b = await connectBot(B)
  trackOffers(a)
  trackOffers(b)
  assert(true, 'bots connected')
  await sleep(1500)
  a.chat('/chatgame on')
  b.chat('/chatgame on')
  a.chat('/coinflip cancel')
  b.chat('/coinflip cancel')
  await sleep(800)

  const st = await rcon('smpeco selftest minigames')
  const sm = st.match(/SUMMARY passed=(\d+) failed=(\d+)/)
  assert(!!sm, `selftest summary present (${st.slice(0, 300)})`)
  if (sm) {
    assert(Number(sm[1]) >= 15, `minigames selftests ran (passed=${sm[1]})`)
    assertEq(Number(sm[2]), 0, `minigames-Selftests failed=0 ${Number(sm[2]) ? st.slice(0, 600) : ''}`)
  }

  await setMoney(A, 1000)
  await setMoney(B, 1000)

  if (process.env.E2E_MINIGAMES_ONLY !== 'mega') {
    await chatGameTests(a, b)
    await coinflipTests(a, b)
    await treasureTests(a, b)
  }
  await megaTests(a, b)

  await setMoney(A, 0).catch(() => {})
  await setMoney(B, 0).catch(() => {})
}, { timeoutMs: 1200000 })

async function chatGameTests(a, b) {
  const qA = waitForMessage(a, /Solve: ([\d\s+\-×()]+)$/, 8000)
  const qB = waitForMessage(b, /Solve: /, 8000).then(() => true, () => false)
  const started = await rcon('chatgame start math')
  assert(/started/.test(started), `admin starts chat game via RCON (${started.trim()})`)
  const q = await qA
  assert(await qB, 'both players see the task')
  const answer = evalMath(q[1])
  const balA = await balance(A)
  const balB = await balance(B)
  b.chat(String(answer + 1000))
  await sleep(700)
  const winA = waitForMessage(a, new RegExp(`${A} won.*Answer: ${answer}\\b`), 8000)
  const winSeenByB = waitForMessage(b, new RegExp(`${A} won\\b`), 8000).then(() => true, () => false)
  a.chat(String(answer))
  await winA
  assert(true, `${A} wins with ${q[1]} = ${answer}`)
  assert(await winSeenByB, 'winner message also at the other player')
  const markB = b.messages.length
  b.chat(String(answer))
  await sleep(1500)
  assert(!since(b, markB).some((l) => / won \(/.test(l)), 'second correct answer gives no second winner')
  const gainA = (await balance(A)) - balA
  assert(gainA >= 10000, `prize credited (+${gainA} cents, base prize $100 x multiplier)`)
  assertEq(await balance(B), balB, 'wrong or late answer brings nothing')

  b.chat('/chatgame off')
  await waitForMessage(b, /Chat games turned off/, 5000)
  const markB2 = b.messages.length
  const typeQ = waitForMessage(a, /First to type: (.+)$/, 8000)
  await rcon('chatgame start fasttype')
  const text = (await typeQ)[1].trim()
  await sleep(1000)
  assert(!since(b, markB2).some((l) => /First to type/.test(l)), 'player with chat games off does not see the task')
  const balB2 = await balance(B)
  b.chat(text)
  await sleep(1200)
  const info = await rcon('chatgame info')
  assert(/active=true/.test(info), `answer of "off" player does not end the game (${info.trim()})`)
  const winType = waitForMessage(a, new RegExp(`${A} won\\b`), 8000)
  a.chat(text.toLowerCase())
  await winType
  assert(true, 'fast typing: case does not matter, A wins')
  await sleep(500)
  assertEq(await balance(B), balB2, '"off" player gets no money')
  assert(!since(b, markB2).some((l) => / won \(/.test(l)), '"off" player does not see the winner message either')
  b.chat('/chatgame on')
  await waitForMessage(b, /Chat games turned on/, 5000)

  for (const [type, re] of [['riddle', /Who am I\?/], ['unscramble', /Unscramble the word: (\S+)/]]) {
    const shown = waitForMessage(b, re, 8000)
    await rcon(`chatgame start ${type}`)
    await shown
    const inf = await rcon('chatgame info')
    const sol = inf.match(/solution=(.+?) endsIn=/)
    assert(!!sol, `chatgame info returns the solution (${inf.trim()})`)
    if (!sol) continue
    const win = waitForMessage(a, new RegExp(`${B} won\\b`), 8000).then(() => true, () => false)
    b.chat(sol[1])
    assert(await win, `${type}: correct answer "${sol[1]}" wins`)
    await sleep(400)
  }

  await rcon('chatgame start math')
  await sleep(500)
  const status = waitForMessage(a, /Solve: /, 5000).then(() => true, () => false)
  a.chat('/chatgame')
  assert(await status, '/chatgame shows the running task')
  const stopped = waitForMessage(a, /ended by an admin/, 5000).then(() => true, () => false)
  const stopOut = await rcon('chatgame stop')
  assert(/ended/.test(stopOut) && await stopped, 'admin stop ends the chat game')
  assert(/active=false/.test(await rcon('chatgame info')), 'no chat game active any more')
  const bad = await rcon('chatgame start nonsense')
  assert(/Unknown type/.test(bad), 'unknown type refused')
}

async function coinflipTests(a, b) {
  await closeWindows(a, b)
  await setMoney(A, 1000)
  await setMoney(B, 1000)
  const poor = waitForMessage(b, /Not enough money/, 5000).then(() => true, () => false)
  b.chat('/coinflip 999999')
  assert(await poor, 'coinflip without enough money refused')
  const low = waitForMessage(b, /Minimum bet/, 5000).then(() => true, () => false)
  b.chat('/coinflip 0.50')
  assert(await low, 'coinflip below the minimum bet refused')
  const invalid = waitForMessage(b, /Invalid amount/, 5000).then(() => true, () => false)
  b.chat('/coinflip abc')
  assert(await invalid, 'invalid amount refused')
  assertEq(await balance(B), 100000, 'error cases book nothing')

  await announcementTests(a, b)

  const created = waitForMessage(a, /Coinflip for \$100\.00 created/, 5000).then(() => true, () => false)
  const markAnn = b.messages.length
  a.chat('/coinflip 100')
  assert(await created, 'coinflip created')
  await sleep(1000)
  assert(!since(b, markAnn).some((l) => /started a coinflip for/.test(l)), '$100 flip below the threshold not announced')
  assertEq(await balance(A), 90000, 'stake charged at once (escrow)')
  const status = await rcon('minigames status')
  assert(new RegExp(`creator=${A} amount=10000`).test(status), 'flip visible in coinflips.json/status')

  await prepareHouse()
  const feeBefore = await coinflipFeeIncome()
  const list = await expectWindow(b, () => b.chat('/coinflip'))
  assert(/Coinflip/.test(windowTitle(list)), `coinflip GUI opened ("${windowTitle(list)}")`)
  const slot = findSlot(list, new RegExp(A))
  assert(slot >= 0, 'flip of A in the list')
  if (slot < 0) return
  assert(itemLore(list.slots[slot]).some((l) => /Bet: \$100\.00/.test(l)), 'lore shows the stake')
  assert(itemLore(list.slots[slot]).some((l) => /Accept/.test(l)), 'other player\'s flip: "Accept"')
  const dialog = await expectWindow(b, () => clickSlot(b, slot))
  assert(/Accept coinflip/.test(windowTitle(dialog)), `confirm dialog ("${windowTitle(dialog)}")`)
  const marks = marksOf(a, b)
  const creatorMsg = waitForMessage(a, /You (won|lost) your coinflip for \$100\.00/, 10000)
  const resultB = waitForMessage(b, /(You won!|You lost!)/, 15000)
  const anim = await expectWindow(b, () => clickSlot(b, 9))
  assert(/Flipping/.test(windowTitle(anim)), `animation opened ("${windowTitle(anim)}")`)
  const cm = await creatorMsg
  const rb = await resultB
  const creatorWon = cm[1] === 'won'
  assertEq(rb[1] === 'You lost!', creatorWon, 'both sides see the same result')
  // money is booked on accepting, before any message; check before double or nothing
  const balA = await balance(A)
  const balB = await balance(B)
  if (creatorWon) {
    assertEq([balA, balB], [90000 + 19600, 90000], 'A wins: pot $200 minus 2 % fee')
  } else {
    assertEq([balA, balB], [90000, 90000 + 19600], 'B wins: pot $200 minus 2 % fee')
  }
  assertEq((await coinflipFeeIncome()) - feeBefore, 400, 'fee $4.00 in the treasury (coinflip_fee)')
  assert(!new RegExp(`creator=${A} amount=10000`).test(await rcon('minigames status')), 'flip removed after accepting')

  // chain PvP win, offer, house round, for both winner branches (50/50: more flips until each branch ran; 12
  // flips reach both with > 99.9 %)
  try {
    const branches = new Set()
    await doubleOrNothingChain(a, b, creatorWon, marks)
    branches.add(creatorWon ? 'creator' : 'acceptor')
    for (let n = 1; n < 12 && branches.size < 2; n++) {
      const r = await quickFlip(a, b)
      if (!r) break
      await doubleOrNothingChain(a, b, r.creatorWon, r.marks)
      branches.add(r.creatorWon ? 'creator' : 'acceptor')
    }
    assertEq(branches.size, 2, `"double or nothing" checked after both winner branches (${[...branches].join(', ')})`)
  } finally {
    await closeWindows(a, b)
    await restoreHouse()
  }
  await setMoney(A, 1000)
  await setMoney(B, 1000)

  const before = await balance(A)
  a.chat('/coinflip 150')
  await waitForMessage(a, /Coinflip for \$150\.00 created/, 5000)
  assertEq(await balance(A), before - 15000, 'stake $150 reserved')
  const own = await expectWindow(a, () => a.chat('/coinflip'))
  const ownSlot = findSlot(own, new RegExp(A))
  assert(ownSlot >= 0 && itemLore(own.slots[ownSlot]).some((l) => /Cancel/.test(l)), 'own flip: "Cancel"')
  if (ownSlot >= 0) {
    const cancelDialog = await expectWindow(a, () => clickSlot(a, ownSlot))
    assert(/Cancel coinflip/.test(windowTitle(cancelDialog)), 'cancel dialog')
    const refunded = waitForMessage(a, /cancelled/, 5000).then(() => true, () => false)
    const reopened = expectWindow(a, () => clickSlot(a, 9)).then((w) => w, () => null)
    assert(await refunded, 'cancel confirmed')
    const w = await reopened
    assert(w && /Coinflip/.test(windowTitle(w)) && findSlot(w, new RegExp(A)) < 0, 'list without the flip afterwards')
  }
  await closeWindows(a)
  assertEq(await balance(A), before, 'cancel refunds the stake')

  a.chat('/coinflip 175')
  await waitForMessage(a, /Coinflip for \$175\.00 created/, 5000)
  const cmdCancel = waitForMessage(a, /refunded/, 5000).then(() => true, () => false)
  a.chat('/coinflip cancel')
  assert(await cmdCancel, '/coinflip cancel refunds')
  assertEq(await balance(A), before, 'balance the same after /coinflip cancel')

  const balB0 = await balance(B)
  const gui = await expectWindow(b, () => b.chat('/coinflip'))
  assertEq(itemLabel(gui.slots[45]), 'New Coinflip', 'Button "New Coinflip"')
  const anvil = await expectWindow(b, () => clickSlot(b, 45))
  assert(/Enter your bet/.test(windowTitle(anvil)), 'anvil input for the stake')
  b._client.write('name_item', { name: '120' })
  await sleep(400)
  const madeB = waitForMessage(b, /Coinflip for \$120\.00 created/, 5000).then(() => true, () => false)
  const back = expectWindow(b, () => clickSlot(b, 2)).then((w) => w, () => null)
  assert(await madeB, 'flip created through GUI input')
  await back
  await closeWindows(b)
  assertEq(await balance(B), balB0 - 12000, 'stake reserved')
  b.chat('/coinflip cancel')
  await waitForMessage(b, /refunded/, 5000)
  assertEq(await balance(B), balB0, 'refunded')
  const leaked = [...a.inventory.items(), ...b.inventory.items()].filter((it) => it.name === 'player_head' || it.name.includes('stained_glass'))
  assertEq(leaked.length, 0, 'no GUI items in the inventory')
}

async function treasureTests(a, b) {
  await closeWindows(a, b)
  await rcon(`forceload add ${X - 16} ${Z - 16} ${X + 32} ${Z + 16}`)
  await sleep(1500)
  const platform = `fill ${X - 12} ${Y - 1} ${Z - 6} ${X + 26} ${Y - 1} ${Z + 6} minecraft:glass`
  await rcon(`fill ${X - 12} ${Y} ${Z - 6} ${X + 26} ${Y + 4} ${Z + 6} minecraft:air`)
  await rcon(platform)
  for (const n of [A, B]) {
    await rcon(`gamemode creative ${n}`)
    await rcon(`clear ${n}`)
  }
  await rcon(`tp ${A} ${X + 22.5} ${Y} ${Z + 0.5}`)
  await rcon(`tp ${B} ${X + 22.5} ${Y} ${Z + 3.5}`)
  await sleep(1500)
  try {
    const hint = waitForMessage(b, /A treasure has been hidden/, 8000).then(() => true, () => false)
    const hint1 = waitForMessage(b, /Hint 1 » .*biome/, 8000).then(() => true, () => false)
    const placed = await rcon(`treasure place ${X + 2} ${Y} ${Z}`)
    const pm = placed.match(/money=(\d+)/)
    assert(!!pm, `admin hides the treasure (${placed.trim()})`)
    assert(await hint, 'start announced')
    assert(await hint1, 'first hint (biome + direction)')
    const money = pm ? Number(pm[1]) : 0
    await sleep(500)
    assertEq(a.blockAt(new Vec3(X + 2, Y, Z))?.name, 'chest', 'chest stands in the world')
    const info = await rcon('treasure info')
    assert(new RegExp(`active=true x=${X + 2} y=${Y} z=${Z}`).test(info), `status active (${info.trim()})`)
    const st = waitForMessage(a, /left\. Click the chest to open it/, 5000).then(() => true, () => false)
    a.chat('/treasure')
    assert(await st, '/treasure shows time left and hints')

    // Left-click packet (START_DESTROY_BLOCK) from 20 blocks away, as a modified client can send it: Fabric's
    // AttackBlockCallback runs before vanilla's reach check, and the treasure must not be found that way.
    const balFar = await balance(B)
    b._client.write('block_dig', { status: 0, location: new Vec3(X + 2, Y, Z), face: 1, sequence: 0 })
    await sleep(800)
    assert(/active=true/.test(await rcon('treasure info')), 'left click from 20 blocks does not find the treasure')
    assertEq(await balance(B), balFar, 'no money for the far left click')
    assert(!b.currentWindow, 'no reward GUI from afar')
    assertEq(a.blockAt(new Vec3(X + 2, Y, Z))?.name, 'chest', 'chest still stands after the far left click')

    // explosion next to the chest: chest stays (explosion mixin)
    await rcon(`summon minecraft:tnt ${X} ${Y} ${Z} {fuse:1}`)
    await sleep(2000)
    assertEq(a.blockAt(new Vec3(X + 2, Y, Z))?.name, 'chest', 'TNT does not destroy the treasure chest')
    assert(/active=true/.test(await rcon('treasure info')), 'treasure hunt continues after the explosion')
    await rcon(`fill ${X - 12} ${Y - 1} ${Z - 6} ${X + 26} ${Y - 1} ${Z + 6} minecraft:glass replace minecraft:air`)

    await rcon(`tp ${A} ${X + 4.5} ${Y} ${Z + 0.5}`)
    await rcon(`tp ${B} ${X + 4.5} ${Y} ${Z + 2.5}`)
    await sleep(1200)
    const balA = await balance(A)
    const found = waitForMessage(b, new RegExp(`${A} found the treasure`), 8000).then(() => true, () => false)
    const win = await expectWindow(a, () => a.activateBlock(a.blockAt(new Vec3(X + 2, Y, Z))))
    assert(/Treasure found/.test(windowTitle(win)), `reward GUI ("${windowTitle(win)}")`)
    if (money > 0) assert(findSlot(win, /Money Voucher/) >= 0, 'money voucher in the GUI')
    assert(await found, 'find announced')
    await sleep(500)
    assertEq((await balance(A)) - balA, money, 'money reward credited exactly once')
    await closeWindows(a)
    assertEq(a.blockAt(new Vec3(X + 2, Y, Z))?.name, 'air', 'chest removed after the find')
    assert(/active=false/.test(await rcon('treasure info')), 'treasure hunt ended')
    const items = a.inventory.items().filter((it) => !/glass|player_head/.test(it.name))
    assert(items.length >= 1, `item reward in the inventory (${items.map((it) => `${it.count}x ${it.name}`).join(', ')})`)

    const balB = await balance(B)
    try { await b.activateBlock(b.blockAt(new Vec3(X + 2, Y - 1, Z))) } catch {}
    await sleep(800)
    assertEq(await balance(B), balB, 'nothing left after the find')
    assert(!b.currentWindow, 'no second reward GUI')
    const noneMsg = waitForMessage(b, /No treasure hunt is running/, 5000).then(() => true, () => false)
    b.chat('/treasure')
    assert(await noneMsg, '/treasure: no treasure hunt active')

    await rcon(`treasure place ${X + 2} ${Y} ${Z}`)
    await sleep(500)
    const stop = await rcon('treasure stop')
    assert(/chest removed/.test(stop), `admin stop (${stop.trim()})`)
    await sleep(500)
    assertEq(a.blockAt(new Vec3(X + 2, Y, Z))?.name, 'air', 'chest removed after stop')
  } finally {
    await closeWindows(a, b)
    await rcon('treasure stop').catch(() => {})
    await rcon(`fill ${X - 12} ${Y - 1} ${Z - 6} ${X + 26} ${Y + 4} ${Z + 6} minecraft:air`)
    for (const n of [A, B]) {
      await rcon(`clear ${n}`)
      await rcon(`gamemode survival ${n}`)
    }
    await rcon(`forceload remove ${X - 16} ${Z - 16} ${X + 32} ${Z + 16}`)
  }
}

const MEGA_REWARD = 1_000_000

// record titles and boss bars from packets (1.21.11: NBT components, see textOf)
function trackMega(bot) {
  if (bot.megaTracked) return
  bot.megaTracked = true
  bot.titles = []
  bot.bars = new Map()
  bot._client.on('set_title_text', (p) => bot.titles.push({ kind: 'title', text: textOf(p.text), at: Date.now() }))
  bot._client.on('set_title_subtitle', (p) => bot.titles.push({ kind: 'subtitle', text: textOf(p.text), at: Date.now() }))
  bot._client.on('boss_bar', (p) => {
    const id = String(p.entityUUID)
    if (p.action === 0) bot.bars.set(id, { title: textOf(p.title), health: p.health })
    else if (p.action === 1) bot.bars.delete(id)
    else if (bot.bars.has(id)) {
      const bar = bot.bars.get(id)
      if (p.action === 2) bar.health = p.health
      if (p.action === 3) bar.title = textOf(p.title)
    }
  })
}

const megaBar = (bot) => [...bot.bars.values()].find((b) => /MEGA/.test(b.title))

async function until(fn, timeoutMs = 6000, stepMs = 200) {
  const end = Date.now() + timeoutMs
  while (Date.now() < end) {
    const v = await fn()
    if (v) return v
    await sleep(stepMs)
  }
  return await fn()
}

// MegaChallenge.MAX_PAYOUT_RECORDS: the list keeps only the last 100 payouts, so on a long-used server
// "payouts=" stops counting; exactly-once checks use balance and lastPaidId/lastWinner there.
const MAX_PAYOUT_RECORDS = 100
const nextPayouts = (n) => Math.min(n + 1, MAX_PAYOUT_RECORDS)

async function megaStatus() {
  const line = stripColors(await rcon('chatgame mega status'))
  const v = {}
  for (const m of line.matchAll(/(\w+)=(\S+)/g)) v[m[1]] = /^-?\d+$/.test(m[2]) ? Number(m[2]) : m[2]
  if (!v.phase) throw new Error(`chatgame mega status: ${line}`)
  return v
}

// only RCON may see the solution
async function megaSolution() {
  const out = await rcon('chatgame mega solution')
  const m = out.match(/mega solution=(.+?) accepted=(.*?) opensIn=(\d+)ms/)
  if (!m) throw new Error(`chatgame mega solution: ${out.trim()}`)
  return { solution: m[1].trim(), accepted: m[2].split('|'), opensAt: Date.now() + Number(m[3]) }
}

/** Waits until just after the answers open; that moment is secret and visible only over RCON. */
async function untilOpen(sol, extraMs = 400) {
  await sleep(Math.max(0, sol.opensAt + extraMs - Date.now()))
}

async function megaRules() {
  const line = stripColors(await rcon('chatgame mega rules'))
  const m = line.match(/minPlaytime=(\d+) onePerIp=(true|false)/)
  if (!m) throw new Error(`chatgame mega rules: ${line}`)
  return { minPlaytime: Number(m[1]), onePerIp: m[2] === 'true' }
}

function divisorSum(n) {
  let s = 0
  for (let d = 1; d * d <= n; d++) if (n % d === 0) s += d * d === n ? d : d + n / d
  return s
}

function sawChat(bot, mark, name, text) {
  return since(bot, mark).some((l) => l.includes(name) && l.includes(text) && !/MEGA|Solution|Answer:/.test(l))
}

async function megaTests(a, b) {
  trackMega(a)
  trackMega(b)
  await closeWindows(a, b)
  for (const n of [A, B]) {
    await rcon(`gamemode survival ${n}`)
    await rcon(`clear ${n}`)
  }
  await rcon('chatgame mega stop').catch(() => {})
  await rcon('chatgame stop').catch(() => {})
  await sleep(500)
  await setMoney(A, 0)
  await setMoney(B, 0)
  const st0 = await megaStatus()
  assertEq(st0.phase, 'idle', `no MEGA-CHALLENGE before (${JSON.stringify(st0)})`)
  assert(st0.enabled === 'true' && /^\d+s$/.test(String(st0.nextIn)), `automatic planning active (nextIn=${st0.nextIn})`)
  assertEq(st0.interval, '30m', 'MEGA-CHALLENGE every 30 minutes')
  let payouts = st0.payouts

  // participation rules: minimum playtime against fresh alt accounts; the fresh bots must not take part then
  const rules0 = await megaRules()
  assert(rules0.minPlaytime > 0 && rules0.onePerIp, `rules active: ${rules0.minPlaytime} min playtime, one account per public IP`)
  try {
    await rcon('chatgame mega rules playtime 10080')
    const told = waitForMessage(a, /You couldn't take part: only from 10080 min playtime/, 6000).then(() => true, () => false)
    const none = await rcon('chatgame mega now math')
    assert(/cancelled/.test(none), `nobody with enough playtime: skipped (${none.trim()})`)
    assert(await told, 'player learns the reason (playtime)')
    assertEq((await megaStatus()).phase, 'idle', 'no question without participants')
    await rcon('chatgame mega rules playtime 0')
    payouts = (await megaStatus()).payouts

    await megaQuestionTests(a, b, payouts)
    payouts = (await megaStatus()).payouts
    await megaRaceTests(a, b, payouts)
    payouts = (await megaStatus()).payouts

    await setMoney(A, 0)
    const q4 = waitForMessage(a, /What is the remainder of/, 8000).then(() => true, () => false)
    await rcon('chatgame mega now math modulo')
    assert(await q4, 'modulo task started')
    const stopped = waitForMessage(a, /ended by an admin/, 5000).then(() => true, () => false)
    const solShown = waitForMessage(a, /Answer: \d+/, 5000).then(() => true, () => false)
    const stopOut = await rcon('chatgame mega stop')
    assert(/ended/.test(stopOut) && await stopped && await solShown, 'admin stop ends the question and shows the solution')
    const st4 = await megaStatus()
    assertEq([st4.phase, st4.payouts], ['idle', payouts], 'admin stop pays nothing')
    assertEq(await balance(A), 0, 'no money after the admin stop')
    const noMega = await rcon('chatgame mega stop')
    assert(/No MEGA-CHALLENGE/.test(noMega), 'stop without a running MEGA-CHALLENGE says so')
    const after = await rcon('chatgame start math')
    assert(/started/.test(after), 'normal chat games work again afterwards')
    await rcon('chatgame stop').catch(() => {})
  } finally {
    await rcon('chatgame mega stop').catch(() => {})
    await rcon(`chatgame mega rules playtime ${rules0.minPlaytime}`).catch(() => {})
    await setMoney(A, 0).catch(() => {})
    await setMoney(B, 0).catch(() => {})
  }
  assertEq((await megaRules()).minPlaytime, rules0.minPlaytime, 'playtime rule restored')
}

async function megaQuestionTests(a, b, payouts) {
  const BONUS_REWARD = 2 * MEGA_REWARD
  const titleMark = a.titles.length
  const ann = waitForMessage(a, /BONUS QUESTION » The MEGA-CHALLENGE starts in 60 seconds! Category: Math .*\$20,000\.00/, 8000).then(() => true, () => false)
  const noHelp = waitForMessage(a, /No external help! .*calculators.*Only your FIRST answer counts/, 8000).then(() => true, () => false)
  const started = await rcon('chatgame mega bonus math divisors')
  assert(/MEGA-CHALLENGE started \(announced\) \[BONUS \$20,000\.00\]/.test(started), `admin starts the bonus question with announcement (${started.trim()})`)
  assert(await ann, 'announcement in chat (60 s, BONUS, category, $20,000)')
  assert(await noHelp, 'announcement: "No external help" and only the first try counts')
  const title = await until(() => a.titles.slice(titleMark).find((t) => t.kind === 'title' && /BONUS QUESTION/.test(t.text)), 5000)
  const sub = a.titles.slice(titleMark).find((t) => t.kind === 'subtitle')
  assert(!!title && !!sub && /\$20,000\.00/.test(sub.text), `big title "BONUS QUESTION" with prize in the subtitle (${sub?.text})`)
  const bar0 = await until(() => megaBar(b), 4000)
  assert(!!bar0 && /BONUS/.test(bar0.title) && /starts in/.test(bar0.title), `Bossbar-Countdown (${bar0?.title})`)
  const stA = await megaStatus()
  assertEq([stA.phase, stA.type, stA.variant, stA.bonus, stA.reward], ['announce', 'math', 'divisors', 'true', BONUS_REWARD], 'status: bonus announcement running')
  const blocked = await rcon('chatgame start math')
  assert(/MEGA-CHALLENGE/.test(blocked) && !/started/.test(blocked), `normal chat games paused (${blocked.trim()})`)
  const noSecond = await rcon('chatgame mega now math')
  assert(/already running/.test(noSecond), 'no second MEGA-CHALLENGE at the same time')
  const early = await rcon('chatgame mega solution')
  assert(/No MEGA question/.test(early), 'no question or solution yet during the announcement')
  const noRaceBonus = await rcon('chatgame mega stop').then(() => rcon('chatgame mega bonus race'))
  assert(/never a race/.test(noRaceBonus), `bonus is never a race (${noRaceBonus.trim()})`)
  // start as a bonus question with announcement again; the stop above ended the announcement without payout
  const ann2 = waitForMessage(a, /BONUS QUESTION » The MEGA-CHALLENGE starts in 60 seconds/, 8000).then(() => true, () => false)
  const started2 = await rcon('chatgame mega bonus math divisors')
  assert(/\[BONUS \$20,000\.00\]/.test(started2) && await ann2, `bonus question announced again (${started2.trim()})`)

  const rulesLine = waitForMessage(a, /secret wait/, 75000).then((m) => m.input, () => '')
  const qm = await waitForMessage(a, /sum of all positive divisors of (\d+)\?/, 75000)
  const n = Number(qm[1])
  const answer = divisorSum(n)
  const sol = await megaSolution()
  assertEq(sol.solution, String(answer), `solution recalculated independently: divisor sum of ${n} = ${answer}`)
  const rl = await rulesLine
  assert(/No external help/.test(rl) && /only your FIRST answer counts/.test(rl), `rules: No external help, only the first try (${rl})`)
  assert(/up to 30 s/.test(rl) && !/at least|\b12 s\b/.test(rl), `rules name only the upper limit of the secret wait (${rl})`)
  assert(sol.opensAt - Date.now() > 4000, `answers only after a wait (${sol.opensAt - Date.now()} ms)`)
  const bar1 = await until(() => megaBar(a) && /Math/.test(megaBar(a).title) && megaBar(a), 3000)
  assert(!!bar1 && /\$20,000/.test(bar1.title), `boss bar shows time left and $20,000 (${bar1?.title})`)

  // A answers correctly before the secret opening: does not count, the only try is gone, no time left shown
  const tooEarly = waitForMessage(a, /Too early – .*used up.*only your first answer counts/, 4000).then((m) => m.input, () => '')
  a.chat(String(answer))
  const te = await tooEarly
  assert(!!te && !/\d+ s\b/.test(te), `answer before the opening: private, only try used, no seconds left shown (${te})`)
  assertEq((await megaStatus()).phase, 'question', 'too early correct answer does not win')
  // unclear forms with digits: private, do not count
  const markB = b.messages.length
  const markA = a.messages.length
  const unclear = ['5 3 1', '5,3,1', 'i think 54', 'x=4 y=-3']
  for (const t of unclear) {
    const hint = waitForMessage(b, /Not counted – type only the number/, 4000).then(() => true, () => false)
    b.chat(t)
    assert(await hint, `"${t}": private, hint "only the number"`)
    await sleep(350)
  }
  const pub = waitForMessage(a, new RegExp(`${B}.*gg everyone`), 4000).then(() => true, () => false)
  b.chat('gg everyone')
  assert(await pub, 'normal chat without digits stays public')
  await sleep(300)
  for (const t of unclear) assert(!sawChat(a, markA, B, t), `"${t}" does not appear in public chat`)

  await untilOpen(sol)
  // A correct again after the opening: only the first try counts, so ignored with a private hint
  const ignored = waitForMessage(a, /Only your first answer counts – you have already answered/, 4000).then(() => true, () => false)
  a.chat(String(answer))
  assert(await ignored, 'second try (correct) ignored: private hint')
  await sleep(500)
  assertEq((await megaStatus()).phase, 'question', 'second try does not win')
  assert(!sawChat(b, markB, A, String(answer)), 'ignored try does not appear in public chat')
  // B: the first counted try (unclear forms did not count) is correct, exactly one winner
  const winTitleMark = a.titles.length
  const winA = waitForMessage(a, new RegExp(`${B} won the MEGA-CHALLENGE`), 6000).then(() => true, () => false)
  const explain = waitForMessage(a, /Solution » .*sum of divisors/, 8000).then(() => true, () => false)
  b.chat(String(answer))
  assert(await winA, `${B} wins, everyone sees the message`)
  const winTitle = await until(() => a.titles.slice(winTitleMark).find((t) => t.kind === 'subtitle' && new RegExp(`${B} wins \\$20,000\\.00`).test(t.text)), 4000)
  assert(!!winTitle, `winner title to all (${winTitle?.text})`)
  assert(await explain, 'reveal with explanation (prime factors to divisor sum)')
  await sleep(3200)
  const markLate = b.messages.length
  a.chat(String(answer))
  await sleep(1200)
  assert(!since(b, markLate).some((l) => /won the MEGA-CHALLENGE/.test(l)), 'later correct answer: no second winner')
  assertEq([await balance(A), await balance(B)], [0, BONUS_REWARD], 'exactly one payout: $20,000 (bonus) to the winner')
  const st1 = await megaStatus()
  assertEq([st1.phase, st1.payouts, st1.lastWinner, st1.lastAmount, st1.lastBonus], ['idle', nextPayouts(payouts), B, BONUS_REWARD, 'true'],
    'bonus payout noted exactly once')
  payouts = st1.payouts
  assert(!!(await until(() => !megaBar(a), 3000)), 'boss bar removed after the end')

  const kinds = [
    ['trivia health', /health points/],
    ['trivia enchantment', /max levels/],
    ['trivia smelting', /in a furnace\?/],
    ['riddle', /Who or what am I\?/],
    ['unscramble', /Unscramble the Minecraft term/],
    ['math expression', /Calculate: /],
    ['math linear_system', /What is the product/],
    ['math quadratic', /has two integer solutions/],
  ]
  let outsider = null
  for (const [spec, re] of kinds) {
    await setMoney(A, 0)
    await setMoney(B, 0)
    const shown = waitForMessage(b, re, 8000).then(() => true, () => false)
    const out = await rcon(`chatgame mega now ${spec}`)
    assert(/MEGA-CHALLENGE started:/.test(out), `started at once: ${spec} (${out.trim()})`)
    assert(await shown, `${spec}: question in chat`)
    const s = await megaSolution()
    if (spec === 'riddle') {
      // sentence with the solution: private, unrated; short normal chat stays public
      const markS = a.messages.length
      const hint = waitForMessage(b, /Not counted – type only the term/, 4000).then(() => true, () => false)
      b.chat(`is it ${s.solution}?`)
      assert(await hint, 'riddle: sentence with the solution stays private (hint "only the term")')
      const gg = waitForMessage(a, new RegExp(`${B}.*\\blol\\b`), 4000).then(() => true, () => false)
      b.chat('lol')
      assert(await gg, 'riddle: short normal chat stays public')
      await sleep(300)
      assert(!sawChat(a, markS, B, s.solution), 'riddle: solution in a sentence not public')
      if (!outsider) {
        // comes online only after the start: right and wrong answers are treated alike (no oracle)
        outsider = await connectBot('minigaBot3')
        await sleep(800)
        outsider.chat('/chatgame on')
        await sleep(600)
        const markO = a.messages.length
        const n1 = waitForMessage(outsider, /not taking part in this MEGA-CHALLENGE/, 5000).then((m) => m.input, () => '')
        outsider.chat(s.solution)
        const note1 = await n1
        await sleep(10_300)
        const n2 = waitForMessage(outsider, /not taking part in this MEGA-CHALLENGE/, 5000).then((m) => m.input, () => '')
        outsider.chat('Echo Shard' === s.solution ? 'Trident' : 'Echo Shard')
        const note2 = await n2
        assert(!!note1 && note1 === note2, `non-participant: same reply to right and wrong (${note1})`)
        await sleep(400)
        assert(!sawChat(a, markO, 'minigaBot3', s.solution), "non-participant's solution does not appear in chat")
        assertEq((await megaStatus()).phase, 'question', 'question continues')
      }
    }
    await untilOpen(s)
    if (spec === 'math expression') {
      // only the first try counts: A wrong, then right is ignored (private); B wins with the first try
      const wrongA = waitForMessage(a, /Your answer: -?\d+ » ✘ wrong \(only your first answer counts\)/, 4000).then(() => true, () => false)
      a.chat(String(Number(s.solution) + 7))
      assert(await wrongA, `${spec}: wrong first try, private hint "only the first try"`)
      const ign = waitForMessage(a, /Only your first answer counts – you have already answered/, 4000).then(() => true, () => false)
      a.chat(s.solution)
      assert(await ign, `${spec}: correct answer after a wrong one is ignored`)
      await sleep(400)
      assertEq((await megaStatus()).phase, 'question', `${spec}: no win with the second try`)
    }
    let answerText = s.solution
    if (spec === 'trivia enchantment') answerText = s.solution.split('').join(', ') // digit sequence with commas also counts
    const won = waitForMessage(a, new RegExp(`${B} won the MEGA-CHALLENGE`), 6000).then(() => true, () => false)
    const reveal = waitForMessage(a, /Answer: /, 6000).then(() => true, () => false)
    b.chat(answerText)
    assert(await won, `${spec}: correct answer "${answerText}" wins`)
    assert(await reveal, `${spec}: solution revealed`)
    await sleep(3200)
    a.chat(s.solution)
    await sleep(800)
    assertEq([await balance(A), await balance(B)], [0, MEGA_REWARD], `${spec}: exactly one payout`)
    const st = await megaStatus()
    assertEq([st.phase, st.payouts], ['idle', nextPayouts(payouts)], `${spec}: exactly one payout noted`)
    payouts = st.payouts
  }
  if (outsider) { try { outsider.quit() } catch {} }
}

async function megaRaceTests(a, b, payouts) {
  // Races over RCON count only gains from the start (statistic deltas), never inventory: endermen in the
  // overworld (kill credit; only a moving player wins), stone (naturally mined counts, self-placed does not),
  // sprint distance, hostile mobs at night (cannot start by day, sunrise ends it).
  const RX = 4700, RY = 230, RZ = 4700
  await setMoney(A, 0)
  await setMoney(B, 0)
  for (const n of [A, B]) await rcon(`clear ${n}`)
  const home = [[A, a.entity.position.clone()], [B, b.entity.position.clone()]]
  const timeBefore = Number((await rcon('time query daytime')).match(/(\d+)/)?.[1] ?? 1000)
  await rcon(`forceload add ${RX - 8} ${RZ - 8} ${RX + 48} ${RZ + 8}`)
  await rcon(`fill ${RX - 6} ${RY - 1} ${RZ - 6} ${RX + 40} ${RY - 1} ${RZ + 6} minecraft:glass`)
  await rcon(`fill ${RX - 6} ${RY} ${RZ - 6} ${RX + 40} ${RY + 5} ${RZ + 6} minecraft:air`)
  await rcon('weather clear')
  await rcon('time set 1000')
  await rcon(`tp ${A} ${RX + 0.5} ${RY} ${RZ + 0.5} 0 0`)
  await rcon(`tp ${B} ${RX - 3.5} ${RY} ${RZ + 0.5} 0 0`)
  await sleep(2500)
  const summon = (type = 'enderman') => rcon(`summon minecraft:${type} ${RX + 0.5} ${RY} ${RZ + 4.5} {NoAI:1b,Silent:1b,PersistenceRequired:1b,Tags:["megae2e"]}`)
  const progressOf = async (bot, re) => {
    const got = waitForMessage(bot, re, 4000).then((m) => m, () => null)
    bot.chat('/chatgame mega')
    return await got
  }
  try {
    const raceShown = waitForMessage(a, /RACE » Kill 3 Endermen in the Overworld/, 8000).then(() => true, () => false)
    const raceNoHelp = waitForMessage(a, /No external help! .*Only your kills from now on count/, 8000).then(() => true, () => false)
    const raceOut = await rcon('chatgame mega now race endermen')
    assert(/MEGA-CHALLENGE started: Race/.test(raceOut), `race started (${raceOut.trim()})`)
    assert(await raceShown, 'race task in chat')
    assert(await raceNoHelp, 'race: "No external help", only kills from now on')
    const raceBar = await until(() => megaBar(a) && /MEGA RACE/.test(megaBar(a).title) && megaBar(a), 4000)
    assert(!!raceBar, `race boss bar (${raceBar?.title})`)
    assert(!!(await progressOf(a, /Your progress: 0\/3/)), '/chatgame mega shows own progress')
    await summon()
    await rcon('kill @e[type=minecraft:enderman,tag=megae2e]')
    await sleep(600)
    assert(!!(await progressOf(a, /Your progress: 0\/3/)), 'death without kill credit (/kill) does not count')
    for (let i = 0; i < 3; i++) {
      await summon()
      const out = await rcon(`damage @e[type=minecraft:enderman,tag=megae2e,limit=1] 1000 minecraft:player_attack by ${A}`)
      assert(!/error|unknown|No entity/i.test(out), `enderman ${i + 1} killed by ${A} (${out.trim()})`)
      await sleep(300)
    }
    await sleep(1500)
    const idle = waitForMessage(a, /Goal reached – move/, 4000).then(() => true, () => false)
    assert(!!(await progressOf(a, /Your progress: 3\/3/)), '3 endermen with kill credit counted (statistic gain)')
    assert(await idle, 'hint: target reached, but won only after moving (AFK does not count)')
    assertEq((await megaStatus()).phase, 'race', 'target reached, but no win without moving or looking around (AFK)')
    const raceWin = waitForMessage(b, new RegExp(`${A} won the MEGA-CHALLENGE`), 8000).then(() => true, () => false)
    await a.look(Math.PI / 2, 0.4, true)
    assert(await raceWin, `${A} looks around and wins the race`)
    await sleep(1000)
    assertEq([await balance(A), await balance(B)], [MEGA_REWARD, 0], 'race: exactly one payout')
    const st3 = await megaStatus()
    assertEq([st3.phase, st3.payouts, st3.lastWinner], ['idle', nextPayouts(payouts), A], 'race payout noted')
    payouts = st3.payouts

    await rcon(`setblock ${RX + 2} ${RY} ${RZ} minecraft:stone`)
    await rcon(`setblock ${RX + 2} ${RY} ${RZ + 1} minecraft:stone`)
    await rcon(`give ${A} minecraft:iron_pickaxe`)
    await rcon(`give ${A} minecraft:stone 1`)
    await sleep(800)
    const stoneShown = waitForMessage(a, /RACE » Mine 100 Stone or Deepslate/, 8000).then(() => true, () => false)
    const stoneRule = waitForMessage(a, /No external help! .*never your inventory, placed blocks or Veinminer/, 8000).then(() => true, () => false)
    const stoneOut = await rcon('chatgame mega now race stone')
    assert(/started: Race/.test(stoneOut) && await stoneShown, `stone race started (${stoneOut.trim()})`)
    assert(await stoneRule, 'stone race: placed blocks and veinminer do not count')
    const pick = a.inventory.items().find((i) => i.name === 'iron_pickaxe')
    assert(!!pick, 'pickaxe in inventory')
    await a.equip(pick, 'hand')
    await a.dig(a.blockAt(new Vec3(RX + 2, RY, RZ)))
    await a.dig(a.blockAt(new Vec3(RX + 2, RY, RZ + 1)))
    await sleep(500)
    assert(!!(await progressOf(a, /Your progress: 2\/100/)), 'two natural stone blocks counted')
    const stoneItem = a.inventory.items().find((i) => i.name === 'stone')
    assert(!!stoneItem, 'stone to place in the inventory')
    await a.equip(stoneItem, 'hand')
    await a.placeBlock(a.blockAt(new Vec3(RX + 2, RY - 1, RZ)), new Vec3(0, 1, 0)).catch(() => {})
    await sleep(500)
    assertEq(a.blockAt(new Vec3(RX + 2, RY, RZ))?.name, 'stone', 'stone placed by self')
    await a.equip(a.inventory.items().find((i) => i.name === 'iron_pickaxe'), 'hand')
    await a.dig(a.blockAt(new Vec3(RX + 2, RY, RZ)))
    await sleep(600)
    assert(!!(await progressOf(a, /Your progress: 2\/100/)), 'self-placed and mined stone does not count')
    assert(!!(await progressOf(b, /Your progress: 0\/100/)), `${B} without mining: 0`)
    await rcon('chatgame mega stop')
    await rcon(`clear ${A}`)

    await rcon(`tp ${A} ${RX - 5.5} ${RY} ${RZ + 0.5} -90 0`)
    await rcon(`effect give ${A} minecraft:saturation 10 0 true`)
    await sleep(1200)
    const sprintShown = waitForMessage(a, /RACE » Sprint 1,000 blocks/, 8000).then(() => true, () => false)
    const sprintOut = await rcon('chatgame mega now race sprint')
    assert(/started: Race/.test(sprintOut) && await sprintShown, `sprint race started (${sprintOut.trim()})`)
    await a.lookAt(new Vec3(RX + 40, RY + 1.6, RZ + 0.5), true)
    // mineflayer 4.39 sends the wrong action ID for 1.21.11 when sprinting (3 = START_RIDING_JUMP), and the
    // server would see only walking. So send START_SPRINTING (1) and STOP_SPRINTING (2) as a real client does.
    const sprintCmd = (start) => a._client.write('entity_action', { entityId: a.entity.id, actionId: start ? 1 : 2, jumpBoost: 0 })
    sprintCmd(true)
    a.setControlState('forward', true)
    await sleep(3000)
    a.setControlState('forward', false)
    sprintCmd(false)
    await sleep(800)
    const sp = await progressOf(a, /Your progress: (\d+)\/1000/)
    assert(!!sp && Number(sp[1]) >= 5, `sprint distance since the start counted (${sp?.[1]} blocks)`)
    assert(!!(await progressOf(b, /Your progress: 0\/1000/)), `${B} without sprint: 0`)
    await rcon('chatgame mega stop')

    await rcon(`tp ${A} ${RX + 0.5} ${RY} ${RZ + 0.5} 0 0`)
    await rcon('time set 1000')
    const dayOut = await rcon('chatgame mega now race night_hunt')
    assert(/only at night/.test(dayOut), `night hunt refused during the day (${dayOut.trim()})`)
    assertEq((await megaStatus()).phase, 'idle', 'no night race by day')
    await rcon('time set 13000')
    const nightShown = waitForMessage(a, /RACE » Kill 10 hostile mobs before sunrise \(Overworld\)/, 8000).then(() => true, () => false)
    const nightOut = await rcon('chatgame mega now race night_hunt')
    assert(/started: Race/.test(nightOut) && await nightShown, `night hunt started (${nightOut.trim()})`)
    await summon('zombie')
    await rcon(`damage @e[type=minecraft:zombie,tag=megae2e,limit=1] 1000 minecraft:player_attack by ${A}`)
    await summon('cow')
    await rcon(`damage @e[type=minecraft:cow,tag=megae2e,limit=1] 1000 minecraft:player_attack by ${A}`)
    await sleep(800)
    assert(!!(await progressOf(a, /Your progress: 1\/10/)), 'at night: zombie counts, cow (not hostile) does not')
    const sunrise = waitForMessage(a, /The sun has risen – nobody finished the night hunt/, 6000).then(() => true, () => false)
    await rcon('time set 1000')
    assert(await sunrise, 'sunrise ends the night hunt')
    const st4 = await megaStatus()
    assertEq([st4.phase, st4.payouts], ['idle', payouts], 'night hunt without a winner pays nothing')
  } finally {
    a.clearControlStates()
    await rcon('chatgame mega stop').catch(() => {})
    await rcon('kill @e[tag=megae2e]').catch(() => {})
    await rcon(`time set ${timeBefore}`).catch(() => {})
    for (const n of [A, B]) await rcon(`clear ${n}`).catch(() => {})
    for (const [n, pos] of home) await rcon(`tp ${n} ${pos.x} ${pos.y} ${pos.z}`).catch(() => {})
    await sleep(1000)
    await rcon(`fill ${RX - 6} ${RY - 1} ${RZ - 6} ${RX + 40} ${RY + 5} ${RZ + 6} minecraft:air`).catch(() => {})
    await rcon(`kill @e[type=minecraft:item,x=${RX},y=${RY},z=${RZ},distance=..60]`).catch(() => {})
    await rcon(`forceload remove ${RX - 8} ${RZ - 8} ${RX + 48} ${RZ + 8}`).catch(() => {})
  }
}
