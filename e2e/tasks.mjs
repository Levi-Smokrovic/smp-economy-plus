// Daily tasks (/tasks): deterministic assignment (date + UUID), GUI with progress bars, progress from vanilla
// statistics (jumping, mining), automatic payout exactly once plus bonus (money and key), self-placed blocks do
// not count (not even through right-click harvest), day change through the task clock (/smpeco tasks clock).
import {
  connectBot, rcon, moduleEnabled, assert, assertEq, sleep, runE2E, waitForMessage, clickSlot, findSlot,
  itemLabel, itemLore, windowTitle, expectWindow, Vec3,
} from './lib.mjs'

const B1 = 'tasksBot1'
const B2 = 'tasksBot2'
const X = 4300, Y = 200, Z = 4300 // test area far from spawn (spawn protection)
const TASK_SLOTS = [11, 13, 15]
const BAR_SLOTS = [20, 22, 24]
const BONUS_SLOT = 31
const CLOSE_SLOT = 40
// display name of the crate ID (CrateKeys.label), as bonus lore and message must show it
const KEY_LABEL = { common: 'Common', rare: 'Rare', epic: 'Epic' }
const keyText = (n, tier) => `${n}× ${KEY_LABEL[tier] ?? tier} Key${n === 1 ? '' : 's'}`

async function info(name) {
  const out = await rcon(`smpeco tasks info ${name}`)
  const head = out.match(/tasks player=(\S+) date=(\S+) done=(\d+)\/(\d+) bonus=(true|false) balance=(-?\d+) keys=(\d+) online=(true|false)(?: today=(\S+) bonusMoney=(\d+) bonusKeys=(\d+) bonusTier=(\S+))?/)
  if (!head) throw new Error(`tasks info ${name}: ${out}`)
  const slots = [...out.matchAll(/slot=(\d+) id=(\S+) diff=(\S*) target=(\d+) progress=(\d+) reward=(\d+) paid=(true|false) excluded=(\d+) credited=(\d+) \(([^)]*)\)/g)]
    .map((m) => ({ slot: +m[1], id: m[2], diff: m[3], target: +m[4], progress: +m[5], reward: +m[6], paid: m[7] === 'true', excluded: +m[8], credited: +m[9], desc: m[10] }))
  return { date: head[2], done: +head[3], total: +head[4], bonus: head[5] === 'true', balance: +head[6], keys: +head[7], today: head[9], bonusMoney: +(head[10] ?? 0), bonusKeys: +(head[11] ?? 0), bonusTier: head[12], slots, raw: out }
}

async function preview(name, date = '') {
  const out = await rcon(`smpeco tasks preview ${name} ${date}`.trim())
  const m = out.match(/picks=(\S*)/)
  if (!m) throw new Error(`tasks preview ${name}: ${out}`)
  return m[1].split(',').filter(Boolean).map((p) => { const [id, amount, reward] = p.split(':'); return { id, amount: +amount, reward: +reward } })
}

async function until(cond, timeoutMs = 10000, stepMs = 300) {
  const end = Date.now() + timeoutMs
  while (Date.now() < end) {
    try { if (await cond()) return true } catch {}
    await sleep(stepMs)
  }
  return false
}

const fmt = (n) => Number(n).toLocaleString('en-US')
const nextDay = (iso) => { const d = new Date(`${iso}T12:00:00Z`); d.setUTCDate(d.getUTCDate() + 1); return d.toISOString().slice(0, 10) }
const count = (bot, re) => bot.messages.filter((m) => re.test(m)).length

async function equip(bot, itemName) {
  for (let i = 0; i < 20 && !bot.inventory.items().some((it) => it.name === itemName); i++) await sleep(100)
  const item = bot.inventory.items().find((it) => it.name === itemName)
  if (!item) throw new Error(`${itemName} not in inventory`)
  await bot.equip(item, 'hand')
}

async function jump(bot, ms) {
  bot.setControlState('jump', true)
  await sleep(ms)
  bot.setControlState('jump', false)
  await sleep(400)
}

