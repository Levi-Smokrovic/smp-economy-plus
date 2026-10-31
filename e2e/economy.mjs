// Economy: /balance, /pay (edge cases, pay_receive, offline target with pending summary), /paytoggle, /baltop
// (order, own rank, pages), /eco over RCON, /eco treasury, /treasury against /smpeco treasury, admin commands
// blocked for non-ops.
import {
  connectBot, rcon, assert, assertEq, sleep, runE2E, waitForMessage, treasuryFlows, treasuryDelta, treasuryOtherNet,
} from './lib.mjs'

const A = 'economBot1'
const B = 'economBot2'
const MAX_CENTS = 1_000_000_000_000_000 // Money.MAX ($10T)

// cent amounts below 2^53
function fmt(cents) {
  const neg = cents < 0
  const abs = Math.abs(cents)
  const whole = Math.floor(abs / 100).toLocaleString('en-US')
  return `${neg ? '-' : ''}$${whole}.${String(abs % 100).padStart(2, '0')}`
}

const esc = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')

// error texts from core/Args
const INVALID = /Invalid amount/i
const UNKNOWN_PLAYER = /Player not found/i

async function cents(name) {
  const out = await rcon(`eco get ${name}`)
  const m = out.match(/\((\d+) cents\)/)
  if (!m) throw new Error(`eco get ${name}: ${out}`)
  return Number(m[1])
}

/**
 * Admin entries come from the flows: they do not drop out of the 24 h statistics at the hour change. Claims
 * about the balance leave out background entries of other reasons.
 */
async function treasury() {
  const flows = await treasuryFlows()
  const out = await rcon('smpeco treasury')
  const m = out.match(/balance=(\d+) allowance=(\d+) spentLastHour=(\d+) maxPerHour=(\d+)/)
  if (!m) throw new Error(`smpeco treasury: ${out}`)
  const admin = flows.flows[ADMIN] ?? { income: 0, expense: 0 }
  return {
    balance: Number(m[1]), allowance: Number(m[2]), spent: Number(m[3]), maxPerHour: Number(m[4]),
    adminIn: admin.income, adminOut: admin.expense, flows,
  }
}

const ADMIN = 'admin'

function adminBalanceDelta(before, after) {
  return after.flows.balance - before.flows.balance - treasuryOtherNet(before.flows, after.flows, ADMIN)
}

function otherExpense(before, after) {
  let sum = 0
  for (const r of new Set([...Object.keys(before.flows), ...Object.keys(after.flows)])) {
    if (r !== ADMIN) sum += Math.max(0, treasuryDelta(before, after, r).expense)
  }
  return sum
}

// Vanilla kicks after about 10 chat commands in a row (TickThrottler 20/200), so keep at least 1 s per bot
const lastCommand = new Map()
async function send(bot, command) {
  const wait = (lastCommand.get(bot) ?? 0) + 1050 - Date.now()
  if (wait > 0) await sleep(wait)
  lastCommand.set(bot, Date.now())
  bot.chat(command)
}

async function cmd(bot, command, regex, timeoutMs = 8000) {
  const pending = waitForMessage(bot, regex, timeoutMs).catch(() => null)
  await send(bot, command)
  return pending
}

async function lines(bot, command, until, timeoutMs = 8000) {
  const start = bot.messages.length
  const m = await cmd(bot, command, until, timeoutMs)
  await sleep(400)
  // multi-line replies (baltop, treasury) arrive as one message with line breaks
  return { ok: !!m, lines: bot.messages.slice(start).flatMap((msg) => String(msg).split('\n')) }
}

async function expectUnchanged(label, fn) {
  const before = [await cents(A), await cents(B)]
  await fn()
  await sleep(300)
  assertEq([await cents(A), await cents(B)], before, `${label}: balances unchanged`)
}

