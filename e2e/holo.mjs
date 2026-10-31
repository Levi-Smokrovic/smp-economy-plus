// Leaderboard holograms (text_display): selftests, permissions, /holo create by command and admin GUI, text and
// style on the real entity, no duplicates (refresh, foreign copies with our tags, moving, chunk unload and
// reload), respawn in a loaded but non-ticking chunk, removal through the GUI and /holo remove, console.
import {
  connectBot, rcon, assert, assertEq, sleep, runE2E, waitForMessage, clickSlot, findSlot, itemLore, windowTitle, expectWindow,
} from './lib.mjs'

const BOT = 'holoBot1'
const BOT2 = 'holoBot2'
const X = 5300, Y = 120, Z = -5300
const ALL_HERE = `@e[type=minecraft:text_display,tag=smpeco_holo,x=${X},y=${Y},z=${Z},distance=..30]`
const byId = (id) => `@e[type=minecraft:text_display,tag=smpeco_holo_${id}]`

async function count(selector) {
  const out = await rcon(`execute if entity ${selector}`)
  const m = out.match(/count:?\s*(\d+)/i)
  if (m) return Number(m[1])
  if (/failed/i.test(out)) return 0
  throw new Error(`execute if entity ${selector}: ${out}`)
}

async function waitFor(fn, timeoutMs, stepMs = 500) {
  const end = Date.now() + timeoutMs
  let last
  while (Date.now() < end) {
    last = await fn()
    if (last) return last
    await sleep(stepMs)
  }
  return last
}

const holoText = (id) => rcon(`holo text ${id}`)

function parsePos(out) {
  const m = out.match(/pos=(-?[\d.]+),(-?[\d.]+),(-?[\d.]+)/)
  return m ? m.slice(1, 4).map(Number) : null
}

async function cleanupArea() {
  const out = await rcon('holo list')
  const re = /#(\d+) \S+ » (\S+) (-?[\d.]+), (-?[\d.]+), (-?[\d.]+)/g
  let m
  let removed = 0
  while ((m = re.exec(out)) !== null) {
    const [, id, dim, x, , z] = m
    if (dim === 'overworld' && Math.abs(Number(x) - X) < 60 && Math.abs(Number(z) - Z) < 60) {
      await rcon(`holo remove ${id}`)
      removed++
    }
  }
  if (removed) console.log(`[E2E] INFO ${removed} old test holograms removed`)
}

async function tpBot(x, y, z) {
  await rcon(`tp ${BOT} ${x} ${y} ${z}`)
  await sleep(600)
}

async function chatExpect(bot, cmd, regex, timeoutMs = 8000) {
  const p = waitForMessage(bot, regex, timeoutMs)
  bot.chat(cmd)
  return p
}

/**
 * Takes op, creative mode and the test money back from the bot. Runs before each run, since the global timeout
 * can end a run with process.exit, and in the finally block.
 */
async function resetBot() {
  for (const cmd of [`eco reset ${BOT}`, `gamemode survival ${BOT}`, `deop ${BOT}`]) {
    try {
      await rcon(cmd)
    } catch (err) {
      console.log(`[E2E] INFO cleanup "${cmd}" failed: ${err?.message ?? err}`)
    }
  }
}