runE2E(async () => {
  await rcon('smpeco tasks clock reset')
  // before connecting, so bots that stood here on the last run do not fall
  await rcon(`forceload add ${X} ${Z}`)
  await sleep(1500)
  await rcon(`fill ${X - 4} ${Y - 1} ${Z - 4} ${X + 4} ${Y + 3} ${Z + 4} minecraft:air`)
  await rcon(`fill ${X - 4} ${Y - 1} ${Z - 4} ${X + 4} ${Y - 1} ${Z + 4} minecraft:glass`)
  const bot1 = await connectBot(B1)
  let bot2 = await connectBot(B2)
  assert(true, 'bots connected')

  // briefly creative so no fall damage remains from the teleport
  for (const name of [B1, B2]) {
    await rcon(`gamemode creative ${name}`)
    await rcon(`clear ${name}`)
    await rcon(`effect give ${name} minecraft:saturation 600 1 true`)
  }
  await rcon(`tp ${B1} ${X - 2.5} ${Y} ${Z + 0.5}`)
  await rcon(`tp ${B2} ${X + 0.5} ${Y} ${Z + 0.5}`)
  await sleep(1500)
  for (const name of [B1, B2]) await rcon(`gamemode survival ${name}`)
  await sleep(500)
  assert(bot1.entity.position.distanceTo(new Vec3(X - 2.5, Y, Z + 0.5)) < 1.5 && bot2.entity.position.distanceTo(new Vec3(X + 0.5, Y, Z + 0.5)) < 1.5, 'bots stand on the test area')

  try {
    await assignmentTests(bot1, bot2)
    await guiTests(bot1)
    await payoutTests(bot1)
    await placedBlockTests(bot2)
    await rightClickHarvestTests(bot2)
    bot2 = await reconnectReminder(bot2)
    await midnightTests(bot1)
  } finally {
    for (const bot of [bot1, bot2]) {
      bot.clearControlStates()
      if (bot.currentWindow) bot.closeWindow(bot.currentWindow)
    }
    await rcon('smpeco tasks clock reset')
    for (const name of [B1, B2]) await rcon(`tp ${name} ${X} ${Y} ${Z}`)
    await rcon(`smpeco tasks reset ${B1}`)
    await rcon(`smpeco tasks reset ${B2}`)
    await rcon(`fill ${X - 4} ${Y} ${Z - 4} ${X + 4} ${Y + 3} ${Z + 4} minecraft:air`) // glass floor stays for a safe join
    await rcon(`clear ${B1}`)
    await rcon(`clear ${B2}`)
    await rcon(`forceload remove ${X} ${Z}`)
  }
}, { timeoutMs: 240000 })

