// /smpeco admin (settings GUI) and /smpeco config: an op bot opens the GUI, a click changes the sell multiplier
// and affects /sell prices at once, a click on an extra module toggles it in modules.json and shows "Restart
// required", a second click reverts it; without op there is no GUI. Everything is reset at the end.
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { connectBot, rcon, runE2E, assert, assertEq, sleep, expectWindow, windowTitle, clickSlot, findSlot, itemLabel, itemLore, waitForMessage } from './lib.mjs'
import { E2E_SERVER_DIR } from '../tools/server-restart.mjs'

const BOT = 'BotAdminA'
const MODULES = join(E2E_SERVER_DIR, 'config', 'smpeconomy', 'modules.json')
const SLOT_SELL = 29 // AdminGui.SLOT_SETTINGS[7]
const SLOT_MODULES = 10

async function sellMult() {
  const m = (await rcon('smpeco price minecraft:wheat')).match(/sellMult=([\d.]+)/)
  return m ? Number(m[1]) : NaN
}

async function configValue(key) {
  return (await rcon(`smpeco config get ${key}`)).match(new RegExp(`${key.replace('.', '\\.')}=(\\S*)`))?.[1]
}

runE2E(async () => {
  const st = await rcon('smpeco selftest admin')
  assert(/SUMMARY passed=\d+ failed=0/.test(st), `Selftests admin (${st.slice(-60)})`)
  const before = { sell: await configValue('multipliers.sell'), modules: readFileSync(MODULES, 'utf8') }
  const bot = await connectBot(BOT)
  try {
    await rcon(`deop ${BOT}`)
    await sleep(300)
    const denied = waitForMessage(bot, /Unknown or incomplete command|Unknown command|permission/i, 5000).catch(() => null)
    bot.chat('/smpeco admin')
    assert(await denied, 'without op: no /smpeco admin')

    await rcon(`op ${BOT}`)
    await sleep(500)
    let win = await expectWindow(bot, () => bot.chat('/smpeco admin'))
    assert(/Admin/.test(windowTitle(win)), `/smpeco admin opens the GUI (${windowTitle(win)})`)
    for (const name of ['Modules', 'NPCs', 'Starter kit', 'Reload configs', 'Start money', 'Chat prefix', 'Sidebar title',
      'Currency symbol', 'Sell multiplier', 'Job pay multiplier']) {
      assert(findSlot(win, (it) => itemLabel(it) === name) >= 0, `Button ${name}`)
    }
    assertEq(itemLabel(win.slots[SLOT_SELL]), 'Sell multiplier', 'sell multiplier on slot 29')
    const multBefore = await sellMult()
    await clickSlot(bot, SLOT_SELL)
    await sleep(600)
    assertEq(await configValue('multipliers.sell'), '1.05', 'left click: sell 1.0 → 1.05 in core.json')
    const multAfter = await sellMult()
    assert(Math.abs(multAfter - multBefore * 1.05) < 0.002, `sell factor applies immediately (${multBefore} → ${multAfter})`)
    win = bot.currentWindow
    assert(win && itemLore(win.slots[SLOT_SELL]).some((l) => /Now: 1\.05/.test(l)), 'GUI shows the new value')

    win = await expectWindow(bot, () => clickSlot(bot, SLOT_MODULES))
    assert(/Modules/.test(windowTitle(win)), `module page (${windowTitle(win)})`)
    const target = findSlot(win, (it) => /^Daily rewards/.test(itemLabel(it)))
    assert(target >= 0, 'daily rewards on the module page')
    const wasOn = JSON.parse(readFileSync(MODULES, 'utf8')).daily === true
    await sleep(400)
    await clickSlot(bot, target)
    await sleep(800)
    assertEq(JSON.parse(readFileSync(MODULES, 'utf8')).daily, !wasOn, 'click toggles daily in modules.json')
    win = bot.currentWindow
    assert(win && /Restart required/.test(itemLabel(win.slots[4])), `hint "Restart required" (${win && itemLabel(win.slots[4])})`)
    await sleep(400)
    await clickSlot(bot, target)
    await sleep(800)
    assertEq(JSON.parse(readFileSync(MODULES, 'utf8')).daily, wasOn, 'second click reverts it')
    win = bot.currentWindow
    assert(win && /No restart needed/.test(itemLabel(win.slots[4])), 'no restart needed any more')
    bot.closeWindow(win)
  } finally {
    await rcon(`smpeco config set multipliers.sell ${before.sell ?? '1.0'}`)
    await rcon(`deop ${BOT}`)
    assertEq(await configValue('multipliers.sell'), before.sell, 'multiplier reset')
    const norm = (txt) => { const o = JSON.parse(txt); delete o._info; return JSON.stringify(Object.keys(o).sort().map((k) => [k, o[k]])) }
    assertEq(norm(readFileSync(MODULES, 'utf8')), norm(before.modules), 'modules.json content unchanged')
  }
})
