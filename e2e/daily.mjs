// Daily rewards: /daily GUI, click filter and debounce, join reminder, a 7 day streak with the rare key, new
// week, streak loss after 48 h, admin commands only over RCON or op. Days are simulated with `daily admin shift
// <player> <hours>`, which moves the last claim into the past.
import {
  connectBot, rcon, assert, assertEq, sleep, runE2E, waitForMessage, clickSlot, itemLabel, itemLore,
  windowTitle, expectWindow, cents,
} from './lib.mjs'

const B1 = 'dailyBot1'
const B2 = 'dailyBot2'
const SLOT_INFO = 4
const FIRST_DAY = 10
const SLOT_CLAIM = 22

async function info(name) {
  const out = await rcon(`daily admin info ${name}`)
  const m = out.match(/streak=(\d+) stored=(\d+) next=(\d+) claimable=(true|false) broken=(true|false) cycle=(\d+) best=(\d+) claims=(\d+) last=(\d+) balance=(\d+) keys=(\d+)/)
  if (!m) throw new Error(`daily admin info ${name}: ${out}`)
  return {
    streak: Number(m[1]), stored: Number(m[2]), next: Number(m[3]), claimable: m[4] === 'true', broken: m[5] === 'true',
    cycle: Number(m[6]), best: Number(m[7]), claims: Number(m[8]), last: Number(m[9]), balance: Number(m[10]), keys: Number(m[11]),
  }
}

async function openDaily(bot) {
  const win = await expectWindow(bot, () => bot.chat('/daily'), 10000)
  return win
}

/**
 * Sends the click packet directly, since prismarine-windows cannot simulate mode 6. stateId -1 makes the server
 * resend the whole window.
 */
async function rawClick(bot, slot, mouseButton, mode) {
  bot._client.write('window_click', {
    windowId: bot.currentWindow.id, stateId: -1, slot, mouseButton, mode, changedSlots: [],
    cursorItem: { itemCount: 0, components: [], removeComponents: [] },
  })
  await sleep(250)
}

/**
 * Vanilla closes the current menu on a close packet without checking the ID, but chat commands use another
 * queue. An /daily right after could be handled before the close and get closed again on the server.
 */
async function closeWindow(bot) {
  if (bot.currentWindow) bot.closeWindow(bot.currentWindow)
  await sleep(400)
}

function seen(bot, regex, timeoutMs = 5000, opts) {
  return waitForMessage(bot, regex, timeoutMs, opts).then(() => true, () => false)
}

// mobs at spawn would kill the bots at night, and death closes the GUI and spoils the clicks
async function protect(name) {
  await rcon(`effect give ${name} minecraft:resistance 600 4 true`)
  await rcon(`effect give ${name} minecraft:fire_resistance 600 0 true`)
  await rcon(`effect give ${name} minecraft:saturation 600 0 true`)
}

async function connect(name) {
  const bot = await connectBot(name)
  bot.on('death', () => console.log(`[E2E] WARN ${name} died`))
  await protect(name)
  return bot
}

async function claimViaChat(bot, day) {
  const got = seen(bot, new RegExp(`Day ${day} claimed`), 6000)
  bot.chat('/daily claim')
  return got
}

