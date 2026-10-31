// Jobs: GUI (overview, joining, switching with confirmation and cooldown, action bar switch), real bot actions
// (digging, ore including veinminer per block, ripe and unripe crops, right-click harvest, bone meal
// anti-exploit, wood, mob kills including spawner and bred marks and a respawned ender dragon, fishing), payout
// and action bar, level-up, commands with a second bot. Amounts are checked in thousandths of a cent through
// "jobs admin info".
import {
  connectBot, rcon, runE2E, assert, assertEq, sleep, waitForMessage, expectWindow, clickSlot, findSlot,
  itemLabel, itemLore, windowTitle, Vec3,
} from './lib.mjs'

const B1 = 'jobsBot1'
const B2 = 'jobsBot2'
const X = 4300, Y = 200, Z = 4300
const JOBS = ['miner', 'lumberjack', 'farmer', 'hunter', 'fisher', 'digger']


async function info(name) {
  const out = await rcon(`jobs admin info ${name}`)
  const m = out.match(/job=(\S+) level=(\d+) xp=(\d+) cooldownMs=(\d+) balance=(\d+) pendingMilli=(\d+) earned=(\d+) mult=([\d.]+) actionbar=(true|false)/)
  if (!m) throw new Error(`jobs admin info ${name}: ${out}`)
  return { job: m[1], level: +m[2], xp: +m[3], cooldownMs: +m[4], balance: +m[5], pending: +m[6], earned: +m[7], mult: +m[8], actionbar: m[9] === 'true' }
}

// force the payout and read the state; fractions stay as pendingMilli for online players
async function snapshot(name) {
  await rcon('jobs admin flush')
  return info(name)
}

const earnedMilli = (a, b) => (b.balance - a.balance) * 1000 + (b.pending - a.pending)

/**
 * Runs an action and checks the wage in thousandths of a cent: floor(base cents * 1000 * factor). The level is
 * set to 1 with 0 XP first, so a level-up mid-measurement cannot change the factor.
 */
async function expectEarn(name, job, label, baseCents, action, { factor = 1, count = 1 } = {}) {
  await rcon(`jobs admin level ${name} ${job} 1 0`)
  const before = await snapshot(name)
  await action()
  await sleep(600)
  const after = await snapshot(name)
  const n = typeof count === 'function' ? await count() : count
  const expected = n * Math.floor(baseCents * 1000 * before.mult * factor + 1e-6)
  const got = earnedMilli(before, after)
  assert(Math.abs(got - expected) <= 2 * Math.max(1, n), `${label}: ${got} milli (expected ${expected}, factor ${before.mult})`)
  return { before, after, got }
}

async function equip(bot, itemName) {
  for (let i = 0; i < 30 && !bot.inventory.items().some((it) => it.name === itemName); i++) await sleep(100)
  const item = bot.inventory.items().find((it) => it.name === itemName)
  if (!item) throw new Error(`${itemName} not in inventory`)
  await bot.equip(item, 'hand')
}

/**
 * Only for tools that do not mine: mineflayer cannot compute mining times with 1.21.11 enchantments. Falls back
 * to the plain item on unknown syntax.
 */
async function giveTool(name, item, enchant) {
  if (enchant) {
    const out = await rcon(`give ${name} minecraft:${item}[minecraft:enchantments={${enchant}}]`)
    if (/Gave/i.test(out)) return
  }
  await rcon(`give ${name} minecraft:${item}`)
}

async function setblock(x, y, z, block) {
  await rcon(`setblock ${x} ${y} ${z} ${block}`)
}

// block updates after setblock arrive asynchronously
async function waitBlock(bot, x, y, z, name, timeoutMs = 3000) {
  for (let t = 0; t < timeoutMs; t += 100) {
    if (bot.blockAt(new Vec3(x, y, z))?.name === name) return true
    await sleep(100)
  }
  return false
}

async function dig(bot, x, y, z) {
  const block = bot.blockAt(new Vec3(x, y, z))
  if (!block || block.name === 'air') throw new Error(`no block at ${x} ${y} ${z}`)
  await bot.dig(block, true)
}

