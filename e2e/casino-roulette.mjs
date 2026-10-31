// Roulette: selftests, /casino menu entry, /roulette table (chip choice, placing and removing chips, shift
// clicks, spin with animation, result), rounds with chips left in place, table limit (straight above the
// treasury's coverage), closing and disconnecting during the spin (paid exactly once). Money conservation per
// round: balance = before - stake + payout, the treasury books exactly that under "casino", and the payout
// matches the number (own calculation, 35:1 / 2:1 / 1:1 minus a 1 % house fee, rounded down; RTP 36/37 x 0.99,
// about 96.3 %).
import {
  connectBot, rcon, assert, assertEq, sleep, runE2E, waitForMessage, clickSlot, itemLabel, itemLore,
  windowTitle, expectWindow, treasuryFlows, cents, closeWindow, kv,
} from './lib.mjs'

/** CasinoMenus.SLOT_ROULETTE */
const HUB_SLOT = 13
const BOT = 'BotRouletteA'
const S = { zero: 36, low: 37, even: 38, red: 39, black: 40, odd: 41, high: 42, chip: 43, spin: 44, back: 45, dozen1: 46, column1: 49, clear: 52, info: 53, pointer: 4, center: 13 }
const numSlot = (n) => (n === 0 ? S.zero : n - 1)
const RED = new Set([1, 3, 5, 7, 9, 12, 14, 16, 18, 19, 21, 23, 25, 27, 30, 32, 34, 36])
const CHIPS = [100, 500, 1000, 5000, 10000, 50000, 100000, 500000, 1000000, 5000000]

async function info() {
  const line = await rcon(`smpeco casino info ${BOT}`)
  const v = kv(line)
  if (v.balance === undefined) throw new Error(`smpeco casino info: ${line}`)
  const f = (await treasuryFlows()).flows.casino ?? { income: 0, expense: 0 }
  v.casinoIn = f.income
  v.casinoOut = f.expense
  return v
}

const dollars = (c) => `${c < 0 ? '-' : ''}$${(Math.abs(c) / 100).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`

// 1 % house fee on every payout, rounded down: floor(gross * 99 / 100).
const afterFee = (gross) => (gross <= 0 ? 0 : gross - Math.ceil(gross / 100))

/**
 * Payout (stake included, after the fee) of a table { spotSlot: cents } on number n, calculated independently
 * of the server.
 */
function payout(table, n) {
  let sum = 0
  for (const [slotStr, amount] of Object.entries(table)) {
    const slot = Number(slotStr)
    let mult = 0
    if (slot < 36 || slot === S.zero) mult = numSlot(n) === slot ? 36 : 0
    else if (n === 0) mult = 0
    else if (slot === S.red) mult = RED.has(n) ? 2 : 0
    else if (slot === S.black) mult = RED.has(n) ? 0 : 2
    else if (slot === S.even) mult = n % 2 === 0 ? 2 : 0
    else if (slot === S.odd) mult = n % 2 === 1 ? 2 : 0
    else if (slot === S.low) mult = n <= 18 ? 2 : 0
    else if (slot === S.high) mult = n >= 19 ? 2 : 0
    else if (slot >= S.dozen1 && slot < S.dozen1 + 3) mult = Math.floor((n - 1) / 12) === slot - S.dozen1 ? 3 : 0
    else if (slot >= S.column1 && slot < S.column1 + 3) mult = (n - 1) % 3 === slot - S.column1 ? 3 : 0
    sum += mult * amount
  }
  return afterFee(sum)
}

const total = (table) => Object.values(table).reduce((a, b) => a + b, 0)
const slotLabel = (bot, slot) => itemLabel(bot.currentWindow?.slots?.[slot])

async function waitSlot(bot, slot, regex, timeoutMs = 8000) {
  const end = Date.now() + timeoutMs
  while (Date.now() < end) {
    const item = bot.currentWindow?.slots?.[slot]
    if (item && regex.test(itemLabel(item))) return item
    await sleep(100)
  }
  return null
}

// left = bigger, right = smaller
async function selectChip(bot, value) {
  for (let i = 0; i < CHIPS.length + 2; i++) {
    const cur = cents(slotLabel(bot, S.chip))
    if (cur === value) return true
    await clickSlot(bot, S.chip, cur < value ? 0 : 1)
    await sleep(200)
  }
  return cents(slotLabel(bot, S.chip)) === value
}

