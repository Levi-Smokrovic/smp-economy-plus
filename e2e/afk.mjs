// AFK zone: /afkzone as an admin bot, countdown in the action bar, reward (money and key) after a full
// interval, at most one per interval; leaving on foot, reconnecting and changing the zone reset the timer; the
// IP rule (both bots share an IP); non-admins change nothing. The old afk.json is restored at the end.
import { connectBot, rcon, runE2E, assert, assertEq, sleep, waitForMessage, stripColors, Vec3 } from './lib.mjs'

const A = 'afkBot1'
const B = 'afkBot2'
const X = 4400, Y = 200, Z = 4400
const INTERVAL = 6 // seconds (minimum 5)
const REWARD = 5000 // cents ($50)
const INSIDE = [X - 4 + 0.5, Y, Z + 0.5]
const OUTSIDE = [X + 4 + 0.5, Y, Z + 0.5]

const INFO_RE = /afk active=(\w+) pos1=(\S+) pos2=(\S+) interval=(\d+) reward=(\d+) keychance=(\S+) daylimit=(\d+) treasury=(\w+) padding=(\d+) inside=(\d+) ipmax=(\d+)/
const STATUS_RE = /afk status name=(\S+) online=(\w+) inside=(\w+) seconds=(\d+) interval=(\d+) today=(\d+) rewards=(\d+) earned=(\d+) keys=(\d+) balance=(\d+)/

async function info() {
  const out = await rcon('afkzone info')
  const m = out.match(INFO_RE)
  if (!m) throw new Error(`afkzone info not readable: ${out.slice(0, 300)}`)
  return {
    active: m[1] === 'true', pos1: m[2], pos2: m[3], interval: Number(m[4]), reward: Number(m[5]),
    keychance: m[6], daylimit: Number(m[7]), treasury: m[8] === 'true', inside: Number(m[10]), ipmax: Number(m[11]), raw: out,
  }
}

async function status(name) {
  const out = await rcon(`afkzone status ${name}`)
  const m = out.match(STATUS_RE)
  if (!m) throw new Error(`afkzone status ${name} not readable: ${out.slice(0, 300)}`)
  return {
    online: m[2] === 'true', inside: m[3] === 'true', seconds: Number(m[4]), today: Number(m[6]),
    rewards: Number(m[7]), keys: Number(m[9]), balance: Number(m[10]),
  }
}

async function waitStatus(name, pred, timeoutMs = 5000) {
  const end = Date.now() + timeoutMs
  let s = await status(name)
  while (!pred(s) && Date.now() < end) {
    await sleep(250)
    s = await status(name)
  }
  return s
}

async function command(bot, text, regex, timeoutMs = 5000) {
  const pending = waitForMessage(bot, regex, timeoutMs)
  bot.chat(text)
  return pending
}

async function tp(name, [x, y, z]) {
  await rcon(`tp ${name} ${x} ${y} ${z}`)
  await sleep(400)
}

function collectActionBar(bot) {
  const bars = []
  bot.on('actionBar', (msg) => bars.push(stripColors(msg.toString())))
  return bars
}

function collectRewards(bot) {
  const list = []
  bot.on('messagestr', (msg, position) => {
    if (position === 'game_info') return
    if (/AFK reward: /.test(stripColors(msg))) list.push(Date.now())
  })
  return list
}

async function waitBar(bars, regex, timeoutMs = 3000, from = 0) {
  const end = Date.now() + timeoutMs
  while (Date.now() < end) {
    if (bars.slice(from).some((t) => regex.test(t))) return true
    await sleep(100)
  }
  return bars.slice(from).some((t) => regex.test(t))
}

function money(cents) {
  return (cents / 100).toFixed(2)
}

async function restore(before) {
  await rcon('afkzone clear')
  for (const [key, value] of [['pos1', before.pos1], ['pos2', before.pos2]]) {
    if (value === 'none') continue
    const [dim, x, y, z] = value.split(',')
    await rcon(`afkzone ${key} ${x} ${y} ${z} ${dim}`)
  }
  await rcon(`afkzone set interval ${before.interval}`)
  await rcon(`afkzone set reward ${money(before.reward)}`)
  await rcon(`afkzone set keychance ${before.keychance}`)
  await rcon(`afkzone set daylimit ${before.daylimit}`)
  await rcon(`afkzone set iplimit ${before.ipmax}`)
}