async function assignmentTests(bot1, bot2) {
  const announced = waitForMessage(bot1, /New Daily Tasks/, 8000).then(() => true, () => false)
  const resetOut = await rcon(`smpeco tasks reset ${B1}`)
  assert(/reassigned/.test(resetOut), `reset assigns again (${resetOut.trim()})`)
  assert(await announced, 'player gets "New Daily Tasks"')
  const s1 = await info(B1)
  assertEq(s1.date, s1.today, 'tasks for today (server time zone)')
  assertEq(s1.slots.length, 3, '3 tasks')
  assertEq(s1.slots.map((s) => s.diff), ['easy', 'medium', 'hard'], 'difficulties easy/medium/hard')
  assertEq(new Set(s1.slots.map((s) => s.id)).size, 3, 'no duplicate task')
  assert(s1.slots.every((s) => !s.paid && s.reward > 0 && s.target > 0), 'all open, with target and reward')
  assertEq([s1.done, s1.total, s1.bonus], [0, 3, false], 'done=0/3, bonus open')

  const p1 = await preview(B1)
  assertEq(p1.map((p) => `${p.id}:${p.amount}`), s1.slots.map((s) => `${s.id}:${s.target}`), 'preview = assigned tasks')
  await rcon(`smpeco tasks reset ${B1}`)
  const s1b = await info(B1)
  assertEq(s1b.slots.map((s) => `${s.id}:${s.target}:${s.reward}`), s1.slots.map((s) => `${s.id}:${s.target}:${s.reward}`), 'same day + player gives same tasks')
  const day = '2027-01-15'
  assertEq(await preview(B1, day), await preview(B1, day), 'preview for a fixed date stable')
  let differs = 0
  for (let d = 1; d <= 5; d++) {
    const date = `2027-02-0${d}`
    if (JSON.stringify(await preview(B1, date)) !== JSON.stringify(await preview(B2, date))) differs++
  }
  assert(differs >= 3, `different players get different tasks (${differs}/5 days)`)
  const days = new Set()
  for (let d = 10; d <= 19; d++) days.add((await preview(B1, `2027-03-${d}`))[0]?.id)
  assert(days.size >= 3, `tasks change daily (${days.size} different in 10 days)`)

  assert(await until(async () => (await info(B2)).total === 3, 8000), 'tasksBot2 has 3 tasks after the join')

  bot2.chat(`/smpeco tasks info ${B2}`)
  await sleep(800)
  assert(!bot2.messages.some((m) => /tasks player=/.test(m)), 'player without op sees no admin output')
}

async function guiTests(bot) {
  const st = await info(B1)
  let win = await expectWindow(bot, () => bot.chat('/tasks'))
  assert(/Daily Tasks/.test(windowTitle(win)), `GUI opened (title "${windowTitle(win)}")`)
  assertEq(itemLabel(win.slots[4]), 'Daily Tasks', 'info clock on top')
  assert(itemLore(win.slots[4]).some((l) => /New tasks in/.test(l)), 'countdown to the reset')
  assertEq(win.slots[0]?.name, 'black_stained_glass_pane', 'frame')
  for (let i = 0; i < 3; i++) {
    const lore = itemLore(win.slots[TASK_SLOTS[i]])
    assert(lore.some((l) => l === st.slots[i].desc), `task ${i + 1}: description "${st.slots[i].desc}"`)
    assert(lore.some((l) => /^\|{20} \d+%$/.test(l)), `task ${i + 1}: progress bar in the lore`)
    assert(lore.some((l) => new RegExp(`Progress: \\d[\\d,]*/${fmt(st.slots[i].target)}$`).test(l)), `task ${i + 1}: progress x/${fmt(st.slots[i].target)}`)
    assert(lore.some((l) => /Reward: \$/.test(l)), `task ${i + 1}: reward shown`)
    assert(/stained_glass_pane$/.test(win.slots[BAR_SLOTS[i]]?.name ?? '') && /\d+%/.test(itemLabel(win.slots[BAR_SLOTS[i]])), `task ${i + 1}: bar pane with percent`)
  }
  assert(/Bonus/.test(itemLabel(win.slots[BONUS_SLOT])), 'bonus display')
  const bonusKey = keyText(st.bonusKeys, st.bonusTier)
  if (await moduleEnabled('crates')) {
    assert(itemLore(win.slots[BONUS_SLOT]).some((l) => l === `• ${bonusKey}`), `bonus names the key ("${bonusKey}")`)
  } else {
    assert(!itemLore(win.slots[BONUS_SLOT]).some((l) => /\bKeys?\b/.test(l)), 'crates off: bonus names no key')
  }
  assertEq(win.slots[CLOSE_SLOT]?.name, 'barrier', 'close button')

  await clickSlot(bot, TASK_SLOTS[0])
  await clickSlot(bot, TASK_SLOTS[1], 1, 0)
  await clickSlot(bot, TASK_SLOTS[2], 0, 1)
  await clickSlot(bot, BAR_SLOTS[0], 0, 2)
  await sleep(300)
  assert(bot.currentWindow && /Daily Tasks/.test(windowTitle(bot.currentWindow)), 'GUI stays open after clicks')
  await clickSlot(bot, CLOSE_SLOT)
  await sleep(400)
  assert(!bot.currentWindow, 'close button closes the GUI')
  assertEq(bot.inventory.items().map((it) => it.name), [], 'no GUI items in the inventory')

}

