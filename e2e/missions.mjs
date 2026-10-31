// Missions board as a real player: board GUI, accepting with confirmation, cancelling and cooldown, delivery
// through the GUI (pristine rule, partial delivery, price cap, exactly once), mining missions from real break
// events (placed blocks do not count), hunting from real kills (bred and revived mobs do not count, spawner
// mobs partly), exploration, at most 3 active, expiry, per-player boards, daily elite.
import {
  connectBot, rcon, moduleEnabled, runE2E, assert, assertEq, sleep, waitForMessage, expectWindow, windowTitle, clickSlot, findSlot,
  itemLabel, itemLore, textOf, Vec3,
} from './lib.mjs'

const B1 = 'missioBot1'
const B2 = 'missioBot2'
const B3 = 'missioBot3' // only for the daily elite's join hint
const X = 4300, Y = 200, Z = 4300 // test area far from spawn (spawn protection); other features use 4000
const GUI_ITEMS = new Set([
  'black_stained_glass_pane', 'gray_stained_glass_pane', 'light_gray_stained_glass_pane', 'lime_stained_glass_pane',
  'red_stained_glass_pane', 'lime_dye', 'yellow_dye', 'orange_dye', 'magenta_dye', 'book', 'writable_book', 'clock',
  'barrier', 'hopper', 'red_dye', 'arrow', 'spyglass', 'compass', 'magenta_stained_glass_pane', 'sculk_shrieker',
])


function kv(line) {
  const out = {}
  for (const m of line.matchAll(/(\w+)=(\S*)/g)) out[m[1]] = m[2]
  return out
}

async function status(name) {
  const out = await rcon(`smpeco missions status ${name}`)
  const lines = out.split('\n').map((l) => l.trim()).filter(Boolean)
  const head = lines.find((l) => l.startsWith('missions status'))
  if (!head) throw new Error(`status ${name}: ${out}`)
  const s = kv(head)
  const missions = lines.filter((l) => l.startsWith('missions active')).map((l) => {
    const a = kv(l)
    const [progress, amount] = a.progress.split('/').map(Number)
    return { ...a, instance: Number(a.instance), progress, amount, paid: Number(a.paid), reward: Number(a.reward), perItem: Number(a.perItem) }
  })
  return {
    active: Number(s.active), cooldownMs: Number(s.cooldownMs), completed: Number(s.completed), balance: Number(s.balance),
    keys: s.keys, explored: Number(s.explored), taken: s.taken ? s.taken.split(',').filter(Boolean) : [], missions, raw: out,
  }
}

async function board(name) {
  const out = await rcon(`smpeco missions board ${name}`)
  return out.split('\n').map((l) => l.trim()).filter((l) => l.startsWith('offer ')).map((l) => {
    const o = kv(l)
    const name = l.replace(/^.* name=/, '')
    return { slot: Number(o.slot), tier: o.tier, id: o.id, type: o.type, amount: Number(o.amount), item: o.item, status: o.status, name }
  })
}

async function assign(name, id, amount) {
  const out = await rcon(`smpeco missions assign ${name} ${id}${amount ? ' ' + amount : ''}`)
  const m = out.match(/assign ok instance=(\d+)/)
  if (!m) throw new Error(`assign ${id}: ${out}`)
  return Number(m[1])
}

async function waitFor(fn, timeoutMs, label) {
  const end = Date.now() + timeoutMs
  let last
  while (Date.now() < end) {
    last = await fn()
    if (last) return last
    await sleep(250)
  }
  throw new Error(`Timeout (${timeoutMs} ms): ${label}`)
}

// with a window open read its inventory part; mineflayer updates bot.inventory only after closing
function playerItems(bot) {
  const w = bot.currentWindow
  if (!w) return bot.inventory.items()
  return w.slots.slice(w.inventoryStart, w.inventoryEnd).filter(Boolean)
}

function countItems(bot, name, pred = () => true) {
  return playerItems(bot).filter((it) => it.name === name && pred(it)).reduce((n, it) => n + it.count, 0)
}

async function closeAll(bot) {
  if (bot.currentWindow) bot.closeWindow(bot.currentWindow)
  await sleep(300)
}

async function placed(x, y, z) {
  const out = await rcon(`smpeco placed ${x} ${y} ${z}`)
  const m = out.match(/placed=(true|false)/)
  return m ? m[1] === 'true' : null
}

async function expectNoWindow(bot, action, ms = 1200) {
  let opened = false
  const onOpen = () => { opened = true }
  bot.on('windowOpen', onOpen)
  await action()
  await sleep(ms)
  bot.off('windowOpen', onOpen)
  return !opened
}