async function scenario() {
  const bot = await connectBot(BOT)
  assert(true, `${BOT} connected`)
  await rcon(`op ${BOT}`)
  await rcon(`gamemode creative ${BOT}`)
  await rcon(`tp ${BOT} ${X + 0.5} ${Y} ${Z + 0.5}`)
  const loaded = await waitFor(async () => /passed/i.test(await rcon(`execute if loaded ${X} ${Y} ${Z}`)), 30000)
  assert(!!loaded, 'test area loaded')
  await rcon(`fill ${X - 6} ${Y - 1} ${Z - 6} ${X + 6} ${Y - 1} ${Z + 6} minecraft:stone`)
  await rcon(`fill ${X - 6} ${Y} ${Z - 6} ${X + 6} ${Y + 8} ${Z + 6} minecraft:air`)
  await tpBot(X + 0.5, Y, Z + 0.5)
  await cleanupArea()
  await sleep(500)
  assertEq(await count(ALL_HERE), 0, 'test area without holograms')

  const st = await rcon('smpeco selftest holo')
  const sm = st.match(/SUMMARY passed=(\d+) failed=(\d+)/)
  assert(!!sm && Number(sm[1]) >= 10, `holo selftests ran (${sm ? sm[0] : st.slice(0, 200)})`)
  assert(!!sm && sm[2] === '0', `holo selftests green${sm && sm[2] !== '0' ? ', ' + st.slice(0, 800) : ''}`)
  assertEq(await count(ALL_HERE), 0, 'selftests leave no displays behind')

  const bot2 = await connectBot(BOT2)
  await rcon(`deop ${BOT2}`)
  await sleep(500)
  const denied = await chatExpect(bot2, '/holo create money', /Unknown or incomplete command|permission/i).catch(() => null)
  assert(!!denied, 'non-admin: /holo refused')
  await sleep(500)
  assertEq(await count(ALL_HERE), 0, 'non-admin created nothing')
  bot2.quit()

  const ecoOut = await rcon(`eco set ${BOT} 950b`)
  const ecoOk = !/unknown|incomplete/i.test(ecoOut)
  if (!ecoOk) console.log(`[E2E] INFO /eco not available (${ecoOut.trim().slice(0, 80)}), rank check skipped`)
  const usage = await chatExpect(bot, '/holo create', /Usage: \/holo create <money\|kills\|playtime\|jobs>/).catch(() => null)
  assert(!!usage, '/holo create without type shows usage')
  const cm = await chatExpect(bot, '/holo create money', /Hologram #(\d+) \(Money\) created/)
  const moneyHolo = Number(cm[1])
  assert(moneyHolo > 0, `money hologram #${moneyHolo} created`)
  assertEq(await count(byId(moneyHolo)), 1, 'money: exactly one display')
  let txt = await holoText(moneyHolo)
  assert(/live=true/.test(txt), 'money: display loaded (live=true)')
  assert(/Richest Players/.test(txt), 'money: title')
  assert(/type=money /.test(txt), 'money: type id money')
  assert(/#1 /.test(txt) && /#10 /.test(txt), 'money: 10 ranks')
  assert(/Updated: \d\d:\d\d/.test(txt), 'money: as-of line')
  // rank 1 is likely, but other tests may have left richer bots, so only check the top 10
  if (ecoOk) assert(new RegExp(`#\\d+ ${BOT} » \\$950B`).test(txt), `money: ${BOT} with $950B in the top 10 (${txt.split('\n').slice(1, 5).join(' | ')})`)
  const nbtText = await rcon(`data get entity @e[type=minecraft:text_display,tag=smpeco_holo_${moneyHolo},limit=1] text`)
  assert(/Richest Players/.test(nbtText), 'money: text is in the entity (data get)')
  if (ecoOk) assert(nbtText.includes(BOT), 'money: bot name in entity text')
  const bb = await rcon(`data get entity @e[type=minecraft:text_display,tag=smpeco_holo_${moneyHolo},limit=1] billboard`)
  assert(/center/.test(bb), `money: billboard=center (${bb.trim().slice(-40)})`)
  const lw = await rcon(`data get entity @e[type=minecraft:text_display,tag=smpeco_holo_${moneyHolo},limit=1] line_width`)
  assert(/300/.test(lw), 'money: line_width=300')
  const pos = parsePos(txt)
  assert(!!pos && Math.abs(pos[0] - (X + 0.5)) < 0.01 && Math.abs(pos[2] - (Z + 0.5)) < 0.01, `money: at the player position (${pos})`)

  await tpBot(X + 3.5, Y, Z + 0.5)
  const list = await expectWindow(bot, () => bot.chat('/holo'))
  assert(/Holograms/.test(windowTitle(list)), `GUI "Holograms" open (${windowTitle(list)})`)
  const moneySlot = findSlot(list, new RegExp(`^#${moneyHolo} Money$`))
  assert(moneySlot >= 0, 'GUI: money hologram listed')
  if (moneySlot >= 0) assert(itemLore(list.slots[moneySlot]).some((l) => /Display: loaded/.test(l)), 'GUI: Lore "Display: loaded"')
  const newSlot = findSlot(list, /New Hologram/)
  assertEq(newSlot, 45, 'GUI: "New Hologram" bottom left')
  const typeWin = await expectWindow(bot, () => clickSlot(bot, newSlot))
  assert(/New Hologram/.test(windowTitle(typeWin)), 'GUI: type selection open')
  const playtimeSlot = findSlot(typeWin, /^Playtime$/)
  assert(playtimeSlot >= 0, 'GUI: type "Playtime" present')
  const playtimeMsg = waitForMessage(bot, /Hologram #(\d+) \(Playtime\) created/, 8000)
  const backWin = await expectWindow(bot, () => clickSlot(bot, playtimeSlot))
  const playtimeHolo = Number((await playtimeMsg)[1])
  assert(playtimeHolo > moneyHolo, `GUI: playtime hologram #${playtimeHolo} created`)
  assert(/Holograms/.test(windowTitle(backWin)), 'GUI: back in the list')
  assert(findSlot(backWin, new RegExp(`^#${playtimeHolo} Playtime$`)) >= 0, 'GUI: new hologram listed')
  bot.closeWindow(backWin)
  await sleep(300)
  assertEq(await count(byId(playtimeHolo)), 1, 'playtime: exactly one display')
  txt = await holoText(playtimeHolo)
  assert(/Most Playtime/.test(txt) && /live=true/.test(txt), 'playtime: title and loaded')

  await tpBot(X - 2.5, Y, Z + 0.5)
  const kills = Number((await chatExpect(bot, '/holo create kills', /Hologram #(\d+) \(Kills\) created/))[1])
  await tpBot(X + 0.5, Y, Z + 4.5)
  const jobs = Number((await chatExpect(bot, '/holo create jobs', /Hologram #(\d+) \(Jobs\) created/))[1])
  assert(/Most Kills/.test(await holoText(kills)), 'kills: title')
  assert(/Top Workers/.test(await holoText(jobs)), 'jobs: title')
  const near = (p, x, z) => Math.abs(p.x - x) < 0.6 && Math.abs(p.z - z) < 0.6
  await tpBot(X + 5.5, Y, Z + 5.5)
  const tpWin = await expectWindow(bot, () => bot.chat('/holo'))
  const killsSlot = findSlot(tpWin, new RegExp(`^#${kills} Kills$`))
  assert(killsSlot >= 0, 'GUI: kills hologram listed')
  const tpMsg = waitForMessage(bot, new RegExp(`Teleported to hologram #${kills}`), 8000)
  await clickSlot(bot, killsSlot, 0)
  assert(!!(await tpMsg.catch(() => null)), 'GUI left click: teleport message')
  const tpOk = await waitFor(async () => near(bot.entity.position, X - 2.5, Z + 0.5), 4000, 200)
  assert(!!tpOk, `GUI left click: at the kills hologram (${bot.entity.position})`)
  assert(!bot.currentWindow, 'GUI closed after the teleport')
  await chatExpect(bot, `/holo tp ${jobs}`, new RegExp(`Teleported to hologram #${jobs}`))
  const tp2 = await waitFor(async () => near(bot.entity.position, X + 0.5, Z + 4.5), 4000, 200)
  assert(!!tp2, `/holo tp: at the jobs hologram (${bot.entity.position})`)

  const listOut = await rcon('holo list')
  for (const [id, label] of [[moneyHolo, 'Money'], [playtimeHolo, 'Playtime'], [kills, 'Kills'], [jobs, 'Jobs']]) {
    assert(listOut.includes(`#${id} ${label}`), `/holo list contains #${id} ${label}`)
  }

  await chatExpect(bot, '/holo refresh', /Holograms refreshed/)
  await sleep(2500) // more than one guard pass (1 s)
  for (const id of [moneyHolo, playtimeHolo, kills, jobs]) assertEq(await count(byId(id)), 1, `exactly one display after refresh (#${id})`)
  assertEq(await count(ALL_HERE), 4, '4 displays in the test area')

  await rcon(`summon minecraft:text_display ${X} ${Y + 5} ${Z} {Tags:["smpeco_holo","smpeco_holo_${moneyHolo}"],text:"old"}`)
  await rcon(`summon minecraft:text_display ${X} ${Y + 5} ${Z} {Tags:["smpeco_holo","smpeco_holo_99999"],text:"orphan"}`)
  await sleep(1000)
  assertEq(await count(byId(moneyHolo)), 1, 'foreign copy with the same ID removed, original stays')
  assertEq(await count(byId(99999)), 0, 'orphaned own display removed')
  assert(/live=true/.test(await holoText(moneyHolo)), 'original still loaded')

  await rcon(`execute as ${byId(moneyHolo)} at @s run tp @s ~ ~3 ~`)
  const home = await waitFor(async () => (await count(`@e[type=minecraft:text_display,tag=smpeco_holo_${moneyHolo},x=${pos[0]},y=${pos[1]},z=${pos[2]},distance=..0.6]`)) === 1, 6000)
  assert(!!home, 'moved display jumps back to its place')

  await rcon(`tp ${BOT} ${X + 3000} 200 ${Z}`)
  const unloaded = await waitFor(async () => /failed/i.test(await rcon(`execute if loaded ${X} ${Y} ${Z}`)), 40000, 1000)
  assert(!!unloaded, 'test area unloaded')
  await sleep(8000) // let entities save and unload
  await rcon(`tp ${BOT} ${X + 0.5} ${Y} ${Z + 0.5}`)
  const reloaded = await waitFor(async () => (await count(ALL_HERE)) >= 4, 30000, 1000)
  assert(!!reloaded, 'displays back after reload')
  await sleep(3000)
  for (const id of [moneyHolo, playtimeHolo, kills, jobs]) assertEq(await count(byId(id)), 1, `exactly one display after reload (#${id})`)
  assertEq(await count(ALL_HERE), 4, 'no duplicates after reload')
  assert(/live=true/.test(await holoText(moneyHolo)), 'managed again after reload (live=true)')

  // Move the bot away chunk by chunk until the money hologram's chunk is loaded (block queries work) but no
  // longer ticking ("execute if loaded" requires ENTITY_TICKING), as outside the simulation distance. A
  // vanished display must be respawned there too.
  const ticking = async () => /passed/i.test(await rcon(`execute if loaded ${X} ${Y} ${Z}`))
  const fullLoaded = async () => /Test (passed|failed)/i.test(await rcon(`execute if block ${X} ${Y - 1} ${Z} minecraft:stone`))
  const uuidOf = async (id) => (await rcon(`data get entity @e[type=minecraft:text_display,tag=smpeco_holo_${id},limit=1] UUID`)).match(/\[I;[^\]]*\]/)?.[0] ?? null
  let notTicking = 0
  for (let d = 1; d <= 20; d++) {
    await rcon(`tp ${BOT} ${X + 0.5 + d * 16} 200 ${Z + 0.5}`)
    await sleep(1500)
    if (await ticking()) continue
    if (await fullLoaded()) notTicking = d
    break
  }
  assert(notTicking > 0, `money hologram chunk loaded but not ticking (bot ${notTicking} chunks away)`)
  if (notTicking > 0) {
    assertEq(await count(byId(moneyHolo)), 1, 'no tick: display still there')
    const before = await uuidOf(moneyHolo)
    assert(/Killed/i.test(await rcon(`kill ${byId(moneyHolo)}`)), 'no tick: display removed with /kill')
    const respawned = await waitFor(async () => (await count(byId(moneyHolo))) === 1, 8000)
    assert(!!respawned, 'no tick: vanished display respawned')
    const after = await uuidOf(moneyHolo)
    assert(!!after && after !== before, `without tick: new display (${before} → ${after})`)
    await sleep(2500)
    assertEq(await count(byId(moneyHolo)), 1, 'no tick: exactly one display (no duplicates)')
    assertEq(await uuidOf(moneyHolo), after, 'no tick: same display stays')
    assert(/live=true/.test(await holoText(moneyHolo)), 'no tick: managed (live=true)')
    const t = await rcon(`data get entity @e[type=minecraft:text_display,tag=smpeco_holo_${moneyHolo},limit=1] text`)
    assert(/Richest Players/.test(t), 'no tick: text on the new display')
    assert(!(await ticking()) && (await fullLoaded()), 'chunk stayed loaded and non-ticking during the check')
  }
  await rcon(`tp ${BOT} ${X + 0.5} ${Y} ${Z + 0.5}`)
  assert(!!(await waitFor(ticking, 30000, 1000)), 'test area ticks again')
  await sleep(2000)
  for (const id of [moneyHolo, playtimeHolo, kills, jobs]) assertEq(await count(byId(id)), 1, `back: exactly one display (#${id})`)
  assertEq(await count(ALL_HERE), 4, 'back: no duplicates')

  const win = await expectWindow(bot, () => bot.chat('/holo'))
  const playtimeRow = findSlot(win, new RegExp(`^#${playtimeHolo} Playtime$`))
  assert(playtimeRow >= 0, 'GUI: playtime hologram listed')
  const confirm = await expectWindow(bot, () => clickSlot(bot, playtimeRow, 1))
  assert(/Remove hologram #\d+\?/.test(windowTitle(confirm)), `GUI: confirmation open (${windowTitle(confirm)})`)
  const rmMsg = waitForMessage(bot, new RegExp(`Hologram #${playtimeHolo} \\(Playtime\\) removed`), 8000)
  const after = await expectWindow(bot, () => clickSlot(bot, 9))
  assert(!!(await rmMsg), 'GUI: removal confirmed')
  assert(findSlot(after, new RegExp(`^#${playtimeHolo} `)) < 0, 'GUI: no longer listed')
  bot.closeWindow(after)
  await sleep(300)
  assertEq(await count(byId(playtimeHolo)), 0, 'GUI removal: display gone')

  await tpBot(X - 2.5, Y, Z + 1.5)
  await chatExpect(bot, '/holo remove', new RegExp(`Hologram #${kills} \\(Kills\\) removed`))
  assertEq(await count(byId(kills)), 0, '/holo remove: nearest (kills) removed')
  await chatExpect(bot, `/holo remove ${jobs}`, new RegExp(`Hologram #${jobs} \\(Jobs\\) removed`))
  assertEq(await count(byId(jobs)), 0, '/holo remove <id>: jobs removed')
  await tpBot(X + 0.5, Y, Z + 12.5) // more than 5 blocks from the last hologram (money at Z+0.5)
  const none = await chatExpect(bot, '/holo remove', /No hologram within/).catch(() => null)
  assert(!!none, '/holo remove with no hologram nearby: error')
  const unknown = await chatExpect(bot, '/holo remove 99999', /Hologram #99999 doesn't exist/).catch(() => null)
  assert(!!unknown, '/holo remove with unknown ID: error')
  assertEq(await count(byId(moneyHolo)), 1, 'money hologram untouched')

  assert(/In-game only/.test(await rcon('holo create money')), 'console: create in-game only (money)')
  assert(/removed/.test(await rcon(`holo remove ${moneyHolo}`)), 'console: remove by ID')
  await sleep(500)
  assertEq(await count(ALL_HERE), 0, 'no displays in the test area at the end')
  assert(!new RegExp(`#${moneyHolo} `).test(await rcon('holo list')), 'no longer in /holo list at the end')
}

runE2E(async () => {
  await resetBot()
  try {
    await scenario()
  } finally {
    // also after exceptions or timeouts: leave no permissions, money or test holograms behind
    await resetBot()
    try {
      await cleanupArea()
    } catch (err) {
      console.log(`[E2E] INFO test area cleanup failed: ${err?.message ?? err}`)
    }
  }
}, { timeoutMs: 300000 })