async function placed(x, y, z) {
  const out = await rcon(`smpeco placed ${x} ${y} ${z}`)
  const m = out.match(/placed=(true|false)/)
  return m ? m[1] === 'true' : null
}

function age(bot, x, y, z) {
  const props = bot.blockAt(new Vec3(x, y, z))?.getProperties?.() ?? {}
  return props.age === undefined ? -1 : Number(props.age)
}

async function openJobs(bot) {
  const win = await expectWindow(bot, () => bot.chat('/jobs'))
  assert(/Jobs/.test(windowTitle(win)), `jobs GUI opened ("${windowTitle(win)}")`)
  return win
}

/**
 * The server closes the currently open menu on a close packet without checking the ID, so a late close packet
 * would shut a menu opened by command right after.
 */
async function closeWindow(bot) {
  if (bot.currentWindow) bot.closeWindow(bot.currentWindow)
  await sleep(400)
}

/**
 * Daily tasks pay into the account on completion. "Diamond fever" (8 diamond ore) can complete during a wage
 * measurement after several runs on one day and would count as job pay. Sets all slots to a task this test
 * never serves (crafting arrows); the bot must be online.
 */
async function neutralizeTasks(name) {
  const out = await rcon(`smpeco tasks info ${name}`)
  const total = Number(out.match(/done=\d+\/(\d+)/)?.[1] ?? 0)
  for (let slot = 1; slot <= total; slot++) {
    const r = await rcon(`smpeco tasks set ${name} ${slot} fletcher 64`)
    if (!/tasks set slot=/.test(r)) console.log(`[E2E] INFO task in slot ${slot} not replaced: ${r.trim()}`)
  }
}

async function resetBot(name) {
  await rcon(`gamemode survival ${name}`)
  await rcon(`clear ${name}`)
  await rcon(`jobs admin set ${name} none`)
  for (const j of JOBS) await rcon(`jobs admin level ${name} ${j} 1 0`)
  await neutralizeTasks(name)
  await rcon(`effect give ${name} minecraft:resistance 600 4 true`)
  await rcon(`effect give ${name} minecraft:saturation 600 1 true`)
}


runE2E(async () => {
  const st = await rcon('smpeco selftest jobs')
  const sm = st.match(/SUMMARY passed=(\d+) failed=(\d+)/)
  assert(!!sm && Number(sm[1]) > 0, `jobs selftests ran (${st.slice(0, 200).replace(/\n/g, ' ')})`)
  if (sm) assertEq(Number(sm[2]), 0, 'Selftests jobs failed=0')

  const bot = await connectBot(B1)
  await rcon(`forceload add ${X - 16} ${Z - 16} ${X + 16} ${Z + 16}`)
  await sleep(1500)
  await rcon(`fill ${X - 10} ${Y - 4} ${Z - 10} ${X + 10} ${Y + 20} ${Z + 10} minecraft:air`)
  await rcon(`fill ${X - 6} ${Y - 1} ${Z - 6} ${X + 6} ${Y - 1} ${Z + 6} minecraft:glass`)
  await resetBot(B1)
  await rcon(`tp ${B1} ${X + 0.5} ${Y} ${Z + 0.5} 0 30`)
  await sleep(1500)

  try {
    await guiAndDigger(bot)
    await minerTests(bot)
    await farmerTests(bot)
    await lumberjackTests(bot)
    await hunterTests(bot)
    await fisherTests(bot)
    await commandTests()
  } finally {
    await closeWindow(bot)
    bot.setControlState('sneak', false)
    await rcon(`kill @e[type=minecraft:husk,x=${X},y=${Y},z=${Z},distance=..20]`)
    await rcon(`kill @e[type=minecraft:item,x=${X},y=${Y},z=${Z},distance=..20]`)
    await rcon(`fill ${X - 10} ${Y - 4} ${Z - 10} ${X + 10} ${Y + 20} ${Z + 10} minecraft:air`)
    await rcon(`forceload remove ${X - 16} ${Z - 16} ${X + 16} ${Z + 16}`)
    for (const name of [B1, B2]) {
      await rcon(`clear ${name}`)
      await rcon(`jobs admin set ${name} none`)
    }
  }
}, { timeoutMs: 360000 })