runE2E(async () => {
  const titles = (bot) => {
    bot.titles = []
    bot._client.on('set_title_text', (p) => bot.titles.push({ kind: 'title', text: textOf(p.text), at: Date.now() }))
    bot._client.on('set_title_subtitle', (p) => bot.titles.push({ kind: 'subtitle', text: textOf(p.text), at: Date.now() }))
  }
  const bot1 = await connectBot(B1, { setup: titles })
  const bot2 = await connectBot(B2, { setup: titles })
  assert(true, 'bots connected')

  for (const n of [B1, B2]) {
    await rcon(`smpeco missions reset ${n}`)
    await rcon(`clear ${n}`)
    await rcon(`gamemode survival ${n}`)
  }
  await rcon(`forceload add ${X} ${Z}`)
  await sleep(1000)
  await rcon(`fill ${X - 4} ${Y} ${Z - 4} ${X + 4} ${Y + 3} ${Z + 4} minecraft:air`)
  await rcon(`fill ${X - 4} ${Y - 1} ${Z - 4} ${X + 4} ${Y - 1} ${Z + 4} minecraft:glass`)
  await rcon(`tp ${B1} ${X + 0.5} ${Y} ${Z + 0.5}`)
  await rcon(`tp ${B2} ${X - 2.5} ${Y} ${Z - 2.5}`)
  await sleep(1500)

  try {
    const st = await rcon('smpeco selftest missions')
    const sm = st.match(/SUMMARY passed=(\d+) failed=(\d+)/)
    assert(!!sm && Number(sm[1]) >= 10, `selftests ran (${sm ? sm[0] : st.slice(0, 200)})`)
    if (sm) assertEq(Number(sm[2]), 0, `Selftests missions failed=0 ${Number(sm[2]) > 0 ? st.slice(0, 600) : ''}`)
    const chk = await rcon('smpeco missions check')
    assert(/problems=0/.test(chk) && Number(chk.match(/pool=(\d+)/)?.[1]) >= 40, `missions.json ok (${chk.split('\n')[0]})`)

    await boardTests(bot1)
    await acceptAndCancelTests(bot1)
    await deliveryTests(bot1)
    await miningTests(bot1)
    await huntTests(bot1)
    await exploreTests(bot1)
    await limitAndExpireTests(bot1)
    await secondPlayerTests(bot1, bot2)
    await eliteTests(bot1, bot2)

    await closeAll(bot1)
    await closeAll(bot2)
    const leaked = [...bot1.inventory.items(), ...bot2.inventory.items()].filter((it) => GUI_ITEMS.has(it.name)).map((it) => it.name)
    assertEq(leaked, [], 'no GUI items in the inventory')
  } finally {
    await closeAll(bot1).catch(() => {})
    await closeAll(bot2).catch(() => {})
    await rcon('smpeco missions elite clear').catch(() => {})
    for (const n of [B1, B2, B3]) {
      await rcon(`smpeco missions reset ${n}`).catch(() => {})
      await rcon(`clear ${n}`).catch(() => {})
      await rcon(`gamemode survival ${n}`).catch(() => {})
    }
    await rcon(`kill @e[type=minecraft:husk,x=${X},y=${Y},z=${Z},distance=..24]`).catch(() => {})
    await rcon(`kill @e[type=minecraft:warden,x=${X},y=${Y},z=${Z},distance=..24]`).catch(() => {})
    await rcon(`kill @e[type=minecraft:item,x=${X},y=${Y},z=${Z},distance=..24]`).catch(() => {})
    await rcon(`fill ${X - 4} ${Y - 1} ${Z - 4} ${X + 4} ${Y + 3} ${Z + 4} minecraft:air`).catch(() => {})
    await rcon(`forceload remove ${X} ${Z}`).catch(() => {})
  }
}, { timeoutMs: 300000 })

async function boardTests(bot) {
  const offers = await board(B1)
  assert(offers.length >= 4, `board has offers (${offers.length})`)
  assertEq([...new Set(offers.map((o) => o.tier))], ['easy', 'medium', 'hard', 'elite'], 'all four tiers on the board')
  assertEq((await board(B1)).map((o) => o.id), offers.map((o) => o.id), 'board deterministic (second query the same)')

  const win = await expectWindow(bot, () => bot.chat('/missions'))
  assert(/Mission Board/.test(windowTitle(win)), `board opened (title "${windowTitle(win)}")`)
  assertEq([9, 18, 27, 36].map((s) => itemLabel(win.slots[s])), ['Easy', 'Medium', 'Hard', 'Elite'], 'tier signs on the left')
  const mismatched = offers.filter((o) => itemLabel(win.slots[o.slot]) !== o.name)
  assertEq(mismatched.map((o) => o.id), [], 'GUI shows exactly the server board')
  const lore = itemLore(win.slots[offers[0].slot])
  assert(lore.some((l) => /Reward/.test(l)) && lore.some((l) => /Time limit/.test(l)), 'offer shows reward and time limit')
  assert(lore.some((l) => /Click to accept/.test(l)), 'offer can be accepted')
  const crates = await moduleEnabled('crates')
  const elite = offers.find((o) => o.tier === 'elite' && o.type !== 'deliver')
  const hardOffer = offers.find((o) => o.tier === 'hard' && o.type !== 'deliver')
  if (crates) {
    if (elite) assert(itemLore(win.slots[elite.slot]).some((l) => /Epic Key/.test(l)), 'elite shows Epic Key')
    if (hardOffer) assert(itemLore(win.slots[hardOffer.slot]).some((l) => /Rare Key/.test(l)), 'hard shows Rare Key')
  } else {
    for (const o of offers) assert(!itemLore(win.slots[o.slot]).some((l) => /\bKey\b/.test(l)), `crates off: ${o.id} without key`)
  }
  const keyedDeliver = offers.filter((o) => (o.tier === 'hard' || o.tier === 'elite') && o.type === 'deliver')
  for (const o of keyedDeliver) assert(!itemLore(win.slots[o.slot]).some((l) => /\bKey\b/.test(l)), `delivery ${o.id} without key`)
  const deliver = offers.find((o) => o.type === 'deliver')
  if (deliver) assert(itemLore(win.slots[deliver.slot]).some((l) => / each\)/.test(l)), 'delivery shows price per piece')
  await closeAll(bot)
}

