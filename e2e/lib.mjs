// Shared helpers for the e2e tests: mineflayer bots plus RCON. See README.md for the environment variables.
import { appendFileSync } from 'node:fs'
import mineflayer from 'mineflayer'
import nbt from 'prismarine-nbt'
import { Rcon } from 'rcon-client'
import { Vec3 } from 'vec3'

export { Vec3 }
export const HOST = process.env.E2E_HOST ?? '127.0.0.1'
export const PORT = Number(process.env.E2E_PORT ?? 25631)
export const RCON_PORT = Number(process.env.E2E_RCON_PORT ?? 25632)
export const RCON_PASSWORD = process.env.E2E_RCON_PASSWORD ?? 'smpeco'
export const VERSION = process.env.E2E_MC_VERSION ?? '1.21.11'
const TICK_END = process.env.E2E_TICK_END === '1'

let passed = 0
let failed = 0
let rconConn = null
const bots = new Set()

export const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

/**
 * Connects an offline bot and waits for the spawn. bot.messages collects all chat lines without colors. {@code
 * protect} (default on) gives resistance, fire resistance and saturation over RCON, because mobs at spawn can
 * kill a bot mid-test and death closes every GUI. Tests that need real damage call {@code effect clear} first.
 * {@code setup(bot)} runs before the join, for listeners on packets sent during the join.
 */
export async function connectBot(name, { timeoutMs = 60000, protect = true, setup = null } = {}) {
  const bot = await connectBotRaw(name, { timeoutMs, setup })
  // a bot that left dead comes back dead, so wait for the respawn first
  await sleep(300)
  if (bot.health !== undefined && bot.health <= 0) {
    await new Promise((resolve) => {
      const t = setTimeout(resolve, 5000)
      bot.once('spawn', () => { clearTimeout(t); resolve() })
    })
    await sleep(300)
  }
  if (protect) {
    try {
      await rcon(`effect give ${name} minecraft:resistance infinite 4 true`)
      await rcon(`effect give ${name} minecraft:fire_resistance infinite 0 true`)
      await rcon(`effect give ${name} minecraft:saturation infinite 0 true`)
    } catch (err) {
      console.log(`[E2E] WARN protection effects for ${name} failed: ${err.message}`)
    }
  }
  return bot
}

/**
 * mineflayer 4.39 accepts a required resource pack during configuration, but writes the reply with a uuid-1345
 * object instead of the UUID as text. The server gets a wrong pack ID, keeps waiting, and the bot never spawns.
 * Every script that calls {@code mineflayer.createBot} itself needs this right after.
 */
export function fixResourcePackAnswer(bot) {
  const client = bot._client
  const write = client.write.bind(client)
  client.write = (packet, params) => {
    if (packet === 'resource_pack_receive' && params?.uuid != null && typeof params.uuid === 'object') {
      params = { ...params, uuid: params.uuid.ascii ?? String(params.uuid) }
    }
    const res = write(packet, params)
    // Servers from 26.2 on kick (invalid_player_movement) on two position packets without
    // ServerboundClientTickEnd in between, and mineflayer never sends tick_end.
    if (TICK_END && (packet === 'position' || packet === 'position_look' || packet === 'flying' || packet === 'look')) {
      try { write('tick_end', {}) } catch {}
    }
    return res
  }
}

function connectBotRaw(name, { timeoutMs = 60000, setup = null } = {}) {
  return new Promise((resolve, reject) => {
    const bot = mineflayer.createBot({ host: HOST, port: PORT, username: name, version: VERSION, auth: 'offline' })
    fixResourcePackAnswer(bot)
    if (setup) setup(bot)
    bot.messages = []
    bot.on('messagestr', (msg) => bot.messages.push(msg))
    if (TEXT_DUMP) dumpTexts(bot, name)
    let spawned = false
    const timer = setTimeout(() => {
      if (!spawned) { bot.end(); reject(new Error(`${name}: no spawn after ${timeoutMs} ms`)) }
    }, timeoutMs)
    bot.once('spawn', () => {
      spawned = true
      clearTimeout(timer)
      bots.add(bot)
      resolve(bot)
    })
    bot.on('kicked', (reason) => {
      const text = typeof reason === 'string' ? reason : JSON.stringify(reason)
      if (!spawned) { clearTimeout(timer); reject(new Error(`${name} kicked: ${text}`)) } else console.log(`[E2E] ${name} kicked: ${text}`)
    })
    bot.on('error', (err) => {
      if (!spawned) { clearTimeout(timer); reject(err) } else console.log(`[E2E] ${name} error: ${err.message}`)
    })
    bot.on('end', () => bots.delete(bot))
  })
}

