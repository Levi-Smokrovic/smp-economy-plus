// Bank: /bank commands (deposit, withdraw, percent, "all", errors), GUI (10/50/100 % buttons, anvil input,
// click filter, no item leaks), interest (24 h rule, 1 %, $2,000 cap, treasury) over RCON diagnostics.
import {
  connectBot, rcon, assert, assertEq, sleep, runE2E, waitForMessage, expectWindow, clickSlot, itemLabel, itemLore,
  windowTitle,
} from './lib.mjs'

const B1 = 'bankBot1'
const B2 = 'bankBot2'

const DEP = { 10: 10, 50: 11, 100: 12 }
const WDR = { 10: 14, 50: 15, 100: 16 }
const INFO = 13
const DEP_INPUT = 20
const INTEREST = 22
const WDR_INPUT = 24
const CLOSE = 31

async function info(name) {
  const out = await rcon(`smpeco bank info ${name}`)
  const m = out.match(/balance=(\d+) wallet=(\d+) eligible=(\d+) eligibleNext=(\d+) lots=(\d+) interest=(\d+)/)
  if (!m) throw new Error(`smpeco bank info ${name}: ${out.trim()}`)
  return { bank: Number(m[1]), wallet: Number(m[2]), eligible: Number(m[3]), lots: Number(m[5]), interest: Number(m[6]) }
}

// commands run asynchronously to chat
async function waitState(name, bank, wallet, timeoutMs = 4000) {
  const until = Date.now() + timeoutMs
  let s = await info(name)
  while ((s.bank !== bank || s.wallet !== wallet) && Date.now() < until) {
    await sleep(150)
    s = await info(name)
  }
  return s
}

async function expectState(name, bank, wallet, label) {
  const s = await waitState(name, bank, wallet)
  assertEq([s.bank, s.wallet], [bank, wallet], `${label} (bank/cash in cents)`)
  return s
}

/**
 * At most about one command per second: vanilla counts commands toward chat spam (+20 per message, -1 per tick,
 * kick above 200).
 */
async function chat(bot, text) {
  const wait = (bot.lastChatAt ?? 0) + 1100 - Date.now()
  if (wait > 0) await sleep(wait)
  bot.lastChatAt = Date.now()
  bot.chat(text)
}

async function expectMsg(bot, text, regex, timeoutMs = 5000) {
  const pending = waitForMessage(bot, regex, timeoutMs).then(() => true, () => false)
  await chat(bot, text)
  return pending
}

async function clickExpect(bot, slot, regex, timeoutMs = 5000) {
  const pending = waitForMessage(bot, regex, timeoutMs).then(() => true, () => false)
  await clickSlot(bot, slot)
  return pending
}

function payoutOf(out) {
  const m = out.match(/players=(\d+) due=(\d+) paid=(\d+) budget=(\d+) factor=([\d.]+) failed=(true|false)/)
  if (!m) throw new Error(`smpeco bank payout: ${out.trim()}`)
  return { players: Number(m[1]), due: Number(m[2]), paid: Number(m[3]), budget: Number(m[4]), failed: m[6] === 'true' }
}

function loreHas(item, regex) {
  return itemLore(item).some((l) => regex.test(l))
}

async function reset(bot, name, walletDollars) {
  if (bot.currentWindow) bot.closeWindow(bot.currentWindow)
  const s = await info(name)
  if (s.bank > 0) await expectMsg(bot, '/bank withdraw all', /Withdrew/)
  await rcon(`eco set ${name} ${walletDollars}`)
  return waitState(name, 0, walletDollars * 100)
}

