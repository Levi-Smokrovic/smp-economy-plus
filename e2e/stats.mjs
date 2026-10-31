// Stats: PvP kill through the foundation hook (real sword hit), mob kill, environmental death (kill streak),
// /stats GUI (own, other, offline; item safety, refresh, leaderboard buttons), /playtime, /leaderboard
// <category> [page]. Setup and asserts use RCON, since /smpeco stats <player> is machine-readable.
import {
  connectBot, rcon, runE2E, assert, assertEq, sleep, expectWindow, windowTitle, clickSlot, itemLabel,
  itemLore, waitForMessage, stripColors, closeWindow,
} from './lib.mjs'

const B1 = 'statsBot1'
const B2 = 'statsBot2'
const X = -4300, Y = 200, Z = 4300
const PAGE_SIZE = 10 // default stats.json pageSize

const SLOT = { head: 4, kills: 19, deaths: 20, kd: 21, mobs: 22, playtime: 23, money: 24, misc: 25, refresh: 36, topKills: 38, topDeaths: 39, close: 44 }

async function stats(name) {
  const out = await rcon(`smpeco stats ${name}`)
  const m = {}
  for (const [, k, v] of out.matchAll(/(\w+)=(\S+)/g)) m[k] = v
  if (!('kills' in m)) throw new Error(`smpeco stats ${name}: ${out.trim()}`)
  const n = (k) => Number(m[k])
  return {
    raw: out.trim(), online: m.online === 'true', service: m.service, kills: n('kills'), pvpKills: n('pvpKills'),
    pvpDeaths: n('pvpDeaths'), deaths: n('deaths'), mobKills: n('mobKills'), streak: n('streak'), bestStreak: n('bestStreak'),
    playtimeTicks: n('playtimeTicks'), cachedDeaths: n('cachedDeaths'), cachedMobKills: n('cachedMobKills'),
  }
}

async function until(fn, timeoutMs = 8000, stepMs = 250) {
  const end = Date.now() + timeoutMs
  let last
  while (Date.now() < end) {
    last = await fn()
    if (last) return last
    await sleep(stepMs)
  }
  return last
}

function loreNumber(item) {
  const line = itemLore(item)[0] ?? ''
  return Number(line.replace(/[^\d.-]/g, ''))
}

async function chatAfter(bot, action, waitMs = 1200) {
  const start = bot.messages.length
  await action()
  await sleep(waitMs)
  return bot.messages.slice(start).map(stripColors)
}

/**
 * Real PvP kill: bring the victim down to a few hearts with /damage (no attacker), then a sword hit from the
 * killer.
 */
async function pvpKill(killer, victim, victimDeathsBefore) {
  for (let attempt = 1; attempt <= 6; attempt++) {
    const hp = Math.floor(victim.health ?? 20)
    if (hp > 4) await rcon(`damage ${victim.username} ${hp - 3} minecraft:generic`)
    await sleep(800) // wait out damage invulnerability (10 ticks) and the sword cooldown
    const target = killer.players[victim.username]?.entity
    if (!target) {
      console.log(`[E2E] attempt ${attempt}: ${victim.username} not visible to ${killer.username}`)
      await sleep(500)
      continue
    }
    killer.attack(target)
    const died = await until(async () => (await stats(victim.username)).deaths > victimDeathsBefore, 2500)
    if (died) return attempt
    console.log(`[E2E] attempt ${attempt}: no death (HP ${victim.health})`)
  }
  return 0
}