/**
 * With {@code E2E_TEXT_DUMP=<file>} every bot appends all text a player gets to read to that file (chat, action
 * bar, titles, boss bar, sidebar, tab list, window titles, item names and lore), one line per text, for
 * checking the wording. Errors here must never disturb a test.
 */
const TEXT_DUMP = process.env.E2E_TEXT_DUMP || null

function dumpTexts(bot, name) {
  const out = (kind, raw) => {
    try {
      const text = typeof raw === 'string' ? stripColors(raw) : textOf(raw)
      if (text && text.trim()) appendFileSync(TEXT_DUMP, `${kind}\t${name}\t${text.replace(/\s*\n\s*/g, ' | ')}\n`)
    } catch {}
  }
  const on = (packet, fn) => bot._client.on(packet, (p) => { try { fn(p) } catch {} })
  bot.on('messagestr', (msg, position) => out(`chat:${position}`, msg))
  on('set_title_text', (p) => out('title', p.text))
  on('set_title_subtitle', (p) => out('subtitle', p.text))
  on('action_bar', (p) => out('actionbar', p.text))
  on('boss_bar', (p) => { if (p.title != null) out('bossbar', p.title) })
  on('scoreboard_objective', (p) => { if (p.displayText != null) out('objective', p.displayText) })
  on('scoreboard_score', (p) => { if (p.display_name != null) out('score', p.display_name) })
  on('teams', (p) => { out('team', [p.prefix, p.name, p.suffix].filter((x) => x != null).map(textOf).join('')) })
  on('playerlist_header', (p) => { out('tab-header', p.header); out('tab-footer', p.footer) })
  bot.on('windowOpen', (window) => {
    setTimeout(() => {
      try {
        out('window', window.title)
        const end = window.inventoryStart ?? window.slots.length
        for (let i = 0; i < end; i++) {
          const item = window.slots[i]
          if (!item) continue
          if (item.customName != null) out('item', item.customName)
          for (const line of itemLore(item)) out('lore', line)
        }
      } catch {}
    }, 400)
  })
}

export async function rcon(cmd) {
  for (let attempt = 0; attempt < 2; attempt++) {
    try {
      if (!rconConn) {
        rconConn = await Rcon.connect({ host: HOST, port: RCON_PORT, password: RCON_PASSWORD, timeout: 30000 })
        rconConn.on('end', () => { rconConn = null })
        rconConn.on('error', () => { rconConn = null })
      }
      const res = await rconConn.send(cmd)
      return stripColors(res)
    } catch (err) {
      try { await rconConn?.end() } catch {}
      rconConn = null
      if (attempt === 1) throw err
    }
  }
}

export async function moduleEnabled(id) {
  return /\benabled=true\b/.test(await rcon(`smpeco module info ${id}`))
}

export async function closeRcon() {
  const conn = rconConn
  rconConn = null
  try { await conn?.end() } catch {}
}

export function stripColors(text) {
  return String(text ?? '').replace(/§[0-9a-fk-or]/gi, '')
}

/**
 * Treasury balance and entries per reason since opening, in cents: { balance, flows: { <reason>: { income,
 * expense } } }. Asserts compare the entries of their own reason ({@link treasuryDelta}), never the raw
 * balance: NPC orders, NPC auction buyers, bank interest and the lottery change it in the background at any
 * time.
 */
export async function treasuryFlows() {
  const out = await rcon('smpeco treasury flows')
  const m = out.match(/treasury flows balance=(\d+)/)
  if (!m) throw new Error(`smpeco treasury flows: ${out.trim().slice(0, 200)}`)
  const flows = {}
  for (const f of out.matchAll(/flow:(\S+?)=(\d+)\/(\d+)/g)) flows[f[1]] = { income: Number(f[2]), expense: Number(f[3]) }
  return { balance: Number(m[1]), flows }
}

export function treasuryDelta(before, after, reason) {
  const b = before.flows[reason] ?? { income: 0, expense: 0 }
  const a = after.flows[reason] ?? { income: 0, expense: 0 }
  const income = a.income - b.income
  const expense = a.expense - b.expense
  return { income, expense, net: income - expense }
}

/**
 * Makes sure the treasury's hourly NPC budget ({@code allowance}, cents) is at least {@code minCents}. Other
 * scripts may have used up the last hour's budget, and a fixed deposit is not enough then, because the budget
 * is pct % of (treasury + last hour's spending) minus that spending. Returns the deposited dollars so the
 * caller can withdraw them at the end.
 */