async function acceptAndCancelTests(bot) {
  let offers = await board(B1)
  const pick = offers.find((o) => o.type !== 'explore' && o.status === 'ok')
  assert(!!pick, `acceptable offer found (${pick?.id})`)
  if (!pick) return

  let win = await expectWindow(bot, () => bot.chat('/missions'))
  const dialog = await expectWindow(bot, () => clickSlot(bot, pick.slot))
  assert(/Accept Mission/.test(windowTitle(dialog)), `confirm dialog (${windowTitle(dialog)})`)
  assertEq(itemLabel(dialog.slots[13]), pick.name, 'preview shows the mission')
  const accepted = waitForMessage(bot, /Mission accepted/, 5000).then(() => true, () => false)
  win = await expectWindow(bot, () => clickSlot(bot, 9))
  assert(await accepted, 'message "Mission accepted"')
  assert(/Active Missions/.test(windowTitle(win)), 'then "Active Missions" opens')
  assertEq(itemLabel(win.slots[11]), pick.name, 'mission in the first place')
  assert(itemLore(win.slots[11]).some((l) => /Progress/.test(l)), 'progress in the lore')
  let s = await status(B1)
  assertEq([s.active, s.missions[0]?.id], [1, pick.id], 'server: 1 active mission')
  assert(s.taken.includes(pick.id), 'mission used for this round')

  win = await expectWindow(bot, () => clickSlot(bot, 36))
  assert(/Mission Board/.test(windowTitle(win)), 'back button opens the board')
  assert(itemLore(win.slots[pick.slot]).some((l) => /Already taken/.test(l)), 'offer marked accepted')
  const again = waitForMessage(bot, /already taken this mission/, 4000).then(() => true, () => false)
  assert(await expectNoWindow(bot, () => clickSlot(bot, pick.slot)), 'no dialog for an accepted mission')
  assert(await again, 'error "already taken"')

  win = await expectWindow(bot, () => clickSlot(bot, 47))
  assert(/Active Missions/.test(windowTitle(win)), 'Button "Active Missions"')
  const cancelSlot = findSlot(win, /^Abandon$/)
  assertEq(cancelSlot, 29, 'cancel button below the mission')
  const confirm = await expectWindow(bot, () => clickSlot(bot, cancelSlot))
  assert(/Abandon Mission/.test(windowTitle(confirm)), 'cancel dialog')
  const cancelled = waitForMessage(bot, /Mission abandoned/, 5000).then(() => true, () => false)
  win = await expectWindow(bot, () => clickSlot(bot, 9))
  assert(await cancelled, 'message "Mission abandoned"')
  assertEq(itemLabel(win.slots[11]), 'Free Slot', 'place free again')
  s = await status(B1)
  assertEq(s.active, 0, 'server: no active mission')
  assert(s.cooldownMs > 60_000, `cooldown running (${s.cooldownMs} ms)`)

  offers = await board(B1)
  const other = offers.find((o) => o.id !== pick.id)
  win = await expectWindow(bot, () => clickSlot(bot, 36))
  assert(itemLore(win.slots[other.slot]).some((l) => /Cooldown/.test(l)), 'board shows cooldown')
  const cd = waitForMessage(bot, /take a new one in/, 4000).then(() => true, () => false)
  assert(await expectNoWindow(bot, () => clickSlot(bot, other.slot)), 'no dialog during the cooldown')
  assert(await cd, 'cooldown error')
  await closeAll(bot)
  await rcon(`smpeco missions cooldown ${B1} clear`)
  assertEq((await status(B1)).cooldownMs, 0, 'cooldown cleared by admin')
}