runE2E(async () => {
  const bot1 = await connectBot(B1)
  const bot2 = await connectBot(B2)
  assert(true, 'bots connected')
  await sleep(1500)
  await rcon(`clear ${B1}`)
  await rcon(`clear ${B2}`)

  const s1 = await reset(bot1, B1, 1000)
  const s2 = await reset(bot2, B2, 0)
  if (!assertEq([s1.bank, s1.wallet, s2.bank, s2.wallet], [0, 100000, 0, 0], 'initial state: bank empty, cash from eco set')) {
    throw new Error('initial state not reachable (/eco set available?)')
  }
  const status = await rcon('smpeco bank status')
  assert(/accounts=\d+ total=\d+ lastDate=\S+ .*pending=\d+/.test(status), `Bank-Status per RCON (${status.trim().slice(0, 160)})`)

  await commandTests(bot1)
  await guiTests(bot1)
  await interestTests(bot1, bot2)

  for (const [bot, name] of [[bot1, B1], [bot2, B2]]) {
    if (bot.currentWindow) bot.closeWindow(bot.currentWindow)
    const s = await info(name)
    if (s.bank > 0) assert(await expectMsg(bot, '/bank withdraw all', /Withdrew/), `${name}: all withdrawn`)
    const end = await waitState(name, 0, s.wallet + s.bank)
    assertEq(end.bank, 0, `${name}: bank empty at the end`)
    await rcon(`eco set ${name} 0`)
  }
}, { timeoutMs: 240000 })

async function commandTests(bot) {
  assert(await expectMsg(bot, '/bank deposit 250', /Deposited: \$250\.00/), 'command: deposit $250')
  await expectState(B1, 25000, 75000, 'after deposit $250')
  assert(await expectMsg(bot, '/bank deposit 5000', /Not enough cash/), 'command: more than cash refused')
  assert(await expectMsg(bot, '/bank withdraw 300', /Not enough bank balance/), 'command: more than bank balance refused')
  assert(await expectMsg(bot, '/bank deposit abc', /Invalid amount/), 'command: nonsense refused')
  assert(await expectMsg(bot, '/bank deposit 0', /Invalid amount/), 'command: 0 refused')
  assert(await expectMsg(bot, '/bank withdraw 101%', /Invalid amount/), 'command: 101 % refused')
  assert(await expectMsg(bot, '/bank deposit 1.000', /Invalid amount/), 'command: dot thousands separator refused')
  assert(await expectMsg(bot, '/bank deposit', /Usage: \/bank deposit/), 'command without amount shows help')
  assert(await expectMsg(bot, '/bank withdraw', /Usage: \/bank withdraw/), 'withdraw without amount shows help')
  await expectState(B1, 25000, 75000, 'errors change nothing')
  assert(await expectMsg(bot, '/bank withdraw 50', /Withdrew: \$50\.00/), 'withdraw $50')
  await expectState(B1, 20000, 80000, 'after withdrawing $50')
  assert(await expectMsg(bot, '/bank deposit 10%', /Deposited: \$80\.00/), 'deposit 10 % of cash')
  await expectState(B1, 28000, 72000, 'after 10 %')
  assert(await expectMsg(bot, '/bank withdraw 8', /Withdrew: \$8\.00/), 'withdraw $8')
  await expectState(B1, 27200, 72800, 'after withdraw $8')
  assert(await expectMsg(bot, '/bank info', /Bank balance: \$272\.00/), '/bank info shows bank balance')
}

