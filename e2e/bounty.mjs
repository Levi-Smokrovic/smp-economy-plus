// Bounties: error cases by command, placing by command (opens the confirm dialog first; cancel and ESC book
// nothing) and GUI, announcement to an uninvolved observer, environmental death and suicide keep the bounty, a
// team kill does not pay, a PvP melee kill pays under the default rules despite the shared loopback IP of all
// bots, exactly once, admin refund, throttled notices to the target.
import {
  connectBot, rcon, runE2E, assert, assertEq, sleep, waitForMessage, expectWindow, windowTitle, clickSlot, findSlot,
  itemLabel, itemLore, stripColors, treasuryFlows,
} from './lib.mjs'

const B1 = 'bountyBot1' // places the bounty, kills later
const B2 = 'bountyBot2' // target
const B3 = 'bountyBot3' // uninvolved observer (announcements)
const X = 4300, Y = 200, Z = 4300
const LIST_SLOTS = 45 // entries in slots 0-44, navigation 45-53
const TEAM = 'bountyE2E'

async function info(name) {
  const out = await rcon(`smpeco bounty info ${name}`)
  const m = out.match(/total=(\d+) contributors=(\d+) balance=(\d+)/)
  if (!m) throw new Error(`smpeco bounty info ${name}: ${out}`)
  const pt = out.match(/playtime=(\d+)/)
  return { total: Number(m[1]), contributors: Number(m[2]), balance: Number(m[3]), playtime: pt ? Number(pt[1]) : -1, raw: out.trim() }
}

/**
 * Treasury income from bounty fees since opening, in cents. The 24 h lines lose the oldest hour at the hour
 * change, so they do not work for differences.
 */
async function feeIncome() {
  return (await treasuryFlows()).flows.bounty_fee?.income ?? 0
}

function expectChat(bot, regex, timeoutMs = 8000) {
  return waitForMessage(bot, regex, timeoutMs).then(() => true, () => false)
}

async function chatAfter(bot, regex, action, timeoutMs = 8000) {
  const p = expectChat(bot, regex, timeoutMs)
  await action()
  return p
}

function waitForRespawn(bot, timeoutMs = 20000) {
  return new Promise((resolve) => {
    const timer = setTimeout(() => { bot.off('spawn', onSpawn); resolve(false) }, timeoutMs)
    const onSpawn = () => { clearTimeout(timer); resolve(true) }
    bot.once('spawn', onSpawn)
  })
}

function waitForDeath(bot, timeoutMs) {
  return new Promise((resolve) => {
    const timer = setTimeout(() => { bot.off('death', onDeath); resolve(false) }, timeoutMs)
    const onDeath = () => { clearTimeout(timer); resolve(true) }
    bot.once('death', onDeath)
  })
}

// world spawn, to teleport back at the end so nobody falls off the remote platform on the next join
let home = null

function farFromPlatform(pos) {
  return pos && (Math.abs(pos.x - X) > 50 || Math.abs(pos.z - Z) > 50)
}

async function dieBy(bot, action) {
  const respawned = waitForRespawn(bot)
  const died = waitForDeath(bot, 10000)
  await action()
  const ok = await died
  const back = await respawned
  if (back && !home && farFromPlatform(bot.entity?.position)) home = bot.entity.position.clone()
  return ok && back
}

async function closeWindow(bot) {
  if (bot.currentWindow) bot.closeWindow(bot.currentWindow)
  await sleep(300)
}

async function toPlatform() {
  await rcon(`tp ${B1} ${X + 0.5} ${Y} ${Z + 0.5} -90 0`)
  await rcon(`tp ${B2} ${X + 2.0} ${Y} ${Z + 0.5} 90 0`)
  await rcon(`tp ${B3} ${X + 0.5} ${Y} ${Z + 3.5}`)
  await sleep(1500)
}

function entrySlot(win, name) {
  return findSlot(win, new RegExp(`#\\d+ ${name}$`), { to: LIST_SLOTS })
}

const CONFIRM_SLOT = 9 // Gui.confirm: green 9-11, preview 13, red 15-17
const CANCEL_SLOT = 15