runE2E(async () => {
  const bot1 = await connectBot(B1)
  const bot2 = await connectBot(B2)
  assert(true, 'bots connected')

  const st = await rcon('smpeco selftest stats')
  const sm = st.match(/SUMMARY passed=(\d+) failed=(\d+)/)
  assert(!!sm, `selftest summary present (${st.slice(0, 200).trim()})`)
  if (sm) {
    assert(Number(sm[1]) >= 15, `stats selftests ran (passed=${sm[1]})`)
    assertEq(Number(sm[2]), 0, 'stats-Selftests failed=0')
  }

  const s1 = await stats(B1)
  const s2 = await stats(B2)
  assertEq(s1.service, 'yes', 'Services.PlayerStats registered (hud/holo)')
  assert(s1.online && s2.online, 'both bots online')
  assertEq(s1.cachedDeaths, s1.deaths, 'cache filled on join')

  await rcon(`forceload add ${X} ${Z}`)
  await sleep(1000)
  await rcon(`fill ${X - 3} ${Y - 1} ${Z - 3} ${X + 3} ${Y + 3} ${Z + 3} minecraft:air`)
  await rcon(`fill ${X - 3} ${Y - 1} ${Z - 3} ${X + 3} ${Y - 1} ${Z + 3} minecraft:glass`)
  for (const n of [B1, B2]) {
    await rcon(`gamemode survival ${n}`)
    await rcon(`clear ${n}`)
    await rcon(`effect clear ${n}`)
  }
  await rcon(`tp ${B1} ${X + 0.5} ${Y} ${Z + 0.5} -90 0`)
  await rcon(`tp ${B2} ${X + 1.8} ${Y} ${Z + 0.5} 90 0`)
  await rcon(`item replace entity ${B1} weapon.mainhand with minecraft:netherite_sword`)
  await sleep(3500) // spawn invulnerability (60 ticks) after the join

  try {
    const attempts = await pvpKill(bot1, bot2, s2.deaths)
    assert(attempts > 0, `${B1} kills ${B2} with a sword (attempts: ${attempts})`)
    await sleep(500)
    const a1 = await stats(B1)
    const a2 = await stats(B2)
    assertEq(a1.kills - s1.kills, 1, 'PvP: killer +1 player kill')
    assertEq(a1.pvpKills - s1.pvpKills, 1, 'PvP: hook counted exactly once')
    assertEq(a2.deaths - s2.deaths, 1, 'PvP: victim +1 death')
    assertEq(a2.pvpDeaths - s2.pvpDeaths, 1, 'PvP: victim +1 PvP death')
    assertEq(a2.kills, s2.kills, 'PvP: victim gets no kill')
    assertEq(a1.deaths, s1.deaths, 'PvP: killer deaths unchanged')
    assertEq(a1.streak, s1.streak + 1, 'PvP: kill streak +1')
    assert(a1.bestStreak >= a1.streak, 'PvP: record >= streak')
    assertEq(a2.streak, 0, 'PvP: victim streak ended')
    assertEq(a2.cachedDeaths, a2.deaths, 'PvP: victim offline cache updated at once')

    await rcon(`summon minecraft:chicken ${X + 1.5} ${Y} ${Z + 1.5} {NoAI:1b,Health:1.0f,Tags:["statsE2E"]}`)
    const chicken = await until(() => Object.values(bot1.entities).find((e) => e.name === 'chicken' && bot1.entity && e.position.distanceTo(bot1.entity.position) < 4), 4000)
    assert(!!chicken, 'chicken next to the bot')
    if (chicken) {
      await sleep(800)
      bot1.attack(chicken)
      const mk = await until(async () => { const s = await stats(B1); return s.mobKills > a1.mobKills ? s : null }, 4000)
      assert(!!mk && mk.mobKills - a1.mobKills === 1, `Mob-Kill +1 (${mk ? mk.mobKills - a1.mobKills : 'none'})`)
      if (mk) assertEq(mk.kills, a1.kills, 'mob kill does not count as player kill')
    }

    await rcon(`clear ${B1}`)
    await rcon(`xp set ${B1} 0 levels`)
    await rcon(`xp set ${B1} 0 points`)
    const before = await stats(B1)
    const b2before = await stats(B2)
    await rcon(`kill ${B1}`)
    const after = await until(async () => { const s = await stats(B1); return s.deaths > before.deaths ? s : null }, 5000)
    assert(!!after, 'environmental death recorded')
    if (after) {
      assertEq(after.deaths - before.deaths, 1, 'environment death: +1 death')
      assertEq(after.kills, before.kills, 'environmental death: kills unchanged')
      assertEq(after.pvpDeaths, before.pvpDeaths, 'environmental death is not a PvP death')
      assertEq(after.streak, 0, 'environmental death ends the kill streak')
      assert(after.bestStreak >= 1, 'record streak stays')
    }
    assertEq((await stats(B2)).kills, b2before.kills, 'environmental death: nobody gets a kill')
    await sleep(1500)
    // back to the test area, since the respawn is at world spawn; the item check below must not clear anything
    // foreign there
    await rcon(`tp ${B1} ${X + 0.5} ${Y} ${Z + 0.5}`)
    await sleep(500)

    await rcon(`clear ${B1}`)
    const me = await stats(B1)
    let win = await expectWindow(bot1, () => bot1.chat('/stats'))
    assert(/^Stats » /.test(windowTitle(win)) && windowTitle(win).includes(B1), `GUI title (${windowTitle(win)})`)
    assertEq(win.slots[SLOT.head]?.name, 'player_head', 'GUI: player head')
    assertEq(itemLabel(win.slots[SLOT.head]), B1, 'GUI: head with name')
    assert(itemLore(win.slots[SLOT.head]).some((l) => /Online/.test(l)), 'GUI: head shows online')
    assertEq(loreNumber(win.slots[SLOT.kills]), me.kills, 'GUI: player kills')
    assertEq(loreNumber(win.slots[SLOT.deaths]), me.deaths, 'GUI: deaths')
    assertEq(loreNumber(win.slots[SLOT.mobs]), me.mobKills, 'GUI: Mob-Kills')
    assert(itemLore(win.slots[SLOT.kills]).some((l) => /Kill Streak: 0/.test(l)), 'GUI: kill streak 0 after death')
    assert((itemLore(win.slots[SLOT.playtime])[0] ?? '').length > 0, `GUI: playtime (${itemLore(win.slots[SLOT.playtime])[0]})`)
    assert(/\$/.test(itemLore(win.slots[SLOT.money])[0] ?? ''), 'GUI: balance')
    assert(/^\d+\.\d\d$/.test(itemLore(win.slots[SLOT.kd])[0] ?? ''), `GUI: K/D (${itemLore(win.slots[SLOT.kd])[0]})`)
    assertEq(win.slots[0]?.name, 'black_stained_glass_pane', 'GUI: frame')

    // Item safety: shift click, number key, Q on display items (checked after closing). First clear items left
    // on the test area, such as feathers from the chicken.
    const p0 = bot1.entity.position
    await rcon(`kill @e[type=minecraft:item,x=${p0.x.toFixed(1)},y=${p0.y.toFixed(1)},z=${p0.z.toFixed(1)},distance=..6]`)
    await clickSlot(bot1, SLOT.kills, 0, 1)
    await clickSlot(bot1, SLOT.deaths, 0, 2)
    await clickSlot(bot1, SLOT.mobs, 0, 4)
    await clickSlot(bot1, SLOT.kd, 1, 4)
    await sleep(300)
    assert(!!bot1.currentWindow, 'GUI stays open after clicks on display items')
    // dropped GUI items would lie next to the bot as item entities; kill removes them and reports the count
    const p = bot1.entity.position
    const dropped = await rcon(`kill @e[type=minecraft:item,x=${p.x.toFixed(1)},y=${p.y.toFixed(1)},z=${p.z.toFixed(1)},distance=..6]`)
    assert(/No entity was found/i.test(dropped), `Q on display items drops nothing (${dropped.trim()})`)

    win = await expectWindow(bot1, () => clickSlot(bot1, SLOT.refresh))
    assert(windowTitle(win).includes(B1), 'refresh reopens the statistics')

    let lines = await chatAfter(bot1, () => clickSlot(bot1, SLOT.topKills), 1200)
    assert(lines.some((l) => /Leaderboard » Player Kills \(Page 1\/\d+\)/.test(l)), `button shows kill leaderboard (${lines.slice(0, 2).join(' | ')})`)
    assert(!bot1.currentWindow, 'GUI closed after leaderboard button')
    await sleep(300)
    assertEq(bot1.inventory.items().map((it) => it.name), [], 'no GUI items in the inventory')

    const other = await stats(B2)
    win = await expectWindow(bot1, () => bot1.chat(`/stats ${B2}`))
    assert(windowTitle(win).includes(B2), `other player's statistics opened (${windowTitle(win)})`)
    assertEq(loreNumber(win.slots[SLOT.kills]), other.kills, 'other player: player kills')
    assertEq(loreNumber(win.slots[SLOT.deaths]), other.deaths, 'other player: deaths')
    assert(itemLore(win.slots[SLOT.deaths]).some((l) => new RegExp(`By players: ${other.pvpDeaths}$`).test(l)), 'other: PvP deaths in the lore')
    await clickSlot(bot1, SLOT.close)
    await sleep(300)
    assert(!bot1.currentWindow, 'close button closes the GUI')

    // the error text belongs to core Args, accept any wording
    const unknown = waitForMessage(bot1, /not found|unknown player/i, 4000).then(() => true, () => false)
    bot1.chat('/stats NoSuch_x9')
    assert(await unknown, '/stats with unknown name gives an error')

    lines = await chatAfter(bot1, () => bot1.chat('/playtime'))
    assert(lines.some((l) => /Your playtime: \d/.test(l)), `/playtime own time (${lines.join(' | ')})`)
    assert(lines.some((l) => /This session: \d/.test(l)), '/playtime shows the session')
    lines = await chatAfter(bot1, () => bot1.chat(`/playtime ${B2}`))
    assert(lines.some((l) => l.includes(`Playtime of ${B2}: `)), `/playtime ${B2} (${lines.join(' | ')})`)
    const pt = await rcon(`playtime ${B1}`)
    assert(/Playtime of statsBot1/.test(pt), `/playtime per RCON (${pt.trim()})`)

    lines = await chatAfter(bot1, () => bot1.chat('/leaderboard kills'))
    assert(lines.some((l) => /Leaderboard » Player Kills \(Page 1\/\d+\)/.test(l)), '/leaderboard kills: header')
    const own = lines.join('\n').match(/Your rank: #(\d+) \(([\d,]+)\)/)
    assert(!!own, `/leaderboard kills: own rank (${lines.join(' | ')})`)
    if (own) {
      assertEq(Number(own[2].replace(/,/g, '')), me.kills, '/leaderboard kills: own value = player kills')
      if (Number(own[1]) <= PAGE_SIZE) assert(lines.some((l) => new RegExp(`#${own[1]} ${B1} » `).test(l)), '/leaderboard kills: bot in the list')
    }
    lines = await chatAfter(bot1, () => bot1.chat('/leaderboard deaths'))
    assert(lines.some((l) => /Leaderboard » Deaths/.test(l)) && lines.some((l) => /Your rank: #\d+/.test(l)), '/leaderboard deaths')
    lines = await chatAfter(bot1, () => bot1.chat('/leaderboard playtime'))
    assert(lines.some((l) => /Leaderboard » Playtime/.test(l)) && lines.some((l) => /Your rank: #\d+/.test(l)), '/leaderboard playtime')
    lines = await chatAfter(bot1, () => bot1.chat('/leaderboard money'))
    assert(lines.some((l) => /Leaderboard » Balance/.test(l)) && lines.some((l) => /Your rank|not on this leaderboard/.test(l)), '/leaderboard money')
    lines = await chatAfter(bot1, () => bot1.chat('/leaderboard mobs'))
    assert(lines.some((l) => /Leaderboard » Mob Kills/.test(l)), '/leaderboard mobs')
    lines = await chatAfter(bot1, () => bot1.chat('/leaderboard death'))
    assert(lines.some((l) => /Leaderboard » Deaths/.test(l)), '/leaderboard death (alias)')
    lines = await chatAfter(bot1, () => bot1.chat('/leaderboard time'))
    assert(lines.some((l) => /Leaderboard » Playtime/.test(l)), '/leaderboard time (alias)')
    lines = await chatAfter(bot1, () => bot1.chat('/leaderboard kills 999'))
    const pg = lines.join('\n').match(/Player Kills \(Page (\d+)\/(\d+)\)/)
    assert(!!pg && pg[1] === pg[2], `/leaderboard kills 999 gives the last page (${pg ? pg[0] : lines.join(' | ')})`)
    lines = await chatAfter(bot1, () => bot1.chat('/leaderboard nonsense'))
    assert(lines.some((l) => /Unknown category/.test(l)), '/leaderboard nonsense gives an error')
    lines = await chatAfter(bot1, () => bot1.chat('/leaderboard'))
    assert(lines.some((l) => /Pick a category/.test(l)) && lines.some((l) => /\[playtime\]/.test(l)), '/leaderboard without category gives an overview')
    const consoleTop = await rcon('leaderboard kills')
    assert(/Leaderboard » Player Kills/.test(consoleTop) && !/Your rank/.test(consoleTop), '/leaderboard over RCON (no own rank)')

    // offline: values from stats.json, written on leaving
    const lastOnline = await stats(B2)
    bot2.quit()
    const off = await until(async () => { const s = await stats(B2); return s.online ? null : s }, 6000)
    assert(!!off, `${B2} offline`)
    if (off) {
      assertEq(off.deaths, lastOnline.deaths, 'offline: deaths from the cache')
      assertEq(off.kills, lastOnline.kills, 'offline: player kills')
      assertEq(off.mobKills, lastOnline.mobKills, 'offline: mob kills from the cache')
      assert(off.playtimeTicks >= lastOnline.playtimeTicks, 'offline: playtime stays')
    }
    win = await expectWindow(bot1, () => bot1.chat(`/stats ${B2}`))
    assert(itemLore(win.slots[SLOT.head]).some((l) => /Offline/.test(l)), `offline GUI: head shows offline (${itemLore(win.slots[SLOT.head]).join(' | ')})`)
    assertEq(loreNumber(win.slots[SLOT.deaths]), lastOnline.deaths, 'offline GUI: deaths')
    assertEq(loreNumber(win.slots[SLOT.kills]), lastOnline.kills, 'offline GUI: player kills')
    await closeWindow(bot1)
    lines = await chatAfter(bot1, () => bot1.chat(`/playtime ${B2}`))
    assert(lines.some((l) => /Last online: .+ ago/.test(l)), `/playtime offline shows "last online" (${lines.join(' | ')})`)
  } finally {
    await closeWindow(bot1)
    await rcon(`kill @e[type=minecraft:chicken,tag=statsE2E]`)
    await rcon(`kill @e[type=minecraft:item,x=${X},y=${Y},z=${Z},distance=..12]`)
    await rcon(`kill @e[type=minecraft:experience_orb,x=${X},y=${Y},z=${Z},distance=..12]`)
    // keep the glass floor: the bots log out here and rejoin here on the next run
    await rcon(`fill ${X - 3} ${Y} ${Z - 3} ${X + 3} ${Y + 3} ${Z + 3} minecraft:air`)
    await rcon(`forceload remove ${X} ${Z}`)
    await rcon(`clear ${B1}`)
    await rcon(`effect clear ${B1}`)
  }
}, { timeoutMs: 240000 })
