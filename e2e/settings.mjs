// /settings GUI as a player (toggling by switch and icon, sound and action bar, click filter, no item leaks,
// reset confirmation, close), chat commands, persistence over a reconnect, the real spawn mixins
// (NaturalSpawner and pillager patrols through "/smpeco settings spawntest" in a dark test room, PhantomSpawner
// as a dry run) and the private death message with coordinates.
import { writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { E2E_SERVER_DIR } from '../tools/server-restart.mjs'
import {
  connectBot, rcon, moduleEnabled, runE2E, assert, assertEq, sleep, expectWindow, windowTitle, clickSlot, findSlot, itemLabel, itemLore,
  waitForMessage, stripColors,
} from './lib.mjs'

const B1 = 'settinBot1'
const B2 = 'settinBot2'
// test room far from spawn; the box lies entirely in one chunk (7200..7215 / -7216..-7201)
const X0 = 7208, Y0 = 180, Z0 = -7208
const P1 = X0 + 40 // platform bot 1 (34-46 blocks from every position in the room)
const P2 = X0 + 56 // platform bot 2 (50-62 blocks)
const PF1 = X0 + 80 // outside 64 blocks (74-86)
const PF2 = X0 + 96
const PLATFORMS = [P1, P2, PF1, PF2]
const SPAWN_ATTEMPTS = 20
const PATROL_ATTEMPTS = 3

let bot1
let bot2
// display names of the switches, read from the server, since the texts belong to core Settings
let L = {}
let KEYS = ['sidebar', 'mobspawn', 'phantoms', 'pay_receive', 'chatgames', 'announcements', 'notifications', 'job_actionbar', 'death_coords']
const SETTINGS_JSON = join(E2E_SERVER_DIR, 'config', 'smpeconomy', 'settings.json')

async function allowSpawnToggles(on) {
  writeFileSync(SETTINGS_JSON, `${JSON.stringify({ allowMobSpawnToggle: on, allowPhantomToggle: on }, null, 2)}\n`)
  const out = await rcon('smpeco reload')
  assert(/reloaded/i.test(out), `settings.json (${on}) reloaded (${out.trim()})`)
}


const esc = (text) => String(text).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
const exact = (key) => new RegExp(`^${esc(L[key])}$`)

async function settingLabels(bot, cmd = '/settings list') {
  const lines = []
  const on = (msg) => lines.push(stripColors(String(msg)))
  bot.on('messagestr', on)
  bot.chat(cmd)
  await sleep(1500)
  bot.off('messagestr', on)
  const map = {}
  for (const l of lines) {
    const m = l.match(/^• (.+) \(([a-z0-9_.]+)\): (On|Off)$/)
    if (m) map[m[2]] = m[1]
  }
  return map
}

async function values(name) {
  const out = await rcon(`smpeco settings ${name}`)
  const m = out.match(/settings \S+ ((?:\S+=(?:true|false)\s*)+)/)
  if (!m) throw new Error(`smpeco settings ${name}: ${out}`)
  const map = {}
  for (const part of m[1].trim().split(/\s+/)) {
    const [k, v] = part.split('=')
    map[k] = v === 'true'
  }
  return map
}

function waitForActionBar(bot, regex, timeoutMs = 5000) {
  return new Promise((resolve) => {
    const onBar = (msg) => {
      if (regex.test(String(msg))) { cleanup(); resolve(true) }
    }
    const timer = setTimeout(() => { cleanup(); resolve(false) }, timeoutMs)
    const cleanup = () => { clearTimeout(timer); bot.off('actionBar', onBar) }
    bot.on('actionBar', onBar)
  })
}

/**
 * Matched by pitch (on = 1.6, off = 0.7) instead of name, because the sound IDs in minecraft-data for 1.21.11
 * can be off by one.
 */
function waitForSound(bot, pitch, timeoutMs = 5000) {
  return new Promise((resolve) => {
    const match = (p) => Math.abs(Number(p) - pitch) < 0.05
    const onNamed = (name, pos, volume, p) => { if (match(p)) { cleanup(); resolve(true) } }
    const onId = (id, category, pos, volume, p) => { if (match(p)) { cleanup(); resolve(true) } }
    const timer = setTimeout(() => { cleanup(); resolve(false) }, timeoutMs)
    const cleanup = () => {
      clearTimeout(timer)
      bot.off('soundEffectHeard', onNamed)
      bot.off('hardcodedSoundEffectHeard', onId)
    }
    bot.on('soundEffectHeard', onNamed)
    bot.on('hardcodedSoundEffectHeard', onId)
  })
}

function waitForEvent(bot, event, timeoutMs = 10000) {
  return new Promise((resolve) => {
    const on = () => { clearTimeout(timer); resolve(true) }
    const timer = setTimeout(() => { bot.off(event, on); resolve(false) }, timeoutMs)
    bot.once(event, on)
  })
}

function sees(bot, regex, timeoutMs) {
  return waitForMessage(bot, regex, timeoutMs).then(() => true, () => false)
}

function openSettings(bot, cmd = '/settings') {
  return expectWindow(bot, () => bot.chat(cmd))
}

async function closeWindow(bot) {
  if (bot.currentWindow) bot.closeWindow(bot.currentWindow)
  await sleep(300)
}

async function noItemsLeaked(name, what) {
  const out = await rcon(`clear ${name}`)
  assert(/No items were found/i.test(out), `${what}: no GUI items in the inventory (${out.trim()})`)
}

async function tpTo(name, x) {
  await rcon(`tp ${name} ${x + 0.5} ${Y0} ${Z0 + 0.5}`)
}

async function spawned(art = 'natural', attempts = SPAWN_ATTEMPTS) {
  const out = await rcon(`smpeco settings spawntest ${X0} ${Y0} ${Z0} ${attempts} ${art}`)
  const m = out.match(/spawned=(\d+)/)
  if (!m) throw new Error(`spawntest ${art}: ${out}`)
  return Number(m[1])
}

/** Patrol members exactly at the test position (PatrolSpawner, same rule as natural spawning). */
function patrol() {
  return spawned('patrol', PATROL_ATTEMPTS)
}

async function check() {
  const out = await rcon(`smpeco settings check ${X0} ${Y0} ${Z0}`)
  const m = out.match(/blocked=(true|false) nearest=(\S+)/)
  if (!m) throw new Error(`check: ${out}`)
  return { blocked: m[1] === 'true', nearest: m[2], raw: out.trim() }
}

function listOf(out, key) {
  const m = out.match(new RegExp(`${key}=(\\S*)`))
  return m && m[1] ? m[1].split(',') : []
}


runE2E(async () => {
  const st = await rcon('smpeco selftest settings')
  const sm = st.match(/SUMMARY passed=(\d+) failed=(\d+)/)
  assert(!!sm && Number(sm[1]) >= 9 && Number(sm[2]) === 0, `settings selftests green (${(sm && sm[0]) || st.trim().slice(0, 300)})`)

  if (!(await moduleEnabled('minigames'))) KEYS = KEYS.filter((k) => k !== 'chatgames')
  // by default the spawn switches do not exist (settings.json): unlock for the tests, lock again afterwards
  await allowSpawnToggles(true)
  bot1 = await connectBot(B1)
  bot2 = await connectBot(B2)
  for (const n of [B1, B2]) {
    await rcon(`smpeco settings ${n} reset`)
    await rcon(`gamemode survival ${n}`)
    await rcon(`clear ${n}`)
  }
  const start = await values(B1)
  assert(Object.keys(start).length >= 9 && Object.values(start).every((v) => v), 'after reset: all switches on')
  L = await settingLabels(bot1)
  assert(KEYS.every((k) => typeof L[k] === 'string' && L[k].length > 0), `/settings list names all switches (${JSON.stringify(L)})`)

  try {
    await guiTests()
    await commandTests()
    await persistenceTests()
    await resetTests()
    await spawnTests()
    await phantomTests()
    await deathTests()
    await configOffTests()
  } finally {
    await allowSpawnToggles(false).catch(() => {})
    await closeWindow(bot1)
    for (const n of [B1, B2]) {
      await rcon(`smpeco settings ${n} reset`)
      await rcon(`gamemode survival ${n}`)
      await rcon(`clear ${n}`)
    }
    for (const b of [bot1, bot2]) { try { b?.quit() } catch {} }
    await sleep(1000)
    await cleanupArea()
  }
}, { timeoutMs: 300000 })

async function configOffTests() {
  await allowSpawnToggles(false)
  const labels = await settingLabels(bot1)
  assert(!('mobspawn' in labels) && !('phantoms' in labels), `default: /settings list without mobspawn/phantoms (${Object.keys(labels).join(',')})`)
  assert('sidebar' in labels, '/settings list shows the other switches')
  const unknown = waitForMessage(bot1, /Unknown setting: mobspawn/, 5000).then(() => true, () => false)
  bot1.chat('/settings mobspawn off')
  assert(await unknown, 'default: /settings mobspawn off gives unknown setting')
  const win = await openSettings(bot1)
  assert(findSlot(win, exact('mobspawn'), { to: 45 }) < 0 && findSlot(win, exact('phantoms'), { to: 45 }) < 0, 'default: GUI without spawn switches')
  await closeWindow(bot1)
}

async function guiTests() {
  let win = await openSettings(bot1)
  assert(/Settings/.test(windowTitle(win)), `GUI open (title "${windowTitle(win)}")`)
  assertEq(win.slots[0]?.name, 'black_stained_glass_pane', 'frame top left')
  for (const key of KEYS) {
    assert(findSlot(win, exact(key), { to: 45 }) >= 0, `switch present: ${key} (${L[key]})`)
  }
  const icon = findSlot(win, exact('mobspawn'), { to: 45 })
  const sw = icon + 9
  assertEq(win.slots[sw]?.name, 'lime_dye', 'switch green (on)')
  assert(/On$/.test(itemLabel(win.slots[sw])), `switch text "On" (${itemLabel(win.slots[sw])})`)
  const lore = itemLore(win.slots[icon])
  assert(lore.some((l) => /\b64\b/.test(l)), 'lore explains the effect (64 blocks)')
  assert(lore.some((l) => /Status: On/.test(l)), 'lore shows the status')
  assert(lore.some((l) => /Default: On/.test(l)) && lore.some((l) => /Click to toggle/.test(l)), 'lore: default and hint')

  const bass = waitForSound(bot1, 0.7)
  const bar = waitForActionBar(bot1, new RegExp(`${esc(L.mobspawn)}: Off`))
  await clickSlot(bot1, sw)
  await sleep(250)
  win = bot1.currentWindow
  assertEq(win?.slots[sw]?.name, 'red_dye', 'switch red (off)')
  assert(/Off$/.test(itemLabel(win?.slots[sw])), 'switch text "Off"')
  assert(itemLore(win?.slots[icon]).some((l) => /Status: Off/.test(l)), 'lore updated')
  assert(await bass, 'sound on switching off')
  assert(await bar, 'action bar shows the new value')
  assertEq((await values(B1)).mobspawn, false, 'Server: mobspawn=false')

  await sleep(350)
  const pling = waitForSound(bot1, 1.6)
  await clickSlot(bot1, icon, 1)
  await sleep(250)
  assertEq(bot1.currentWindow?.slots[sw]?.name, 'lime_dye', 'right click on the icon switches on again')
  assert(await pling, 'sound on switching on')
  assertEq((await values(B1)).mobspawn, true, 'Server: mobspawn=true')

  // number key and Q do not trigger, a fast double click only once (prismarine-windows cannot send mode 6)
  await sleep(350)
  await clickSlot(bot1, sw, 0, 2)
  await clickSlot(bot1, sw, 0, 4)
  await sleep(300)
  assertEq((await values(B1)).mobspawn, true, 'number key and Q do not toggle')
  assertEq(bot1.currentWindow?.slots[sw]?.name, 'lime_dye', 'switch unchanged after number key and Q')
  await sleep(350)
  await bot1.clickWindow(sw, 0, 0)
  await bot1.clickWindow(sw, 0, 0) // second click right after (< 300 ms) is debounced
  await sleep(500)
  assertEq((await values(B1)).mobspawn, false, 'fast double click toggles exactly once')
  await sleep(350)
  await clickSlot(bot1, sw)
  assertEq((await values(B1)).mobspawn, true, 'on again')

  await sleep(350)
  await clickSlot(bot1, 49)
  await sleep(300)
  assert(!bot1.currentWindow, 'close button closes the GUI')
  await noItemsLeaked(B1, 'GUI clicks')
}

async function commandTests() {
  const ms = esc(L.mobspawn)
  let got = sees(bot1, new RegExp(`» ${ms}: Off$`), 5000)
  bot1.chat('/settings mobspawn off')
  assert(await got, '/settings mobspawn off gives a confirmation')
  assertEq((await values(B1)).mobspawn, false, 'Server: mobspawn=false (Chat)')

  got = sees(bot1, new RegExp(`${ms} is already Off`), 5000)
  bot1.chat('/settings mobspawn off')
  assert(await got, 'same value gives a hint')

  got = sees(bot1, new RegExp(`» ${ms}: On$`), 5000)
  bot1.chat('/settings mobspawn')
  assert(await got, '/settings mobspawn without a value toggles')
  assertEq((await values(B1)).mobspawn, true, 'Server: mobspawn=true (Chat)')

  got = sees(bot1, /Unknown setting: nonexistent/, 5000)
  bot1.chat('/settings nonexistent')
  assert(await got, 'unknown switch gives an error')

  got = sees(bot1, /Please use on or off/, 5000)
  bot1.chat('/settings mobspawn maybe')
  assert(await got, 'invalid value gives an error')

  const leak = sees(bot1, /settings settinBot1 sidebar=/, 2500)
  bot1.chat(`/smpeco settings ${B1}`)
  assert(!(await leak), '/smpeco settings for admins only')
}

async function persistenceTests() {
  let win = await openSettings(bot1)
  const ph = findSlot(win, exact('phantoms'), { to: 45 })
  assert(ph >= 0, 'phantoms icon found')
  await clickSlot(bot1, ph + 9)
  await sleep(250)
  assertEq(bot1.currentWindow?.slots[ph + 9]?.name, 'red_dye', 'phantoms off (GUI)')
  await closeWindow(bot1)

  win = await openSettings(bot1)
  assertEq(win.slots[ph + 9]?.name, 'red_dye', 'still off after reopening')
  await closeWindow(bot1)

  bot1.quit()
  await sleep(2000)
  bot1 = await connectBot(B1)
  win = await openSettings(bot1)
  assertEq(win.slots[ph + 9]?.name, 'red_dye', 'still off after reconnect')
  const ms = findSlot(win, exact('mobspawn'), { to: 45 })
  assertEq(win.slots[ms + 9]?.name, 'lime_dye', 'other switches unchanged')
  assertEq((await values(B1)).phantoms, false, 'server: phantoms=false after reconnect')
  await closeWindow(bot1)
}

async function resetTests() {
  let win = await openSettings(bot1)
  const ph = findSlot(win, exact('phantoms'), { to: 45 })
  assert(/Restore Defaults/.test(itemLabel(win.slots[53])), 'reset button bottom right')

  let dialog = await expectWindow(bot1, () => clickSlot(bot1, 53))
  assert(/Reset all/.test(windowTitle(dialog)), `confirm dialog (${windowTitle(dialog)})`)
  win = await expectWindow(bot1, () => clickSlot(bot1, 15))
  assert(/Settings/.test(windowTitle(win)), 'cancel opens the settings again')
  assertEq((await values(B1)).phantoms, false, 'cancel changes nothing')

  await sleep(350)
  dialog = await expectWindow(bot1, () => clickSlot(bot1, 53))
  const msg = sees(bot1, /Settings reset \(1 changed\)/, 5000)
  win = await expectWindow(bot1, () => clickSlot(bot1, 9))
  assert(await msg, 'reset confirmed (1 changed)')
  assertEq(win.slots[ph + 9]?.name, 'lime_dye', 'phantoms on again')
  const v = await values(B1)
  assert(Object.values(v).every((x) => x), `all switches at default (${JSON.stringify(v)})`)

  await sleep(350)
  const hint = sees(bot1, /already on default/, 4000)
  await clickSlot(bot1, 53)
  assert(await hint, 'reset without changes gives a hint')
  assert(/Settings/.test(windowTitle(bot1.currentWindow)), 'no dialog, GUI stays open')
  await closeWindow(bot1)
  await noItemsLeaked(B1, 'Reset-Dialog')
}

async function spawnTests() {
  const diff = await rcon('difficulty')
  const peaceful = /peaceful/i.test(diff)
  if (peaceful) await rcon('difficulty easy')
  await rcon(`forceload add ${X0 - 8} ${Z0 - 8} ${PF2 + 2} ${Z0 + 8}`)
  await sleep(2000)
  try {
    // dark closed room (13x4x13 inside, stone floor), plains biome; glass platforms for the bots
    await rcon(`fill ${X0 - 7} ${Y0 - 1} ${Z0 - 7} ${X0 + 7} ${Y0 + 4} ${Z0 + 7} minecraft:stone`)
    await rcon(`fill ${X0 - 6} ${Y0} ${Z0 - 6} ${X0 + 6} ${Y0 + 3} ${Z0 + 6} minecraft:air`)
    const biome = await rcon(`fillbiome ${X0 - 7} ${Y0 - 1} ${Z0 - 7} ${X0 + 7} ${Y0 + 4} ${Z0 + 7} minecraft:plains`)
    console.log(`[E2E] fillbiome: ${biome.trim()}`)
    for (const px of PLATFORMS) await rcon(`fill ${px - 1} ${Y0 - 1} ${Z0 - 1} ${px + 1} ${Y0 - 1} ${Z0 + 1} minecraft:glass`)
    await tpTo(B1, P1)
    await tpTo(B2, P2)
    await sleep(2500)

    let c = await check()
    assert(!c.blocked && c.nearest === B1, `check: bot 1 is nearest, allowed (${c.raw})`)
    const base = await spawned()
    assert(base > 0, `both on: natural monsters spawn (${base})`)
    const basePatrol = await patrol()
    assert(basePatrol > 0, `both on: pillager patrol spawns (${basePatrol})`)

    await rcon(`smpeco settings ${B1} mobspawn off`)
    c = await check()
    assert(c.blocked && c.nearest === B1, `check: bot 1 (off) decides, forbidden (${c.raw})`)
    assertEq(await spawned(), 0, 'nearest player has mob spawning off: no monsters')
    assertEq(await spawned('natural'), 0, 'type "natural" explicit')
    assertEq(await patrol(), 0, 'nearest player has mob spawning off: no pillager patrol')

    await rcon(`smpeco settings ${B1} mobspawn on`)
    await rcon(`smpeco settings ${B2} mobspawn off`)
    const far = await spawned()
    assert(far > 0, `only the nearest player counts (bot 2 off, but further away): monsters (${far})`)
    assert(await patrol() > 0, 'only the nearest player counts: pillager patrol spawns')

    // spectators do not count: bot 1 (off) as spectator nearest, bot 2 (on) behind
    await rcon(`smpeco settings ${B1} mobspawn off`) // admin
    await rcon(`smpeco settings ${B2} mobspawn on`)
    await rcon(`gamemode spectator ${B1}`)
    await sleep(500)
    c = await check()
    assert(!c.blocked && c.nearest === B2, `spectator ignored, bot 2 decides (${c.raw})`)
    assert(await spawned() > 0, 'spectator with mob spawning off prevents nothing')
    await rcon(`gamemode survival ${B1}`)

    // radius: bot 1 (off) 74-86 blocks away, bot 2 further: nothing prevented
    await tpTo(B1, PF1)
    await tpTo(B2, PF2)
    await sleep(1500)
    c = await check()
    assert(!c.blocked && c.nearest === '-', `outside 64 blocks nobody decides (${c.raw})`)
    assert(await spawned() > 0, 'mob spawning off only works within the 64 block radius')

    await tpTo(B1, P1)
    await sleep(1000)
    assertEq(await spawned(), 0, 'prevented again in the radius')
  } finally {
    await rcon(`gamemode survival ${B1}`)
    await rcon(`smpeco settings ${B1} reset`)
    await rcon(`smpeco settings ${B2} reset`)
    if (peaceful) await rcon('difficulty peaceful')
  }
}

async function phantomTests() {
  const dayOut = await rcon('time query daytime')
  const day = (dayOut.match(/(\d+)/) || [])[1]
  const ruleOut = await rcon('gamerule spawn_phantoms')
  const ruleWasOff = /false/.test(ruleOut)
  if (ruleWasOff) await rcon('gamerule spawn_phantoms true')
  await rcon('time set 18000')
  await sleep(1000)
  try {
    await rcon(`smpeco settings ${B1} phantoms off`)
    let out = await rcon('smpeco settings phantomtest')
    assert(/dark=true/.test(out) && /rule=true/.test(out), `phantom tick runs (night, gamerule) (${out.trim()})`)
    let checked = listOf(out, 'checked')
    let skipped = listOf(out, 'skipped')
    assert(checked.includes(B1) && checked.includes(B2), `PhantomSpawner checks both bots (${checked})`)
    assert(skipped.includes(B1), 'bot 1 (phantoms off) skipped')
    assert(!skipped.includes(B2), 'bot 2 (phantoms on) not skipped')

    await rcon(`smpeco settings ${B1} phantoms on`)
    out = await rcon('smpeco settings phantomtest')
    skipped = listOf(out, 'skipped')
    checked = listOf(out, 'checked')
    assert(checked.includes(B1) && !skipped.includes(B1), `no longer skipped after switching on (${out.trim()})`)
  } finally {
    if (day) await rcon(`time set ${day}`)
    if (ruleWasOff) await rcon('gamerule spawn_phantoms false')
  }
}

async function deathTests() {
  await tpTo(B1, P1)
  await tpTo(B2, P2)
  await sleep(1000)
  await rcon(`clear ${B1}`)
  const coords = new RegExp(`You died at X ${P1}, Y ${Y0}, Z ${Z0} \\(Overworld\\)`)
  const mine = sees(bot1, coords, 8000)
  const other = sees(bot2, /You died at X/, 5000)
  let respawn = waitForEvent(bot1, 'spawn', 15000)
  await rcon(`kill ${B1}`)
  assert(await mine, 'death message with coordinates')
  assert(!(await other), 'other player does not see the coordinates')
  assert(await respawn, 'bot 1 respawns')
  await sleep(1500)

  const win = await openSettings(bot1)
  const dc = findSlot(win, exact('death_coords'), { to: 45 })
  await clickSlot(bot1, dc + 9)
  await sleep(250)
  assertEq(bot1.currentWindow?.slots[dc + 9]?.name, 'red_dye', 'death coordinates off (GUI)')
  await closeWindow(bot1)
  assertEq((await values(B1)).death_coords, false, 'Server: death_coords=false')

  await tpTo(B1, P1)
  await sleep(1000)
  const none = sees(bot1, /You died at X/, 5000)
  respawn = waitForEvent(bot1, 'spawn', 15000)
  await rcon(`kill ${B1}`)
  assert(!(await none), 'switch off: no death coordinates')
  assert(await respawn, 'bot 1 respawns again')
  await sleep(1000)
}

// disconnect the bots first so they do not fall
async function cleanupArea() {
  try {
    // do not let mobs that spawned naturally in the room fall down; spawntest removes only its own
    await rcon(`kill @e[type=!minecraft:player,x=${X0 - 7},y=${Y0 - 1},z=${Z0 - 7},dx=14,dy=6,dz=14]`)
    await rcon(`fill ${X0 - 7} ${Y0 - 1} ${Z0 - 7} ${X0 + 7} ${Y0 + 4} ${Z0 + 7} minecraft:air`)
    for (const px of PLATFORMS) await rcon(`fill ${px - 1} ${Y0 - 1} ${Z0 - 1} ${px + 1} ${Y0 - 1} ${Z0 + 1} minecraft:air`)
    await rcon(`forceload remove ${X0 - 8} ${Z0 - 8} ${PF2 + 2} ${Z0 + 8}`)
  } catch (err) {
    console.log(`[E2E] cleanup failed: ${err?.message ?? err}`)
  }
}
