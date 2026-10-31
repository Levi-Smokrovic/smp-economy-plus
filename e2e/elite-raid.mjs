// Daily elite "raid" with real vanilla raids (Raid#spawnGroup, Raider#die, Raid#tick). A raid started at level
// 1 and raised to 5 by Bad Omen mid-raid does not count, since the level at the first wave's spawn decides,
// even though vanilla grants Hero of the Village V. A raid started at level 5: bot 2 kills a single raider (a
// vanilla hero) and leaves, which does not count; bot 1 kills the rest in the village and completes it exactly
// once. The village is a glass box with a bed and an invulnerable villager at Y=200, difficulty easy (3 waves
// plus bonus). Bots "kill" with /damage ... by <bot>, which has a player as damage source like melee. Raid Omen
// (30 s in vanilla) is cut to 1 s over RCON; the position stays remembered.
import { connectBot, rcon, moduleEnabled, runE2E, assert, assertEq, sleep, waitForMessage } from './lib.mjs'

const B1 = 'raidBot1'
const B2 = 'raidBot2'
const Y = 200
const SITE_A = { x: 4700, z: 4700 } // level 1 stacked to 5
const SITE_B = { x: 5100, z: 5100 } // level 5 from the start; separate place since the win in A celebrates there for another 30 s
const AWAY = { x: 5100, z: 5800 } // more than 96 blocks from village B, same dimension

function kv(line) {
  const out = {}
  for (const m of line.matchAll(/(\w+)=(\S*)/g)) out[m[1]] = m[2]
  return out
}

async function waitFor(fn, timeoutMs, label, everyMs = 250) {
  const end = Date.now() + timeoutMs
  while (Date.now() < end) {
    const v = await fn()
    if (v) return v
    await sleep(everyMs)
  }
  throw new Error(`Timeout (${timeoutMs} ms): ${label}`)
}

async function eliteStatus(name) {
  const out = await rcon(`smpeco missions elite status ${name}`)
  const line = out.split('\n').find((l) => l.startsWith('missions elite status'))
  if (!line) throw new Error(`elite status ${name}: ${out}`)
  const s = kv(line)
  return { id: s.id, done: s.done === 'true', paid: Number(s.paid), payout: Number(s.payout), epic: Number(s.keys.split('/')[2]), raw: line }
}

async function raids() {
  const out = await rcon('smpeco missions elite raids')
  return out.split('\n').map((l) => l.trim()).filter((l) => l.startsWith('raid ')).map((l) => {
    const r = kv(l)
    const center = (r.center ?? '').split(',').map(Number)
    const kills = {}
    if (r.kills && r.kills !== '-') for (const p of r.kills.split(',')) { const [n, c] = p.split(':'); kills[n] = Number(c) }
    return {
      key: r.key, start: Number(r.start), total: Number(r.total), finished: r.finished === 'true', level: Number(r.level),
      groups: Number(r.groups), alive: Number(r.alive), status: r.status, center, kills, raw: l,
    }
  })
}

async function raidAt(site) {
  return (await raids()).find((r) => r.center.length === 3 && Math.abs(r.center[0] - site.x) < 40 && Math.abs(r.center[2] - site.z) < 40)
}

async function effects(name) {
  return rcon(`data get entity ${name} active_effects`)
}

/** Amplifier of Hero of the Village (raid level at the win - 1), or null. */
async function heroAmplifier(name) {
  const out = await effects(name)
  const m = out.match(/id: "minecraft:hero_of_the_village"[^}]*?amplifier: (\d+)b/) ?? out.match(/amplifier: (\d+)b[^}]*?id: "minecraft:hero_of_the_village"/)
  if (m) return Number(m[1])
  return /hero_of_the_village/.test(out) ? 0 : null
}

async function buildVillage(site, tag) {
  const { x, z } = site
  await rcon(`forceload add ${x - 32} ${z - 32} ${x + 32} ${z + 32}`)
  await sleep(1500)
  await rcon(`fill ${x - 24} ${Y} ${z - 24} ${x + 24} ${Y + 8} ${z + 24} minecraft:air`)
  await rcon(`fill ${x - 24} ${Y - 1} ${z - 24} ${x + 24} ${Y - 1} ${z + 24} minecraft:stone`)
  await rcon(`fill ${x + 4} ${Y - 1} ${z - 2} ${x + 8} ${Y + 3} ${z + 2} minecraft:glass hollow`)
  await rcon(`setblock ${x + 6} ${Y} ${z + 1} minecraft:red_bed[facing=south,part=head]`)
  await rcon(`setblock ${x + 6} ${Y} ${z} minecraft:red_bed[facing=south,part=foot]`)
  await rcon(`summon minecraft:villager ${x + 5.5} ${Y} ${z - 0.5} {Invulnerable:1b,PersistenceRequired:1b,Tags:["${tag}"]}`)
  await waitFor(async () => /minecraft:home/.test(await rcon(`data get entity @e[type=minecraft:villager,tag=${tag},limit=1] Brain.memories`)),
    60000, `villager occupies the bed (${tag})`, 1000)
}