/**
 * {@code /bounty <target> <amount>} opens the confirm dialog and books nothing yet; confirming books. true if
 * {@code okRegex} then shows in chat.
 */
async function placeByCommand(bot, target, amount, okRegex) {
  const dialog = await expectWindow(bot, () => bot.chat(`/bounty ${target} ${amount}`)).catch(() => null)
  if (!dialog || !/Confirm bounty/.test(windowTitle(dialog))) {
    console.log(`[E2E] INFO /bounty ${target} ${amount}: no confirm dialog (${dialog ? `"${windowTitle(dialog)}"` : 'no window'})`)
    if (bot.currentWindow) await closeWindow(bot)
    return false
  }
  await sleep(350) // click debounce (Gui.SAME_SLOT_DEBOUNCE_MS) between consecutive dialogs
  return chatAfter(bot, okRegex, () => clickSlot(bot, CONFIRM_SLOT))
}

runE2E(async () => {
  const bot1 = await connectBot(B1)
  const bot2 = await connectBot(B2)
  let bot3 = await connectBot(B3)
  assert(true, 'bots connected')
  if (farFromPlatform(bot3.entity?.position)) home = bot3.entity.position.clone()
  // remember payout rules (all bots share an IP) and restore them at the end
  const rules = await rcon('smpeco bounty rule')
  const rulesBefore = {
    team: !/team=false/.test(rules),
    sameip: !/sameip=false/.test(rules),
    playtime: Number(rules.match(/playtime=(\d+)/)?.[1] ?? 120),
  }
  const noticeSeconds = Number(rules.match(/notice=(\d+)/)?.[1] ?? 30)
  assert(/bounty rules team=\w+ sameip=\w+/.test(rules), `rules queryable (${rules.trim()})`)

  try {
    for (const n of [B1, B2, B3]) await rcon(`smpeco bounty remove ${n}`)
    await rcon(`forceload add ${X} ${Z}`)
    await sleep(1000)
    await rcon(`fill ${X - 2} ${Y - 1} ${Z - 2} ${X + 4} ${Y + 3} ${Z + 5} minecraft:air`)
    await rcon(`fill ${X - 2} ${Y - 1} ${Z - 2} ${X + 4} ${Y - 1} ${Z + 5} minecraft:glass`)
    // items left by other scripts (missions uses the same area) would get picked up by the bots
    await rcon(`kill @e[type=minecraft:item,x=${X},y=${Y},z=${Z},distance=..24]`)
    for (const n of [B1, B2, B3]) {
      await rcon(`gamemode survival ${n}`)
      await rcon(`clear ${n}`)
      await rcon(`effect clear ${n}`)
    }
    const eco1 = await rcon(`eco set ${B1} 10000`)
    const eco2 = await rcon(`eco set ${B2} 10000`)
    let i1 = await info(B1)
    let i2 = await info(B2)
    assert(i1.balance >= 200_000 && i2.balance >= 200_000,
      `starting balance present (${B1}=${i1.balance}, ${B2}=${i2.balance}; eco: "${eco1.trim().slice(0, 60)}" / "${eco2.trim().slice(0, 60)}")`)
    assertEq([i1.total, i2.total], [0, 0], 'no bounties at the start')
    await toPlatform()

    assert(await chatAfter(bot1, /can't place a bounty on yourself/, () => bot1.chat(`/bounty ${B1} 500`)), 'bounty on self refused')
    assert(await chatAfter(bot1, /minimum bounty/, () => bot1.chat(`/bounty ${B2} 1`)), 'below the minimum refused')
    assert(await chatAfter(bot1, /Not enough money/, () => bot1.chat(`/bounty ${B2} 1t`)), 'not enough money refused')
    assert(await chatAfter(bot1, /Invalid amount/, () => bot1.chat(`/bounty ${B2} abc`)), 'invalid amount refused')
    assert(await chatAfter(bot1, /Player not found/, () => bot1.chat('/bounty NobodyXyz42 500')), 'unknown player refused')
    assertEq((await info(B1)).balance, i1.balance, 'error cases book nothing')
    assert(!bot1.currentWindow, 'error cases arrive in chat at once, without a confirm dialog')

    // /bounty <player> <amount> does not book at once but opens the same dialog as the GUI (typo guard)
    let cmdDialog = await expectWindow(bot1, () => bot1.chat(`/bounty ${B2} 500`))
    assert(/Confirm bounty/.test(windowTitle(cmdDialog)), `/bounty <player> <amount> opens the confirm dialog ("${windowTitle(cmdDialog)}")`)
    const cmdPreview = itemLore(cmdDialog.slots[13])
    assert(cmdPreview.some((l) => /Your stake: \$500\.00/.test(l)) && cmdPreview.some((l) => /no refunds/.test(l)),
      `dialog shows stake and "no refund" (${cmdPreview.join(' | ')})`)
    assertEq([(await info(B1)).balance, (await info(B2)).total], [i1.balance, 0], 'the command alone books nothing')
    await sleep(350)
    assert(await chatAfter(bot1, /Cancelled – no bounty placed/, () => clickSlot(bot1, CANCEL_SLOT)), 'cancel in the dialog reports "Cancelled"')
    await sleep(300)
    assert(!bot1.currentWindow, 'cancel closes the dialog (no list with the command)')
    cmdDialog = await expectWindow(bot1, () => bot1.chat(`/bounty ${B2} 500`))
    assert(await chatAfter(bot1, /Cancelled – no bounty placed/, () => closeWindow(bot1)), 'ESC in the dialog also cancels')
    assertEq([(await info(B1)).balance, (await info(B2)).total], [i1.balance, 0], 'cancel and ESC book nothing')

    const targetMsg = expectChat(bot2, new RegExp(`A bounty was placed on you: \\$500\\.00 by ${B1}`))
    const observerMsg = expectChat(bot3, new RegExp(`${B1} put a \\$500\\.00 bounty on ${B2}`))
    const n1Before = bot1.messages.length
    assert(await placeByCommand(bot1, B2, 500, new RegExp(`Bounty on ${B2} placed: \\$500\\.00`)), 'bounty set by command after confirmation')
    await sleep(300)
    assert(!bot1.currentWindow, 'no list opens after confirming by command')
    assert(await targetMsg, 'target notified')
    assert(await observerMsg, 'observer sees the announcement')
    await sleep(500)
    assert(!bot1.messages.slice(n1Before).some((m) => /put a \$500\.00 bounty on/.test(m)), 'placer gets no double announcement')
    i2 = await info(B2)
    assertEq([i2.total, i2.contributors], [50_000, 1], 'bounty $500 with 1 supporter')
    assertEq((await info(B1)).balance, i1.balance - 50_000, 'placer charged $500')
    assert(await chatAfter(bot3, new RegExp(`Bounty on ${B2}: \\$500\\.00 \\(1 contributor\\)`), () => bot3.chat(`/bounty ${B2}`)), '/bounty <player> shows the bounty')
    const check1 = await rcon('smpeco bounty check')
    assert(/ok=true deficit=0 surplus=0 locked=false/.test(check1), `escrow = sum of bounties, not locked (${check1.trim()})`)

    i1 = await info(B1)
    let win = await expectWindow(bot1, () => bot1.chat('/bounty'))
    assert(/Bounties/.test(windowTitle(win)), `bounty GUI open ("${windowTitle(win)}")`)
    let slot = entrySlot(win, B2)
    assert(slot >= 0, `${B2} in the list (slot ${slot})`)
    if (slot < 0) throw new Error('entry missing, aborting')
    assertEq(win.slots[slot].name, 'player_head', 'entry is a player head')
    assert(itemLore(win.slots[slot]).some((l) => /Bounty: \$500\.00/.test(l)), 'lore shows $500.00')
    assert(itemLore(win.slots[slot]).some((l) => /Contributors: 1/.test(l)), 'lore shows 1 supporter')
    assert(/How it works/.test(itemLabel(win.slots[45])), 'info book bottom left')
    assert(/Your bounties/.test(itemLabel(win.slots[52])) && itemLore(win.slots[52]).some((l) => new RegExp(`${B2}: \\$500\\.00`).test(l)),
      'own contributions shown')

    let anvil = await expectWindow(bot1, () => clickSlot(bot1, slot))
    assert(/Amount for/.test(windowTitle(anvil)), `amount input open ("${windowTitle(anvil)}")`)
    win = await expectWindow(bot1, () => clickSlot(bot1, 1))
    assert(/Bounties/.test(windowTitle(win)), 'cancel in the anvil opens the list again')

    await sleep(350)
    anvil = await expectWindow(bot1, () => clickSlot(bot1, entrySlot(win, B2)))
    bot1._client.write('name_item', { name: '100' })
    await sleep(400)
    let dialog = await expectWindow(bot1, () => clickSlot(bot1, 2))
    assert(/Confirm bounty/.test(windowTitle(dialog)), 'confirm dialog open')
    win = await expectWindow(bot1, () => clickSlot(bot1, 15))
    assert(/Bounties/.test(windowTitle(win)), 'cancel in the dialog opens the list again')
    assertEq((await info(B2)).total, 50_000, 'cancel books nothing')

    await sleep(350)
    anvil = await expectWindow(bot1, () => clickSlot(bot1, entrySlot(win, B2)))
    bot1._client.write('name_item', { name: '250' })
    await sleep(400)
    dialog = await expectWindow(bot1, () => clickSlot(bot1, 2))
    const preview = itemLore(dialog.slots[13])
    assert(preview.some((l) => /Your stake: \$250\.00/.test(l)), `preview: stake $250 (${preview.join(' | ')})`)
    assert(preview.some((l) => /Bounty after: \$750\.00/.test(l)), 'preview: $750 afterwards')
    const raised = expectChat(bot1, new RegExp(`Bounty on ${B2} raised: \\$250\\.00`))
    win = await expectWindow(bot1, () => clickSlot(bot1, 9))
    assert(await raised, 'confirm raises the bounty')
    assert(/Bounties/.test(windowTitle(win)), 'list again after confirming')
    slot = entrySlot(win, B2)
    assert(slot >= 0 && itemLore(win.slots[slot]).some((l) => /Bounty: \$750\.00/.test(l)), 'list now shows $750.00')
    i2 = await info(B2)
    assertEq([i2.total, i2.contributors], [75_000, 1], 'bounty $750, still 1 supporter')
    assertEq((await info(B1)).balance, i1.balance - 25_000, 'GUI stake charged')
    await closeWindow(bot1)

    i2 = await info(B2)
    let win2 = await expectWindow(bot2, () => bot2.chat('/bounty'))
    assert(/Bounty on you/.test(itemLabel(win2.slots[47])) && itemLore(win2.slots[47]).some((l) => /\$750\.00/.test(l)),
      'own head shows the bounty on me')
    const self2 = entrySlot(win2, B2)
    assert(await chatAfter(bot2, /can't place a bounty on yourself/, () => clickSlot(bot2, self2)), 'click on own entry refused')
    await sleep(350)
    const nameAnvil = await expectWindow(bot2, () => clickSlot(bot2, 46))
    assert(/player name/.test(windowTitle(nameAnvil)), 'name input open')
    bot2._client.write('name_item', { name: B1 })
    await sleep(400)
    const amountAnvil = await expectWindow(bot2, () => clickSlot(bot2, 2))
    assert(new RegExp(`Amount for ${B1}`).test(windowTitle(amountAnvil)), `amount input for ${B1}`)
    bot2._client.write('name_item', { name: '300' })
    await sleep(400)
    dialog = await expectWindow(bot2, () => clickSlot(bot2, 2))
    const set2 = expectChat(bot2, new RegExp(`Bounty on ${B1} placed: \\$300\\.00`))
    const target1 = expectChat(bot1, new RegExp(`A bounty was placed on you: \\$300\\.00 by ${B2}`))
    win2 = await expectWindow(bot2, () => clickSlot(bot2, 9))
    assert(await set2, 'bounty set through "Set bounty"')
    assert(await target1, 'target bot 1 notified')
    await closeWindow(bot2)
    assertEq((await info(B1)).total, 30_000, 'bounty on bot 1 = $300')
    assertEq((await info(B2)).balance, i2.balance - 30_000, 'bot 2 charged')
    assertEq([...bot1.inventory.items(), ...bot2.inventory.items()].map((it) => it.name), [], 'no GUI items in the inventory')

    i1 = await info(B1)
    const fee0 = await feeIncome()
    assert(await dieBy(bot2, () => rcon(`kill ${B2}`)), 'bot 2 dies by /kill and respawns')
    await sleep(500)
    assertEq((await info(B2)).total, 75_000, 'death by /kill: bounty stays')
    await toPlatform()
    assert(await dieBy(bot2, () => rcon(`damage ${B2} 1000 minecraft:lava`)), 'bot 2 dies by lava damage')
    await sleep(500)
    assertEq((await info(B2)).total, 75_000, 'environmental death: bounty stays')
    await toPlatform()
    assert(await dieBy(bot2, () => rcon(`damage ${B2} 1000 minecraft:player_attack by ${B2}`)), 'bot 2 kills itself')
    await sleep(500)
    assertEq((await info(B2)).total, 75_000, 'suicide: bounty stays')

    // From here on the default rules (team=true, sameip=true). All bots come from 127.0.0.1, as all players
    // behind a tunnel share the tunnel agent's address. Internal addresses never count as "same IP", so the PvP
    // kill below must pay despite the IP rule.
    await rcon('smpeco bounty rule sameip true')
    await rcon('smpeco bounty rule team true')
    await rcon(`team add ${TEAM}`)
    await rcon(`team join ${TEAM} ${B1}`)
    await rcon(`team join ${TEAM} ${B2}`)
    await toPlatform()
    const blocked = expectChat(bot1, new RegExp(`No bounty for ${B2}: you're on the same team`))
    assert(await dieBy(bot2, () => rcon(`damage ${B2} 1000 minecraft:player_attack by ${B1}`)), 'bot2 dies in PvP (same team)')
    assert(await blocked, 'same team: shooter gets the hint')
    assertEq((await info(B2)).total, 75_000, 'same team: bounty stays')
    await rcon(`team remove ${TEAM}`)
    assertEq((await info(B1)).balance, i1.balance, 'nobody paid out')
    assertEq(await feeIncome(), fee0, 'no fee booked')

    // Minimum playtime of the shooter (alt account guard). Behind a tunnel the IP rule sees nothing, so fresh
    // accounts must not collect.
    const pt1 = (await info(B1)).playtime
    assert(pt1 >= 0, `playtime of bot 1 readable (${pt1} ticks)`)
    const minMinutes = Math.floor(Math.max(0, pt1) / 1200) + 60
    assert(new RegExp(`playtime=${minMinutes} `).test(await rcon(`smpeco bounty rule playtime ${minMinutes}`)), `rule: at least ${minMinutes} min of playtime`)
    await toPlatform()
    const tooNew = expectChat(bot1, new RegExp(`No bounty for ${B2}: your account only has .+ of playtime`))
    assert(await dieBy(bot2, () => rcon(`damage ${B2} 1000 minecraft:player_attack by ${B1}`)), 'bot 2 dies in PvP (shooter with too little playtime)')
    assert(await tooNew, 'too little playtime: shooter gets the hint')
    assertEq((await info(B2)).total, 75_000, 'too little playtime: bounty stays')
    assertEq((await info(B1)).balance, i1.balance, 'too little playtime: no payout')
    assertEq(await feeIncome(), fee0, 'too little playtime: no fee')
    await rcon('smpeco bounty rule playtime 0')

    assert(/team=true sameip=true playtime=0 /.test(await rcon('smpeco bounty rule')), 'team and IP rule active (team=true sameip=true), playtime rule off for the bots')
    const n1Kill = bot1.messages.length
    await toPlatform()
    await rcon(`give ${B1} minecraft:netherite_sword 1`)
    await sleep(600)
    const sword = bot1.inventory.items().find((it) => it.name === 'netherite_sword')
    if (sword) await bot1.equip(sword, 'hand')
    await sleep(1200) // attack cooldown after the swap
    const paid = expectChat(bot1, new RegExp(`Bounty claimed: \\$712\\.50 for ${B2}`), 15000)
    const victimMsg = expectChat(bot2, new RegExp(`${B1} claimed the \\$750\\.00 bounty on you`), 15000)
    const announce = expectChat(bot3, new RegExp(`${B1} took out ${B2} and claimed a \\$750\\.00 bounty`), 15000)
    const respawned = waitForRespawn(bot2, 30000)
    let dead = false
    const died = waitForDeath(bot2, 15000).then((v) => { dead = v; return v })
    await rcon(`damage ${B2} 14 minecraft:generic`)
    await sleep(600)
    for (let i = 0; i < 8 && !dead; i++) {
      const target = bot1.players[B2]?.entity
      if (target) {
        await bot1.lookAt(target.position.offset(0, 1.5, 0), true)
        bot1.attack(target)
      }
      await sleep(800)
    }
    if (!dead) {
      console.log('[E2E] INFO melee kill failed (pvp gamerule or reach?), falling back to /damage … by')
      await rcon(`damage ${B2} 1000 minecraft:player_attack by ${B1}`)
    }
    assert(await died, `bot2 dies in PvP (${dead ? 'melee' : 'fallback'})`)
    assert(await paid, 'shooter gets $712.50 (= $750 - 5 %) although both bots share a loopback IP')
    assert(!bot1.messages.slice(n1Kill).some((m) => /same IP address/.test(m)), 'same loopback or tunnel IP does not block')
    assert(await victimMsg, 'victim notified')
    assert(await announce, 'observer sees the payout')
    await respawned
    await sleep(500)
    i2 = await info(B2)
    assertEq([i2.total, i2.contributors], [0, 0], 'bounty deleted after payout')
    assertEq((await info(B1)).balance, i1.balance + 71_250, 'Bot1 +$712.50')
    assertEq(await feeIncome(), fee0 + 3_750, 'treasury +$37.50 (bounty_fee)')
    const check2 = await rcon('smpeco bounty check')
    assert(/ok=true/.test(check2), `escrow consistent after payout (${check2.trim()})`)
    const win3 = await expectWindow(bot3, () => bot3.chat('/bounty'))
    assert(itemLore(win3.slots[51]).some((l) => new RegExp(`${B1} → ${B2} \\$750\\.00`).test(l)), 'GUI "Recent hunts" shows the payout')
    assert(entrySlot(win3, B2) < 0, `${B2} no longer in the list`)
    const e1 = entrySlot(win3, B1)
    assert(e1 >= 0 && itemLore(win3.slots[e1]).some((l) => new RegExp(`Top contributor: ${B2}`).test(l)), 'list shows the largest supporter')
    await closeWindow(bot3)

    await toPlatform()
    i1 = await info(B1)
    const again = expectChat(bot1, /Bounty claimed/, 4000)
    assert(await dieBy(bot2, () => rcon(`damage ${B2} 1000 minecraft:player_attack by ${B1}`)), 'bot2 dies again in PvP')
    assert(!(await again), 'no second bounty')
    assertEq((await info(B1)).balance, i1.balance, 'no money on the second kill')

    i2 = await info(B2)
    const refundMsg = expectChat(bot2, new RegExp(`bounty on ${B1} was removed by an admin\\. Refunded: \\$300\\.00`))
    const removed = await rcon(`smpeco bounty remove ${B1}`)
    assert(/refunded=30000 contributors=1 remaining=0/.test(removed), `admin removes bounty on bot 1 (${removed.trim()})`)
    assert(await refundMsg, 'supporter told about the refund')
    assertEq((await info(B2)).balance, i2.balance + 30_000, 'bot 2 gets $300 back')
    assertEq((await info(B1)).total, 0, 'no bounty left on bot 1')
    // Throttled target notices. Offline: 5 deposits give exactly one summary on join, not 5 pending messages
    // that crowd out others.
    bot3.quit()
    await sleep(2000)
    for (let i = 0; i < 5; i++) {
      assert(await placeByCommand(bot1, B3, 100, new RegExp(`Bounty on ${B3} (placed|raised): \\$100\\.00`)),
        `deposit ${i + 1}/5 on ${B3} (offline)`)
    }
    assertEq((await info(B3)).total, 50_000, 'bounty on bot 3 = $500')
    bot3 = await connectBot(B3)
    const offlineSummary = await waitForMessage(bot3, /5 bounties were placed on you: \$500\.00 \(total \$500\.00\)/, 10000,
      { includePast: true }).then(() => true, () => false)
    assert(offlineSummary, 'offline target gets a summary on join')
    await sleep(1000)
    const joinLines = bot3.messages.filter((m) => /(bounty was|bounties were) placed on you/.test(stripColors(m)))
    assertEq(joinLines.length, 1, 'exactly one bounty message on join')

    // online: 3 fast deposits give one at once and the other 2 as one summary after the lock time
    const n3 = bot3.messages.length
    for (let i = 0; i < 3; i++) {
      assert(await placeByCommand(bot1, B3, 100, new RegExp(`Bounty on ${B3} raised: \\$100\\.00`)),
        `deposit ${i + 1}/3 on ${B3} (online)`)
    }
    await sleep(1500)
    const now3 = bot3.messages.slice(n3).map(stripColors).filter((m) => /(bounty was|bounties were) placed on you/.test(m))
    assertEq(now3.length, 1, 'online: only one message at once')
    assert(new RegExp(`A bounty was placed on you: \\$100\\.00 by ${B1} \\(total \\$600\\.00\\)`).test(now3[0] ?? ''),
      `first deposit at once with name (${now3[0] ?? '-'})`)
    const burst = await waitForMessage(bot3, /2 more bounties were placed on you: \$200\.00 \(total \$800\.00\)/,
      noticeSeconds * 1000 + 10000, { includePast: true }).then(() => true, () => false)
    assert(burst, `online: summary after ${noticeSeconds} s`)
    assertEq(bot3.messages.slice(n3).filter((m) => /(bounty was|bounties were) placed on you/.test(stripColors(m))).length, 2, 'online: 2 instead of 3 messages in total')
    const removed3 = await rcon(`smpeco bounty remove ${B3}`)
    assert(/refunded=80000 contributors=1 remaining=0/.test(removed3), `admin refunds bounty on bot 3 (${removed3.trim()})`)

    const check3 = await rcon('smpeco bounty check')
    assert(/ok=true/.test(check3), `escrow consistent at the end (${check3.trim()})`)
    if (/active=0 /.test(check3)) {
      const empty = await expectWindow(bot3, () => bot3.chat('/bounty'))
      assert(/No bounties/.test(itemLabel(empty.slots[22])), 'empty list shows "No bounties"')
      await closeWindow(bot3)
    }
  } finally {
    for (const b of [bot1, bot2, bot3]) { try { if (b.currentWindow) b.closeWindow(b.currentWindow) } catch {} }
    await rcon(`team remove ${TEAM}`)
    await rcon(`smpeco bounty rule team ${rulesBefore.team}`)
    await rcon(`smpeco bounty rule sameip ${rulesBefore.sameip}`)
    await rcon(`smpeco bounty rule playtime ${rulesBefore.playtime}`)
    for (const n of [B1, B2, B3]) {
      await rcon(`smpeco bounty remove ${n}`)
      await rcon(`clear ${n}`)
      if (home) await rcon(`tp ${n} ${home.x.toFixed(2)} ${home.y.toFixed(2)} ${home.z.toFixed(2)}`)
    }
    if (home) {
      // remove the platform only once all bots are back at spawn, or they fall on the next join
      await sleep(500)
      await rcon(`fill ${X - 2} ${Y - 1} ${Z - 2} ${X + 4} ${Y + 3} ${Z + 5} minecraft:air`)
    } else {
      console.log('[E2E] INFO world spawn unknown, glass platform stays so no bot falls')
    }
    await rcon(`forceload remove ${X} ${Z}`)
  }
}, { timeoutMs: 420000 })