async function payoutTests(bot) {
  for (const [slot, n] of [[1, 3], [2, 4], [3, 5]]) {
    const out = await rcon(`smpeco tasks set ${B1} ${slot} bouncy_castle ${n}`)
    assert(new RegExp(`tasks set slot=${slot} id=bouncy_castle target=${n}`).test(out), `slot ${slot}: jump x${n} set`)
  }
  const before = await info(B1)
  assert(before.slots.every((s) => s.id === 'bouncy_castle' && s.progress === 0 && !s.paid), 'jump tasks at 0')
  const rewards = before.slots.reduce((a, s) => a + s.reward, 0)
  const doneBefore = count(bot, /Task complete/)

  const win = await expectWindow(bot, () => bot.chat('/tasks'))
  assert(/0\/3$/.test(itemLore(win.slots[TASK_SLOTS[0]]).find((l) => /Progress:/.test(l)) ?? ''), 'GUI shows 0/3')
  const bonusMsg = waitForMessage(bot, /All Daily Tasks done!.*/, 25000).then((m) => m[0], () => null)
  bot.setControlState('jump', true)
  const paidAll = await bonusMsg
  bot.setControlState('jump', false)
  assert(paidAll, 'bonus message after all tasks')
  const bonusKey = keyText(before.bonusKeys, before.bonusTier)
  if (await moduleEnabled('crates')) {
    assert(new RegExp(`(?:Bonus: | \\+ )${bonusKey}$`).test(paidAll), `bonus message names "${bonusKey}": ${paidAll}`)
  } else {
    assert(!/\bKeys?\b/.test(paidAll), `crates off: bonus message without key: ${paidAll}`)
  }
  assertEq(count(bot, /Task complete/) - doneBefore, 3, '3× "Task complete"')
  const refreshed = await until(() => /Done – reward received/.test(itemLore(bot.currentWindow?.slots[TASK_SLOTS[2]]).join(' ')), 4000)
  assert(refreshed, 'GUI updates live (task 3 done)')
  const w = bot.currentWindow
  assertEq(w?.slots[BAR_SLOTS[0]]?.name, 'lime_stained_glass_pane', 'bar green')
  assert(/Bonus received/.test(itemLabel(w?.slots[BONUS_SLOT])), 'bonus marked received')
  if (bot.currentWindow) bot.closeWindow(bot.currentWindow)
  await sleep(300)

  const after = await info(B1)
  assert(after.slots.every((s) => s.paid), 'all tasks paid')
  assertEq([after.done, after.total, after.bonus], [3, 3, true], 'done=3/3, bonus noted')
  assertEq(after.balance - before.balance, rewards + before.bonusMoney, `balance + rewards (${rewards}) + bonus (${before.bonusMoney})`)
  const keysGiven = before.bonusTier === 'common' && (await moduleEnabled('crates')) ? before.bonusKeys : 0
  assertEq(after.keys - before.keys, keysGiven, `+${keysGiven} common key`)
  assert(before.bonusKeys >= 1 && before.bonusTier === 'common', 'bonus contains common keys (default)')

  await jump(bot, 2500)
  await sleep(2500)
  const again = await info(B1)
  assertEq([again.balance, again.keys], [after.balance, after.keys], 'no second payout')
  assertEq(count(bot, /Task complete/) - doneBefore, 3, 'no further "Task complete"')
  assertEq(count(bot, /All Daily Tasks done/), 1, 'bonus message exactly once')
}