runE2E(async () => {
  const st = await rcon('smpeco selftest daily')
  const sm = st.match(/SUMMARY passed=(\d+) failed=(\d+)/)
  assert(!!sm, `daily selftest summary (${st.slice(0, 200).trim()})`)
  if (sm) {
    assert(Number(sm[1]) >= 15, `daily selftests ran (passed=${sm[1]})`)
    assertEq(Number(sm[2]), 0, 'daily-Selftests failed=0')
  }

  let bot = await connect(B1)
  await rcon(`deop ${B1}`)
  await rcon(`clear ${B1}`)
  assert(/Daily reset for/.test(await rcon(`daily admin reset ${B1}`)), 'Admin reset per RCON')
  bot.quit()
  await sleep(1500)
  bot = await connect(B1)
  assert(await seen(bot, /daily reward is waiting: Day 1/, 15000, { includePast: true }), 'join reminder for day 1')
  assert(bot.messages.some((m) => /\[Claim\]/.test(m)), 'reminder has [Claim] button')

  let s = await info(B1)
  assert(s.claimable && s.next === 1 && s.stored === 0, `new: claimable, day 1 (${JSON.stringify(s)})`)
  const bal0 = s.balance
  const keys0 = s.keys

  let win = await openDaily(bot)
  assert(/Daily Reward/.test(windowTitle(win)), `GUI opened (title "${windowTitle(win)}")`)
  const rewards = []
  for (let d = 1; d <= 7; d++) {
    const it = win.slots[FIRST_DAY + d - 1]
    assert(new RegExp(`^Day ${d}\\b`).test(itemLabel(it)), `Tag ${d} an Slot ${FIRST_DAY + d - 1} (${itemLabel(it)})`)
    assertEq(it?.count, d, `day ${d}: count = day`)
    rewards.push(cents(itemLore(it).find((l) => /Reward/.test(l)) ?? ''))
  }
  assert(rewards.every((r, i) => r > 0 && (i === 0 || r >= rewards[i - 1])), `rewards rising (${rewards.join(', ')})`)
  assert(/claim now/.test(itemLabel(win.slots[FIRST_DAY])), 'day 1 marked claimable')
  assertEq(win.slots[FIRST_DAY + 6]?.name, 'ender_chest', 'day 7 as ender chest')
  assert(itemLore(win.slots[FIRST_DAY + 6]).some((l) => /Rare Key/.test(l)), 'day 7: Rare Key in the lore')
  assert(/Claim Reward/.test(itemLabel(win.slots[SLOT_CLAIM])), 'claim button')
  assert(/Your Streak: 0 days/.test(itemLabel(win.slots[SLOT_INFO])), `Info: Streak 0 (${itemLabel(win.slots[SLOT_INFO])})`)
  assertEq(win.slots[0]?.name, 'black_stained_glass_pane', 'frame')

  await clickSlot(bot, SLOT_CLAIM, 0, 2)
  await clickSlot(bot, SLOT_CLAIM, 0, 4)
  await rawClick(bot, FIRST_DAY, 0, 6)
  await clickSlot(bot, FIRST_DAY, 2, 3)
  await sleep(400)
  s = await info(B1)
  assert(s.claimable && s.balance === bal0, 'non-normal clicks claim nothing')

  win = bot.currentWindow
  let got = seen(bot, /Day 1 claimed/, 6000)
  await clickSlot(bot, FIRST_DAY)
  assert(await got, 'day 1 claimed (chat)')
  s = await info(B1)
  assert(!s.claimable && s.stored === 1 && s.claims === 1, `after day 1: streak 1, no longer claimable (${JSON.stringify(s)})`)
  assertEq(s.balance - bal0, rewards[0], 'money for day 1')
  await sleep(400)
  win = bot.currentWindow
  assert(!!win && /Daily Reward/.test(windowTitle(win)), 'GUI stays open')
  assertEq(win?.slots[FIRST_DAY]?.name, 'lime_stained_glass', 'day 1 checked')
  assert(/Already claimed today/.test(itemLabel(win?.slots[SLOT_CLAIM])), 'button shows "Already claimed today"')
  assert(itemLore(win?.slots[FIRST_DAY + 1]).some((l) => /midnight/.test(l)), 'day 2: from midnight')
  assert(/Your Streak: 1 day\b/.test(itemLabel(win?.slots[SLOT_INFO])), 'Info: Streak 1 Tag')

  got = seen(bot, /already claimed your daily reward/, 5000)
  await clickSlot(bot, SLOT_CLAIM)
  assert(await got, 'second click: "already claimed"')
  await clickSlot(bot, FIRST_DAY)
  await closeWindow(bot)
  got = seen(bot, /already claimed your daily reward/, 5000)
  bot.chat('/daily claim')
  assert(await got, '/daily claim refused on the same day')
  s = await info(B1)
  assertEq([s.claims, s.balance - bal0], [1, rewards[0]], 'no second payment on the same day')
  await sleep(500)
  assertEq(bot.inventory.items().map((it) => it.name), [], 'no GUI items in the player inventory')

  assert(/last claim back by 24h/.test(await rcon(`daily admin shift ${B1} 24`)), 'shift 24 h')
  s = await info(B1)
  assert(s.claimable && !s.broken && s.next === 2, 'after 24 h: day 2 claimable, streak holds')
  win = await openDaily(bot)
  assert(/claim now/.test(itemLabel(win.slots[FIRST_DAY + 1])), 'GUI: day 2 claimable')
  assertEq(win.slots[FIRST_DAY]?.name, 'lime_stained_glass', 'GUI: day 1 checked')
  const before2 = bot.messages.length
  got = seen(bot, /Day 2 claimed/, 6000)
  await clickSlot(bot, SLOT_CLAIM)
  await rawClick(bot, SLOT_CLAIM, 0, 6)
  assert(await got, 'day 2 claimed (button)')
  await sleep(500)
  assertEq(bot.messages.slice(before2).filter((m) => /already claimed|claimed!/.test(m)).length, 1, 'double click: exactly one message')
  s = await info(B1)
  assertEq([s.claims, s.stored, s.balance - bal0], [2, 2, rewards[0] + rewards[1]], 'double click: claimed exactly once')

  await closeWindow(bot)
  await rcon(`daily admin shift ${B1} 24`)
  win = await openDaily(bot)
  const before3 = bot.messages.length
  got = seen(bot, /Day 3 claimed/, 6000)
  await bot.clickWindow(SLOT_CLAIM, 0, 0)
  await bot.clickWindow(SLOT_CLAIM, 0, 0)
  assert(await got, 'day 3 claimed')
  await sleep(600)
  assertEq(bot.messages.slice(before3).filter((m) => /already claimed|claimed!/.test(m)).length, 1, 'fast double clicks: one message')
  s = await info(B1)
  assertEq(s.claims, 3, 'fast double clicks: claimed once')
  await closeWindow(bot)

  for (let d = 4; d <= 7; d++) {
    await rcon(`daily admin shift ${B1} 24`)
    if (d === 7) {
      const keyMsg = seen(bot, /Rare Key/, 6000)
      assert(await claimViaChat(bot, d), `day ${d} claimed (chat)`)
      assert(await keyMsg, 'day 7: key message')
    } else {
      assert(await claimViaChat(bot, d), `day ${d} claimed (chat)`)
    }
  }
  s = await info(B1)
  const weekSum = rewards.reduce((a, b) => a + b, 0)
  assertEq([s.stored, s.claims, s.balance - bal0], [7, 7, weekSum], 'week complete: streak 7, sum of all days')
  assertEq(s.keys - keys0, 1, 'exactly 1 rare key')
  win = await openDaily(bot)
  assert([0, 1, 2, 3, 4, 5, 6].every((i) => win.slots[FIRST_DAY + i]?.name === 'lime_stained_glass'), 'GUI: all 7 days ticked')
  assert(/Your Streak: 7 days/.test(itemLabel(win.slots[SLOT_INFO])), 'info: streak 7 days')
  await closeWindow(bot)

  await rcon(`daily admin shift ${B1} 24`)
  s = await info(B1)
  assert(s.claimable && s.next === 1 && s.cycle === 0, 'after day 7: new week at day 1')
  assert(await claimViaChat(bot, 1), 'week 2 day 1 claimed')
  s = await info(B1)
  assertEq([s.stored, s.balance - bal0, s.keys - keys0], [8, weekSum + rewards[0], 1], 'streak 8, money for day 1, no new key')

  await rcon(`daily admin shift ${B1} 49`)
  s = await info(B1)
  assert(s.claimable && s.broken && s.next === 1 && s.streak === 0, `broken after 49 h (${JSON.stringify(s)})`)
  bot.quit()
  await sleep(1500)
  bot = await connect(B1)
  assert(await seen(bot, /streak was lost/, 15000, { includePast: true }), 'join reminder names the broken streak')
  win = await openDaily(bot)
  assert(itemLore(win.slots[SLOT_INFO]).some((l) => /Streak lost/.test(l)), 'GUI info: streak broken')
  assert(/claim now/.test(itemLabel(win.slots[FIRST_DAY])), 'GUI: day 1 claimable')
  const lost = seen(bot, /streak was lost/, 6000)
  got = seen(bot, /Day 1 claimed/, 6000)
  await clickSlot(bot, FIRST_DAY)
  assert(await lost, 'message "streak was lost"')
  assert(await got, 'after the break: day 1 claimed')
  s = await info(B1)
  assertEq([s.stored, s.best, s.claims], [1, 8, 9], 'streak 1, best streak 8')
  await closeWindow(bot)

  const balBefore = s.balance
  assert(/streak to 6 days/.test(await rcon(`daily admin setstreak ${B1} 6`)), 'setstreak 6')
  s = await info(B1)
  assertEq([s.stored, s.claimable, s.balance], [6, false, balBefore], 'setstreak on claim day: stays claimed, no money')
  await rcon(`daily admin shift ${B1} 24`)
  win = await openDaily(bot)
  assert(/claim now/.test(itemLabel(win.slots[FIRST_DAY + 6])), 'after setstreak 6 + 24 h: day 7 claimable')
  await closeWindow(bot)

  const b1Before = await info(B1)
  let bot2 = await connect(B2)
  await rcon(`deop ${B2}`)
  await rcon(`daily admin reset ${B2}`)
  assert(await claimViaChat(bot2, 1), 'dailyBot2: day 1 claimed')
  const s2 = await info(B2)
  assertEq([s2.stored, s2.claimable], [1, false], 'dailyBot2: Streak 1')
  bot2.chat(`/daily admin reset ${B1}`)
  bot2.chat(`/daily admin shift ${B1} 24`)
  await sleep(1500)
  const b1After = await info(B1)
  assertEq([b1After.last, b1After.stored, b1After.claimable], [b1Before.last, b1Before.stored, b1Before.claimable], 'admin commands without op have no effect; bot 1 unchanged')
  bot2.quit()
  await sleep(1500)
  bot2 = await connect(B2)
  await sleep(6000)
  assert(!bot2.messages.some((m) => /daily reward is waiting/.test(m)), 'no reminder when already claimed')

  await rcon(`daily admin reset ${B1}`)
  await rcon(`daily admin reset ${B2}`)
}, { timeoutMs: 240000 })