async function guiAndDigger(bot) {
  let win = await openJobs(bot)
  const names = ['Miner', 'Lumberjack', 'Farmer', 'Hunter', 'Fisher', 'Digger']
  for (const n of names) assert(findSlot(win, new RegExp('^' + n)) >= 0, `GUI shows job ${n}`)
  assert(/Your Job: none/.test(itemLabel(win.slots[4])), `info: no job yet ("${itemLabel(win.slots[4])}")`)
  const digSlot = findSlot(win, /^Digger/)
  const lore = itemLore(win.slots[digSlot])
  assert(lore.some((l) => /Example rewards/.test(l)), 'Lore: Example rewards')
  assert(lore.some((l) => /\$\d/.test(l) && /XP/.test(l)), `lore: amounts with XP (${lore.find((l) => /\$/.test(l))})`)
  assert(lore.some((l) => /Click to join/.test(l)), 'Lore: Click to join')

  const joined = waitForMessage(bot, /Your job is now Digger/, 5000).then(() => true, () => false)
  await clickSlot(bot, digSlot)
  assert(await joined, 'click on Digger joins')
  await sleep(300)
  win = bot.currentWindow
  assert(win && /Digger/.test(itemLabel(win.slots[4])), 'info shows the new job')
  assert(win && /active/.test(itemLabel(win.slots[findSlot(win, /^Digger/)])), 'Digger marked active')
  await closeWindow(bot)
  const i1 = await info(B1)
  assertEq(i1.job, 'digger', 'job saved')
  assert(i1.cooldownMs > 25 * 60_000, `switch cooldown running (${i1.cooldownMs} ms)`)

  await setblock(X + 2, Y, Z, 'minecraft:dirt')
  await setblock(X + 2, Y, Z + 1, 'minecraft:gravel')
  await sleep(300)
  await expectEarn(B1, 'digger', 'Digger: dirt', 1, () => dig(bot, X + 2, Y, Z))
  const ab = waitForMessage(bot, /\+\$[\d.,]+ \(Digger\)/, 8000).then((m) => m[0], () => null)
  await expectEarn(B1, 'digger', 'Digger: gravel', 1.5, () => dig(bot, X + 2, Y, Z + 1))
  const abText = await ab
  assert(!!abText, `action bar with wage (${abText})`)

  await rcon(`give ${B1} minecraft:dirt 1`)
  await equip(bot, 'dirt')
  await bot.placeBlock(bot.blockAt(new Vec3(X + 1, Y - 1, Z - 2)), new Vec3(0, 1, 0))
  await sleep(400)
  assertEq(await placed(X + 1, Y, Z - 2), true, 'placed dirt is marked')
  await expectEarn(B1, 'digger', 'Digger: placed dirt pays nothing', 0, () => dig(bot, X + 1, Y, Z - 2))

  win = await openJobs(bot)
  const off = waitForMessage(bot, /Job Action Bar: off/, 5000).then(() => true, () => false)
  await clickSlot(bot, 38)
  assert(await off, 'action bar switched off through the GUI')
  await closeWindow(bot)
  assertEq((await info(B1)).actionbar, false, 'setting job_actionbar saved')
  await setblock(X + 2, Y, Z, 'minecraft:clay')
  await sleep(300)
  const silent = waitForMessage(bot, /\(Digger\)/, 3000).then(() => false, () => true)
  await expectEarn(B1, 'digger', 'Digger: clay (action bar off)', 3, () => dig(bot, X + 2, Y, Z))
  assert(await silent, 'no action bar with the setting off')
  win = await openJobs(bot)
  const on = waitForMessage(bot, /Job Action Bar: on/, 5000).then(() => true, () => false)
  await clickSlot(bot, 38)
  assert(await on, 'action bar switched on again')
  await closeWindow(bot)

  await rcon(`jobs admin level ${B1} digger 1 1000000`)
  await setblock(X + 2, Y, Z, 'minecraft:dirt')
  await sleep(300)
  const lvl = waitForMessage(bot, /Level up!.*Digger.*level 2/, 5000).then(() => true, () => false)
  await dig(bot, X + 2, Y, Z)
  assert(await lvl, 'level-up message')
  assertEq((await info(B1)).level, 2, 'level 2 saved')
}