async function placedBlockTests(bot) {
  const out = await rcon(`smpeco tasks set ${B2} 1 groundwork 2`)
  assert(/id=groundwork target=2/.test(out), `slot 1: mine dirt x2 (${out.trim()})`)
  await rcon(`give ${B2} minecraft:dirt 1`)
  await sleep(500)

  await equip(bot, 'dirt')
  await bot.placeBlock(bot.blockAt(new Vec3(X + 2, Y - 1, Z)), new Vec3(0, 1, 0))
  await sleep(400)
  assertEq(bot.blockAt(new Vec3(X + 2, Y, Z))?.name, 'dirt', 'dirt placed')
  assertEq((await rcon(`smpeco placed ${X + 2} ${Y} ${Z}`)).match(/placed=(\w+)/)?.[1], 'true', 'dirt marked as placed')
  await bot.dig(bot.blockAt(new Vec3(X + 2, Y, Z)))
  await sleep(1500)
  let s = (await info(B2)).slots[0]
  assertEq([s.progress, s.excluded], [0, 1], 'placed dirt does not count (excluded)')

  await rcon(`setblock ${X + 2} ${Y} ${Z} minecraft:dirt`)
  await rcon(`setblock ${X + 2} ${Y} ${Z + 1} minecraft:dirt`)
  await sleep(400)
  await bot.dig(bot.blockAt(new Vec3(X + 2, Y, Z)))
  assert(await until(async () => (await info(B2)).slots[0].progress === 1, 4000), 'natural dirt counts (1/2)')
  const done = waitForMessage(bot, /Task complete: Groundwork/, 8000).then(() => true, () => false)
  await bot.dig(bot.blockAt(new Vec3(X + 2, Y, Z + 1)))
  assert(await done, 'Groundwork done (2/2)')
  s = (await info(B2)).slots[0]
  assert(s.paid && s.excluded === 1, 'paid, exclusion stays 1')
}

/**
 * Right-click harvest (RightClickHarvest raises "mined" itself, without breaking): harvesting self-placed sugar
 * cane must not count, naturally set cane must. Without that mod only INFO.
 */
async function rightClickHarvestTests(bot) {
  const cx = X + 2, cz = Z - 2
  // sugar cane needs water next to the soil: dirt and water in the glass floor, glass below so nothing drains
  await rcon(`setblock ${cx + 1} ${Y - 2} ${cz} minecraft:glass`)
  await rcon(`setblock ${cx + 1} ${Y - 1} ${cz} minecraft:water`)
  await rcon(`setblock ${cx} ${Y - 1} ${cz} minecraft:dirt`)
  try {
    const out = await rcon(`smpeco tasks set ${B2} 2 sugar_cane_harvest 2`)
    assert(/id=sugar_cane_harvest target=2/.test(out), `slot 2: harvest sugar cane x2 (${out.trim()})`)
    await rcon(`give ${B2} minecraft:sugar_cane 2`)
    await sleep(500)
    const at = (dy) => bot.blockAt(new Vec3(cx, Y + dy, cz))
    // With cane in hand a right click places a new one on top instead of harvesting. The previous harvest often
    // lands in the selected empty slot, so clear drops and hold no cane before each further harvest.
    const emptyHand = async () => {
      await rcon(`kill @e[type=minecraft:item,x=${cx},y=${Y},z=${cz},distance=..6]`)
      await rcon(`clear ${B2} minecraft:sugar_cane`)
      await sleep(300)
    }

    // exploit: place cane, place a second on top, harvest with right click
    await equip(bot, 'sugar_cane')
    await bot.placeBlock(bot.blockAt(new Vec3(cx, Y - 1, cz)), new Vec3(0, 1, 0))
    await sleep(300)
    await equip(bot, 'sugar_cane')
    await bot.placeBlock(at(0), new Vec3(0, 1, 0))
    await sleep(400)
    assertEq([at(0)?.name, at(1)?.name], ['sugar_cane', 'sugar_cane'], 'two sugar canes placed on top of each other')
    assertEq((await rcon(`smpeco placed ${cx} ${Y + 1} ${cz}`)).match(/placed=(\w+)/)?.[1], 'true', 'upper sugar cane marked as placed')
    await bot.unequip('hand').catch(() => {})
    const before = (await info(B2)).slots[1]
    await bot.activateBlock(at(1))
    const rch = await until(() => at(1)?.name === 'air', 3000, 100)
    console.log(`[E2E] INFO RightClickHarvest ${rch ? 'active, checking right-click harvest' : 'not active, right click does not harvest'}`)
    if (!rch) return
    await sleep(1500) // tasks are checked every 20 ticks
    let s = (await info(B2)).slots[1]
    assertEq([s.progress, s.excluded - before.excluded], [0, 1], 'right-click harvest of placed sugar cane does not count (excluded)')

    await emptyHand()
    await rcon(`setblock ${cx} ${Y + 1} ${cz} minecraft:sugar_cane`)
    await sleep(400)
    await bot.activateBlock(at(1))
    assert(await until(async () => (await info(B2)).slots[1].progress === 1, 4000), 'natural sugar cane counts (1/2)')
    const done = waitForMessage(bot, /Task complete: Sugar Cane Harvest/, 8000).then(() => true, () => false)
    await emptyHand()
    await rcon(`setblock ${cx} ${Y + 1} ${cz} minecraft:sugar_cane`)
    await sleep(400)
    await bot.activateBlock(at(1))
    assert(await done, 'Sugar Cane Harvest done (2/2)')
    s = (await info(B2)).slots[1]
    assert(s.paid && s.excluded === before.excluded + 1, 'paid, exclusion stays 1')
  } finally {
    await rcon(`fill ${cx} ${Y} ${cz} ${cx} ${Y + 2} ${cz} minecraft:air`)
    await rcon(`setblock ${cx + 1} ${Y - 1} ${cz} minecraft:glass`)
    await rcon(`setblock ${cx} ${Y - 1} ${cz} minecraft:glass`)
    await rcon(`setblock ${cx + 1} ${Y - 2} ${cz} minecraft:air`)
    await rcon(`kill @e[type=minecraft:item,x=${X},y=${Y},z=${Z},distance=..12]`)
  }
}