async function deliveryTests(bot) {
  await rcon(`clear ${B1}`)
  const inst = await assign(B1, 'medium_iron', 10)
  let s = await status(B1)
  const m = s.missions.find((x) => x.instance === inst)
  assert(m && m.perItem > 0, `delivery assigned, price per piece ${m?.perItem}`)
  const price = (await rcon(`smpeco price minecraft:iron_ingot ${B1}`)).match(/npcOrderMax=(\d+)/)
  if (price) assert(m.perItem <= Number(price[1]), `price per piece <= cap (${m.perItem} <= ${price[1]})`)

  await rcon(`give ${B1} minecraft:iron_ingot 6`)
  let res = await rcon(`give ${B1} minecraft:iron_ingot[custom_name="Fake"] 1`)
  if (!/Gave/i.test(res)) res = await rcon(`give ${B1} minecraft:iron_ingot[custom_name='"Fake"'] 1`)
  await waitFor(async () => countItems(bot, 'iron_ingot') >= 7, 5000, 'iron in the inventory')
  assertEq(countItems(bot, 'iron_ingot', (it) => it.customName != null), 1, 'renamed iron in the inventory')

  const before = s.balance
  let win = await expectWindow(bot, () => bot.chat('/missions active'))
  const deliverSlot = findSlot(win, /^Deliver$/)
  assertEq(deliverSlot, 20, 'deliver button below the mission')
  assert(itemLore(win.slots[deliverSlot]).some((l) => /In inventory: 6/.test(l)), 'lore counts only unchanged items (6)')
  const got = waitForMessage(bot, /Delivered 6×/, 5000).then(() => true, () => false)
  await clickSlot(bot, deliverSlot)
  assert(await got, 'message "Delivered 6×"')
  s = await status(B1)
  const m2 = s.missions.find((x) => x.instance === inst)
  assertEq([m2?.progress, m2?.amount], [6, 10], 'progress 6/10')
  assertEq(m2?.paid, 6 * m.perItem, 'paid 6 x price per piece')
  assertEq(s.balance - before, 6 * m.perItem, 'balance risen')
  await sleep(300)
  assertEq(countItems(bot, 'iron_ingot', (it) => it.customName == null), 0, 'unchanged iron ingots handed in')
  assertEq(countItems(bot, 'iron_ingot', (it) => it.customName != null), 1, 'renamed iron stays (pristine rule)')

  await rcon(`give ${B1} minecraft:iron_ingot 10`)
  await waitFor(async () => countItems(bot, 'iron_ingot', (it) => it.customName == null) >= 10, 5000, '10 iron received')
  const before2 = (await status(B1)).balance
  const done = waitForMessage(bot, /Mission complete/, 6000).then(() => true, () => false)
  await sleep(350) // another click on the same slot needs more than 300 ms
  await clickSlot(bot, deliverSlot)
  assert(await done, 'message "Mission complete"')
  s = await status(B1)
  assert(!s.missions.some((x) => x.instance === inst), 'delivery no longer active')
  assertEq(s.balance - before2, 4 * m.perItem, 'only 4 pieces paid')
  await sleep(300)
  assertEq(countItems(bot, 'iron_ingot', (it) => it.customName == null), 6, '6 iron left over')
  win = bot.currentWindow
  assert(win && itemLabel(win.slots[11]) === 'Free Slot', 'GUI shows the free place')
  await sleep(2500)
  assertEq((await status(B1)).balance, s.balance, 'no further payment (exactly once)')
  await closeAll(bot)
  await rcon(`clear ${B1}`)
}

// mining from real break events, not the statistic, which keeps it veinminer-safe; self-placed blocks do not
// count
async function miningTests(bot) {
  await rcon(`tp ${B1} ${X + 0.5} ${Y} ${Z + 0.5}`)
  await rcon(`gamemode survival ${B1}`)
  await rcon(`setblock ${X + 2} ${Y} ${Z} minecraft:dirt`)
  await rcon(`setblock ${X + 2} ${Y} ${Z + 1} minecraft:dirt`)
  await sleep(800)
  assertEq(await placed(X + 2, Y, Z), false, 'dirt set with /setblock is "natural"')
  const inst = await assign(B1, 'easy_dirt', 2)
  let s = await status(B1)
  const m = s.missions.find((x) => x.instance === inst)
  assert(m && m.reward > 0, `mining mission assigned (reward ${m?.reward})`)

  await rcon(`give ${B1} minecraft:dirt 1`)
  await waitFor(async () => countItems(bot, 'dirt') >= 1, 5000, 'dirt received')
  await bot.equip(bot.inventory.items().find((it) => it.name === 'dirt'), 'hand')
  await bot.placeBlock(bot.blockAt(new Vec3(X - 1, Y - 1, Z)), new Vec3(0, 1, 0))
  await sleep(500)
  assertEq(await placed(X - 1, Y, Z), true, 'self-placed dirt is marked')
  await bot.dig(bot.blockAt(new Vec3(X - 1, Y, Z)))
  await sleep(1800)
  s = await status(B1)
  assertEq(s.missions.find((x) => x.instance === inst)?.progress, 0, 'placed block does not count')

  const before = s.balance
  const done = waitForMessage(bot, /Mission complete: Digger/, 8000).then(() => true, () => false)
  await bot.dig(bot.blockAt(new Vec3(X + 2, Y, Z)))
  await sleep(1500)
  s = await status(B1)
  assertEq(s.missions.find((x) => x.instance === inst)?.progress, 1, 'natural dirt counts (1/2)')
  await bot.dig(bot.blockAt(new Vec3(X + 2, Y, Z + 1)))
  assert(await done, 'mining mission completed')
  s = await status(B1)
  assertEq(s.balance - before, m.reward, 'reward exactly once')
  await rcon(`clear ${B1}`)
}