/** Settle line "Roulette: … (17 Black[, window closed])", read through the number in parentheses. */
function settleLine(bot, timeoutMs = 12000, opts = {}) {
  return waitForMessage(bot, /Roulette: .*\((\d+) (Red|Black|Green)(, window closed)?\)/, timeoutMs, opts)
    .then((m) => ({ n: Number(m[1]), color: m[2], closed: !!m[3] }))
}

function checkMoney(label, before, after, stake, paid) {
  assertEq(after.balance, before.balance - stake + paid, `${label}: balance = before - stake + payout`)
  assertEq([after.casinoIn - before.casinoIn, after.casinoOut - before.casinoOut], [stake, paid], `${label}: treasury books stake/payout (reason casino)`)
  assertEq(after.open, 'none', `${label}: no open round`)
}

runE2E(async () => {
  const st = await rcon('smpeco selftest casino-roulette')
  const sm = st.match(/SUMMARY passed=(\d+) failed=(\d+)/)
  assert(!!sm, `selftest summary present (${st.slice(0, 300)})`)
  if (sm) {
    assert(Number(sm[1]) >= 8, `Roulette selftests ran (passed=${sm[1]})`)
    assertEq(Number(sm[2]), 0, `Roulette-Selftests failed=0 ${Number(sm[2]) ? st.slice(0, 1500) : ''}`)
  }

  let bot = await connectBot(BOT)
  await sleep(1500)
  await rcon(`smpeco casino settle ${BOT}`)
  await rcon(`smpeco casino resetlimit ${BOT}`)
  await rcon(`eco set ${BOT} 1000`)
  await sleep(300)
  const start = await info()
  assertEq(start.balance, 100000, 'starting balance $1000')
  assert(start.maxBet >= 5000, `max bet allows $50 tables (maxBet=${start.maxBet}, treasury=${start.treasury})`)

  try {
    let win = await expectWindow(bot, () => bot.chat('/casino'))
    assert(/Casino/.test(windowTitle(win)), `casino menu open (${windowTitle(win)})`)
    assertEq(itemLabel(win.slots[HUB_SLOT]), 'Roulette', `Roulette button in the casino menu (slot ${HUB_SLOT})`)
    assert(itemLore(win.slots[HUB_SLOT]).some((l) => /35:1/.test(l)), 'button names 35:1')
    assert(itemLore(win.slots[HUB_SLOT]).some((l) => /RTP 96\.3/.test(l)), 'button names RTP 96.3 %')
    win = await expectWindow(bot, () => clickSlot(bot, HUB_SLOT))
    assert(/Roulette/.test(windowTitle(win)), `Roulette table open (${windowTitle(win)})`)
    const hint = await waitForMessage(bot, /left-click .*fields/i, 3000, { includePast: true }).then(() => true, () => false)
    assert(hint, 'help line on first open')

    assertEq(slotLabel(bot, 0), '1 Red', 'Slot 0 = 1 Red')
    assertEq(win.slots[0]?.name, 'red_concrete', '1 is red')
    assertEq(slotLabel(bot, 16), '17 Black', 'Slot 16 = 17 Black')
    assertEq(win.slots[16]?.count, 17, 'count shows the number 17')
    assertEq(slotLabel(bot, S.zero), '0 Green', 'Null')
    assertEq(slotLabel(bot, S.red), 'Red', 'red without chips')
    assertEq(slotLabel(bot, S.dozen1 + 1), '2nd 12', '2nd dozen')
    assertEq(slotLabel(bot, S.column1), 'Column 1', 'Column 1')
    assert(itemLore(win.slots[S.info]).some((l) => /Number 35:1/.test(l)), 'rules name 35:1')
    assert(itemLore(win.slots[S.info]).some((l) => /RTP 96\.3/.test(l)), 'rules name RTP 96.3 %')
    assert(itemLore(win.slots[S.info]).some((l) => /1 % of every payout/.test(l)), 'rules name the 1 % fee')
    assertEq(slotLabel(bot, S.spin), 'Place your chips', 'spin blocked without chips')

    assert(await selectChip(bot, 500), 'chip $5 chosen (right = smaller)')
    assert(await selectChip(bot, 1000), 'chip $10 chosen (left = bigger)')
    assertEq(slotLabel(bot, S.chip), 'Chip: $10.00', 'chip display $10')
    await clickSlot(bot, S.red)
    await clickSlot(bot, numSlot(17))
    await clickSlot(bot, S.dozen1 + 1)
    await sleep(150)
    await clickSlot(bot, S.red) // second chip on red, at least 300 ms later
    await sleep(150)
    assertEq(slotLabel(bot, S.red), 'Red · $20.00', 'two chips on red')
    await clickSlot(bot, S.red, 1)
    await sleep(150)
    assertEq(slotLabel(bot, S.red), 'Red · $10.00', 'right click takes a chip back')
    await clickSlot(bot, S.odd, 0, 1)
    await sleep(150)
    assertEq(slotLabel(bot, S.odd), 'Odd · $50.00', 'shift+left places 5 chips')
    await clickSlot(bot, S.odd, 1, 1)
    await sleep(150)
    assertEq(slotLabel(bot, S.odd), 'Odd', 'shift + right takes all chips')
    assertEq(slotLabel(bot, numSlot(17)), '17 Black · $10.00', '$10 on 17')
    assertEq(slotLabel(bot, S.dozen1 + 1), '2nd 12 · $10.00', '$10 on 2nd dozen')
    assertEq(slotLabel(bot, S.spin), 'SPIN ($30.00)', 'spin button shows the total stake')
    assert(itemLore(bot.currentWindow.slots[S.spin]).some((l) => /Best case: \$386\.10/.test(l)), 'best case $386.10 (17 black: straight + dozen, $390 - 1 %)')
    assert(itemLore(bot.currentWindow.slots[numSlot(17)]).some((l) => /wins \$356\.40/.test(l)), 'number 17: $10 → wins $356.40 ($360 − 1 %)')
    const after0 = await info()
    assertEq(after0.balance, start.balance, 'placing chips charges nothing yet')
    const table = { [S.red]: 1000, [numSlot(17)]: 1000, [S.dozen1 + 1]: 1000 }

    let wins = 0
    for (let round = 1; round <= 4; round++) {
      await waitSlot(bot, S.spin, /^SPIN/, 8000)
      const before = await info()
      const line = settleLine(bot)
      await clickSlot(bot, S.spin)
      const mid = await info()
      assertEq(mid.open, 'roulette:decided', `round ${round}: result decided before the animation`)
      assertEq(mid.balance, before.balance - total(table), `round ${round}: stake taken before the animation`)
      assert(/Spinning/.test(slotLabel(bot, S.spin)), `round ${round}: wheel spins`)
      const pointer = await waitSlot(bot, S.pointer, /WIN|No win/, 8000)
      assert(!!pointer, `round ${round}: ball landed`)
      const center = slotLabel(bot, S.center)
      const m = center.match(/^(\d+) (Red|Black|Green)$/)
      assert(!!m, `round ${round}: result in the middle (${center})`)
      const n = Number(m?.[1])
      const expected = payout(table, n)
      const msg = await line
      assertEq(msg.n, n, `round ${round}: chat line names the same number`)
      if (expected > 0) assertEq(cents(itemLabel(pointer)), expected, `round ${round}: display WIN ${dollars(expected)}`)
      else assert(/No win/.test(itemLabel(pointer)), `round ${round}: display "No win"`)
      await sleep(300)
      const after = await info()
      checkMoney(`round ${round} (${n}, ${expected ? 'win ' + dollars(expected) : 'lost'})`, before, after, total(table), expected)
      assertEq(after.rounds, before.rounds + 1, `round ${round}: stats +1`)
      if (expected > 0) wins++
      assert(!!(await waitSlot(bot, S.spin, /^SPIN \(\$30\.00\)/, 6000)), `round ${round}: back at the table, chips still there`)
      assertEq(slotLabel(bot, numSlot(17)), '17 Black · $10.00', `round ${round}: chip on 17 still there`)
    }
    console.log(`[E2E] INFO Roulette: ${wins}/4 rounds won`)

    {
      const before = await info()
      const line = settleLine(bot)
      await clickSlot(bot, S.spin)
      await sleep(400)
      await closeWindow(bot)
      const msg = await line
      assert(msg.closed, 'close during the spin: settled with "window closed"')
      await sleep(500)
      const after = await info()
      checkMoney(`close during the spin (${msg.n})`, before, after, total(table), payout(table, msg.n))
      await sleep(5500) // longer than the 5 s watchdog: nothing may follow
      const later = await info()
      assertEq([later.balance, later.casinoIn, later.casinoOut], [after.balance, after.casinoIn, after.casinoOut], 'no second settlement (watchdog)')
    }

    win = await expectWindow(bot, () => bot.chat('/roulette'))
    assertEq(slotLabel(bot, S.red), 'Red · $10.00', 'table remembered after reopening')
    assert(itemLore(win.slots[S.info]).some((l) => /Last numbers:/.test(l)), 'rules show the last numbers')
    await clickSlot(bot, S.clear)
    await sleep(150)
    assertEq(slotLabel(bot, S.red), 'Red', 'clear table')
    assertEq(slotLabel(bot, S.spin), 'Place your chips', 'empty table: spin blocked')
    {
      const lim = await info()
      // several chips up to above the coverage (affordable), otherwise a single chip whose x36 is already
      // uncovered (coverage is checked before the balance)
      const chip = [...CHIPS].reverse().find((c) => c * 3 <= Math.min(lim.maxBet, lim.balance) && afterFee(c * 3 * 36) > lim.cover)
        ?? CHIPS.find((c) => afterFee(c * 36) > lim.cover && c <= lim.maxBet)
      if (chip && afterFee(chip * 36) > lim.cover) {
        assert(await selectChip(bot, chip), `chip ${dollars(chip)} for the limit test`)
        let placed = 0
        let rejected = null
        for (let k = 1; k <= 8 && !rejected; k++) {
          const next = (placed + 1) * chip
          const msg = waitForMessage(bot, /Table limit|treasury can cover|Not enough money|loss limit/, 1200).then((m) => m[0], () => null)
          await clickSlot(bot, numSlot(7))
          await sleep(200)
          const got = await msg
          if (got) {
            rejected = got
            if (next <= lim.maxBet) assert(/treasury can cover/.test(got), `straight ${dollars(next)} (x36 - 1 % = ${dollars(afterFee(next * 36))} > ${dollars(lim.cover)}) refused: ${got}`)
            else assert(/Table limit/.test(got), `total stake above the max bet refused: ${got}`)
          } else {
            placed++
          }
          await sleep(150)
        }
        assert(!!rejected, 'table limit applies')
        assertEq(cents(slotLabel(bot, numSlot(7))) || 0, placed * chip, 'refused chip not on the table')
        assert(afterFee(placed * chip * 36) <= lim.cover, 'placed straight stays covered')
        await clickSlot(bot, S.clear)
        await sleep(150)
      } else {
        assert(true, `limit test skipped (maxBet=${lim.maxBet}, cover=${lim.cover})`)
      }
    }
    // right click on "clear table" doubles
    assert(await selectChip(bot, 1000), 'Chip $10')
    await clickSlot(bot, S.even)
    await sleep(150)
    await clickSlot(bot, S.clear, 1)
    await sleep(150)
    assertEq(slotLabel(bot, S.even), 'Even · $20.00', 'right click on clear doubles all stakes')

    win = await expectWindow(bot, () => clickSlot(bot, S.back))
    assert(/Casino/.test(windowTitle(win)), `back opens /casino (${windowTitle(win)})`)
    await closeWindow(bot)

    const help = waitForMessage(bot, /Payouts: number 35:1/, 4000).then(() => true, () => false)
    bot.chat('/roulette help')
    assert(await help, '/roulette help names the odds')

    {
      win = await expectWindow(bot, () => bot.chat('/roulette'))
      assertEq(slotLabel(bot, S.even), 'Even · $20.00', 'table remembered')
      const before = await info()
      await clickSlot(bot, S.spin)
      bot.quit()
      await sleep(2500)
      const after = await info()
      assertEq(after.open, 'none', 'disconnect: round settled')
      bot = await connectBot(BOT)
      const msg = await settleLine(bot, 10000, { includePast: true }).catch(() => null)
      assert(!!msg && msg.closed, 'result delivered after reconnecting (window closed)')
      if (msg) checkMoney(`disconnect during the spin (${msg.n})`, before, after, 2000, msg.n % 2 === 0 && msg.n !== 0 ? afterFee(4000) : 0)
    }

    const end = await info()
    assertEq((end.balance - start.balance) + ((end.casinoIn - start.casinoIn) - (end.casinoOut - start.casinoOut)), 0,
      `conservation: player ${dollars(end.balance - start.balance)} + treasury (casino) = 0`)
    assertEq(end.pending, 0, 'no pending payouts')
  } finally {
    try { await closeWindow(bot) } catch {}
    await rcon(`smpeco casino settle ${BOT}`)
    await rcon(`smpeco casino resetlimit ${BOT}`)
  }
}, { timeoutMs: 240000 })
