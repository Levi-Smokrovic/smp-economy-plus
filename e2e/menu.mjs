// Main menu /menu and /commands: selftests, menu icons, opening features from the menu and the way back through
// the "Main Menu" button, help in chat, the join hint (once per join) and the tab list footer.
import { connectBot, rcon, moduleEnabled, runE2E, assert, assertEq, expectWindow, windowTitle, clickSlot, findSlot, itemLabel, itemLore, waitForMessage, sleep, stripColors, textOf } from './lib.mjs'

const BOT = 'BotMenuA'
const isMenuButton = (it) => it.name === 'nether_star' && /^Main Menu$/.test(itemLabel(it))

runE2E(async () => {
  const st = await rcon('smpeco selftest menu')
  assert(/SUMMARY passed=\d+ failed=0/.test(st), `Selftests menu (${st.slice(-60)})`)

  const bot = await connectBot(BOT, { setup: (b) => b._client.on('playerlist_header', (p) => { b.tabFooter = textOf(p.footer) }) })
  const HINT = /All features: \[\/menu\] . Commands: \[\/commands\]/
  const hint = waitForMessage(bot, HINT, 15000, { includePast: true }).catch(() => null)
  await sleep(500)

  let win = await expectWindow(bot, () => bot.chat('/menu'))
  assert(/Main Menu/.test(windowTitle(win)), `/menu opens the main menu (${windowTitle(win)})`)
  const labels = []
  for (let i = 0; i < win.inventoryStart; i++) {
    const it = win.slots[i]
    if (it && !/stained_glass_pane/.test(it.name)) labels.push(itemLabel(it))
  }
  // icon to module; icons of disabled extra modules are missing
  const OWNER = { 'Daily Reward': 'daily', Crates: 'crates', Casino: 'casino', Clan: 'clans', News: 'news', Lottery: 'events',
    Bounty: 'bounty', Market: 'market', Coinflip: 'minigames', 'Events & Trader Rank': 'events' }
  const ALL_SYMBOLS = ['Shop', 'Sell', 'Auction House', 'Orders', 'Jobs', 'Missions', 'Daily Tasks', 'Daily Reward', 'Crates',
    'Casino', 'Bank', 'Clan', 'Stats', 'News', 'Settings', 'Lottery', 'Bounty', 'Market', 'Coinflip', 'Events & Trader Rank']
  const on = {}
  for (const id of new Set(Object.values(OWNER))) on[id] = await moduleEnabled(id)
  const SYMBOLS = ALL_SYMBOLS.filter((n) => !OWNER[n] || on[OWNER[n]])
  for (const name of SYMBOLS) {
    assert(labels.includes(name), `icon ${name} present`)
  }
  for (const name of ALL_SYMBOLS.filter((n) => !SYMBOLS.includes(n))) {
    assert(!labels.includes(name), `icon ${name} absent (module ${OWNER[name]} off)`)
  }
  assert(!win.slots.slice(0, win.inventoryStart).some((it) => it?.name === 'gray_dye'), 'no "unavailable" icon')
  const info = findSlot(win, (it) => it.name === 'nether_star')
  assert(info >= 0 && itemLore(win.slots[info]).some((l) => /Balance: \$/.test(l)), 'info shows the balance')

  const shopSlot = findSlot(win, /^Shop$/)
  win = await expectWindow(bot, () => clickSlot(bot, shopSlot))
  assert(/Shop/i.test(windowTitle(win)), `click on Shop opens the shop (${windowTitle(win)})`)
  const shopBack = findSlot(win, isMenuButton)
  assertEq(shopBack, 45, 'Shop: main menu button bottom left')
  win = await expectWindow(bot, () => clickSlot(bot, shopBack))
  assert(/Main Menu/.test(windowTitle(win)), `Shop to main menu (${windowTitle(win)})`)
  bot.closeWindow(win)
  await sleep(400)

  win = await expectWindow(bot, () => bot.chat('/menu'))
  assert(/Main Menu/.test(windowTitle(win)), '/menu opens the main menu again')
  const settingsSlot = findSlot(win, /^Settings$/)
  win = await expectWindow(bot, () => clickSlot(bot, settingsSlot))
  assert(/Settings/i.test(windowTitle(win)), `click on Settings opens /settings (${windowTitle(win)})`)
  bot.closeWindow(win)
  await sleep(400)

  for (const [label, title, check, mod] of [
    [/^Market$/, /Market/, null, 'market'],
    [/^Coinflip$/, /^Coinflip$/, (w) => findSlot(w, /New Coinflip/) >= 0, 'minigames'],
    [/^Events & Trader Rank$/, /Server.Events/i, null, 'events'],
  ]) {
    if (!on[mod]) continue
    win = await expectWindow(bot, () => bot.chat('/menu'))
    const slot = findSlot(win, label)
    assert(slot >= 0, `icon ${label} found`)
    win = await expectWindow(bot, () => clickSlot(bot, slot))
    assert(title.test(windowTitle(win)), `click on ${label} opens ${title} (${windowTitle(win)})`)
    if (check) assert(check(win), `${windowTitle(win)}: player coinflips (button "New Coinflip")`)
    bot.closeWindow(win)
    await sleep(400)
  }

  // Sell has no "Main Menu" button because closing that window sells its contents.
  win = await expectWindow(bot, () => bot.chat('/menu'))
  for (const name of SYMBOLS) {
    if (name === 'Sell') continue
    const slot = findSlot(win, (it) => itemLabel(it) === name)
    if (!assert(slot >= 0, `round trip: icon ${name} in the menu`)) continue
    const feature = await expectWindow(bot, () => clickSlot(bot, slot))
    const title = windowTitle(feature)
    const back = findSlot(feature, isMenuButton)
    if (!assert(back >= 0, `${name} (${title}): main menu button present`)) {
      bot.closeWindow(feature)
      await sleep(400)
      win = await expectWindow(bot, () => bot.chat('/menu'))
      continue
    }
    assert(itemLore(feature.slots[back]).some((l) => /\/menu/.test(l)), `${name}: button names /menu`)
    win = await expectWindow(bot, () => clickSlot(bot, back))
    assert(/Main Menu/.test(windowTitle(win)), `${name} (slot ${back}) back in the main menu (${windowTitle(win)})`)
  }
  bot.closeWindow(win)
  await sleep(400)

  const help = waitForMessage(bot, /Trading: .*\/shop/, 8000)
  bot.chat('/commands')
  const m = await help.catch(() => null)
  assert(!!m, '/commands lists the trading commands')
  assert(bot.messages.some((l) => /Money: .*\/balance.*\/pay/.test(l)), '/commands lists the money commands')
  assert(bot.messages.some((l) => /Menu: .*\/menu.*\/commands/.test(l)), '/commands names /menu and itself')
  assert(!bot.messages.some((l) => /\/eco\b/.test(l)), '/commands shows no admin commands')
  assertEq(bot.currentWindow ?? null, null, 'no window open after /commands')

  assert(!!(await hint), 'join hint for /menu and /commands in chat')
  await sleep(500)
  assertEq(bot.messages.filter((l) => HINT.test(stripColors(l))).length, 1, 'join hint exactly once')
  assert(/\/menu . \/commands/.test(bot.tabFooter ?? ''), `tab list footer names /menu and /commands (${JSON.stringify(bot.tabFooter ?? '')})`)
})