// Hunting from real kills (kill event with an origin check, not the statistic): bred and revived mobs do not
// count (shulker splitting, dragon from end crystals), spawner mobs at spawnerKillFactor (default 0.25). The
// fatal hit comes through /damage "by" the bot, which credits the kill like a sword hit.
async function huntTests(bot) {
  await rcon(`tp ${B1} ${X + 0.5} ${Y} ${Z + 0.5}`)
  await rcon(`gamemode survival ${B1}`)
  await rcon(`jobs admin set ${B1} none`).catch(() => {}) // no hunter wage in the balance
  const inst = await assign(B1, 'easy_zombies', 3)
  let s = await status(B1)
  const m = s.missions.find((x) => x.instance === inst)
  assert(m && m.reward > 0, `hunting mission assigned (reward ${m?.reward})`)
  const sel = `@e[type=minecraft:husk,x=${X},y=${Y},z=${Z},distance=..12]`
  const kill = async (tag) => {
    await rcon(`kill ${sel}`)
    await rcon(`summon minecraft:husk ${X + 2.5} ${Y} ${Z + 0.5} {NoAI:1b,Silent:1b,PersistenceRequired:1b${tag ? `,Tags:["${tag}"]` : ''}}`)
    await sleep(300)
    const out = await rcon(`damage ${sel.replace(']', ',limit=1]')} 1000 minecraft:player_attack by ${B1}`)
    await sleep(300)
    const left = await rcon(`execute if entity ${sel}`)
    assert(!/passed/i.test(left), `husk killed${tag ? ` (tag ${tag})` : ''} (${out.trim()})`)
  }
  const mission = async () => (await status(B1)).missions.find((x) => x.instance === inst)

  await kill('smpeco_jobs_bred')
  await kill('smpeco_jobs_respawned')
  await kill('smpeco_jobs_egg')
  await sleep(1300)
  let cur = await mission()
  assertEq([cur?.progress, Number(cur?.killMilli)], [0, 0], 'bred, revived and spawn egg mobs do not count')

  await kill(null)
  await sleep(1300)
  cur = await mission()
  assertEq([cur?.progress, Number(cur?.killMilli)], [1, 1000], 'natural husk counts (1/3)')

  for (let i = 0; i < 4; i++) await kill('smpeco_jobs_spawner')
  await sleep(1300)
  cur = await mission()
  assertEq([cur?.progress, Number(cur?.killMilli)], [2, 2000], '4 Spawner-Husks = 1 Kill (2/3)')

  const before = (await status(B1)).balance
  const done = waitForMessage(bot, /Mission complete: /, 8000).then(() => true, () => false)
  await kill(null)
  assert(await done, 'hunting mission completed')
  s = await status(B1)
  assert(!s.missions.some((x) => x.instance === inst), 'hunting mission no longer active')
  // >= instead of ==: the kill may complete a daily task on the side
  assert(s.balance - before >= m.reward, `reward paid (${s.balance - before} >= ${m.reward})`)
  await sleep(2500)
  assertEq((await status(B1)).balance, s.balance, 'no further payment (exactly once)')
  await rcon(`kill ${sel}`)
  await rcon(`kill @e[type=minecraft:item,x=${X},y=${Y},z=${Z},distance=..24]`)
  await rcon(`clear ${B1}`)
}