async function minerTests(bot) {
  let win = await openJobs(bot)
  const minerSlot = findSlot(win, /^Miner/)
  assert(itemLore(win.slots[minerSlot]).some((l) => /You can switch in/.test(l)), 'lore shows the cooldown')
  const blocked = waitForMessage(bot, /You can switch jobs in/, 5000).then(() => true, () => false)
  await clickSlot(bot, minerSlot)
  assert(await blocked, 'switch during cooldown refused')
  assert(bot.currentWindow && /Jobs/.test(windowTitle(bot.currentWindow)), 'no confirm dialog during cooldown')
  await closeWindow(bot)
  await rcon(`jobs admin cooldown ${B1}`)
  win = await openJobs(bot)
  const dialog = await expectWindow(bot, () => clickSlot(bot, findSlot(win, /^Miner/)))
  assert(/Switch Job/.test(windowTitle(dialog)), 'confirm dialog on switching')
  const switched = waitForMessage(bot, /Your job is now Miner/, 5000).then(() => true, () => false)
  const back = await expectWindow(bot, () => clickSlot(bot, 9))
  assert(await switched, 'switch to Miner confirmed')
  assert(/Jobs/.test(windowTitle(back)), 'overview again afterwards')
  await closeWindow(bot)
  assertEq((await info(B1)).job, 'miner', 'Job = miner')

  await giveTool(B1, 'diamond_pickaxe', null)
  await equip(bot, 'diamond_pickaxe')
  await setblock(X + 2, Y, Z, 'minecraft:diamond_ore')
  await setblock(X + 2, Y, Z + 1, 'minecraft:stone')
  await sleep(300)
  await expectEarn(B1, 'miner', 'Miner: diamond ore', 250, () => dig(bot, X + 2, Y, Z))
  await expectEarn(B1, 'miner', 'Miner: stone', 1, () => dig(bot, X + 2, Y, Z + 1))

  await rcon(`give ${B1} minecraft:stone 1`)
  await equip(bot, 'stone')
  await bot.placeBlock(bot.blockAt(new Vec3(X + 2, Y - 1, Z - 1)), new Vec3(0, 1, 0))
  await sleep(400)
  await equip(bot, 'diamond_pickaxe')
  await expectEarn(B1, 'miner', 'Miner: placed stone pays nothing', 0, () => dig(bot, X + 2, Y, Z - 1))

  // veinminer (sneaking): each block of the vein counts on its own, whether veinminer is active or not
  const vein = [[X - 2, Y, Z], [X - 3, Y, Z], [X - 4, Y, Z]]
  for (const [x, y, z] of vein) await setblock(x, y, z, 'minecraft:iron_ore')
  await sleep(1200) // wait out the veinminer cooldown (20 ticks) after the last break
  let broken = 0
  await expectEarn(B1, 'miner', 'Miner: iron ore vein (per block)', 25, async () => {
    bot.setControlState('sneak', true)
    await sleep(300)
    try {
      await dig(bot, X - 2, Y, Z)
    } finally {
      await sleep(1000)
      bot.setControlState('sneak', false)
    }
  }, { count: async () => {
    broken = vein.filter(([x, y, z]) => bot.blockAt(new Vec3(x, y, z))?.name !== 'iron_ore').length
    return broken
  } })
  console.log(`[E2E] INFO veinminer broke ${broken} block(s)${broken > 1 ? ' (active)' : ' (not active or no sneak detected)'}`)
  assert(broken >= 1, 'at least the clicked block broken')
}