runE2E(async () => {
  await rcon(`deop ${A}`)
  await rcon(`deop ${B}`)
  let bot1 = await connectBot(A)
  let bot2 = await connectBot(B)
  assert(true, 'bots connected')

  try {
    const st = await rcon('smpeco selftest economy')
    const sm = st.match(/SUMMARY passed=(\d+) failed=(\d+)/)
    assert(!!sm && Number(sm[1]) >= 9 && Number(sm[2]) === 0, `economy selftests green (${(sm?.[0] ?? st).slice(0, 300)})`)

    const setA = await rcon(`eco set ${A} 1000`)
    assert(/Set economBot1's balance to \$1,000\.00/.test(setA), `eco set (${setA.trim()})`)
    await rcon(`eco set ${B} 0`)
    assertEq(await cents(A), 100000, 'eco get returns cents')
    assert(!!(await cmd(bot2, '/paytoggle on', /Receive payments: on/)), '/paytoggle on')

    const bal = await cmd(bot1, '/balance', /Balance: \$([\d,]+\.\d\d)/)
    assertEq(bal?.[1], '1,000.00', '/balance shows own balance')
    assert(!!(await cmd(bot1, '/bal', /Balance: \$1,000\.00/)), '/bal (Alias)')
    assert(!!(await cmd(bot1, '/money economBot2', /economBot2's balance: \$0\.00/)), '/money <player> (alias, other player)')
    assert(/economBot1's balance: \$1,000\.00/.test(await rcon(`balance ${A}`)), '/balance <player> from the console')
    assert(/Players only/.test(await rcon('balance')), '/balance without player from console gives a hint')
    assert(/Treasury isn't a player/.test(await rcon('balance Treasury')), '/balance Treasury points to /treasury')
  
    {
      const gotB = waitForMessage(bot2, /economBot1 sent you \$250\.00/, 8000).then(() => true, () => false)
      const okA = await cmd(bot1, '/pay economBot2 250', /You sent economBot2 \$250\.00\. \(Balance: \$750\.00\)/)
      assert(!!okA, '/pay: confirmation with new balance')
      assert(await gotB, '/pay: online recipient notified')
      assertEq([await cents(A), await cents(B)], [75000, 25000], '/pay: money moved')
      assert(!!(await cmd(bot1, '/pay ECONOMBOT2 1.5', /You sent economBot2 \$1\.50/)), '/pay: name ignoring case, amount 1.5')
      assertEq([await cents(A), await cents(B)], [74850, 25150], '/pay: $1.50 moved')
    }

    // edge cases: nothing may move
    await expectUnchanged('/pay edge cases', async () => {
      assert(!!(await cmd(bot1, '/pay economBot1 5', /can't send money to yourself/)), '/pay to self refused')
      assert(!!(await cmd(bot1, '/pay economBot2 0', INVALID)), '/pay 0 refused')
      assert(!!(await cmd(bot1, '/pay economBot2 -5', INVALID)), '/pay negative refused')
      assert(!!(await cmd(bot1, '/pay economBot2 1.000', INVALID)), '/pay 1.000 (thousands separator) refused')
      assert(!!(await cmd(bot1, '/pay economBot2 999999', /don't have enough money/)), '/pay without funds refused')
      assert(!!(await cmd(bot1, '/pay NobodyXyz42 5', UNKNOWN_PLAYER)), '/pay to unknown refused')
      assert(!!(await cmd(bot1, '/pay Treasury 5', /Treasury isn't a player/)), '/pay to the treasury refused')
    })
    await rcon(`eco set ${B} 10t`)
    assertEq(await cents(B), MAX_CENTS, 'recipient set to the account limit')
    assert(!!(await cmd(bot1, '/pay economBot2 1', /economBot2's balance is full/)), '/pay to a full account refused (overflow)')
    assertEq(await cents(A), 74850, 'overflow: sender unchanged')
    await rcon(`eco set ${B} 251.50`)

    assert(!!(await cmd(bot2, '/paytoggle off', /Receive payments: off/)), '/paytoggle off')
    await expectUnchanged('pay_receive off', async () => {
      assert(!!(await cmd(bot1, '/pay economBot2 10', /economBot2 isn't accepting payments/)), '/pay to player with pay_receive off refused')
    })
    assert(!!(await cmd(bot2, '/paytoggle', /Receive payments: on/)), '/paytoggle switches it on')
    assert(!!(await cmd(bot1, '/pay economBot2 10', /You sent economBot2 \$10\.00/)), '/pay after pay_receive on')
    assertEq([await cents(A), await cents(B)], [73850, 26150], 'after pay_receive on: money moved')

    const t0 = await treasury()
    await expectUnchanged('/eco as non-op', async () => {
      await send(bot1, '/eco give economBot1 1000')
      await send(bot1, '/eco set economBot2 0')
      await sleep(800)
    })
    await send(bot1, '/eco treasury withdraw 1')
    await sleep(800)
    assertEq((await treasury()).adminOut, t0.adminOut, '/eco treasury as non-op has no effect')

    bot2.quit()
    await sleep(1500)
    assert(!!(await cmd(bot1, '/pay economBot2 50', /You sent economBot2 \$50\.00.*offline/)), '/pay to offline player (name cache)')
    assert(!!(await cmd(bot1, '/pay economBot2 25', /You sent economBot2 \$25\.00.*offline/)), '/pay second time offline')
    assertEq([await cents(A), await cents(B)], [66350, 33650], 'offline payments booked')
    bot2 = await connectBot(B)
    const summary = await waitForMessage(bot2, /economBot1 sent you \$75\.00 \(2 payments\)/, 12000, { includePast: true }).then(() => true, () => false)
    assert(summary, 'offline payments delivered on join as one line')
    await sleep(2500)
    assertEq(bot2.messages.filter((m) => /economBot1 sent you/.test(m)).length, 1, 'summary delivered exactly once')
    lastCommand.delete(bot2)

    await rcon(`eco set ${A} 9t`)
    await rcon(`eco set ${B} 8t`)
    {
      const r = await lines(bot1, '/baltop', /Your rank: #\d+/)
      const text = r.lines.join('\n')
      assert(r.ok, '/baltop responds')
      assert(/Baltop . Page 1\/\d+/.test(text), `/baltop header (${r.lines[0] ?? ''})`)
      assert(new RegExp(`#1 ${A} » ${esc(fmt(9e14))}`).test(text), '/baltop #1 = economBot1 ($9T)')
      assert(new RegExp(`#2 ${B} » ${esc(fmt(8e14))}`).test(text), '/baltop #2 = economBot2 ($8T)')
      assert(/Your rank: #1 /.test(text), '/baltop own rank #1')
      const i1 = r.lines.findIndex((l) => l.includes(`#1 ${A}`))
      const i2 = r.lines.findIndex((l) => l.includes(`#2 ${B}`))
      assert(i1 >= 0 && i2 > i1, '/baltop sorted descending')
    }
    assert(!!(await cmd(bot2, '/baltop', /Your rank: #2 /)), '/baltop own rank #2 for economBot2')
    {
      const r = await lines(bot1, '/baltop 999', /Your rank: #\d+/)
      const h = r.lines.join('\n').match(/Page (\d+)\/(\d+)/)
      assert(!!h && h[1] === h[2], `/baltop 999 gives the last page (${h?.[0]})`)
      assert(/Your rank: #1 /.test(r.lines.join('\n')), '/baltop: own rank on every page')
    }
    const consoleTop = await rcon('baltop')
    assert(new RegExp(`#1 ${A}`).test(consoleTop) && !/Your rank/.test(consoleTop), '/baltop from console (no own rank)')

    await rcon(`eco set ${A} 100`)
    {
      const gotGive = waitForMessage(bot1, /You received \$25\.00/, 8000).then(() => true, () => false)
      const out = await rcon(`eco give ${A} 25`)
      assert(/economBot1 received \$25\.00/.test(out), `eco give (${out.trim()})`)
      assert(await gotGive, 'eco give: player notified')
      assertEq(await cents(A), 12500, 'eco give booked')
    }
    assert(/Took \$5\.00 from economBot1/.test(await rcon(`eco take ${A} 5`)), 'eco take')
    assertEq(await cents(A), 12000, 'eco take booked')
    assert(/only has \$120\.00/.test(await rcon(`eco take ${A} 1000`)), 'eco take more than available refused')
    assertEq(await cents(A), 12000, 'eco take error: unchanged')
    assert(INVALID.test(await rcon(`eco give ${A} 0`)), 'eco give 0 refused')
    assert(INVALID.test(await rcon(`eco set ${A} -5`)), 'eco set negative refused')
    await rcon(`eco set ${A} 10t`)
    assert(/Balance limit/.test(await rcon(`eco give ${A} 0.01`)), 'eco give above account limit refused')
    assertEq(await cents(A), MAX_CENTS, 'account limit: unchanged')
    assert(/balance to \$0\.00/.test(await rcon(`eco set ${A} 0`)), 'eco set 0')
    assert(/starting balance/.test(await rcon(`eco reset ${A}`)), 'eco reset gives starting balance')
    assert(/Treasury isn't a player/.test(await rcon('eco give Treasury 5')), 'eco give to the treasury refused')
    assert(UNKNOWN_PLAYER.test(await rcon('eco give NobodyXyz42 5')), 'eco on unknown refused')

    {
      const before = await treasury()
      const show = await rcon('eco treasury')
      assert(/Balance: \$/.test(show) && /NPC budget per hour/.test(show) && /All time/.test(show), '/eco treasury shows statistics')
      const dep = await rcon('eco treasury deposit 100')
      assert(/deposited \$100\.00/.test(dep), `eco treasury deposit (${dep.trim()})`)
      const mid = await treasury()
      assertEq(mid.adminIn - before.adminIn, 10000, 'deposit booked with reason "admin"')
      assertEq(adminBalanceDelta(before, mid), 10000, 'treasury +$100 (without background entries of other reasons)')
      const wd = await rcon('eco treasury withdraw 100')
      assert(/withdrew \$100\.00/.test(wd), `eco treasury withdraw (${wd.trim()})`)
      const after = await treasury()
      const post = await treasuryFlows()
      assertEq(after.adminOut - mid.adminOut, 10000, 'withdrawal booked with reason "admin"')
      assertEq(adminBalanceDelta(before, after), 0, 'treasury back as before (without background entries of other reasons)')
      // spentLastHour may only grow by capped NPC spending of other reasons (mid.flows before, post after both
      // states)
      const npcSpent = otherExpense(mid.flows, post)
      assert(after.spent - mid.spent <= npcSpent,
        `admin withdrawal does not count toward the NPC hourly cap (spentLastHour +${after.spent - mid.spent}, NPC spending ${npcSpent})`)
      const tooMuch = await rcon('eco treasury withdraw 10t')
      assert(/Treasury only has/.test(tooMuch), 'withdrawal above the treasury refused')
      const failed = await treasury()
      assertEq([failed.adminIn, failed.adminOut], [after.adminIn, after.adminOut], 'failure: no admin entry')
      assertEq(adminBalanceDelta(after, failed), 0, 'failure: treasury unchanged (without background entries of other reasons)')
      assert(INVALID.test(await rcon('eco treasury deposit 0')), 'eco treasury deposit 0 refused')
    }
    {
      const before = await treasury()
      const r = await lines(bot1, '/treasury', /Last 24h/)
      const after = await treasury()
      const text = r.lines.join('\n')
      assert(r.ok, '/treasury responds')
      const kb = text.match(/Balance: (\$[\d,]+\.\d\d)/)
      assert(!!kb && (kb[1] === fmt(before.balance) || kb[1] === fmt(after.balance)), `/treasury balance = smpeco treasury (${kb?.[1]} / ${fmt(before.balance)})`)
      const mb = text.match(/NPC budget per hour: (\$[\d,]+\.\d\d) \((\d+(?:\.\d)?)% of the Treasury\)/)
      assert(!!mb && (mb[1] === fmt(before.maxPerHour) || mb[1] === fmt(after.maxPerHour)), `/treasury shows Treasury.maxSpendPerHour (${mb?.[1]} / ${fmt(before.maxPerHour)})`)
      const free = text.match(/left: (\$[\d,]+\.\d\d)/)
      assert(!!free && (free[1] === fmt(before.allowance) || free[1] === fmt(after.allowance)), `/treasury shows free budget (${free?.[1]})`)
      assert(!/balance=/.test(text), '/treasury without admin diagnostics')
    }
  } finally {
    await rcon(`eco reset ${A}`).catch(() => {})
    await rcon(`eco reset ${B}`).catch(() => {})
  }
}, { timeoutMs: 240000 })