async function clearVillage(site, tag) {
  const { x, z } = site
  await rcon(`kill @e[type=minecraft:villager,tag=${tag}]`).catch(() => {})
  await rcon(`kill @e[type=#minecraft:raiders,x=${x},y=${Y},z=${z},distance=..200]`).catch(() => {})
  await rcon(`kill @e[type=minecraft:item,x=${x},y=${Y},z=${z},distance=..200]`).catch(() => {})
  await rcon(`kill @e[type=minecraft:experience_orb,x=${x},y=${Y},z=${z},distance=..200]`).catch(() => {})
  await rcon(`fill ${x - 24} ${Y - 1} ${z - 24} ${x + 24} ${Y + 8} ${z + 24} minecraft:air`).catch(() => {})
  await rcon(`forceload remove ${x - 32} ${z - 32} ${x + 32} ${z + 32}`).catch(() => {})
}

async function stand(name, site) {
  await rcon(`tp ${name} ${site.x + 0.5} ${Y} ${site.z + 0.5}`)
}

async function omen(name, amplifier) {
  await rcon(`effect give ${name} minecraft:bad_omen 60 ${amplifier}`)
  await waitFor(async () => /minecraft:raid_omen/.test(await effects(name)), 8000, `${name}: Bad Omen becomes Raid Omen`)
  await rcon(`effect clear ${name} minecraft:raid_omen`)
  await rcon(`effect give ${name} minecraft:raid_omen 1 ${amplifier}`)
}

function raiderSelector(site) {
  return `@e[type=#minecraft:raiders,x=${site.x},y=${Y},z=${site.z},distance=..160]`
}

async function fightUntilVictory(site, killer, { timeoutMs = 240000 } = {}) {
  const end = Date.now() + timeoutMs
  let last = null
  while (Date.now() < end) {
    await stand(killer, site)
    await rcon(`execute as ${raiderSelector(site)} run damage @s 10000 minecraft:player_attack by ${killer}`)
    last = await raidAt(site)
    if (last && (last.status === 'victory' || last.finished)) return last
    if (last && (last.status === 'loss' || last.status === 'stopped')) throw new Error(`raid not won: ${last.raw}`)
    await sleep(500)
  }
  throw new Error(`raid not won in time: ${last?.raw ?? '-'}`)
}

