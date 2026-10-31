// HUD: packet sidebar (own objective "smpeco:hud", blank number format), only changed lines sent, tab list
// header and footer, /sidebar on|off (persistent over relog), vanilla sidebar overruled or shown when off,
// online count, deaths after /kill and respawn (no second ADD), breaking news hint, job with level and
// progress, missions, daily elite, line switches in hud.json.
import { readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import mineflayer from 'mineflayer'
import { connectBot, rcon, runE2E, assert, assertEq, sleep, waitForMessage, textOf, Vec3 } from './lib.mjs'
import { E2E_SERVER_DIR } from '../tools/server-restart.mjs'

const OBJ = 'smpeco:hud'
const ADDR = 'play.example.test' // test address (last sidebar line)
const B1 = 'hudBot1'
const B2 = 'hudBot2'
const SIDEBAR_SLOT = 1
const BLANK_FORMAT = 0 // NumberFormatTypes: blank=0, styled=1, fixed=2
const X = 5600, Y = 200, Z = -4600 // own test area (mining for mission progress), far from spawn and other scripts
const H = (key) => `smpeco.hud.${key}` // score holder of a line (HudLines.Line)


const originalCreateBot = mineflayer.createBot
mineflayer.createBot = (options) => {
  const bot = originalCreateBot(options)
  track(bot)
  return bot
}

function track (bot) {
  const hud = { objectives: new Map(), slots: {}, header: '', footer: '', tabPackets: 0, log: [], duplicateAdds: [] }
  bot.hud = hud
  const c = bot._client
  c.on('scoreboard_objective', (p) => {
    if (p.action === 0) {
      if (hud.objectives.has(p.name)) hud.duplicateAdds.push(p.name) // real client: "already exists" drops the connection
      hud.objectives.set(p.name, { title: textOf(p.displayText), numberFormat: p.number_format, scores: new Map() })
    } else if (p.action === 1) {
      hud.objectives.delete(p.name)
      for (const slot of Object.keys(hud.slots)) if (hud.slots[slot] === p.name) delete hud.slots[slot]
    } else if (p.action === 2) {
      const o = hud.objectives.get(p.name)
      if (o) { o.title = textOf(p.displayText); o.numberFormat = p.number_format }
    }
    hud.log.push({ at: Date.now(), kind: 'objective', name: p.name, action: p.action })
  })
  c.on('scoreboard_score', (p) => {
    const o = hud.objectives.get(p.scoreName)
    if (o) o.scores.set(p.itemName, { value: p.value, text: p.display_name == null ? p.itemName : textOf(p.display_name), numberFormat: p.number_format })
    hud.log.push({ at: Date.now(), kind: 'score', name: p.scoreName, owner: p.itemName })
  })
  c.on('reset_score', (p) => {
    if (p.objective_name == null) for (const o of hud.objectives.values()) o.scores.delete(p.entity_name)
    else hud.objectives.get(p.objective_name)?.scores.delete(p.entity_name)
    hud.log.push({ at: Date.now(), kind: 'reset', name: p.objective_name, owner: p.entity_name })
  })
  c.on('scoreboard_display_objective', (p) => {
    // as the client: unknown objective means empty slot
    if (p.name && hud.objectives.has(p.name)) hud.slots[p.position] = p.name
    else delete hud.slots[p.position]
    hud.log.push({ at: Date.now(), kind: 'display', name: p.name, position: p.position })
  })
  c.on('playerlist_header', (p) => {
    hud.header = textOf(p.header)
    hud.footer = textOf(p.footer)
    hud.tabPackets++
  })
}

function sidebar (bot) {
  const name = bot.hud.slots[SIDEBAR_SLOT]
  const o = name ? bot.hud.objectives.get(name) : null
  if (!o) return null
  const entries = [...o.scores.entries()]
    .map(([owner, s]) => ({ owner, ...s }))
    .sort((a, b) => b.value - a.value || (a.owner < b.owner ? -1 : 1))
  return { name, title: o.title, numberFormat: o.numberFormat, entries, lines: entries.map((e) => e.text) }
}

function ourSidebar (bot) {
  const sb = sidebar(bot)
  return sb && sb.name === OBJ ? sb : null
}

function line (bot, prefix) {
  return ourSidebar(bot)?.lines.find((l) => l.startsWith(prefix)) ?? null
}

function ownerOf (bot, prefix) {
  return ourSidebar(bot)?.entries.find((e) => e.text.startsWith(prefix))?.owner ?? null
}

function byOwner (bot, owner) {
  return ourSidebar(bot)?.entries.find((e) => e.owner === owner)?.text ?? null
}

// without the playtime minute
function ownersSince (bot, mark) {
  const owners = new Set(packetsSince(bot, mark).filter((e) => e.kind === 'score' || e.kind === 'reset').map((e) => e.owner))
  owners.delete(H('playtime'))
  return [...owners].sort()
}

function num (text) {
  const m = String(text ?? '').match(/(\d[\d,]*)/)
  return m ? Number(m[1].replace(/,/g, '')) : NaN
}

function deaths (bot) {
  const m = (line(bot, 'Kills:') ?? '').match(/Deaths: (\d[\d,]*)/)
  return m ? Number(m[1].replace(/,/g, '')) : NaN
}

async function waitFor (fn, timeoutMs = 5000, stepMs = 100) {
  const end = Date.now() + timeoutMs
  for (;;) {
    const v = fn()
    if (v) return v
    if (Date.now() >= end) return null
    await sleep(stepMs)
  }
}

function packetsSince (bot, mark) {
  return bot.hud.log.slice(mark).filter((e) => e.name === OBJ)
}

async function ensureSidebarOn (bot) {
  if (ourSidebar(bot)) return true
  if (await waitFor(() => ourSidebar(bot), 3000)) return true
  bot.chat('/sidebar on')
  return !!(await waitFor(() => ourSidebar(bot), 5000))
}


runE2E(async () => {
  await rcon('scoreboard objectives remove hudtest')
  await rcon('smpeco pricemod clear')
  // default has no address line, so set one for the test and restore it at the end
  const hudFile = join(E2E_SERVER_DIR, 'config', 'smpeconomy', 'hud.json')
  const hudOriginal = readFileSync(hudFile, 'utf8')
  writeFileSync(hudFile, JSON.stringify({ ...JSON.parse(hudOriginal), address: `§7${ADDR}` }, null, 2))
  await rcon('smpeco reload')

  const bot1 = await connectBot(B1)
  assert(await ensureSidebarOn(bot1), 'sidebar shown after join (objective smpeco:hud in the sidebar slot)')
  let sb = ourSidebar(bot1)
  assertEq(sb?.title, 'SMP Economy', 'sidebar title')
  assertEq(sb?.numberFormat, BLANK_FORMAT, 'objective with blank number format (no red numbers)')
  assert(await waitFor(() => (ourSidebar(bot1)?.lines.length ?? 0) >= 9, 3000), `at least 9 lines (${ourSidebar(bot1)?.lines.length})`)
  sb = ourSidebar(bot1)
  assert(sb.entries.every((e) => e.numberFormat === BLANK_FORMAT), 'each line with blank number format')
  assert(sb.entries.every((e) => !e.owner.startsWith('#')), 'no hidden score holders')
  console.log(`[E2E] Sidebar ${B1}: ${JSON.stringify(sb.lines)}`)
  assert(/^Money: \$[\d,.]+[KMBT]?$/.test(line(bot1, 'Money:') ?? ''), `money line (${line(bot1, 'Money:')})`)
  assert(/^Job: .+/.test(line(bot1, 'Job:') ?? ''), `job line (${line(bot1, 'Job:')})`)
  assert(/^Tasks: (\d+\/\d+( ✔)?|–)$/.test(line(bot1, 'Tasks:') ?? ''), `tasks line (${line(bot1, 'Tasks:')})`)
  assert(/^Kills: \d[\d,]* \| Deaths: \d[\d,]*$/.test(line(bot1, 'Kills:') ?? ''), `kills/deaths line (${line(bot1, 'Kills:')})`)
  assert(/^Playtime: (\d+m|\d+h \d\dm|\d+d \d+h)$/.test(line(bot1, 'Playtime:') ?? ''), `playtime line (${line(bot1, 'Playtime:')})`)
  assert(/^Online: \d+$/.test(line(bot1, 'Online:') ?? ''), `online line (${line(bot1, 'Online:')})`)
  assertEq(sb.lines[sb.lines.length - 1], ADDR, 'address in the last line')

  const diag = await rcon(`smpeco hud ${B1}`)
  assert(/setting=on online=true shown=true/.test(diag), `/smpeco hud: shown (${diag.split('\n')[0]})`)
  assert(diag.includes(line(bot1, 'Money:')), 'diagnostics show the same money line as the client')

  assert(await waitFor(() => /Balance/.test(bot1.hud.footer), 3000), `tab list footer with balance (${JSON.stringify(bot1.hud.footer)})`)
  assert(/SMP Economy/.test(bot1.hud.header) && bot1.hud.header.includes(B1), `tab list header with title and name (${JSON.stringify(bot1.hud.header)})`)
  const onlineNow = num(line(bot1, 'Online:'))
  assert(new RegExp(`Online: ${onlineNow}/\\d+`).test(bot1.hud.footer), 'Footer-Online = Sidebar-Online')

  const moneyOwner = ownerOf(bot1, 'Money:')
  const playtimeOwner = ownerOf(bot1, 'Playtime:')
  let mark = bot1.hud.log.length
  // an amount sure to differ from the current one, since an earlier run may have left $1,234.56
  const [amount, shown] = line(bot1, 'Money:') === 'Money: $1,234.56' ? ['2345.67', '$2,345.67'] : ['1234.56', '$1,234.56']
  const eco = await rcon(`eco set ${B1} ${amount}`)
  assert(!/Unknown or incomplete command/i.test(eco), `/eco available (${eco.trim().slice(0, 80)})`)
  assert(await waitFor(() => line(bot1, 'Money:') === `Money: ${shown}`, 3000), `money line updated (${line(bot1, 'Money:')})`)
  assert(await waitFor(() => bot1.hud.footer.includes(shown), 3000), 'footer balance updated')
  await sleep(1500)
  const moneyOwners = new Set(packetsSince(bot1, mark).filter((e) => e.kind !== 'display').map((e) => e.owner ?? `objective:${e.action}`))
  moneyOwners.delete(playtimeOwner) // the playtime minute change is allowed
  assertEq([...moneyOwners], [moneyOwner], 'only the money line resent')

  mark = bot1.hud.log.length
  const tabBefore = bot1.hud.tabPackets
  await sleep(2500)
  const quiet = packetsSince(bot1, mark).filter((e) => e.owner !== playtimeOwner)
  assertEq(quiet.length, 0, 'no sidebar packets without a change')
  assertEq(bot1.hud.tabPackets, tabBefore, 'no tab list packet without a change')

  const online1 = num(line(bot1, 'Online:'))
  const bot2 = await connectBot(B2)
  assert(await waitFor(() => num(line(bot1, 'Online:')) === online1 + 1, 4000), `Online ${online1} → ${online1 + 1} for ${B1} (${line(bot1, 'Online:')})`)
  assert(await ensureSidebarOn(bot2), `${B2} has the sidebar too`)
  assert(await waitFor(() => new RegExp(`Online: ${online1 + 1}/`).test(bot1.hud.footer), 3000), 'footer online updated')

  assert(await waitFor(() => bot2.health > 0, 5000), `${B2} alive`)
  assert(await waitFor(() => Number.isFinite(deaths(bot2)), 3000), `deaths readable for ${B2}`)
  const d0 = deaths(bot2)
  const addsBefore = bot2.hud.log.filter((e) => e.kind === 'objective' && e.name === OBJ && e.action === 0).length
  const respawned = new Promise((resolve) => bot2.once('respawn', resolve))
  await rcon(`kill ${B2}`)
  await Promise.race([respawned, sleep(8000)])
  assert(await waitFor(() => deaths(bot2) === d0 + 1, 5000), `deaths ${d0} → ${d0 + 1} (${line(bot2, 'Kills:')})`)
  await sleep(1500)
  assert(ourSidebar(bot2) !== null, 'sidebar still visible after respawn')
  const addsAfter = bot2.hud.log.filter((e) => e.kind === 'objective' && e.name === OBJ && e.action === 0).length
  assertEq(addsAfter, addsBefore, 'no new objective ADD after respawn (same connection)')

  const SHOCK = '⚡ Breaking News'
  const shockBefore = ourSidebar(bot1).lines.includes(SHOCK)
  await rcon('smpeco pricemod minecraft:bedrock 0.5 1')
  assert(await waitFor(() => ourSidebar(bot1)?.lines.includes(SHOCK), 3000), `market shock line at ${B1}`)
  assert(await waitFor(() => ourSidebar(bot2)?.lines.includes(SHOCK), 3000), `market shock line at ${B2} (after respawn)`)
  assert(await waitFor(() => bot1.hud.footer.includes(SHOCK), 3000), 'market shock in the tab list footer')
  assertEq(ourSidebar(bot1).lines[ourSidebar(bot1).lines.length - 1], ADDR, 'address stays at the bottom')
  await rcon('smpeco pricemod clear')
  if (!shockBefore) {
    assert(await waitFor(() => !ourSidebar(bot1)?.lines.includes(SHOCK), 3000), 'market shock line disappears again')
  } else {
    console.log('[E2E] INFO another market shock active, disappearance not checked')
  }

  bot1.chat('/sidebar off')
  await waitForMessage(bot1, /Sidebar turned off/, 5000)
  assert(await waitFor(() => !bot1.hud.objectives.has(OBJ), 3000), '/sidebar off removes the objective at the client')
  assert(bot1.hud.slots[SIDEBAR_SLOT] !== OBJ, 'sidebar slot no longer ours')
  bot1.chat('/sidebar off')
  await waitForMessage(bot1, /already off/, 5000)
  assert(true, '/sidebar off twice gives "already off"')
  assert(/setting=off online=true shown=false/.test(await rcon(`smpeco hud ${B1}`)), 'diagnostics: off, not shown')
  bot1.quit()
  await sleep(1500)
  assert(/setting=off online=false/.test(await rcon(`smpeco hud ${B1}`)), 'switch saved offline')
  const bot1b = await connectBot(B1)
  assert(await waitFor(() => /Balance/.test(bot1b.hud.footer), 4000), 'tab list also with sidebar off')
  await sleep(1000)
  assert(!bot1b.hud.objectives.has(OBJ), 'still off after relog (persistent)')
  bot1b.chat('/sidebar on')
  await waitForMessage(bot1b, /Sidebar turned on/, 5000)
  assert(await waitFor(() => (ourSidebar(bot1b)?.lines.length ?? 0) >= 9, 3000), '/sidebar on shows it with all lines')
  bot1b.chat('/sidebar')
  assert(await waitFor(() => !bot1b.hud.objectives.has(OBJ), 3000), '/sidebar without argument switches off')
  await sleep(400)
  bot1b.chat('/sidebar')
  assert(await waitFor(() => ourSidebar(bot1b), 3000), '/sidebar without argument switches on again')

  try {
    await rcon('scoreboard objectives add hudtest dummy')
    await rcon('scoreboard objectives setdisplay sidebar hudtest')
    assert(await waitFor(() => ourSidebar(bot1b), 3000), `${B1}: our sidebar back in front after setdisplay`)
    assert(await waitFor(() => ourSidebar(bot2), 3000), `${B2}: our sidebar back in front after setdisplay`)
    bot2.chat('/sidebar off')
    assert(await waitFor(() => sidebar(bot2)?.name === 'hudtest', 3000), `${B2}: with /sidebar off the vanilla sidebar appears (${sidebar(bot2)?.name})`)
    bot2.chat('/sidebar on')
    assert(await waitFor(() => ourSidebar(bot2), 3000), `${B2}: /sidebar on overrules the vanilla sidebar again`)
  } finally {
    await rcon('scoreboard objectives remove hudtest')
  }

  await jobLines(bot1b)
  await missionLines(bot1b)
  await eliteLines(bot1b)
  await lineSwitches(bot1b)
  const last = ourSidebar(bot1b)
  assert(last.lines.length <= 15, `at most 15 lines (${last.lines.length})`)
  assertEq(last.lines[last.lines.length - 1], ADDR, 'address still at the bottom')
  console.log(`[E2E] sidebar ${B1} at the end: ${JSON.stringify(last.lines)}`)

  // never a double ADD; real clients would be disconnected
  assertEq([...bot1.hud.duplicateAdds, ...bot1b.hud.duplicateAdds, ...bot2.hud.duplicateAdds], [], 'no objective created twice')
  const st = await rcon('smpeco selftest hud')
  const m = st.match(/SUMMARY passed=(\d+) failed=(\d+)/)
  assert(m && Number(m[1]) > 0 && Number(m[2]) === 0, `hud selftests green (${m ? m[0] : st.slice(0, 200)})`)
  if (m && Number(m[2]) > 0) console.log(st)
  writeFileSync(hudFile, hudOriginal)
  await rcon('smpeco reload')
})


async function jobLines (bot) {
  const name = bot.username
  const set = await rcon(`jobs admin set ${name} miner`)
  if (/Unknown or incomplete command/i.test(set)) {
    console.log('[E2E] INFO /jobs admin missing, job lines not checked')
    return
  }
  try {
    // read the XP threshold of level 12 from the reply (too much XP is limited to threshold - 1)
    const probe = await rcon(`jobs admin level ${name} miner 12 999999999`)
    const pm = probe.match(/Level 12 \((\d+) XP\)/)
    assert(!!pm, `XP threshold readable (${probe.trim()})`)
    const need = Number(pm[1]) + 1
    await rcon(`jobs admin level ${name} miner 12 0`)
    assert(await waitFor(() => byOwner(bot, H('job')) === 'Job: Miner (Lv. 12)', 3000), `job line with level (${byOwner(bot, H('job'))})`)
    assert(await waitFor(() => byOwner(bot, H('job_progress')) === ' ▸ ■■■■■■■■■■ 0%', 3000), `progress 0 % (${JSON.stringify(byOwner(bot, H('job_progress')))})`)
    const sb = ourSidebar(bot)
    assertEq(sb.lines.indexOf(byOwner(bot, H('job_progress'))), sb.lines.indexOf('Job: Miner (Lv. 12)') + 1, 'progress directly below the job')

    const half = Math.floor(need / 2)
    const pct = Math.floor(half * 100 / need)
    const mark = bot.hud.log.length
    await rcon(`jobs admin level ${name} miner 12 ${half}`)
    assert(await waitFor(() => byOwner(bot, H('job_progress')) === ` ▸ ${'■'.repeat(10)} ${pct}%`, 3000),
      `progress ${pct} % at ${half}/${need} XP (${byOwner(bot, H('job_progress'))})`)
    await sleep(1200)
    assertEq(ownersSince(bot, mark), [H('job_progress')], 'XP change: only the progress line resent')

    await rcon(`jobs admin level ${name} miner 50`)
    assert(await waitFor(() => byOwner(bot, H('job_progress')) === ' ▸ Max Level', 3000), `max level (${byOwner(bot, H('job_progress'))})`)
    assert(await waitFor(() => byOwner(bot, H('job')) === 'Job: Miner (Lv. 50)', 3000), 'job line Lv. 50')

    const mark2 = bot.hud.log.length
    await rcon(`jobs admin set ${name} none`)
    assert(await waitFor(() => byOwner(bot, H('job')) === 'Job: none', 3000), `without job (${byOwner(bot, H('job'))})`)
    assert(await waitFor(() => byOwner(bot, H('job_progress')) === null, 3000), 'no progress line without a job')
    await sleep(1200)
    assertEq(ownersSince(bot, mark2), [H('job'), H('job_progress')], 'job gone: only job line and reset of the progress line')
    assert(packetsSince(bot, mark2).some((e) => e.kind === 'reset' && e.owner === H('job_progress')), 'progress line removed with a reset')
  } finally {
    await rcon(`jobs admin set ${name} none`).catch(() => {})
    await rcon(`jobs admin level ${name} miner 1 0`).catch(() => {})
  }
}

async function missionLines (bot) {
  const name = bot.username
  const reset = await rcon(`smpeco missions reset ${name}`)
  if (/Unknown or incomplete command/i.test(reset)) {
    console.log('[E2E] INFO /smpeco missions missing, mission lines not checked')
    return
  }
  await rcon(`forceload add ${X} ${Z}`)
  try {
    await rcon(`fill ${X - 3} ${Y} ${Z - 3} ${X + 3} ${Y + 3} ${Z + 3} minecraft:air`)
    await rcon(`fill ${X - 3} ${Y - 1} ${Z - 3} ${X + 3} ${Y - 1} ${Z + 3} minecraft:glass`)
    await rcon(`gamemode survival ${name}`)
    await rcon(`tp ${name} ${X + 0.5} ${Y} ${Z + 0.5}`)
    await rcon(`setblock ${X + 2} ${Y} ${Z} minecraft:dirt`)
    await sleep(1500)

    assert(await waitFor(() => /^Missions: 0\/\d+$/.test(byOwner(bot, H('missions')) ?? ''), 3000), `missions 0/x (${byOwner(bot, H('missions'))})`)
    const slots = Number(byOwner(bot, H('missions')).split('/')[1])
    assertEq(byOwner(bot, H('mission_progress')), null, 'no sub-line without an active mission')

    const a1 = await rcon(`smpeco missions assign ${name} easy_dirt 3`)
    assert(/assign ok/.test(a1), `mining mission assigned (${a1.trim()})`)
    assert(await waitFor(() => byOwner(bot, H('missions')) === `Missions: 1/${slots}`, 3000), `missions 1/${slots} (${byOwner(bot, H('missions'))})`)
    // the mission name comes from missions.json, so check only the format " ▸ <name> 0/3"
    const first = await waitFor(() => (byOwner(bot, H('mission_progress')) ?? '').match(/^ ▸ (.+) 0\/3$/), 3000)
    assert(!!first, `next mission (${byOwner(bot, H('mission_progress'))})`)
    const mName = first ? first[1] : '?'
    let sb = ourSidebar(bot)
    assertEq(sb.lines.indexOf(` ▸ ${mName} 0/3`), sb.lines.indexOf(`Missions: 1/${slots}`) + 1, 'sub-line directly below "Missions"')

    // second mission (0/32): only the count changes, the next stays (same share, less left)
    let mark = bot.hud.log.length
    const a2 = await rcon(`smpeco missions assign ${name} medium_iron 32`)
    assert(/assign ok/.test(a2), `delivery mission assigned (${a2.trim()})`)
    assert(await waitFor(() => byOwner(bot, H('missions')) === `Missions: 2/${slots}`, 3000), `missions 2/${slots}`)
    await sleep(1200)
    assertEq(byOwner(bot, H('mission_progress')), ` ▸ ${mName} 0/3`, 'next mission unchanged')
    assertEq(ownersSince(bot, mark), [H('missions')], 'second mission: only the count resent')

    mark = bot.hud.log.length
    const dirt = await waitFor(() => {
      const b = bot.blockAt(new Vec3(X + 2, Y, Z))
      return b && b.name === 'dirt' ? b : null
    }, 5000)
    assert(!!dirt, `dirt to mine present (${bot.blockAt(new Vec3(X + 2, Y, Z))?.name})`)
    await bot.dig(dirt)
    assert(await waitFor(() => byOwner(bot, H('mission_progress')) === ` ▸ ${mName} 1/3`, 4000), `progress 1/3 (${byOwner(bot, H('mission_progress'))})`)
    await sleep(1200)
    const changed = ownersSince(bot, mark).filter((o) => o !== H('money')) // in case a job pays after all
    assertEq(changed, [H('mission_progress')], 'mining: only the mission sub-line resent')

    // all gone gives 0/x, sub-line removed with a reset, lines below unchanged (no sliding up)
    mark = bot.hud.log.length
    await rcon(`smpeco missions reset ${name}`)
    assert(await waitFor(() => byOwner(bot, H('missions')) === `Missions: 0/${slots}`, 3000), '0 active after reset')
    assert(await waitFor(() => byOwner(bot, H('mission_progress')) === null, 3000), 'no sub-line after reset')
    await sleep(1200)
    assertEq(ownersSince(bot, mark), [H('mission_progress'), H('missions')].sort(), 'reset: only count and sub-line')
    sb = ourSidebar(bot)
    assert(sb.lines.includes(ADDR), 'address still there')
  } finally {
    await rcon(`smpeco missions reset ${name}`).catch(() => {})
    // glass floor stays since the bot stands on it; only the space above is cleared
    await rcon(`fill ${X - 3} ${Y} ${Z - 3} ${X + 3} ${Y + 3} ${Z + 3} minecraft:air`).catch(() => {})
    await rcon(`forceload remove ${X} ${Z}`).catch(() => {})
  }
}

/**
 * If /smpeco missions elite exists the line must appear ("Elite: open"). Completed as admin (daily elite =
 * warden hunt, natural warden through /summon, kill by the bot) it becomes "Elite: ✔", with only this line
 * resent.
 */
async function eliteLines (bot) {
  const name = bot.username
  const out = await rcon('smpeco missions elite')
  const info = out.split('\n').find((l) => l.startsWith('missions elite enabled=')) ?? null
  if (!info) {
    console.log(`[E2E] INFO /smpeco missions elite missing, elite line not checked (${out.trim().slice(0, 120)})`)
    assertEq(byOwner(bot, H('elite')), null, 'no elite line without a daily elite')
    return
  }
  assert(/enabled=true/.test(info) && !/ id=-( |$)/.test(info), `daily elite enabled on the test server (${info.slice(0, 160)})`)
  const sel = `@e[type=minecraft:warden,x=${X},y=${Y},z=${Z},distance=..12]`
  await rcon(`forceload add ${X} ${Z}`)
  try {
    await rcon(`smpeco missions elite reset ${name}`)
    assert(await waitFor(() => byOwner(bot, H('elite')) === 'Elite: open', 3000), `Elite: open (${byOwner(bot, H('elite'))})`)
    const sb = ourSidebar(bot)
    assertEq(sb.lines.indexOf('Elite: open') > sb.lines.findIndex((l) => l.startsWith('Missions:')), true, 'elite line below "Missions"')

    assert(/set ok id=warden/.test(await rcon('smpeco missions elite set warden')), 'admin: daily elite = warden hunt')
    await rcon(`smpeco missions elite reset ${name}`)
    await rcon(`gamemode survival ${name}`)
    await rcon(`tp ${name} ${X + 0.5} ${Y} ${Z + 0.5}`)
    await rcon(`kill ${sel}`)
    await rcon(`summon minecraft:warden ${X + 2.5} ${Y} ${Z + 0.5} {NoAI:1b,Silent:1b,PersistenceRequired:1b}`)
    await sleep(300)
    assert(await waitFor(() => byOwner(bot, H('elite')) === 'Elite: open', 3000), 'open before the kill')
    const mark = bot.hud.log.length
    const hit = await rcon(`damage ${sel.replace(']', ',limit=1]')} 1000 minecraft:player_attack by ${name}`)
    assert(await waitFor(() => byOwner(bot, H('elite')) === 'Elite: ✔', 4000), `after the warden kill "Elite: ✔" (${byOwner(bot, H('elite'))}; ${hit.trim()})`)
    const st = await rcon(`smpeco missions elite status ${name}`)
    assert(/ done=true/.test(st), `daily elite completed according to status (${st.trim().slice(0, 160)})`)
    await sleep(1200)
    const changed = ownersSince(bot, mark).filter((o) => o !== H('money') && o !== H('kills_deaths'))
    assertEq(changed, [H('elite')], 'elite completed: only the elite line resent')

    await rcon(`smpeco missions elite reset ${name}`)
    assert(await waitFor(() => byOwner(bot, H('elite')) === 'Elite: open', 3000), `open again after admin reset (${byOwner(bot, H('elite'))})`)
  } finally {
    await rcon(`kill ${sel}`).catch(() => {})
    await rcon(`kill @e[type=minecraft:item,x=${X},y=${Y},z=${Z},distance=..12]`).catch(() => {})
    await rcon(`kill @e[type=minecraft:experience_orb,x=${X},y=${Y},z=${Z},distance=..12]`).catch(() => {})
    await rcon('smpeco missions elite clear').catch(() => {})
    await rcon(`smpeco missions elite reset ${name}`).catch(() => {})
    await rcon(`forceload remove ${X} ${Z}`).catch(() => {})
  }
}

/**
 * hud.json "lines": hide a single line through /smpeco reload; only its reset packet goes out, then it returns.
 */
async function lineSwitches (bot) {
  const file = join(E2E_SERVER_DIR, 'config', 'smpeconomy', 'hud.json')
  let original
  try {
    original = readFileSync(file, 'utf8')
  } catch (e) {
    console.log(`[E2E] INFO ${file} not readable (${e.message}), line switches not checked`)
    return
  }
  const cfg = JSON.parse(original)
  assert(cfg.lines && typeof cfg.lines === 'object', `hud.json has "lines" (${Object.keys(cfg.lines ?? {}).join(',')})`)
  assert(['money', 'job', 'jobProgress', 'missions', 'missionProgress', 'elite', 'tasks', 'killsDeaths', 'playtime', 'online', 'spacers',
    'marketShock', 'auctionFever', 'address'].every((k) => k in cfg.lines), 'all line switches added to hud.json')
  try {
    assert(await waitFor(() => byOwner(bot, H('online')) !== null, 3000), 'online line there before')
    const mark = bot.hud.log.length
    writeFileSync(file, JSON.stringify({ ...cfg, lines: { ...cfg.lines, online: false } }, null, 2))
    const r = await rcon('smpeco reload')
    console.log(`[E2E] smpeco reload: ${r.trim().slice(0, 200)}`)
    assert(await waitFor(() => byOwner(bot, H('online')) === null, 3000), '"online": false hides the online line')
    await sleep(1200)
    assertEq(ownersSince(bot, mark), [H('online')], 'only the online line removed (no other packets)')
  } finally {
    writeFileSync(file, original)
    await rcon('smpeco reload')
  }
  assert(await waitFor(() => /^Online: \d+$/.test(byOwner(bot, H('online')) ?? ''), 3000), 'online line back after resetting')
}