runE2E(async () => {
  const before = await info()
  assert(true, `afkzone info readable (before active=${before.active} interval=${before.interval})`)

  const a = await connectBot(A)
  let b = await connectBot(B)
  assert(true, 'bots connected')
  try {
    await rcon(`deop ${B}`)
    await rcon(`op ${A}`)
    await rcon(`gamemode creative ${A}`)
    await rcon(`gamemode creative ${B}`)
    await rcon(`forceload add ${X - 8} ${Z - 3} ${X + 8} ${Z + 3}`)
    await sleep(500)
    // closed glass room (no mobs, no falling): inside x X-7..X+7, y Y..Y+2, z Z-2..Z+2
    await rcon(`fill ${X - 8} ${Y - 1} ${Z - 3} ${X + 8} ${Y + 3} ${Z + 3} minecraft:glass`)
    await rcon(`fill ${X - 7} ${Y} ${Z - 2} ${X + 7} ${Y + 2} ${Z + 2} minecraft:air`)
    await tp(A, OUTSIDE)
    await tp(B, OUTSIDE)
    await sleep(300)

    const cleared = await command(a, '/afkzone clear', /AFK zone removed|No AFK zone was set/)
    assert(!!cleared, 'Admin: /afkzone clear')
    assertEq((await info()).active, false, 'info: no zone active')
    const noZone = await command(b, '/afkzone', /no AFK zone|AFK zone/)
    assert(/no AFK zone/.test(noZone[0]), 'player: /afkzone reports "no AFK zone"')

    b.chat('/afkzone pos1')
    b.chat('/afkzone set interval 5')
    await sleep(800)
    const afterB = await info()
    assertEq([afterB.pos1, afterB.interval], ['none', before.interval], 'non-admin cannot use pos1/set')
    assert(!b.messages.some((m) => /Pos1 set/.test(stripColors(m))), 'non-admin gets no confirmation')

    await tp(A, [X - 6 + 0.5, Y, Z - 2 + 0.5])
    const p1 = await command(a, '/afkzone pos1', /Pos1 set: (-?\d+) (-?\d+) (-?\d+)/)
    assertEq(p1.slice(1, 4).map(Number), [X - 6, Y, Z - 2], 'pos1 = own block position')
    await tp(A, [X - 2 + 0.5, Y, Z + 2 + 0.5])
    const activePending = waitForMessage(a, /AFK zone active: (\d+)×(\d+)×(\d+)/, 5000)
    const p2 = await command(a, '/afkzone pos2 ~ ~1 ~', /Pos2 set: (-?\d+) (-?\d+) (-?\d+)/)
    assertEq(p2.slice(1, 4).map(Number), [X - 2, Y + 1, Z + 2], 'pos2 with relative coordinates')
    const active = await activePending
    assertEq(active.slice(1, 4).map(Number), [5, 2, 5], 'zone 5×2×5 active')

    await command(a, `/afkzone set interval ${INTERVAL}`, /AFK interval/)
    await command(a, `/afkzone set reward ${money(REWARD)}`, /Reward per interval: \$50\.00/)
    await command(a, '/afkzone set keychance 100', /Key chance: 100%/)
    await command(a, '/afkzone set daylimit 0', /Daily limit: none/)
    await command(a, '/afkzone set iplimit 0', /AFK zone IP rule: off/)
    const cfg = await info()
    assertEq([cfg.active, cfg.pos1, cfg.pos2, cfg.interval, cfg.reward, cfg.keychance, cfg.daylimit, cfg.ipmax],
      [true, `minecraft:overworld,${X - 6},${Y},${Z - 2}`, `minecraft:overworld,${X - 2},${Y + 1},${Z + 2}`, INTERVAL, REWARD, '100', 0, 0],
      'afk.json takes zone and values')

    const barsA = collectActionBar(a)
    const rewardsA = collectRewards(a)
    const rewardsB = collectRewards(b)
    const a0 = await status(A)
    const b0 = await status(B)
    const firstReward = waitForMessage(a, /AFK reward: \$50\.00 \+ 1× Common Key/, (INTERVAL + 8) * 1000)
    await tp(A, INSIDE)
    const enteredAt = Date.now()
    const sIn = await waitStatus(A, (s) => s.inside, 3000)
    assert(sIn.inside, 'A detected in the zone')
    const show = await command(a, '/afkzone', /You're (not )?in the zone/)
    assert(!show[1], 'player info: "You\'re in the zone"')
    const showB = await command(b, '/afkzone', /You're (not )?in the zone/)
    assert(!!showB[1], 'player info for B: "You\'re not in the zone"')
    await firstReward
    const firstAt = Date.now()
    assert(firstAt - enteredAt >= (INTERVAL - 1.5) * 1000, `first reward only after about ${INTERVAL}s (${firstAt - enteredAt} ms)`)
    const a1 = await status(A)
    assertEq(a1.balance - a0.balance, REWARD, 'A: +$50.00')
    assertEq(a1.keys - a0.keys, 1, 'A: +1 key (common)')
    assertEq(a1.today - a0.today, 1, 'A: today +1')
    assert(barsA.some((t) => /Entered the AFK zone/.test(t)), 'Actionbar: "Entered the AFK zone"')
    assert(barsA.some((t) => /AFK Zone » Next reward in \d+s \(\$50\.00\)/.test(t)), `Actionbar-Countdown (${barsA.slice(-2).join(' | ')})`)
    const b1 = await status(B)
    assertEq([b1.inside, b1.seconds, b1.balance, b1.today], [false, 0, b0.balance, b0.today], 'B outside: no timer, no money')

    await waitForMessage(a, /AFK reward:/, (INTERVAL + 5) * 1000)
    const secondAt = Date.now()
    const gap = secondAt - firstAt
    assert(gap >= (INTERVAL - 1.2) * 1000 && gap <= (INTERVAL + 3) * 1000, `second reward one interval later (${gap} ms)`)
    assertEq(rewardsA.length, 2, 'exactly 2 rewards so far')
    const a2 = await status(A)
    assertEq(a2.balance - a0.balance, 2 * REWARD, 'A: 2 × $50.00')

    await waitStatus(A, (s) => s.inside && s.seconds >= 3, (INTERVAL + 2) * 1000)
    await a.lookAt(new Vec3(X + 6.5, Y + 1.62, Z + 0.5), true)
    a.setControlState('forward', true)
    await sleep(1500)
    a.setControlState('forward', false)
    await sleep(300)
    const walkedOut = a.entity.position.x >= X - 1
    assert(walkedOut, `A walks out of the zone (x=${a.entity.position.x.toFixed(2)})`)
    if (!walkedOut) await tp(A, OUTSIDE)
    const leftAt = Date.now()
    const sOut = await waitStatus(A, (s) => !s.inside, 3000)
    assertEq([sOut.inside, sOut.seconds], [false, 0], 'leaving: timer dropped')
    assert(await waitBar(barsA, /Left the AFK zone/), 'Actionbar: "Left the AFK zone"')
    const rewardsBeforeOutside = rewardsA.length
    await sleep(INTERVAL * 1000 + 1500 - (Date.now() - leftAt))
    assertEq(rewardsA.length, rewardsBeforeOutside, 'no reward outside')

    const reenter = waitForMessage(a, /AFK reward:/, (INTERVAL + 6) * 1000)
    await tp(A, INSIDE)
    const backAt = Date.now()
    const sBack = await waitStatus(A, (s) => s.inside, 3000)
    assert(sBack.inside && sBack.seconds <= 2, `timer restarted after return (seconds=${sBack.seconds})`)
    await reenter
    const afterBack = Date.now() - backAt
    assert(afterBack >= (INTERVAL - 1.5) * 1000, `reward only one full interval after return (${afterBack} ms)`)

    await tp(B, [X - 5 + 0.5, Y, Z - 1 + 0.5])
    const bIn = await waitStatus(B, (s) => s.inside && s.seconds >= 3, (INTERVAL + 2) * 1000)
    assert(bIn.inside && bIn.seconds >= 3 && bIn.seconds < INTERVAL, `B collects time (seconds=${bIn.seconds})`)
    b.quit()
    const bOff = await waitStatus(B, (s) => !s.online, 5000)
    assertEq([bOff.online, bOff.inside], [false, false], 'B offline: session removed')
    b = await connectBot(B)
    const rejoinedAt = Date.now()
    const bReward = waitForMessage(b, /AFK reward:/, (INTERVAL + 8) * 1000)
    const bBack = await waitStatus(B, (s) => s.online && s.inside, 4000)
    assert(bBack.inside && bBack.seconds <= 2, `B back at 0 after reconnect (seconds=${bBack.seconds})`)
    await bReward
    const bWait = Date.now() - rejoinedAt
    assert(bWait >= (INTERVAL - 1.5) * 1000, `B: reward only one full interval after reconnect (${bWait} ms)`)
    assertEq(rewardsB.length, 0, 'old B connection got no reward')

    // both bots share an IP: with iplimit 1 only the one already inside (A) collects
    await tp(B, OUTSIDE)
    await waitStatus(B, (s) => !s.inside, 3000)
    await command(a, '/afkzone set iplimit 1', /Max accounts per IP in the AFK zone: 1/)
    assertEq((await info()).ipmax, 1, 'info: ipmax=1')
    const barsB = collectActionBar(b)
    await tp(B, [X - 5 + 0.5, Y, Z - 1 + 0.5])
    assert(await waitBar(barsB, /Another account on your IP/, 3000), `B (same IP): IP rule hint (${barsB.slice(-2).join(' | ')})`)
    const bBlocked = await status(B)
    assertEq([bBlocked.inside, bBlocked.seconds], [false, 0], 'B blocked: no timer')
    assert((await status(A)).inside, 'A keeps collecting')
    await command(a, '/afkzone set iplimit 0', /AFK zone IP rule: off/)
    const bFree = await waitStatus(B, (s) => s.inside, 3000)
    assert(bFree.inside, 'IP rule off: B collects again')

    await waitStatus(A, (s) => s.inside && s.seconds >= 3, (INTERVAL + 2) * 1000)
    const barsBefore = barsA.length
    // pos2 one row inward (A stays inside): a different zone restarts all timers
    const changed = await rcon(`afkzone pos2 ${X - 2} ${Y + 1} ${Z + 1} minecraft:overworld`)
    assert(/Pos2 set/.test(changed), 'RCON: pos2 with dimension')
    const sChanged = await waitStatus(A, (s) => s.seconds <= 1, 2500)
    assert(sChanged.seconds <= 1, `zone change resets timer (seconds=${sChanged.seconds})`)
    assert(await waitBar(barsA, /The AFK zone was changed/, 3000, barsBefore), 'Actionbar: "The AFK zone was changed"')

    const barsBeforeClear = barsA.length
    await command(a, '/afkzone clear', /AFK zone removed/)
    const off = await info()
    assertEq([off.active, off.pos1, off.pos2], [false, 'none', 'none'], 'clear removes both corners')
    const rewardsAtClear = rewardsA.length
    const sCleared = await waitStatus(A, (s) => !s.inside, 3000)
    assert(!sCleared.inside, 'not in the zone after clear')
    assert(await waitBar(barsA, /The AFK zone was removed/, 3000, barsBeforeClear), `Actionbar: "The AFK zone was removed" (${barsA.slice(-3).join(' | ')})`)
    await sleep(INTERVAL * 1000 + 1000)
    assertEq(rewardsA.length, rewardsAtClear, 'no reward without a zone')
    const final = await status(A)
    assertEq(final.balance - a0.balance, rewardsA.length * REWARD, `money = rewards x $50 (${rewardsA.length})`)
    assertEq(final.keys - a0.keys, rewardsA.length, 'keys = rewards (100 %)')
  } finally {
    try {
      await restore(before)
      const restored = await info()
      assertEq([restored.pos1, restored.pos2, restored.interval, restored.reward, restored.keychance, restored.daylimit, restored.ipmax],
        [before.pos1, before.pos2, before.interval, before.reward, before.keychance, before.daylimit, before.ipmax], 'afk.json restored')
    } catch (err) {
      assert(false, `restore failed: ${err.message}`)
    }
    try {
      await rcon(`deop ${A}`)
      await rcon(`gamemode survival ${A}`)
      await rcon(`gamemode survival ${B}`)
      await rcon(`fill ${X - 8} ${Y - 1} ${Z - 3} ${X + 8} ${Y + 3} ${Z + 3} minecraft:air`)
      await rcon(`forceload remove ${X - 8} ${Z - 3} ${X + 8} ${Z + 3}`)
    } catch (err) {
      console.log(`[E2E] cleanup: ${err.message}`)
    }
  }
}, { timeoutMs: 240000 })
