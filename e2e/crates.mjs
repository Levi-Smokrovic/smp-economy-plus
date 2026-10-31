// Crates through the GUI like a real player: no key, preview, keys from an admin, spin animation (about 3 s),
// prize stored before the animation and delivered exactly once on animation end, ESC or disconnect, pending
// prizes with a full inventory, announcements, permissions, double click, no GUI items.
import {
  connectBot, rcon, runE2E, assert, assertEq, sleep, waitForMessage, expectWindow, clickSlot, findSlot,
  itemLabel, itemLore, windowTitle,
} from './lib.mjs'

const A = 'cratesBot1'
const B = 'cratesBot2'
const TIERS = ['common', 'rare', 'epic']

async function info(name) {
  const out = await rcon(`crates info ${name}`)
  const m = out.match(/common=(\d+) rare=(\d+) epic=(\d+) pending=(\d+) money=(\d+)/)
  if (!m) throw new Error(`crates info ${name}: ${out}`)
  return { common: +m[1], rare: +m[2], epic: +m[3], pending: +m[4], money: +m[5] }
}

/** With a chest GUI open, read its player section: mineflayer does not update bot.inventory then. */
function invItems(bot) {
  return bot.currentWindow ? bot.currentWindow.items() : bot.inventory.items()
}

function count(bot, name) {
  return invItems(bot).filter((it) => it.name === name).reduce((sum, it) => sum + it.count, 0)
}

async function until(cond, timeoutMs = 5000, stepMs = 100) {
  const end = Date.now() + timeoutMs
  while (Date.now() < end) {
    if (await cond()) return true
    await sleep(stepMs)
  }
  return !!(await cond())
}

function reelLabels(win) {
  const labels = []
  for (let i = 9; i <= 17; i++) labels.push(win?.slots[i] ? itemLabel(win.slots[i]) : '')
  return labels.join('|')
}

function guiLeaks(bot) {
  return invItems(bot).map((it) => it.name)
    .filter((n) => n.endsWith('_stained_glass_pane') || ['hopper', 'barrier', 'book', 'chest_minecart', 'arrow', 'paper'].includes(n))
}

/**
 * The server runs chat commands through its own chain, and the close packet carries no checked window ID. A
 * "/crates" sent right after could otherwise be closed by the late close packet (bot timing only, no human is
 * that fast).
 */
async function closeGui(bot, win) {
  if (win ?? bot.currentWindow) bot.closeWindow(win ?? bot.currentWindow)
  await sleep(400)
}

async function openMenu(bot, cmd = '/crates') {
  const win = await expectWindow(bot, () => bot.chat(cmd))
  assert(/Crates/.test(windowTitle(win)), `${cmd} opens the overview ("${windowTitle(win)}")`)
  return win
}

async function protect(name) {
  await rcon(`effect give ${name} minecraft:resistance infinite 4 true`)
  await rcon(`effect give ${name} minecraft:saturation infinite 0 true`)
}

async function resetPlayer(name) {
  for (const t of TIERS) await rcon(`crates set ${name} ${t} 0`)
  await rcon(`clear ${name}`)
}

async function startSpin(bot, crateRegex = /Common Crate/) {
  const menu = await openMenu(bot)
  const slot = findSlot(menu, crateRegex)
  if (slot < 0) throw new Error(`crate ${crateRegex} not in the menu`)
  const spin = await expectWindow(bot, () => clickSlot(bot, slot))
  return spin
}