export async function topUpTreasury(minCents, { minDollars = 50000, label = 'treasury topped up' } = {}) {
  const out = await rcon('smpeco treasury')
  const num = (k) => Number(out.match(new RegExp(`\\b${k}=(\\d+)`))?.[1] ?? NaN)
  const balance = num('balance')
  const allowance = num('allowance')
  const spent = num('spentLastHour')
  const maxPerHour = num('maxPerHour')
  if (![balance, allowance, spent, maxPerHour].every(Number.isFinite)) throw new Error(`smpeco treasury: ${out.trim().slice(0, 200)}`)
  if (allowance >= minCents) return 0
  // maxPerHour = floor(pct % * (treasury + spent)) gives pct; we want a deposit E with pct * (treasury + E +
  // spent) - spent >= minCents
  const pct = balance + spent > 0 ? maxPerHour / (balance + spent) : 0
  let needCents = minCents - allowance
  if (pct > 0 && pct < 1) needCents = Math.ceil((minCents + spent) / pct) - balance - spent
  const dollars = Math.max(minDollars, Math.ceil((needCents * 1.1) / 100))
  const dep = await rcon(`eco treasury deposit ${dollars}`)
  const ok = /deposited/.test(dep)
  console.log(`[E2E] ${label} (allowance=${allowance}, spentLastHour=${spent}): ${dep.trim()}`)
  return ok ? dollars : 0
}

/**
 * Net change of all other reasons. Every treasury movement has a reason, so balance difference = net of own
 * reason + this value.
 */
export function treasuryOtherNet(before, after, ...reasons) {
  let net = 0
  for (const r of new Set([...Object.keys(before.flows), ...Object.keys(after.flows)])) {
    if (!reasons.includes(r)) net += treasuryDelta(before, after, r).net
  }
  return net
}

/** Only new lines unless { includePast: true }. Returns the match array. */
export function waitForMessage(bot, regex, timeoutMs = 10000, { includePast = false } = {}) {
  if (includePast) {
    for (const msg of bot.messages ?? []) {
      const m = stripColors(msg).match(regex)
      if (m) return Promise.resolve(m)
    }
  }
  return new Promise((resolve, reject) => {
    const onMsg = (msg) => {
      const m = stripColors(msg).match(regex)
      if (m) { cleanup(); resolve(m) }
    }
    const timer = setTimeout(() => { cleanup(); reject(new Error(`No message matching ${regex} after ${timeoutMs} ms`)) }, timeoutMs)
    const cleanup = () => { clearTimeout(timer); bot.off('messagestr', onMsg) }
    bot.on('messagestr', onMsg)
  })
}

export function waitForWindow(bot, timeoutMs = 10000) {
  return new Promise((resolve, reject) => {
    const onOpen = (window) => { cleanup(); setTimeout(() => resolve(window), 150) }
    const timer = setTimeout(() => { cleanup(); reject(new Error(`No window after ${timeoutMs} ms`)) }, timeoutMs)
    const cleanup = () => { clearTimeout(timer); bot.off('windowOpen', onOpen) }
    bot.on('windowOpen', onOpen)
  })
}

/**
 * mode 0 = click, 1 = shift, 2 = number key, 4 = drop, 6 = double click; mouseButton 0 = left, 1 = right.
 * Without an open window the click goes to the player inventory, which is almost always a test bug, hence the
 * warning.
 */
export async function clickSlot(bot, slot, mouseButton = 0, mode = 0) {
  if (!bot.currentWindow) console.log(`[E2E] WARN clickSlot(${slot}) without an open window, the click goes to the player inventory`)
  await bot.clickWindow(slot, mouseButton, mode)
  await sleep(250) // let the server process it (sgui callbacks, resync); must exceed Gui.CLICK_DEBOUNCE_MS (150 ms)
}

export function shiftClick(bot, slot) {
  return clickSlot(bot, slot, 0, 1)
}

const NBT_TAG_TYPES = new Set(['compound', 'list', 'string', 'byte', 'short', 'int', 'long', 'float', 'double', 'byteArray', 'intArray', 'longArray'])

/**
 * Plain text of a text component in any form mineflayer delivers it: JSON string, JSON object, or since 1.20.3
 * a prismarine-nbt object (window titles, custom_name, lore).
 */
export function textOf(raw) {
  if (raw == null) return ''
  let v = raw
  if (typeof v === 'string') {
    try { v = JSON.parse(v) } catch { return stripColors(v) }
  }
  if (v && typeof v === 'object' && !Array.isArray(v) && NBT_TAG_TYPES.has(v.type) && 'value' in v) v = nbt.simplify(v)
  return stripColors(flattenText(v))
}