async function farmerTests(bot) {
  await rcon(`jobs admin set ${B1} farmer`)
  const fx = X + 2, fz = Z + 2
  await setblock(fx, Y - 1, fz, 'minecraft:farmland')
  await setblock(fx, Y, fz, 'minecraft:wheat[age=3]')
  await sleep(300)
  await expectEarn(B1, 'farmer', 'Farmer: unripe wheat pays nothing', 0, () => dig(bot, fx, Y, fz))
  await setblock(fx, Y - 1, fz, 'minecraft:farmland')
  await setblock(fx, Y, fz, 'minecraft:wheat[age=7]')
  await sleep(300)
  await expectEarn(B1, 'farmer', 'Farmer: ripe wheat', 4, () => dig(bot, fx, Y, fz))

  // right-click harvest only counts with RightClickHarvest on the server (wheat back at age 0 afterwards)
  await setblock(fx, Y - 1, fz, 'minecraft:farmland')
  await setblock(fx, Y, fz, 'minecraft:wheat[age=7]')
  await sleep(300)
  await bot.unequip('hand').catch(() => {})
  let rch = false
  await expectEarn(B1, 'farmer', 'Farmer: right-click harvest', 4, async () => {
    await bot.activateBlock(bot.blockAt(new Vec3(fx, Y, fz)))
    await sleep(500)
  }, { count: async () => {
    rch = age(bot, fx, Y, fz) === 0
    return rch ? 1 : 0
  } })
  console.log(`[E2E] INFO RightClickHarvest ${rch ? 'active, right-click harvest paid' : 'not active, right click does not harvest'}`)

  // bone meal: instantly ripe pays nothing (anti-exploit), the mark vanishes on breaking
  await setblock(fx, Y, fz, 'minecraft:air')
  await setblock(fx, Y - 1, fz, 'minecraft:farmland')
  await setblock(fx, Y, fz, 'minecraft:wheat[age=0]')
  await rcon(`give ${B1} minecraft:bone_meal 32`)
  await equip(bot, 'bone_meal')
  await waitBlock(bot, fx, Y, fz, 'wheat')
  for (let i = 0; i < 12 && age(bot, fx, Y, fz) < 7; i++) {
    await bot.activateBlock(bot.blockAt(new Vec3(fx, Y, fz)))
    await sleep(250)
  }
  assertEq(age(bot, fx, Y, fz), 7, 'wheat ripe through bone meal')
  await bot.unequip('hand').catch(() => {})
  await expectEarn(B1, 'farmer', 'Farmer: bone meal wheat pays nothing', 0, () => dig(bot, fx, Y, fz))
  await setblock(fx, Y - 1, fz, 'minecraft:farmland')
  await setblock(fx, Y, fz, 'minecraft:wheat[age=7]')
  await sleep(300)
  await expectEarn(B1, 'farmer', 'Farmer: wheat grown afterwards pays again', 4, () => dig(bot, fx, Y, fz))
}

async function lumberjackTests(bot) {
  await rcon(`jobs admin set ${B1} lumberjack`)
  await giveTool(B1, 'diamond_axe', null)
  await equip(bot, 'diamond_axe')
  await setblock(X + 2, Y, Z - 2, 'minecraft:oak_log')
  await sleep(300)
  await expectEarn(B1, 'lumberjack', 'Lumberjack: oak log', 3, () => dig(bot, X + 2, Y, Z - 2))

  const sx = X - 3, sz = Z - 3
  await setblock(sx, Y - 1, sz, 'minecraft:dirt')
  await setblock(sx, Y, sz, 'minecraft:oak_sapling')
  await rcon(`give ${B1} minecraft:bone_meal 64`)
  await equip(bot, 'bone_meal')
  assert(await waitBlock(bot, sx, Y, sz, 'oak_sapling'), 'sapling placed')
  let tries = 0
  for (; tries < 40 && bot.blockAt(new Vec3(sx, Y, sz))?.name === 'oak_sapling'; tries++) {
    await bot.activateBlock(bot.blockAt(new Vec3(sx, Y, sz)))
    await sleep(250)
  }
  await sleep(300)
  const trunk = bot.blockAt(new Vec3(sx, Y, sz))?.name
  console.log(`[E2E] INFO bone meal tree: ${tries} tries, block ${trunk}, bot at ${bot.entity.position}, hand ${bot.heldItem?.name} x${bot.heldItem?.count}`)
  assertEq(trunk, 'oak_log', 'tree grown with bone meal')
  assertEq(await placed(sx, Y, sz), true, 'log from a bone meal tree counts as placed')
  assertEq(await placed(sx, Y + 1, sz), true, 'also the log above')
  await equip(bot, 'diamond_axe')
  if (trunk === 'oak_log') await expectEarn(B1, 'lumberjack', 'Lumberjack: bone meal tree pays nothing', 0, () => dig(bot, sx, Y, sz))
}