async function reconnectReminder(bot) {
  const st = await info(B2)
  bot.quit()
  await sleep(1500)
  const again = await connectBot(B2)
  const re = new RegExp(`Daily Tasks: ${st.done}/${st.total} done`)
  const reminded = await waitForMessage(again, re, 10000, { includePast: true }).then(() => true, () => false)
  assert(reminded, `join hint "Daily Tasks: ${st.done}/${st.total} done"`)
  const after = await info(B2)
  assertEq(after.slots.map((s) => `${s.id}:${s.paid}`), st.slots.map((s) => `${s.id}:${s.paid}`), 'tasks survive the relog')
  assertEq(after.balance, st.balance, 'relog pays nothing again')
  return again
}

async function midnightTests(bot) {
  const today = (await info(B1)).today
  const tomorrow = nextDay(today)
  const expected = await preview(B1, tomorrow)
  const announced = waitForMessage(bot, /New Daily Tasks/, 8000).then(() => true, () => false)
  const clock = await rcon('smpeco tasks clock 1440')
  assert(new RegExp(`today=${tomorrow}`).test(clock), `task clock +1 day (${clock.trim()})`)
  assert(await announced, 'day change: "New Daily Tasks" without relog')
  const st = await info(B1)
  assertEq(st.date, tomorrow, 'new day stored')
  assertEq([st.done, st.bonus], [0, false], 'progress and bonus reset')
  assert(st.slots.every((s) => !s.paid && s.progress === 0), 'new tasks open, at 0')
  assertEq(st.slots.map((s) => `${s.id}:${s.target}`), expected.map((p) => `${p.id}:${p.amount}`), 'new tasks = deterministic selection for tomorrow')

  await rcon('smpeco tasks clock reset')
  await sleep(2500)
  const back = await info(B1)
  assertEq(back.date, tomorrow, 'clock back: state stays (no double reset)')
  assertEq(back.balance, st.balance, 'no payout from the clock jump back')
}