function flattenText(node) {
  if (node == null) return ''
  if (typeof node === 'string' || typeof node === 'number') return String(node)
  if (Array.isArray(node)) return node.map(flattenText).join('')
  if (typeof node.toString === 'function' && node.constructor?.name === 'ChatMessage') return node.toString()
  const own = node.text ?? node[''] ?? node.translate ?? ''
  return String(own) + (node.extra ? flattenText(node.extra) : '')
}

export function itemLabel(item) {
  if (!item) return ''
  const name = item.customName
  return name != null ? textOf(name) : item.name
}

export function itemLore(item) {
  const lore = item?.customLore
  if (!lore) return []
  return (Array.isArray(lore) ? lore : [lore]).map(textOf)
}

export function windowTitle(window) {
  return textOf(window?.title)
}

/** The listener is registered before the action runs. */
export async function expectWindow(bot, action, timeoutMs = 10000) {
  const pending = waitForWindow(bot, timeoutMs)
  await action()
  return pending
}

/** {@code match} is a function, an item ID or a regex on itemLabel. Returns -1 if nothing matches. */
export function findSlot(window, matcher, { from = 0, to } = {}) {
  const end = to ?? window.inventoryStart ?? window.slots.length
  for (let i = from; i < end; i++) {
    const item = window.slots[i]
    if (!item) continue
    if (typeof matcher === 'function' ? matcher(item) : matcher instanceof RegExp ? matcher.test(itemLabel(item)) : item.name === matcher) return i
  }
  return -1
}

/** key=value pairs from a command's output. Whole numbers become numbers. */
export function kv(line) {
  const out = {}
  for (const m of stripColors(line).matchAll(/(\w+)=(\S+)/g)) out[m[1]] = /^-?\d+$/.test(m[2]) ? Number(m[2]) : m[2]
  return out
}

export async function closeWindow(bot) {
  if (bot?.currentWindow) {
    try { bot.closeWindow(bot.currentWindow) } catch {}
    await sleep(400)
  }
}

export const dollars = (c) => (c / 100).toFixed(2)

export function cents(text) {
  const m = String(text).match(/\$([\d,]+)\.(\d{2})/)
  return m ? Number(m[1].replace(/,/g, '')) * 100 + Number(m[2]) : NaN
}

export async function countItem(name, item) {
  const out = await rcon(`clear ${name} ${item} 0`)
  const m = out.match(/Found (\d+)/)
  if (m) return Number(m[1])
  if (/No items were found/i.test(out)) return 0
  throw new Error(`clear ${name} ${item} 0: ${out}`)
}

/** At least 1.1 s apart per bot, since vanilla kicks non-ops for too many commands in a row. */
export async function cmd(bot, text) {
  const wait = (bot._lastCmd ?? 0) + 1100 - Date.now()
  if (wait > 0) await sleep(wait)
  bot._lastCmd = Date.now()
  bot.chat(text)
}

/** Hotbar slot h in a window: the player inventory starts at inventoryStart, 27 main slots, then the hotbar. */
export const hotbar = (win, h) => win.inventoryStart + 27 + h

export async function walletCents(name) {
  const m = (await rcon(`eco get ${name}`)).match(/\((\d+) cents\)/)
  if (!m) throw new Error(`eco get ${name}`)
  return Number(m[1])
}

export function assert(condition, message) {
  if (condition) { passed++; console.log(`[E2E] PASS ${message}`) } else { failed++; console.log(`[E2E] FAIL ${message}`) }
  return !!condition
}

export function assertEq(actual, expected, message) {
  const ok = JSON.stringify(actual) === JSON.stringify(expected)
  return assert(ok, ok ? message : `${message} (expected ${JSON.stringify(expected)}, was ${JSON.stringify(actual)})`)
}

export async function finish() {
  for (const bot of [...bots]) { try { bot.quit() } catch {} }
  try { await rconConn?.end() } catch {}
  rconConn = null
  console.log(`[E2E] SUMMARY passed=${passed} failed=${failed}`)
  process.exitCode = failed > 0 ? 1 : 0
  return { passed, failed }
}

export async function runE2E(main, { timeoutMs = 180000 } = {}) {
  const timer = setTimeout(async () => {
    assert(false, `Timeout after ${timeoutMs} ms`)
    await finish()
    process.exit(1)
  }, timeoutMs)
  try {
    await main()
  } catch (err) {
    assert(false, `Exception: ${err?.stack ?? err}`)
  }
  clearTimeout(timer)
  await finish()
  await sleep(300)
  process.exit(process.exitCode ?? 0)
}