runE2E(async () => {
  let bot = await connectBot(A)
  assert(true, `${A} connected`)
  await protect(A)
  await resetPlayer(A)
  // collect leftovers of earlier runs (inventory is empty)
  if ((await info(A)).pending > 0) {
    bot.chat('/crates claim')
    await sleep(1000)
    await rcon(`clear ${A}`)
  }

  const st = await rcon('smpeco selftest crates')
  assert(/SUMMARY passed=\d+ failed=0/.test(st), `crates selftests green (${st.trim().slice(-80)})`)

  let menu = await openMenu(bot, '/crates')
  const common = findSlot(menu, /Common Crate/)
  assert(common >= 0, 'Common Crate in the menu')
  assert(findSlot(menu, /Rare Crate/) >= 0 && findSlot(menu, /Epic Crate/) >= 0, 'Rare and Epic Crate in the menu')
  assert(itemLore(menu.slots[common]).some((l) => /Keys: 0/.test(l)), 'lore shows 0 keys')
  const noKey = waitForMessage(bot, /don't have a key/, 4000).then(() => true, () => false)
  await clickSlot(bot, common)
  assert(await noKey, 'opening without a key gives an error')
  assert(/Crates/.test(windowTitle(bot.currentWindow)), 'overview stays open')
  let s = await info(A)
  assertEq([s.common, s.pending], [0, 0], 'without a key nothing used, no prize')

  const epicSlot = findSlot(menu, /Epic Crate/)
  const preview = await expectWindow(bot, () => clickSlot(bot, epicSlot, 1))
  assert(/Preview/.test(windowTitle(preview)), `right click opens the preview ("${windowTitle(preview)}")`)
  assert(findSlot(preview, 'enchanted_book', { to: 45 }) >= 0, 'preview shows enchanted books')
  assert(itemLore(preview.slots[0]).some((l) => /Chance: [\d.]+%/.test(l)), 'preview shows chances')
  menu = await expectWindow(bot, () => clickSlot(bot, 45))
  assert(/Crates/.test(windowTitle(menu)), 'back to the overview')
  await closeGui(bot, menu)

  const gave = waitForMessage(bot, /You got 2× Common Crate Key/, 5000).then(() => true, () => false)
  const giveOut = await rcon(`crates give ${A} common 2`)
  assert(/Gave/.test(giveOut), `crates give (${giveOut.trim()})`)
  assert(await gave, 'player is told about new keys')
  await rcon(`crates force ${A} common diamonds`)
  const dia0 = count(bot, 'diamond')
  menu = await openMenu(bot)
  assert(itemLore(menu.slots[common]).some((l) => /Keys: 2/.test(l)), 'lore shows 2 keys')
  const won = waitForMessage(bot, /You won: 2× .*iamond.*Common Crate/i, 9000).then(() => Date.now(), () => 0)
  const t0 = Date.now()
  let spin = await expectWindow(bot, () => clickSlot(bot, common))
  assert(/opening/.test(windowTitle(spin)), `spin animation opened ("${windowTitle(spin)}")`)
  s = await info(A)
  assertEq([s.common, s.pending], [1, 1], 'key used and prize stored before the animation ends')
  const before = reelLabels(bot.currentWindow)
  await sleep(450)
  assert(reelLabels(bot.currentWindow) !== before, 'reel spins')
  // clicks during the animation (shift, number key) do nothing
  await clickSlot(bot, 13, 0, 1)
  await clickSlot(bot, 4, 0, 2)
  const t1 = await won
  assert(t1 > 0, 'prize message after the animation')
  assert(t1 - t0 >= 2000 && t1 - t0 <= 6000, `animation takes about 3 s (${t1 - t0} ms)`)
  assert(await until(() => count(bot, 'diamond') === dia0 + 2, 3000), 'diamonds in the inventory')
  assertEq(bot.currentWindow?.slots[13]?.name, 'diamond', 'prize in the middle')
  assert(/Open Again/.test(itemLabel(bot.currentWindow?.slots[26])), 'button "Open Again" after landing')
  s = await info(A)
  assertEq(s.pending, 0, 'nothing pending any more')

  await rcon(`crates force ${A} common money_250`)
  const money0 = (await info(A)).money
  spin = await expectWindow(bot, () => clickSlot(bot, 26))
  assert(/opening/.test(windowTitle(spin)), '"Open Again" starts the next animation')
  await sleep(300)
  const skipped = waitForMessage(bot, /You won: \$250\.00/, 3000).then(() => Date.now(), () => 0)
  const tClose = Date.now()
  bot.closeWindow(spin)
  const tSkip = await skipped
  assert(tSkip > 0 && tSkip - tClose < 2000, 'ESC delivers the prize at once')
  s = await info(A)
  assertEq(s.money - money0, 25000, '$250 credited exactly once')
  assertEq([s.common, s.pending], [0, 0], 'both keys used, nothing pending')
  const dupMsgs = bot.messages.length
  await sleep(3500)
  assertEq((await info(A)).money - money0, 25000, 'not a second time after the animation time')
  assert(!bot.messages.slice(dupMsgs).some((m) => /You won: .*Common Crate/.test(m)), 'no second prize message')

  await rcon(`crates give ${A} common 2`)
  await rcon(`crates force ${A} common money_100`)
  menu = await openMenu(bot)
  const dbl = expectWindow(bot, async () => {
    bot.clickWindow(common, 0, 0).catch(() => {})
    bot.clickWindow(common, 0, 0).catch(() => {})
  })
  spin = await dbl
  await sleep(600)
  s = await info(A)
  assertEq([s.common, s.pending], [1, 1], 'double click: exactly one crate opened')
  await closeGui(bot, bot.currentWindow ?? spin)
  assertEq((await info(A)).pending, 0, 'delivered after ESC')
  await rcon(`crates set ${A} common 0`)

  await rcon(`crates give ${A} common 1`)
  await rcon(`crates force ${A} common diamonds`)
  const dia1 = count(bot, 'diamond')
  await startSpin(bot)
  await sleep(500)
  bot.quit()
  await sleep(2000)
  s = await info(A)
  assertEq([s.common, s.pending], [0, 0], 'disconnect: prize delivered on leaving')
  bot = await connectBot(A)
  await protect(A)
  assert(await until(() => count(bot, 'diamond') === dia1 + 2, 5000), `exactly ${dia1 + 2} diamonds after rejoin (was ${count(bot, 'diamond')})`)
  await sleep(2500) // wait for the join delivery (2 s): it must not pay twice
  assertEq(count(bot, 'diamond'), dia1 + 2, 'not a second time on join')

  await rcon(`clear ${A}`)
  await rcon(`give ${A} minecraft:stone 2304`)
  await sleep(500)
  await rcon(`crates give ${A} common 1`)
  await rcon(`crates force ${A} common diamonds`)
  await startSpin(bot)
  await sleep(500)
  bot.quit()
  await sleep(2000)
  s = await info(A)
  assertEq([s.common, s.pending], [0, 1], 'full inventory on disconnect: prize stays pending')
  bot = await connectBot(A)
  await protect(A)
  const waiting = await waitForMessage(bot, /crate reward is still waiting/, 8000, { includePast: true }).then(() => true, () => false)
  assert(waiting, 'hint about the pending prize on join')
  assertEq((await info(A)).pending, 1, 'still pending (inventory full)')
  assertEq(count(bot, 'diamond'), 0, 'no diamonds with a full inventory')
  await rcon(`clear ${A} minecraft:stone`)
  await sleep(500)
  menu = await openMenu(bot)
  const claimSlot = findSlot(menu, /Claim Rewards/)
  assert(claimSlot >= 0, 'button "Claim Rewards" visible')
  const claimed = waitForMessage(bot, /Crate reward received/, 5000).then(() => true, () => false)
  menu = await expectWindow(bot, () => clickSlot(bot, claimSlot))
  assert(await claimed, 'claim reports the prize')
  assert(await until(() => count(bot, 'diamond') === 2, 3000), '2 diamonds claimed')
  assertEq((await info(A)).pending, 0, 'nothing pending any more')
  assertEq(findSlot(menu, /Claim Rewards/), -1, 'claim button disappears')
  await closeGui(bot, menu)
  const none = waitForMessage(bot, /no crate rewards waiting/, 4000).then(() => true, () => false)
  bot.chat('/crates claim')
  assert(await none, 'second claim: nothing left')
  assertEq(count(bot, 'diamond'), 2, 'not claimed twice')

  const botB = await connectBot(B)
  await protect(B)
  await resetPlayer(B)
  await rcon(`crates give ${A} epic 1`)
  await rcon(`crates force ${A} epic elytra`)
  const announced = waitForMessage(botB, new RegExp(`${A} won .*elytra.*Epic Crate`, 'i'), 9000).then(() => true, () => false)
  await startSpin(bot, /Epic Crate/)
  assert(await announced, `${B} sees the announcement`)
  assert(await until(() => count(bot, 'elytra') === 1, 3000), 'elytra received')

  botB.chat(`/crates give ${B} epic 5`)
  await sleep(1000)
  assertEq((await info(B)).epic, 0, 'player without permission cannot hand out keys')

  await closeGui(bot)
  assertEq(guiLeaks(bot), [], 'no GUI items in the inventory')

  await resetPlayer(A)
  await resetPlayer(B)
  await rcon(`effect clear ${A}`)
  await rcon(`effect clear ${B}`)
}, { timeoutMs: 240000 })