async function guiTests(bot) {
  let win = await expectWindow(bot, () => chat(bot, '/bank'))
  assert(/Bank/.test(windowTitle(win)), `bank GUI opened (title "${windowTitle(win)}")`)
  assertEq(itemLabel(win.slots[INFO]), 'Bank Account', 'info slot')
  assert(loreHas(win.slots[INFO], /Bank balance: \$272\.00/), 'info shows bank balance')
  assert(loreHas(win.slots[INFO], /Cash: \$728\.00/), 'info shows cash')
  assertEq(itemLabel(win.slots[DEP[50]]), 'Deposit 50%', 'button deposit 50%')
  assert(loreHas(win.slots[DEP[50]], /Amount: \$364\.00/), 'button shows the amount')
  assertEq(itemLabel(win.slots[WDR[100]]), 'Withdraw 100%', 'button withdraw 100%')
  assert(loreHas(win.slots[INTEREST], /Interest: 1% per day/), 'interest info in the GUI')
  assertEq(win.slots[0]?.name, 'black_stained_glass_pane', 'frame')

  assert(await clickExpect(bot, DEP[50], /Deposited: \$364\.00/), 'GUI: +50 % deposits')
  await expectState(B1, 63600, 36400, 'after +50 %')
  await sleep(200)
  assert(loreHas(bot.currentWindow?.slots[INFO], /Bank balance: \$636\.00/), 'GUI updates after the click')
  assert(await clickExpect(bot, DEP[10], /Deposited: \$36\.40/), 'GUI: +10 % deposits')
  await expectState(B1, 67240, 32760, 'after +10 %')
  assert(await clickExpect(bot, WDR[100], /Withdrew: \$672\.40/), 'GUI: -100 % withdraws everything')
  await expectState(B1, 0, 100000, 'after -100 %')
  assert(await clickExpect(bot, DEP[100], /Deposited: \$1,000\.00/), 'GUI: +100 % deposits everything')
  await expectState(B1, 100000, 0, 'after +100 %')
  await sleep(400) // same slot: more than the 300 ms debounce
  assert(await clickExpect(bot, DEP[100], /no cash to deposit/), 'GUI: +100 % without cash gives an error')
  assert(loreHas(bot.currentWindow?.slots[DEP[10]], /No cash/), 'buttons show "No cash"')
  assert(await clickExpect(bot, WDR[50], /Withdrew: \$500\.00/), 'GUI: -50 % withdraws half')
  await expectState(B1, 50000, 50000, 'after -50 %')

  await sleep(400)
  await clickSlot(bot, DEP[50], 0, 2)
  await sleep(400)
  await clickSlot(bot, WDR[50], 0, 4)
  await sleep(600)
  await expectState(B1, 50000, 50000, 'number key and Q change nothing')

  let anvil = await expectWindow(bot, () => clickSlot(bot, DEP_INPUT))
  assert(/Deposit amount/.test(windowTitle(anvil)), `Anvil "Deposit amount" (${anvil.type})`)
  bot._client.write('name_item', { name: '125.50' })
  await sleep(400)
  let msg = waitForMessage(bot, /Deposited: \$125\.50/, 5000).then(() => true, () => false)
  win = await expectWindow(bot, () => clickSlot(bot, 2))
  assert(await msg, 'anvil: $125.50 deposited')
  assert(/Bank/.test(windowTitle(win)), 'bank GUI again afterwards')
  await expectState(B1, 62550, 37450, 'after anvil deposit')

  // anvil input: "all"
  await sleep(350)
  anvil = await expectWindow(bot, () => clickSlot(bot, WDR_INPUT))
  assert(/Withdraw amount/.test(windowTitle(anvil)), 'Anvil "Withdraw amount"')
  bot._client.write('name_item', { name: 'all' })
  await sleep(400)
  msg = waitForMessage(bot, /Withdrew: \$625\.50/, 5000).then(() => true, () => false)
  win = await expectWindow(bot, () => clickSlot(bot, 2))
  assert(await msg, 'anvil: "all" withdrawn')
  await expectState(B1, 0, 100000, 'after anvil "all"')

  await sleep(350)
  anvil = await expectWindow(bot, () => clickSlot(bot, DEP_INPUT))
  bot._client.write('name_item', { name: 'ten euros' })
  await sleep(400)
  msg = waitForMessage(bot, /Invalid amount/, 5000).then(() => true, () => false)
  win = await expectWindow(bot, () => clickSlot(bot, 2))
  assert(await msg, 'anvil: invalid input refused')
  await expectState(B1, 0, 100000, 'invalid input changes nothing')

  await sleep(350)
  anvil = await expectWindow(bot, () => clickSlot(bot, WDR_INPUT))
  win = await expectWindow(bot, () => clickSlot(bot, 1))
  assert(/Bank/.test(windowTitle(win)) && !/Withdraw/.test(windowTitle(win)), 'anvil cancel returns to the bank GUI')

  await clickSlot(bot, CLOSE)
  await sleep(400)
  assert(!bot.currentWindow, 'close button closes the GUI')
  const leaked = bot.inventory.items().map((it) => it.name)
  assertEq(leaked, [], 'no GUI items in the inventory')
  await expectState(B1, 0, 100000, 'state after the GUI')
}