// exploration: the Nether must be entered after accepting
async function exploreTests(bot) {
  const inst = await assign(B1, 'easy_nether')
  let s = await status(B1)
  assertEq(s.missions.find((x) => x.instance === inst)?.armed, 'true', 'accepted in the overworld: armed')
  const before = s.balance
  const reward = s.missions.find((x) => x.instance === inst)?.reward
  const done = waitForMessage(bot, /Mission complete: Hell's Gate/, 20000).then(() => true, () => false)
  await rcon(`gamemode creative ${B1}`)
  await rcon(`execute in minecraft:the_nether run tp ${B1} 0.5 128 0.5`)
  assert(await done, 'Nether entered: mission completed')
  s = await status(B1)
  assertEq(s.balance - before, reward, 'exploration reward')
  assertEq(s.explored, 1, 'target noted as explored')
  await rcon(`execute in minecraft:overworld run tp ${B1} ${X + 0.5} ${Y} ${Z + 0.5}`)
  await sleep(2500)
  await rcon(`gamemode survival ${B1}`)
  assert(bot.game?.dimension?.includes('overworld') ?? true, `back in the overworld (${bot.game?.dimension})`)
  // each target pays only once (no farming through /home, /tpa or portal)
  const again = await rcon(`smpeco missions assign ${B1} easy_nether`)
  assert(/failed=explored/.test(again), `second Nether exploration refused (${again.trim()})`)
  assert(!(await board(B1)).some((o) => o.id === 'easy_nether' && o.status === 'ok'), 'explored mission not acceptable')
}

async function limitAndExpireTests(bot) {
  await rcon(`smpeco missions cooldown ${B1} clear`)
  for (const id of ['medium_skeletons', 'hard_ghasts', 'elite_wither']) await assign(B1, id)
  let s = await status(B1)
  assertEq(s.active, 3, '3 missions active')
  const fourth = await rcon(`smpeco missions assign ${B1} easy_zombies`)
  assert(/failed=full/.test(fourth), 'fourth mission refused (admin)')

  const offers = await board(B1)
  const open = offers.find((o) => o.status === 'full')
  assert(!!open, 'board reports "all places taken"')
  const win = await expectWindow(bot, () => bot.chat('/missions'))
  if (open) {
    assert(itemLore(win.slots[open.slot]).some((l) => /All slots full/.test(l)), 'lore: all places taken')
    const full = waitForMessage(bot, /already have 3 active missions/, 4000).then(() => true, () => false)
    assert(await expectNoWindow(bot, () => clickSlot(bot, open.slot)), 'no dialog with full places')
    assert(await full, 'error "already have 3 active missions"')
  }
  await closeAll(bot)

  const before = s.balance
  const expired = []
  const onMsg = (msg) => { if (/Mission expired/.test(msg)) expired.push(msg) }
  bot.on('messagestr', onMsg)
  const out = await rcon(`smpeco missions expire ${B1}`)
  assert(/marked=3/.test(out), 'expiry forced (3)')
  await waitFor(async () => expired.length >= 3, 6000, '3× "Mission expired"').catch(() => {})
  bot.off('messagestr', onMsg)
  assertEq(expired.length, 3, '3 expiry messages')
  s = await status(B1)
  assertEq(s.active, 0, 'expired missions removed')
  assertEq(s.balance, before, 'expiry pays nothing')
}

async function secondPlayerTests(bot1, bot2) {
  const b1 = (await board(B1)).map((o) => o.id)
  const b2offers = await board(B2)
  assert(b1.join(',') !== b2offers.map((o) => o.id).join(','), 'each player has an own board')
  const pick = b2offers.find((o) => o.status === 'ok' && o.type !== 'explore')
  assert(!!pick, 'bot 2 has an acceptable offer')
  if (!pick) return
  const before1 = await status(B1)
  await expectWindow(bot2, () => bot2.chat('/missions'))
  await expectWindow(bot2, () => clickSlot(bot2, pick.slot))
  const win = await expectWindow(bot2, () => clickSlot(bot2, 9))
  assert(/Active Missions/.test(windowTitle(win)), 'bot 2: mission accepted')
  const s2 = await status(B2)
  assertEq([s2.active, s2.missions[0]?.id], [1, pick.id], 'bot 2: 1 active mission')
  const s1 = await status(B1)
  assertEq([s1.active, s1.taken.join(',')], [before1.active, before1.taken.join(',')], 'bot 1 unchanged')
  await closeAll(bot2)
}

async function eliteInfo() {
  const out = await rcon('smpeco missions elite')
  const line = out.split('\n').find((l) => l.startsWith('missions elite')) ?? out
  return { ...kv(line), name: / name=/.test(line) ? line.replace(/^.* name=/, '').trim() : '' }
}

async function eliteStatus(name) {
  const out = await rcon(`smpeco missions elite status ${name}`)
  const line = out.split('\n').find((l) => l.startsWith('missions elite status'))
  if (!line) throw new Error(`elite status ${name}: ${out}`)
  const s = kv(line)
  const [progress, amount] = s.progress.split('/').map(Number)
  return {
    id: s.id, progress, amount, done: s.done === 'true', paid: Number(s.paid), payout: Number(s.payout), balance: Number(s.balance),
    completed: Number(s.completed), earned: Number(s.earned), announced: s.announced === 'true', epic: Number(s.keys.split('/')[2]),
    killMilli: Number(s.killMilli), raw: out,
  }
}

async function eliteTests(bot1, bot2) {
  await closeAll(bot1)
  await closeAll(bot2)
  await rcon('smpeco missions elite clear')
  const info = await eliteInfo()
  assert(Number(info.pool) >= 8 && info.id && info.id !== '-', `daily elite today: ${info.id} (pool ${info.pool})`)
  assertEq((await eliteInfo()).id, info.id, 'daily elite stable (second query the same)')
  assertEq((await eliteStatus(B1)).id, (await eliteStatus(B2)).id, 'daily elite the same for all players')
  assert(Number(info.endsInMs) > 0 && Number(info.endsInMs) <= 25 * 3600_000, `reset at midnight (in ${info.endsInMs} ms)`)
  const KEYS = await moduleEnabled('crates') ? 1 : 0
  assertEq(info.keys, `${KEYS}:epic`, `reward with ${KEYS} epic keys`)
  const reward = Number(info.reward)
  assert(reward >= 1_500_000 && reward <= 2_500_000, `reward $15k-$25k (${reward})`)

  let win = await expectWindow(bot1, () => bot1.chat('/missions'))
  assert(itemLabel(win.slots[4]).includes(`Daily Elite: ${info.name}`), `daily elite on top of the board ("${itemLabel(win.slots[4])}")`)
  let lore = itemLore(win.slots[4])
  assert(lore.some((l) => /New Daily Elite in: \S/.test(l)), 'lore: countdown to midnight')
  if (KEYS) assert(lore.some((l) => /Reward: .*\$/.test(l) && /Epic Key/.test(l)), `lore: money + Epic Key (${lore.find((l) => /Reward/.test(l))})`)
  else assert(lore.some((l) => /Reward: .*\$/.test(l) && !/Key/.test(l)), `lore: money only, no key (${lore.find((l) => /Reward/.test(l))})`)
  assert(lore.some((l) => /Once per player per day/.test(l)), 'lore: once per day')
  assertEq([win.slots[3]?.name, win.slots[5]?.name], ['magenta_stained_glass_pane', 'magenta_stained_glass_pane'], 'daily elite framed')
  assertEq(itemLabel(win.slots[1]), 'Mission Board', 'info book top left')
  await closeAll(bot1)

  // warden: spawner and spawn egg wardens do not count, a natural one does; big announcement to everyone
  assert(/set ok id=warden/.test(await rcon('smpeco missions elite set warden')), "admin: today's daily elite = warden hunt")
  for (const n of [B1, B2]) await rcon(`smpeco missions elite reset ${n}`)
  await rcon(`tp ${B1} ${X + 0.5} ${Y} ${Z + 0.5}`)
  await rcon(`gamemode survival ${B1}`)
  const sel = `@e[type=minecraft:warden,x=${X},y=${Y},z=${Z},distance=..12]`
  const killWarden = async (tag) => {
    await rcon(`kill ${sel}`)
    await rcon(`summon minecraft:warden ${X + 2.5} ${Y} ${Z + 0.5} {NoAI:1b,Silent:1b,PersistenceRequired:1b${tag ? `,Tags:["${tag}"]` : ''}}`)
    await sleep(300)
    const out = await rcon(`damage ${sel.replace(']', ',limit=1]')} 1000 minecraft:player_attack by ${B1}`)
    await sleep(400)
    assert(!/passed/i.test(await rcon(`execute if entity ${sel}`)), `warden killed${tag ? ` (tag ${tag})` : ''} (${out.trim()})`)
  }
  const before = await eliteStatus(B1)
  assertEq([before.id, before.done, before.progress], ['warden', false, 0], 'warden hunt open, 0/1')
  win = await expectWindow(bot1, () => bot1.chat('/missions')) // the board stays open and refreshes each second
  assert(itemLabel(win.slots[4]).includes('Daily Elite: Warden Hunt'), 'board shows the warden hunt')
  assert(itemLore(win.slots[4]).some((l) => /Spawner, spawn egg, bred/.test(l)), 'lore names the anti-farm rule')
  await killWarden('smpeco_jobs_spawner')
  await killWarden('smpeco_jobs_egg')
  await sleep(1200)
  let st = await eliteStatus(B1)
  assertEq([st.done, st.progress, st.killMilli], [false, 0, 0], 'spawner and spawn egg wardens do not count')
  const t0 = Date.now()
  const done1 = waitForMessage(bot1, /Daily Elite complete: Warden Hunt/, 8000).then(() => true, () => false)
  const done2 = waitForMessage(bot2, new RegExp(`${B1} completed the Daily Elite Warden Hunt`), 8000).then(() => true, () => false)
  await killWarden(null)
  assert(await done1, 'player: "Daily Elite complete"')
  assert(await done2, 'other players: announcement in chat')
  await sleep(500)
  assert(bot1.titles.some((t) => t.at >= t0 && t.kind === 'title' && /COMPLETE!/.test(t.text)), 'player: title "COMPLETE!"')
  assert(bot2.titles.some((t) => t.at >= t0 && t.kind === 'title' && /DAILY ELITE/.test(t.text)), 'others: title "DAILY ELITE"')
  assert(bot2.titles.some((t) => t.at >= t0 && t.kind === 'subtitle' && new RegExp(B1).test(t.text)), 'others: subtitle with the name')
  st = await eliteStatus(B1)
  assertEq([st.done, st.paid, st.completed], [true, 2_000_000, before.completed + 1], 'done, $20,000 paid')
  assert(st.balance - before.balance >= 2_000_000, `balance +$20,000 (${st.balance - before.balance})`)
  assertEq(st.epic, before.epic + KEYS, `${KEYS} epic keys`)
  await sleep(1300)
  win = bot1.currentWindow
  assert(win && /✔ Daily Elite/.test(itemLabel(win.slots[4])), `open board shows ✔ (${itemLabel(win?.slots[4])})`)
  assert(itemLore(win.slots[4]).some((l) => /Completed today/.test(l)), 'lore: done today')
  await killWarden(null)
  await sleep(1200)
  const after = await eliteStatus(B1)
  assertEq([after.completed, after.epic, after.paid], [st.completed, st.epic, st.paid], 'second warden: no second reward')
  assert(after.balance - st.balance < 2_000_000, 'no second payment')
  assertEq((await eliteStatus(B2)).done, false, 'bot 2 still has it open (per player)')
  await closeAll(bot1)

  assert(/set ok id=netherite_block/.test(await rcon('smpeco missions elite set netherite_block')), 'admin: daily elite = netherite block')
  await rcon(`smpeco missions elite reset ${B1}`)
  await rcon(`clear ${B1}`)
  let res = await rcon(`give ${B1} minecraft:netherite_block[custom_name="Fake"] 1`)
  if (!/Gave/i.test(res)) res = await rcon(`give ${B1} minecraft:netherite_block[custom_name='"Fake"'] 1`)
  await waitFor(async () => countItems(bot1, 'netherite_block') >= 1, 5000, 'renamed netherite block')
  const d0 = await eliteStatus(B1)
  const cap = Number((await rcon(`smpeco price minecraft:netherite_block ${B1}`)).match(/npcOrderMax=(\d+)/)?.[1] ?? NaN)
  assert(d0.payout > 0 && d0.payout <= 2_200_000, `payout $0 < ${d0.payout} <= $22,000`)
  assert(!Number.isNaN(cap) && d0.payout <= cap, `payout <= price cap (${d0.payout} <= ${cap})`)
  win = await expectWindow(bot1, () => bot1.chat('/missions'))
  assert(itemLore(win.slots[4]).some((l) => /In inventory: 0\/1/.test(l)), 'lore counts only unchanged items (0/1)')
  const modified = waitForMessage(bot1, /Only unmodified items count/, 4000).then(() => true, () => false)
  assert(await expectNoWindow(bot1, () => clickSlot(bot1, 4)), 'no dialog with a renamed item')
  assert(await modified, 'pristine rule error')
  await closeAll(bot1)
  await rcon(`give ${B1} minecraft:netherite_block 1`)
  await waitFor(async () => countItems(bot1, 'netherite_block', (it) => it.customName == null) >= 1, 5000, 'netherite block received')
  // dialog open, admin switches to ancient debris (40 in the inventory): confirm only applies to the delivery
  // shown
  win = await expectWindow(bot1, () => bot1.chat('/missions'))
  const stale = await expectWindow(bot1, () => clickSlot(bot1, 4))
  assert(itemLabel(stale.slots[13]).includes('Netherite Treasure'), 'dialog shows Netherite Treasure')
  assert(/set ok id=ancient_debris/.test(await rcon('smpeco missions elite set ancient_debris')), 'admin: daily elite = ancient debris (dialog open)')
  await rcon(`give ${B1} minecraft:ancient_debris 40`)
  await waitFor(async () => countItems(bot1, 'ancient_debris') >= 40, 5000, '40 ancient debris received')
  const switched = waitForMessage(bot1, /The Daily Elite has changed/, 6000).then(() => true, () => false)
  win = await expectWindow(bot1, () => clickSlot(bot1, 9))
  assert(await switched, 'confirm after the switch: "The Daily Elite has changed"')
  assert(/Mission Board/.test(windowTitle(win)) && itemLabel(win.slots[4]).includes('Daily Elite: Deep Mining'), 'board shows the new daily elite')
  await sleep(300)
  assertEq(countItems(bot1, 'ancient_debris'), 40, 'ancient debris not taken')
  assertEq(countItems(bot1, 'netherite_block', (it) => it.customName == null), 1, 'netherite block not taken')
  const dSwitch = await eliteStatus(B1)
  assertEq([dSwitch.done, dSwitch.balance, dSwitch.epic], [false, d0.balance, d0.epic], 'nothing paid after the switch')
  await closeAll(bot1)
  await rcon(`clear ${B1} minecraft:ancient_debris`)
  assert(/set ok id=netherite_block/.test(await rcon('smpeco missions elite set netherite_block')), 'admin: back to netherite block')
  win = await expectWindow(bot1, () => bot1.chat('/missions'))
  const dialog = await expectWindow(bot1, () => clickSlot(bot1, 4))
  assert(/Hand In Daily Elite/.test(windowTitle(dialog)), `confirm dialog (${windowTitle(dialog)})`)
  assert(itemLabel(dialog.slots[13]).includes('Netherite Treasure'), 'preview shows the daily elite')
  const delivered = waitForMessage(bot1, /Daily Elite complete: Netherite Treasure/, 6000).then(() => true, () => false)
  win = await expectWindow(bot1, () => clickSlot(bot1, 9))
  assert(await delivered, 'delivery: "Daily Elite complete"')
  assert(/Mission Board/.test(windowTitle(win)), 'board again afterwards')
  const d1 = await eliteStatus(B1)
  assertEq([d1.done, d1.paid], [true, d0.payout], 'delivery paid (= earlier payout)')
  assertEq(d1.balance - d0.balance, d0.payout, 'balance + payout')
  assertEq(d1.epic, d0.epic + KEYS, `${KEYS} epic keys`)
  await sleep(300)
  assertEq(countItems(bot1, 'netherite_block', (it) => it.customName == null), 0, 'netherite block handed in')
  assertEq(countItems(bot1, 'netherite_block', (it) => it.customName != null), 1, 'renamed one stays')
  await rcon(`give ${B1} minecraft:netherite_block 1`)
  await sleep(600)
  win = await expectWindow(bot1, () => bot1.chat('/missions'))
  const again = waitForMessage(bot1, /already completed today/, 4000).then(() => true, () => false)
  assert(await expectNoWindow(bot1, () => clickSlot(bot1, 4)), 'no dialog: already done today')
  assert(await again, 'message "already completed today"')
  const d2 = await eliteStatus(B1)
  assertEq([d2.balance, d2.epic], [d1.balance, d1.epic], 'no second delivery')
  await closeAll(bot1)
  assertEq(countItems(bot1, 'netherite_block', (it) => it.customName == null), 1, 'item stays on the second try')
  await rcon(`clear ${B1}`)

  // hint on the first join of the day, not on every join
  await rcon('smpeco missions elite clear')
  await rcon(`smpeco missions reset ${B3}`)
  const today = await eliteInfo()
  let bot3 = await connectBot(B3)
  const hint = await waitForMessage(bot3, /Daily Elite: (.+?) – /, 12000, { includePast: true }).catch(() => null)
  assert(!!hint && hint[1].trim() === today.name, `join hint for the daily elite (${hint?.[0] ?? '-'})`)
  assert(bot3.messages.some((m) => /\[View\]/.test(m)), 'hint with [View] click')
  assertEq((await eliteStatus(B3)).announced, true, 'hint noted')
  bot3.quit()
  await sleep(1500)
  bot3 = await connectBot(B3)
  await sleep(6000)
  assert(!bot3.messages.some((m) => /Daily Elite: /.test(m)), 'no second hint on the same day')
  bot3.quit()
  await sleep(500)
  await rcon(`smpeco missions reset ${B3}`)
  for (const n of [B1, B2]) await rcon(`smpeco missions elite reset ${n}`)
  await rcon(`kill ${sel}`)
  await rcon(`kill @e[type=minecraft:item,x=${X},y=${Y},z=${Z},distance=..24]`)
}