runE2E(async () => {
  const bot1 = await connectBot(B1)
  const bot2 = await connectBot(B2)
  const difficulty = (await rcon('difficulty')).match(/(peaceful|easy|normal|hard)/i)?.[1]?.toLowerCase() ?? 'normal'
  for (const n of [B1, B2]) {
    await rcon(`gamemode survival ${n}`)
    await rcon(`smpeco missions elite reset ${n}`)
    await rcon(`attribute ${n} minecraft:knockback_resistance base set 1`)
  }
  try {
    await rcon('difficulty easy')
    assert(/set ok id=raid/.test(await rcon('smpeco missions elite set raid')), 'admin: daily elite = raid')
    const info = (await rcon('smpeco missions elite')).split('\n').find((l) => l.startsWith('missions elite')) ?? ''
    assert(/type=raid/.test(info), `daily elite is a raid (${info.slice(0, 120)})`)

    await buildVillage(SITE_A, 'e2e_raid_a')
    await buildVillage(SITE_B, 'e2e_raid_b')

    await stand(B1, SITE_A)
    await stand(B2, SITE_B)
    await sleep(1000)
    const s0 = await eliteStatus(B1)
    await omen(B1, 0)
    const a1 = await waitFor(() => raidAt(SITE_A), 60000, 'raid A: first wave')
    assertEq([a1.start, a1.level], [1, 1], `raid A starts at level 1 (${a1.raw})`)
    await rcon(`execute as ${raiderSelector(SITE_A)} run damage @s 10000 minecraft:player_attack by ${B1}`)
    // +4 gives level 5 mid-raid; a second attempt in case vanilla does not take the omen in this tick
    let a2 = null
    for (let attempt = 0; attempt < 2 && !a2; attempt++) {
      await omen(B1, 3)
      a2 = await waitFor(async () => { const r = await raidAt(SITE_A); return r && r.level === 5 ? r : null }, 12000, 'raid A stacked')
        .catch(async (err) => { console.log(`[E2E] INFO ${err.message}: ${await rcon('smpeco missions elite raids')} ${await effects(B1)}`); return null })
    }
    assert(!!a2, 'raid A stacked to level 5 mid-raid')
    assertEq([a2.start, a2.level], [1, 5], `vanilla: level 5 mid-raid, counted level stays 1 (${a2.raw})`)
    const stacked = waitForMessage(bot1, /This raid started at level 1/, 240000).then(() => true, () => false)
    const aWon = await fightUntilVictory(SITE_A, B1)
    assert(await stacked, 'message: "This raid started at level 1"')
    assert(aWon.kills[B1] >= 5 && aWon.kills[B1] === aWon.total, `all kills of ${B1} counted (${aWon.raw})`)
    assertEq(await heroAmplifier(B1), 4, 'vanilla: Hero of the Village V (raid at level 5 on the win)')
    const s1 = await eliteStatus(B1)
    assertEq([s1.done, s1.paid, s1.epic], [false, 0, s0.epic], 'stacked raid: not completed, nothing paid')
    await rcon(`effect clear ${B1} minecraft:hero_of_the_village`)

    await stand(B1, SITE_B)
    await stand(B2, SITE_B)
    await sleep(1000)
    const b2s0 = await eliteStatus(B2)
    await omen(B1, 4)
    const b1 = await waitFor(() => raidAt(SITE_B), 60000, 'raid B: first wave')
    assertEq([b1.start, b1.level], [5, 5], `raid B starts at level 5 (${b1.raw})`)
    await waitFor(async () => {
      await rcon(`damage @e[type=#minecraft:raiders,x=${SITE_B.x},y=${Y},z=${SITE_B.z},distance=..160,limit=1,sort=nearest] 10000 minecraft:player_attack by ${B2}`)
      return ((await raidAt(SITE_B))?.kills[B2] ?? 0) >= 1
    }, 15000, `${B2} kills one raider`)
    const b2 = await raidAt(SITE_B)
    assertEq(b2.kills[B2], 1, `${B2}: exactly one kill (${b2.raw})`)
    await rcon(`tp ${B2} ${AWAY.x + 0.5} ${Y + 40} ${AWAY.z + 0.5}`)
    const won1 = waitForMessage(bot1, /Daily Elite complete/, 240000).then(() => true, () => false)
    const notEnough = waitForMessage(bot2, /you have 1, you need/, 240000).then(() => true, () => false)
    const bWon = await fightUntilVictory(SITE_B, B1)
    assert(await won1, `${B1}: "Daily Elite complete"`)
    assert(await notEnough, `${B2}: "you have 1, you need …"`)
    assert(bWon.kills[B1] >= 5, `${B1}: ${bWon.kills[B1]} Kills (${bWon.raw})`)
    assert((await heroAmplifier(B2)) !== null, `vanilla: ${B2} is still Hero of the Village (one kill is enough there)`)
    const s2 = await eliteStatus(B1)
    assertEq([s2.done, s2.paid > 0, s2.epic], [true, true, s0.epic + (await moduleEnabled('crates') ? 1 : 0)], `${B1}: completed, paid, epic key`)
    const t2 = await eliteStatus(B2)
    assertEq([t2.done, t2.paid, t2.epic], [false, 0, b2s0.epic], `${B2}: not completed, nothing paid`)
    const bAfter = (await raids()).find((r) => r.key === bWon.key)
    assert(!bAfter || bAfter.finished, `raid B marked finished (cleared 1 min later): ${bAfter?.raw ?? 'already gone'}`)
  } finally {
    await clearVillage(SITE_A, 'e2e_raid_a')
    await clearVillage(SITE_B, 'e2e_raid_b')
    await rcon(`difficulty ${difficulty}`).catch(() => {})
    for (const n of [B1, B2]) {
      await rcon(`effect clear ${n} minecraft:hero_of_the_village`).catch(() => {})
      await rcon(`effect clear ${n} minecraft:bad_omen`).catch(() => {})
      await rcon(`effect clear ${n} minecraft:raid_omen`).catch(() => {})
      await rcon(`attribute ${n} minecraft:knockback_resistance base set 0`).catch(() => {})
      await rcon(`smpeco missions elite reset ${n}`).catch(() => {})
    }
    await rcon('smpeco missions elite clear').catch(() => {})
    bot1.quit()
    bot2.quit()
  }
}, { timeoutMs: 600000 })