/**
 * A hostile mob (husk, does not burn) pays; with a spawner tag only x spawnerMobFactor (default 0.25), with a
 * bred tag (bred or multiplied, such as shulker splitting) x bredMobFactor (default 0).
 */
async function hunterTests(bot) {
  await rcon(`jobs admin set ${B1} hunter`)
  await giveTool(B1, 'netherite_sword', 'sharpness:5')
  await equip(bot, 'netherite_sword')
  const kill = async (tags) => {
    await rcon(`kill @e[type=minecraft:husk,x=${X},y=${Y},z=${Z},distance=..20]`)
    await rcon(`summon minecraft:husk ${X + 2} ${Y} ${Z} {NoAI:1b,Silent:1b,PersistenceRequired:1b${tags ? `,Tags:["${tags}"]` : ''}}`)
    await sleep(800)
    for (let i = 0; i < 15; i++) {
      const husk = bot.nearestEntity((e) => e.name === 'husk')
      if (!husk) break
      await bot.lookAt(husk.position.offset(0, 1, 0))
      bot.attack(husk)
      await sleep(700)
    }
    assert(!bot.nearestEntity((e) => e.name === 'husk'), `husk killed${tags ? ` (tag ${tags})` : ''}`)
  }
  await expectEarn(B1, 'hunter', 'Hunter: Husk', 30, () => kill(null))
  await expectEarn(B1, 'hunter', 'Hunter: Spawner-Husk × 0.25', 30, () => kill('smpeco_jobs_spawner'), { factor: 0.25 })
  await expectEarn(B1, 'hunter', 'Hunter: bred mob pays nothing', 30, () => kill('smpeco_jobs_bred'), { factor: 0 })

  // Ender dragon: the world's first pays in full, a respawned one (tag from the EndDragonFight mixin) pays
  // nothing (respawnedDragonFactor 0). NoAI high in the air means no flight path or block damage; the fatal hit
  // comes through /damage "by" the bot.
  const dragon = async (tag) => {
    const sel = '@e[type=minecraft:ender_dragon,tag=smpeco_e2e_dragon]'
    await rcon(`kill ${sel}`)
    await rcon(`summon minecraft:ender_dragon ${X} ${Y + 60} ${Z} {NoAI:1b,Silent:1b,Tags:["smpeco_e2e_dragon"${tag ? `,"${tag}"` : ''}]}`)
    await sleep(500)
    const out = await rcon(`damage @e[type=minecraft:ender_dragon,tag=smpeco_e2e_dragon,limit=1] 1000 minecraft:player_attack by ${B1}`)
    console.log(`[E2E] INFO Ender Dragon${tag ? ` (${tag})` : ''}: ${out}`)
    await sleep(300)
    await rcon(`kill ${sel}`) // stays in the dying phase with NoAI; kill() pays no wage
  }
  await expectEarn(B1, 'hunter', 'Hunter: ender dragon (first fight)', 10000, () => dragon(null))
  await expectEarn(B1, 'hunter', 'Hunter: respawned ender dragon pays nothing', 10000, () => dragon('smpeco_jobs_respawned'), { factor: 0 })
}

