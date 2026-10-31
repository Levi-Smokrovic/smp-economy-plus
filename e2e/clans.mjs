// Clans: selftests, founding through the GUI (anvil name, anvil tag, color, confirm, fee to the treasury),
// inviting, declining and accepting, member permissions, team prefix at the client, clan chat, clan bank by GUI
// and command with log, promoting and demoting, clan home with a 3 s teleport cancelled by movement, kicking,
// open joining, leaving, disbanding with the bank paid out to the leader.
import {
  connectBot, rcon, runE2E, assert, assertEq, sleep, waitForMessage, waitForWindow, expectWindow, windowTitle, clickSlot,
  findSlot, itemLabel, itemLore, textOf, treasuryFlows, treasuryDelta,
} from './lib.mjs'

const A = 'clansBot1'
const B = 'clansBot2'
const CLAN = 'E2EClans'
const TAG = 'E2C'
const X = 4300, Y = 200, Z = 4300
const HOME = { x: X + 0.5, y: Y + 1, z: Z + 0.5 }
const AWAY = { x: X + 20.5, y: Y + 1, z: Z + 0.5 }

function parseKV(line) {
  const out = {}
  for (const m of String(line).matchAll(/(\w+)=("[^"]*"|\S+)/g)) out[m[1]] = m[2].replace(/^"|"$/g, '')
  return out
}

async function dump() {
  const out = await rcon(`clan admin dump ${CLAN}`)
  return /notfound=/.test(out) || !/clan id=/.test(out) ? null : parseKV(out)
}

async function who(name) {
  return parseKV(await rcon(`clan admin who ${name}`))
}

// warning to plain members on deposit (ClanGui.WITHDRAW_WARNING, without § codes)
const WARN = /Only the leader\/officers can withdraw it again/

// reason of the founding fee in the treasury (ClanService.TREASURY_REASON)
const FEE_REASON = 'clans_fee'

function said(bot, re, ms = 8000) {
  return waitForMessage(bot, re, ms).then(() => true, () => false)
}

// Vanilla kicks for chat or command spam (TickThrottler: +20 per message, -1 per tick, limit 200, ops exempt).
// Assume 10 TPS and stay below 160 so the bots never get "disconnect.spam".
const spam = new Map()
async function paced(bot) {
  for (;;) {
    const now = Date.now()
    const s = spam.get(bot) ?? { level: 0, at: now }
    const level = Math.max(0, s.level - (now - s.at) / 100)
    if (level + 20 < 160) {
      spam.set(bot, { level: level + 20, at: now })
      return
    }
    await sleep(250)
  }
}

async function say(bot, text) {
  await paced(bot)
  bot.chat(text)
}

// the listener registers only after the throttle, so the timeout does not run out
async function cmd(bot, text, re, ms = 8000) {
  await paced(bot)
  const p = said(bot, re, ms)
  bot.chat(text)
  return p
}

async function waitFor(cond, ms = 6000, step = 150) {
  const end = Date.now() + ms
  while (Date.now() < end) {
    try { if (await cond()) return true } catch {}
    await sleep(step)
  }
  return false
}

function near(bot, pos, d = 1.5) {
  const p = bot.entity.position
  return Math.hypot(p.x - pos.x, p.y - pos.y, p.z - pos.z) < d
}

function teamOf(bot, name) {
  return bot.teamMap?.[name]
}

function prefixText(team) {
  if (!team?.prefix) return ''
  try { return String(team.prefix.toString()) } catch { return textOf(team.prefix) }
}

function closeWin(bot) {
  if (bot.currentWindow) bot.closeWindow(bot.currentWindow)
}

async function tp(name, pos) {
  await rcon(`tp ${name} ${pos.x} ${pos.y} ${pos.z}`)
}

async function resetAll() {
  await rcon(`clan admin reset ${A}`)
  await rcon(`clan admin reset ${B}`)
  await rcon(`clan admin disband ${CLAN}`)
}

runE2E(async () => {
  const st = await rcon('smpeco selftest clans')
  const sm = st.match(/SUMMARY passed=(\d+) failed=(\d+)/)
  assert(!!sm && Number(sm[1]) >= 10, `clans selftests ran (${st.slice(0, 200).trim()})`)
  assertEq(sm ? Number(sm[2]) : -1, 0, 'Selftests clans failed=0')

  // orphaned clan bank accounts: the list answers, unknown and clan accounts cannot be paid out
  assert(/orphans=\d+/.test(await rcon('clan admin orphan')), 'clan admin orphan lists')
  assert(/No orphaned/.test(await rcon('clan admin orphan 00000000-0000-0000-0000-00000000c0de treasury')),
    'treasury is not an orphaned clan account')
  assert(/Invalid account ID/.test(await rcon('clan admin orphan broken treasury')), 'invalid account ID refused')

  await resetAll()
  const a = await connectBot(A)
  const b = await connectBot(B)
  assert(true, 'bots connected')
  await resetAll() // both names are known now
  // new bots get the starter kit on first join; empty the inventories, since the item checks expect them empty
  await sleep(1500)
  await rcon(`clear ${A}`)
  await rcon(`clear ${B}`)
  await rcon(`eco set ${A} 0`)
  await rcon(`eco set ${B} 0`)
  assertEq((await who(A)).wallet, '0', 'eco set applies (balance A = 0)')

  await rcon(`forceload add ${X - 4} ${Z - 4} ${X + 24} ${Z + 4}`)
  await sleep(1500)
  await rcon(`fill ${X - 3} ${Y} ${Z - 3} ${X + 23} ${Y} ${Z + 3} minecraft:glass`)
  await rcon(`fill ${X - 3} ${Y + 1} ${Z - 3} ${X + 23} ${Y + 4} ${Z + 3} minecraft:air`)
  await rcon(`gamemode survival ${A}`)
  await rcon(`gamemode survival ${B}`)
  await tp(A, HOME)
  await tp(B, AWAY)
  await sleep(1500)

  try {
    await createAndInvite(a, b)
    await memberRightsAndChat(a, b)
    await bank(a, b)
    await home(a, b)
    await kickJoinLeave(a, b)
    await disband(a, b)
  } finally {
    closeWin(a)
    closeWin(b)
    await resetAll()
    await rcon(`eco set ${A} 0`)
    await rcon(`eco set ${B} 0`)
    await rcon(`fill ${X - 3} ${Y} ${Z - 3} ${X + 23} ${Y + 4} ${Z + 3} minecraft:air`)
    await rcon(`forceload remove ${X - 4} ${Z - 4} ${X + 24} ${Z + 4}`)
  }
}, { timeoutMs: 240000 })

async function createAndInvite(a, b) {
  assert(await cmd(a, `/clan create ${CLAN} ${TAG}`, /Not enough money/), 'founding without money refused')
  assertEq((await who(A)).clan, 'none', 'no clan without money')

  await rcon(`eco set ${A} 6000`)
  assertEq((await who(A)).wallet, '600000', 'A has $6,000')
  const t0 = await treasuryFlows()
  const start = await expectWindow(a, () => say(a, '/clan'))
  assert(/Clans/.test(windowTitle(start)), `start menu without clan (${windowTitle(start)})`)
  const createSlot = findSlot(start, /Create Clan/)
  assert(createSlot >= 0 && itemLore(start.slots[createSlot]).some((l) => /\$5,000/.test(l)), 'found button shows $5,000')
  const nameIn = await expectWindow(a, () => clickSlot(a, createSlot))
  assert(/Clan name/.test(windowTitle(nameIn)), `anvil for the name (${windowTitle(nameIn)})`)
  a._client.write('name_item', { name: CLAN })
  await sleep(400)
  const tagIn = await expectWindow(a, () => clickSlot(a, 2))
  assert(/Clan tag/.test(windowTitle(tagIn)), `anvil for the tag (${windowTitle(tagIn)})`)
  assertEq(itemLabel(tagIn.slots[0]), 'E2E', 'tag suggestion from the name')
  a._client.write('name_item', { name: TAG })
  await sleep(400)
  const picker = await expectWindow(a, () => clickSlot(a, 2))
  assert(/Tag Color/.test(windowTitle(picker)), `color picker (${windowTitle(picker)})`)
  const gold = findSlot(picker, /^Gold$/)
  assert(gold >= 0 && itemLore(picker.slots[gold]).some((l) => l.includes(`[${TAG}] ${CLAN}`)), 'color gold with preview')
  const confirm = await expectWindow(a, () => clickSlot(a, gold))
  assert(/Create clan\?/.test(windowTitle(confirm)), `confirmation (${windowTitle(confirm)})`)
  assert(itemLabel(confirm.slots[13]).includes(`[${TAG}] ${CLAN}`), 'preview in the dialog')
  const created = said(a, /created!/)
  const clanWin = waitForWindow(a, 6000).catch(() => null)
  await clickSlot(a, 9)
  assert(await created, 'clan founded (message)')
  const cw = await clanWin
  assert(cw && /E2EClans/.test(windowTitle(cw)), 'clan overview opens afterwards')
  closeWin(a)

  let d = await dump()
  assert(!!d, 'clan exists')
  if (!d) throw new Error('clan missing')
  assertEq([d.tag, d.color, d.leader, d.bank], [TAG, 'gold', A, '0'], 'tag/color/leader/bank')
  assertEq((await who(A)).wallet, '100000', 'fee $5,000 charged')
  // reason clans_fee instead of the raw balance, since NPC orders and buyers book in the background
  assertEq(treasuryDelta(t0, await treasuryFlows(), FEE_REASON).income, 500000, 'fee in the treasury (clans_fee)')
  assertEq([d.team, d.prefix, d.ff, d.teamPlayers], [`tri_clan_${d.id}`, `[${TAG}] `, 'false', A], 'team: name, prefix, friendly fire off, members')
  assert(await waitFor(() => teamOf(a, A)?.team === d.team), 'client knows the team')
  assert(prefixText(teamOf(a, A)).includes(`[${TAG}]`), `client sees prefix (${prefixText(teamOf(a, A))})`)

  const inv1 = said(b, /invited you to the clan/)
  assert(await cmd(a, `/clan invite ${B}`, /has been invited/), 'invitation sent')
  assert(await inv1, 'B receives the invitation')
  const deniedA = said(a, /declined the invite\./)
  assert(await cmd(b, '/clan deny', /Declined the invite from/), 'B declines')
  assert(await deniedA, 'leader learns of the decline')
  assert(await cmd(b, '/clan accept', /don't have a valid invite/), 'accepting after declining fails')
  assert(await cmd(a, `/clan invite ${B}`, /has been invited/), 'invited again')
  const bStart = await expectWindow(b, () => say(b, '/clan'))
  const invSlot = findSlot(bStart, /Invites/)
  assert(invSlot >= 0 && /\(1\)/.test(itemLabel(bStart.slots[invSlot])), `invitations (1) in the start menu (${itemLabel(bStart.slots[invSlot])})`)
  const invWin = await expectWindow(b, () => clickSlot(b, invSlot))
  assert(/Invites/.test(windowTitle(invWin)), 'invitation GUI')
  const clanSlot = findSlot(invWin, /E2EClans/)
  assert(clanSlot >= 0, 'invitation from E2EClans shown')
  const joinedB = said(b, /You joined the clan/)
  const joinedA = said(a, /clansBot2 joined the clan/)
  await clickSlot(b, clanSlot)
  assert(await joinedB, 'B joined through the GUI')
  assert(await joinedA, 'leader sees the join')
  await sleep(300)
  closeWin(b)
  d = await dump()
  assertEq(d.members, `${A}:LEADER,${B}:MEMBER`, 'member list')
  assertEq(d.teamPlayers, [A, B].sort((x, y) => x.toLowerCase().localeCompare(y.toLowerCase())).join(','), 'both in the team')
  assert(await waitFor(() => teamOf(b, B)?.team === d.team && teamOf(a, B)?.team === d.team), 'clients see B in the clan team')
}

async function memberRightsAndChat(a, b) {
  assert(await cmd(b, `/clan kick ${A}`, /Only the leader and officers/), 'member cannot kick')
  assert(await cmd(b, '/clan bank withdraw 1', /Only the leader and officers can withdraw/), 'member cannot withdraw')
  assert(await cmd(b, '/clan sethome', /Only the leader and officers/), 'member cannot set a home')
  assert(await cmd(b, '/clan disband', /Only the leader can/), 'member cannot disband')
  assert(await cmd(b, `/clan promote ${A}`, /Only the leader can/), 'member cannot promote')
  assert(await cmd(a, '/clan leave', /As the leader you can't leave/), 'leader cannot leave')
  assertEq((await dump()).members, `${A}:LEADER,${B}:MEMBER`, 'nothing changed')

  const got = said(a, new RegExp(`\\[Clan\\] ${B}: hello-e2e`))
  await say(b, '/cc hello-e2e')
  assert(await got, '/cc reaches the leader')
  assert(await cmd(a, '/clan chat', /Clan Chat on/), 'chat mode on')
  assertEq((await who(A)).chat, 'true', 'chat mode saved')
  const got2 = said(b, new RegExp(`\\[Clan\\] ★ ${A}: secret-e2e`))
  await say(a, 'secret-e2e')
  assert(await got2, 'normal chat message goes to the clan in this mode')
  await sleep(500)
  assert(!b.messages.some((m) => /secret-e2e/.test(m) && !/\[Clan\]/.test(m)), 'not sent publicly')
  assert(await cmd(a, '/clan chat', /Clan Chat off/), 'chat mode off')
}

async function bank(a, b) {
  await rcon(`eco set ${B} 2000`)
  assertEq((await who(B)).wallet, '200000', 'B has $2,000')
  const main = await expectWindow(b, () => say(b, '/clan'))
  const bankSlot = findSlot(main, /Clan Bank/)
  assert(bankSlot >= 0, 'bank button in the overview')
  const bankWin = await expectWindow(b, () => clickSlot(b, bankSlot))
  assert(/Clan Bank/.test(windowTitle(bankWin)), `Bank-GUI (${windowTitle(bankWin)})`)
  const s1k = findSlot(bankWin, /Deposit \+\$1K/)
  assert(s1k >= 0, '+$1K-Button')
  assert(itemLore(bankWin.slots[s1k]).some((l) => WARN.test(l)), 'deposit button warns: only leader and officers withdraw')
  // member: each deposit needs a confirmation since members cannot take the money back; cancel books nothing
  let conf = await expectWindow(b, () => clickSlot(b, s1k))
  assert(/Confirm Deposit/.test(windowTitle(conf)), `confirmation before depositing (${windowTitle(conf)})`)
  let pv = itemLore(conf.slots[13])
  assert(pv.some((l) => /Amount: \$1,000\.00/.test(l)) && pv.some((l) => /Your balance: \$2,000\.00 → \$1,000\.00/.test(l))
    && pv.some((l) => WARN.test(l)), `preview: amount, balance before and after, warning (${pv.join(' | ')})`)
  const back1 = await expectWindow(b, () => clickSlot(b, 15))
  assert(/Clan Bank/.test(windowTitle(back1)), 'cancel returns to the bank')
  let d = await dump()
  assertEq([d.bank, (await who(B)).wallet], ['0', '200000'], 'cancel books nothing')
  conf = await expectWindow(b, () => clickSlot(b, findSlot(back1, /Deposit \+\$1K/)))
  const dep1 = said(b, /Deposited /)
  const back2 = await expectWindow(b, () => clickSlot(b, 9))
  assert(await dep1, '+$1,000 deposited (after confirmation)')
  assert(/Clan Bank/.test(windowTitle(back2)), 'bank again after confirming')
  d = await dump()
  assertEq([d.bank, (await who(B)).wallet], ['100000', '100000'], 'Bank $1,000, B $1,000')
  // "Deposit all": confirmation with the amount at click time; money arriving later stays with the player
  const all = findSlot(back2, /Deposit All/)
  conf = await expectWindow(b, () => clickSlot(b, all))
  pv = itemLore(conf.slots[13])
  assert(pv.some((l) => /Your balance: \$1,000\.00 → \$0\.00/.test(l)) && pv.some((l) => /Clan Bank: \$1,000\.00 → \$2,000\.00/.test(l)),
    `deposit all: preview $1,000 to $0, bank to $2,000 (${pv.join(' | ')})`)
  await rcon(`eco give ${B} 50`)
  const dep2 = said(b, /Deposited /)
  await expectWindow(b, () => clickSlot(b, 9))
  assert(await dep2, 'all deposited')
  d = await dump()
  assertEq([d.bank, (await who(B)).wallet], ['200000', '5000'], 'bank $2,000, B keeps the later $50')
  await rcon(`eco set ${B} 0`)
  const locked = findSlot(b.currentWindow, (it) => it.name === 'gray_dye' && /Withdraw/.test(itemLabel(it)))
  assert(locked >= 0 && itemLore(b.currentWindow.slots[locked]).some((l) => /Leader and officers only/.test(l)), 'withdrawing blocked for members')
  const log = findSlot(b.currentWindow, /Statement/)
  assert(log >= 0 && itemLore(b.currentWindow.slots[log]).filter((l) => /Deposit/.test(l)).length === 2, 'statement shows 2 deposits')
  closeWin(b)
  await sleep(300)
  assertEq(b.inventory.items().map((it) => it.name), [], 'no GUI items in the inventory')

  await rcon(`eco set ${B} 100`)
  const cancelled = said(b, /Cancelled – nothing deposited/)
  conf = await expectWindow(b, () => say(b, '/clan bank deposit 100'))
  assert(/Confirm Deposit/.test(windowTitle(conf)), `/clan bank deposit asks members (${windowTitle(conf)})`)
  await clickSlot(b, 15)
  assert(await cancelled, 'cancel by command reported')
  d = await dump()
  assertEq([d.bank, (await who(B)).wallet], ['200000', '10000'], 'command cancelled: nothing booked')
  await rcon(`eco set ${B} 0`)

  const promoted = said(b, /Your rank in .* is now Officer/)
  assert(await cmd(a, `/clan promote ${B}`, /is now an Officer/), 'promoted')
  assert(await promoted, 'B learns of the promotion')
  assertEq((await dump()).members, `${A}:LEADER,${B}:OFFICER`, 'B is an officer')
  assert(await cmd(b, '/clan bank withdraw 500', /Withdrew /), 'officer withdraws $500')
  d = await dump()
  assertEq([d.bank, (await who(B)).wallet, d.log], ['150000', '50000', '3'], 'bank $1,500, B $500, 3 entries')
  assert(await cmd(b, '/clan bank withdraw 999999', /The Clan Bank only has/), 'no more than the bank holds')
  const logLine = said(b, /Withdrawal clansBot2/)
  await say(b, '/clan bank log')
  assert(await logLine, '/clan bank log shows the withdrawal')

  // officer: step buttons deposit without a dialog (officers can withdraw again), "deposit all" still asks
  const offWin = await expectWindow(b, () => say(b, '/clan bank'))
  const s100 = findSlot(offWin, /Deposit \+\$100/)
  assert(s100 >= 0 && !itemLore(offWin.slots[s100]).some((l) => WARN.test(l)), 'officer: no member warning')
  const dep3 = said(b, /Deposited /)
  await clickSlot(b, s100)
  assert(await dep3, 'officer: +$100 deposited at once')
  assert(/Clan Bank/.test(windowTitle(b.currentWindow)), 'officer: no confirm dialog')
  d = await dump()
  assertEq([d.bank, (await who(B)).wallet], ['160000', '40000'], 'Bank $1,600, B $400')
  conf = await expectWindow(b, () => clickSlot(b, findSlot(b.currentWindow, /Deposit All/)))
  assert(/Confirm Deposit/.test(windowTitle(conf)), 'officer: "deposit all" with confirmation')
  await expectWindow(b, () => clickSlot(b, 15))
  closeWin(b)
  assert(await cmd(b, '/clan bank withdraw 100', /Withdrew /), 'officer withdraws the $100 again')
  d = await dump()
  assertEq([d.bank, (await who(B)).wallet, d.log], ['150000', '50000', '5'], 'bank $1,500, B $500, 5 entries')

  assert(await cmd(a, `/clan promote ${B} confirm`, /Nothing to confirm/), 'confirming without a request fails')
  assert(await cmd(a, `/clan promote ${B}`, /Hand over leadership\?/), 'transfer asks for confirmation')
  assertEq((await dump()).leader, A, 'A stays leader without confirmation')
  assert(await cmd(a, `/clan promote ${B} confirm`, /is now the Leader/), 'transfer confirmed')
  assertEq((await dump()).members, `${B}:LEADER,${A}:OFFICER`, 'B is leader, A officer')
  assert(await cmd(b, `/clan promote ${A}`, /Hand over leadership\?/), 'return asks for confirmation')
  assert(await cmd(b, `/clan promote ${A} confirm`, /is now the Leader/), 'return confirmed')
  assertEq((await dump()).members, `${A}:LEADER,${B}:OFFICER`, 'A leader again, B officer')

  const main2 = await expectWindow(a, () => say(a, '/clan'))
  const head = findSlot(main2, (it) => it.name === 'player_head' && new RegExp(B).test(itemLabel(it)))
  assert(head >= 0 && itemLore(main2.slots[head]).some((l) => /manage/.test(l)), 'head of B is manageable')
  const manage = await expectWindow(a, () => clickSlot(a, head))
  assert(new RegExp(`Member: ${B}`).test(windowTitle(manage)), `manage GUI (${windowTitle(manage)})`)
  const demoteSlot = findSlot(manage, /Demote/)
  assert(demoteSlot >= 0, 'demote button')
  const demoted = said(a, /is now a Member/)
  const manage2 = await expectWindow(a, () => clickSlot(a, demoteSlot))
  assert(await demoted, 'demoted through the GUI')
  assert(findSlot(manage2, /Promote/) >= 0, 'promote button afterwards')
  closeWin(a)
  assertEq((await dump()).members, `${A}:LEADER,${B}:MEMBER`, 'B member again')
}

async function home(a, b) {
  await tp(A, HOME)
  await sleep(1200)
  assert(await cmd(a, '/clan sethome', /Clan Home set/), 'home set')
  const d = await dump()
  assert(d.home.startsWith(`minecraft:overworld;${HOME.x.toFixed(2)};`), `home position saved (${d.home})`)

  await tp(B, AWAY)
  await sleep(1500)
  assert(near(b, AWAY), 'B stands far away')
  const welcome = said(b, /Welcome to the Clan Home/, 9000)
  assert(await cmd(b, '/clan home', /Teleporting to the Clan Home in/), 'teleport requested')
  await sleep(1200)
  assert(near(b, AWAY), 'not teleported yet after about 1 s (delay)')
  assert(await welcome, 'teleport done')
  assert(await waitFor(() => near(b, HOME), 3000), `B stands at home (${b.entity.position})`)

  await tp(B, AWAY)
  await sleep(1500)
  const cancelled = said(b, /Teleport cancelled/, 6000)
  assert(await cmd(b, '/clan home', /Teleporting to the Clan Home in/), 'teleport 2 requested')
  await sleep(400)
  b.setControlState('jump', true)
  await sleep(500)
  b.setControlState('jump', false)
  assert(await cancelled, 'jumping cancels the teleport')
  await sleep(3500)
  assert(!near(b, HOME, 5), 'not teleported after cancel')
  assertEq((await who(B)).teleport, 'false', 'no pending teleport any more')
}

async function kickJoinLeave(a, b) {
  const main = await expectWindow(a, () => say(a, '/clan'))
  const head = findSlot(main, (it) => it.name === 'player_head' && new RegExp(B).test(itemLabel(it)))
  const manage = await expectWindow(a, () => clickSlot(a, head))
  const kickSlot = findSlot(manage, /Kick from Clan/)
  assert(kickSlot >= 0, 'kick button')
  const confirm = await expectWindow(a, () => clickSlot(a, kickSlot))
  assert(/Kick .*\?/.test(windowTitle(confirm)), `kick confirmation (${windowTitle(confirm)})`)
  const kicked = said(b, /You were kicked from the clan/)
  const kickedA = said(a, /was kicked from the clan/)
  await clickSlot(a, 9)
  assert(await kickedA, 'B kicked')
  assert(await kicked, 'B learns of the kick')
  await sleep(300)
  closeWin(a)
  let d = await dump()
  assertEq([d.members, d.teamPlayers], [`${A}:LEADER`, A], 'B no longer in clan or team')
  assertEq((await who(B)).team, 'none', 'B without team')
  assert(await waitFor(() => !teamOf(b, B)), 'client: B without team')
  assert(await cmd(b, `/clan join ${CLAN}`, /isn't open/), 'joining a closed clan fails')

  assert(await cmd(a, '/clan open', /is now open/), 'clan opened')
  assert(await cmd(b, `/clan join ${CLAN}`, /You joined the clan/), 'B joins openly')
  const dj = await dump()
  assertEq([dj.members, dj.openJoin], [`${A}:LEADER,${B}:MEMBER`, B], 'B back in (without invitation)')
  assert(await cmd(b, '/clan home', /Clan Home unlocks after/), 'open join: /clan home blocked')
  assertEq((await who(B)).teleport, 'false', 'blocked: no pending teleport')
  assert(await cmd(b, '/clan leave', /You left the clan/), 'B leaves')
  d = await dump()
  assertEq([d.members, d.teamPlayers], [`${A}:LEADER`, A], 'only A after leaving')
  const info = said(b, /Clan Bank: \$1,500\.00/)
  await say(b, `/clan info ${TAG}`)
  assert(await info, '/clan info by tag shows the bank')
  const top = said(b, /#\d+ \[E2C\] E2EClans/)
  await say(b, '/clan top')
  assert(await top, '/clan top lists the clan')
}

async function disband(a, b) {
  const before = Number((await who(A)).wallet)
  const d = await dump()
  const bankCents = Number(d.bank)
  assertEq(bankCents, 150000, 'bank before disbanding $1,500')
  const main = await expectWindow(a, () => say(a, '/clan'))
  const slot = findSlot(main, /Disband Clan/)
  assert(slot >= 0, 'disband button for the leader')
  const confirm = await expectWindow(a, () => clickSlot(a, slot))
  assert(/Disband clan\?/.test(windowTitle(confirm)), `confirm dialog (${windowTitle(confirm)})`)
  const done = said(a, /disbanded/)
  await clickSlot(a, 9)
  assert(await done, 'clan disbanded')
  assert((await dump()) === null, 'clan no longer exists')
  const w = await who(A)
  assertEq(Number(w.wallet), before + bankCents, 'bank paid out to the leader')
  assertEq([w.clan, w.team], ['none', 'none'], 'A without clan/team')
  assert(!(await rcon('team list')).includes(d.team), 'scoreboard team removed')
  assert(await waitFor(() => !teamOf(a, A)), 'client: team removed')
  assert(await cmd(a, '/clan disband', /not in a clan/), 'disbanding again fails')
  assertEq(a.inventory.items().map((it) => it.name), [], 'no GUI items at A')
}