async function interestTests(bot1, bot2) {
  // 24 h rule: freshly deposited money earns no interest
  assert(await expectMsg(bot1, '/bank deposit 500', /Deposited: \$500\.00/), 'interest: $500 deposited')
  await expectState(B1, 50000, 50000, 'before interest')
  let p = payoutOf(await rcon(`smpeco bank payout ${B1}`))
  assertEq([p.due, p.paid], [0, 0], 'freshly deposited: no interest (24 h rule)')

  const back = await rcon(`smpeco bank backdate ${B1} 25`)
  assert(/eligible=50000/.test(back), `backdated 25 h: $500 eligible (${back.trim()})`)
  assert(await expectMsg(bot1, '/bank deposit 100', /Deposited: \$100\.00/), 'fresh $100 added')
  let s = await expectState(B1, 60000, 40000, 'Bank $600')
  assertEq(s.eligible, 50000, 'only the old $500 are eligible')

  let note = waitForMessage(bot1, /Interest credited|No interest today/, 5000).then(() => true, () => false)
  p = payoutOf(await rcon(`smpeco bank payout ${B1}`))
  assertEq(p.due, 500, '1 % of $500 due (not of the fresh $100)')
  assertEq(p.paid, Math.min(p.due, p.budget), `paid = min(due, treasury budget ${p.budget})`)
  s = await waitState(B1, 60000 + p.paid, 40000)
  assertEq(s.bank, 60000 + p.paid, 'interest lands on the bank account')
  assert(await note, 'player gets an interest message')
  if (p.paid > 0) {
    const t = await rcon('smpeco treasury')
    assert(/bank_interest/.test(t), 'treasury records bank_interest')
  }

  // cap: 1 % of $300,000 would be $3,000, capped at $2,000
  await rcon(`eco set ${B2} 300000`)
  await expectState(B2, 0, 30000000, 'bankBot2 has $300,000')
  assert(await expectMsg(bot2, '/bank deposit all', /Deposited: \$300,000\.00/), 'bankBot2 deposits everything')
  await expectState(B2, 30000000, 0, 'bankBot2 Bank $300,000')
  await rcon(`smpeco bank backdate ${B2} 25`)
  p = payoutOf(await rcon(`smpeco bank payout ${B2}`))
  assertEq(p.due, 200000, 'interest capped at $2,000 per day')
  assert(p.paid <= 200000 && p.paid === Math.min(p.due, p.budget), `paid ${p.paid} within cap and budget`)
  s = await waitState(B2, 30000000 + p.paid, 0)
  assertEq(s.bank, 30000000 + p.paid, 'bankBot2: interest credited')

  // LIFO: a withdrawal takes the newest money first (the fresh interest)
  assert(await expectMsg(bot2, '/bank withdraw 100k', /Withdrew: \$100,000\.00/), 'bankBot2 withdraws $100,000')
  s = await waitState(B2, 20000000 + p.paid, 10000000)
  assertEq([s.bank, s.eligible], [20000000 + p.paid, 20000000 + p.paid], 'after withdrawal: the rest is old money, eligible = minimum balance')

  assert(await expectMsg(bot2, '/bank info', /Bank balance: \$20[0-2],\d{3}\.\d\d/), 'bankBot2 sees its bank balance')
}