async function fisherTests(bot) {
  await rcon(`jobs admin set ${B1} fisher`)
  // pool with glass walls so no water runs down
  await rcon(`fill ${X + 1} ${Y - 4} ${Z - 5} ${X + 9} ${Y - 1} ${Z + 5} minecraft:glass`)
  await rcon(`fill ${X + 2} ${Y - 3} ${Z - 4} ${X + 8} ${Y - 1} ${Z + 4} minecraft:water`)
  await giveTool(B1, 'fishing_rod', 'lure:3')
  await equip(bot, 'fishing_rod')
  await bot.lookAt(new Vec3(X + 3.5, Y - 1, Z + 0.5), true)
  await rcon(`jobs admin level ${B1} fisher 1 0`)
  const before = await snapshot(B1)
  let caught = false
  // up to 3 casts: a bobber landing badly (edge, glass) never gets a bite, so reel in and cast again
  for (let attempt = 1; attempt <= 3 && !caught; attempt++) {
    try {
      await Promise.race([bot.fish(), sleep(40000).then(() => { throw new Error(`no bite in 40 s (cast ${attempt})`) })])
      caught = true
    } catch (err) {
      console.log(`[E2E] INFO fishing: ${err.message}`)
      try { bot.activateItem() } catch {}
      await sleep(1000)
      await bot.lookAt(new Vec3(X + 4.5 + attempt, Y - 1, Z + 0.5), true)
    }
  }
  await sleep(800)
  const after = await snapshot(B1)
  assert(caught, 'fish caught (mineflayer bot.fish)')
  const got = earnedMilli(before, after)
  const min = Math.floor(5 * 1000 * before.mult + 1e-6)
  const max = Math.floor(100 * 1000 * before.mult + 1e-6) * 2
  if (caught) assert(got >= min && got <= max, `Fisher: catch paid (${got} milli, ${min}..${max})`)
  await rcon(`fill ${X + 1} ${Y - 4} ${Z - 5} ${X + 9} ${Y - 1} ${Z + 5} minecraft:air`)
  await rcon(`fill ${X + 1} ${Y - 1} ${Z - 5} ${X + 6} ${Y - 1} ${Z + 5} minecraft:glass`)
}

async function commandTests() {
  const bot2 = await connectBot(B2)
  await resetBot(B2)
  await sleep(500)
  let m = waitForMessage(bot2, /Your job is now Lumberjack/, 5000).then(() => true, () => false)
  bot2.chat('/jobs join Lumberjack')
  assert(await m, '/jobs join Lumberjack (display name instead of ID)')
  m = waitForMessage(bot2, /Lumberjack is already your job/, 5000).then(() => true, () => false)
  bot2.chat('/jobs join lumberjack')
  assert(await m, '/jobs join lumberjack (English name): already your job')
  m = waitForMessage(bot2, /You can switch jobs in/, 5000).then(() => true, () => false)
  bot2.chat('/jobs join miner')
  assert(await m, '/jobs join miner during cooldown refused')
  m = waitForMessage(bot2, /Unknown job/, 5000).then(() => true, () => false)
  bot2.chat('/jobs join baker')
  assert(await m, 'unknown job refused')
  m = waitForMessage(bot2, /Your job: Lumberjack/, 5000).then(() => true, () => false)
  const lvl = waitForMessage(bot2, /Level: 1/, 5000).then(() => true, () => false)
  bot2.chat('/jobs info')
  assert(await m, '/jobs info shows the job')
  assert(await lvl, '/jobs info shows the level')
  m = waitForMessage(bot2, /• .+ \$\d+\.\d\d.* XP/, 5000).then(() => true, () => false)
  bot2.chat('/jobs info miner')
  assert(await m, '/jobs info miner lists rewards')
  m = waitForMessage(bot2, /You left your job/, 5000).then(() => true, () => false)
  bot2.chat('/jobs leave')
  assert(await m, '/jobs leave')
  assertEq((await info(B2)).job, 'none', 'job empty after leaving')
  m = waitForMessage(bot2, /You can switch jobs in/, 5000).then(() => true, () => false)
  bot2.chat('/jobs join miner')
  assert(await m, 'cooldown runs after leaving')
  m = waitForMessage(bot2, /You don't have a job/, 5000).then(() => true, () => false)
  bot2.chat('/jobs leave')
  assert(await m, 'leaving twice gives an error')
  const win = await expectWindow(bot2, () => bot2.chat('/job'))
  assert(/Jobs/.test(windowTitle(win)), 'alias /job opens the GUI')
  bot2.closeWindow(win)
  await sleep(300)
  assertEq(bot2.inventory.items().length, 0, 'no GUI items in the inventory')
  bot2.quit()
}
