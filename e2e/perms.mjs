// Permissions through real LuckPerms nodes (apply-sponge-implicit-wildcards = true), not only op and deop.
// Driven with "lp user <bot> ..." on this bot's nodes only, never groups. Checks what the player really gets:
// the command tree from the declare_commands packet, replies to real commands, and GUIs. Covers
// smpeconomy.admin.core only, smpeconomy.admin and smpeconomy.* (same mod commands as an op), admin with a
// negated child, negated shop, sell and auction nodes, .others nodes, and admin.news without admin.prices.
import {
  connectBot, rcon, moduleEnabled, runE2E, assert, assertEq, sleep, waitForMessage, expectWindow,
  clickSlot, findSlot, itemLabel, itemLore, windowTitle, closeWindow, cmd, countItem, walletCents,
} from './lib.mjs'

const P = 'BotPermsA'
const Q = 'BotPermsB' // second known player, for .others
const SENTINEL = 'playtime' // marker command (core module stats); left out of comparisons
const SENTINEL_NODE = 'smpeconomy.playtime'
const DENIED = /Unknown or incomplete command|Incorrect argument for command/

// mod root commands, aliases included; only these are compared with the op tree
const OUR_ROOTS = [
  'menu', 'commands', 'balance', 'bal', 'money', 'pay', 'paytoggle', 'baltop', 'treasury', 'sell', 'worth',
  'shop', 'ah', 'auction', 'market', 'orders', 'jobs', 'job', 'tasks', 'missions', 'daily',
  'crates', 'afkzone', 'coinflip', 'cf', 'casino', 'slots', 'mines', 'hilo',
  'blackjack', 'bj', 'roulette', 'dice', 'crash', 'plinko', 'wheel', 'scratch', 'horserace', 'chatgame', 'treasure', 'lottery', 'event', 'rank',
  'news', 'bounty', 'bank', 'clan', 'cc', 'stats', 'playtime', 'leaderboard', 'settings',
  'sidebar', 'eco', 'holo', 'minigames', 'smpeco',
]
// /smpeco subcommands that need only smpeconomy.admin.core (core/SmpecoCommand)
const CORE_ONLY = new Set(['reload', 'save', 'status', 'selftest', 'placed', 'notify', 'price', 'pricemod', 'treasury', 'guitest'])
const DLG = { back: 45, mode: 47, action: 49 }

/** Tree from the packet: Map name to { exec, redirect, children }; redirects are not followed. */
function parseTree(packet) {
  const nodes = packet.nodes ?? []
  const build = (index, depth) => {
    const out = new Map()
    if (depth > 8) return out
    for (const c of nodes[index]?.children ?? []) {
      const child = nodes[c]
      const name = child?.extraNodeData?.name
      if (name == null) continue
      out.set(name, {
        exec: !!child.flags?.has_command,
        redirect: !!child.flags?.has_redirect_node,
        children: build(c, depth + 1),
      })
    }
    return out
  }
  return build(packet.rootIndex, 0)
}

function attachTree(bot) {
  bot.cmdTrees = 0
  bot._client.on('declare_commands', (packet) => {
    try {
      bot.cmdTree = parseTree(packet)
      bot.cmdTrees++
    } catch (err) {
      console.log(`[E2E] WARN command tree not readable: ${err.message}`)
    }
  })
}

function node(tree, ...path) {
  let cur = { children: tree }
  for (const p of path) {
    cur = cur?.children?.get(p)
    if (!cur) return null
  }
  return cur
}

const has = (tree, ...path) => !!node(tree, ...path)

function execPaths(tree, roots, maxDepth = 6) {
  const out = new Set()
  const walk = (map, prefix, depth) => {
    for (const [name, n] of map) {
      const path = prefix ? `${prefix} ${name}` : name
      if (n.exec || n.redirect) out.add(path)
      if (depth < maxDepth) walk(n.children, path, depth + 1)
    }
  }
  for (const r of roots) {
    const n = tree?.get(r)
    if (!n) continue
    if (n.exec || n.redirect) out.add(r)
    walk(n.children, r, 1)
  }
  return out
}

function diff(a, b) {
  return { onlyA: [...a].filter((x) => !b.has(x)).sort(), onlyB: [...b].filter((x) => !a.has(x)).sort() }
}

async function waitFor(fn, timeoutMs, stepMs = 100) {
  const end = Date.now() + timeoutMs
  for (;;) {
    const v = await fn()
    if (v) return v
    if (Date.now() > end) return v
    await sleep(stepMs)
  }
}

async function reply(bot, text, regex, timeoutMs = 6000) {
  const p = waitForMessage(bot, regex, timeoutMs).then((m) => m, () => null)
  await cmd(bot, text)
  return p
}

async function ahActive(name) {
  const m = (await rcon(`ah admin info ${name}`)).match(/active=(\d+)/)
  if (!m) throw new Error(`ah admin info ${name}`)
  return Number(m[1])
}

async function expectNoWindow(bot, action, ms = 1200) {
  let opened = false
  const onOpen = () => { opened = true }
  bot.on('windowOpen', onOpen)
  await action()
  await sleep(ms)
  bot.off('windowOpen', onOpen)
  return !opened
}

async function hold(bot, itemName) {
  await waitFor(() => bot.inventory.items().some((it) => it.name === itemName), 5000)
  const item = bot.inventory.items().find((it) => it.name === itemName)
  if (!item) throw new Error(`${bot.username}: ${itemName} not in inventory`)
  await bot.equip(item, 'hand')
  await sleep(300)
}

async function give(name, item, n) {
  await rcon(`give ${name} ${item} ${n}`)
  if (!(await waitFor(async () => (await countItem(name, item)) >= n, 5000, 200))) throw new Error(`give ${name} ${item} ${n} had no effect`)
}

/**
 * Toggles the marker last. LuckPerms works through its commands in order, so all earlier changes are active
 * once the tree shows the new marker.
 */
async function lp(bot, ...changes) {
  const visibleNow = has(bot.cmdTree, SENTINEL)
  for (const c of changes) await rcon(`lp user ${P} ${c}`)
  await rcon(`lp user ${P} permission ${visibleNow ? `set ${SENTINEL_NODE} false` : `unset ${SENTINEL_NODE}`}`)
  const ok = await waitFor(() => bot.cmdTree && has(bot.cmdTree, SENTINEL) !== visibleNow, 10000)
  if (!ok) throw new Error(`LuckPerms: change (${changes.join(' · ') || 'marker'}) did not reach the command tree after 10 s`)
  await sleep(800) // let buffered tree sends (500 ms) settle
}

async function setOp(bot, on) {
  await rcon(`${on ? 'op' : 'deop'} ${P}`)
  const ok = await waitFor(() => bot.cmdTree && has(bot.cmdTree, 'smpeco') === on, 8000)
  if (!ok) throw new Error(`${on ? 'op' : 'deop'} ${P}: command tree unchanged`)
  await sleep(500)
}

async function cleanupPlayer() {
  for (const c of [`lp user ${P} clear`, `deop ${P}`, `clear ${P}`, `eco set ${P} 0`, `ah admin purge ${P}`]) {
    try { await rcon(c) } catch (err) { console.log(`[E2E] INFO cleanup "${c}": ${err?.message ?? err}`) }
  }
}

runE2E(async () => {
  const probe = await rcon('lp')
  if (DENIED.test(probe)) {
    assert(false, `LuckPerms missing on the test server (reply to "lp": ${probe.trim().slice(0, 80)})`)
    return
  }
  assert(true, 'LuckPerms present on the test server')

  let newsStarted = false // (9) starts a market shock, which must be ended
  const bot = await connectBot(P, { setup: attachTree })
  await connectBot(Q) // stays connected: known name for /balance <player> and /stats <player>
  try {
    await cleanupPlayer()
    await rcon(`gamemode survival ${P}`)
    await waitFor(() => !!bot.cmdTree, 5000)
    await lp(bot)
    await lp(bot, 'clear')

    let t = bot.cmdTree
    assert(has(t, 'sell') && has(t, 'shop') && has(t, 'menu') && has(t, 'ah', 'sell'), 'no nodes: player commands visible (allowed by default)')
    assert(!has(t, 'smpeco') && !has(t, 'eco') && !has(t, 'holo') && !has(t, 'minigames'), 'no nodes, no op: no admin commands in the tree')
    assert(!!(await reply(bot, '/smpeco treasury', DENIED)), 'no nodes: /smpeco denied')
    assert(!!(await reply(bot, `/balance ${Q}`, new RegExp(`${Q}'s balance`))), 'no nodes: /balance <other> allowed (default)')

    await setOp(bot, true)
    const opPaths = execPaths(bot.cmdTree, OUR_ROOTS.filter((r) => r !== SENTINEL))
    const opSmpeco = [...(node(bot.cmdTree, 'smpeco')?.children?.keys() ?? [])]
    await setOp(bot, false)
    assert(opPaths.size >= 40 && opPaths.has('smpeco sell balance player') && opPaths.has('eco give player amount'),
      `op reference: ${opPaths.size} executable mod paths`)
    const featureBranches = opSmpeco.filter((n) => !CORE_ONLY.has(n))
    assert(featureBranches.length >= 5, `/smpeco has feature branches (${featureBranches.join(', ')})`)

    await lp(bot, 'clear', 'permission set smpeconomy.admin.core true')
    t = bot.cmdTree
    assert(has(t, 'smpeco', 'treasury') && has(t, 'smpeco', 'selftest'), 'admin.core: /smpeco root visible')
    const coreExec = [...execPaths(t, ['smpeco'])].filter((p) => p !== 'smpeco')
    const leaked = coreExec.filter((p) => !CORE_ONLY.has(p.split(' ')[1]))
    assertEq(leaked, [], 'admin.core: no executable /smpeco <feature> branch')
    assert(!has(t, 'eco') && !has(t, 'holo') && !has(t, 'minigames'), 'admin.core: /eco, /holo, /minigames not visible')
    assert(!!(await reply(bot, '/smpeco treasury', /treasury balance=\d+/)), 'admin.core: /smpeco treasury responds')
    const sellDenied = await reply(bot, `/smpeco sell balance ${P}`, new RegExp(`${DENIED.source}|sell balance=`))
    assert(!!sellDenied && DENIED.test(sellDenied[0]), `admin.core: /smpeco sell … denied (${sellDenied?.[0] ?? 'no reply'})`)
    const m1 = await walletCents(P)
    assert(!!(await reply(bot, `/eco give ${P} 5`, DENIED)), 'admin.core: /eco give denied')
    assertEq(await walletCents(P), m1, 'admin.core: no money through /eco')

    await lp(bot, 'clear', 'permission set smpeconomy.admin true')
    let d = diff(opPaths, execPaths(bot.cmdTree, OUR_ROOTS.filter((r) => r !== SENTINEL)))
    assert(d.onlyA.length === 0 && d.onlyB.length === 0,
      `smpeconomy.admin: same mod commands as op (missing: ${d.onlyA.slice(0, 12).join(' | ') || '-'}; extra: ${d.onlyB.slice(0, 12).join(' | ') || '-'})`)
    assert(!!(await reply(bot, `/smpeco sell balance ${P}`, /sell balance=\d+/)), 'smpeconomy.admin: /smpeco sell balance responds')
    assert(!!(await reply(bot, '/smpeco treasury', /treasury balance=\d+/)), 'smpeconomy.admin: /smpeco treasury responds')

    await lp(bot, 'clear', 'permission set smpeconomy.* true')
    d = diff(opPaths, execPaths(bot.cmdTree, OUR_ROOTS.filter((r) => r !== SENTINEL)))
    assert(d.onlyA.length === 0 && d.onlyB.length === 0,
      `smpeconomy.*: same mod commands as op (missing: ${d.onlyA.slice(0, 12).join(' | ') || '-'}; extra: ${d.onlyB.slice(0, 12).join(' | ') || '-'})`)

    await lp(bot, 'clear', 'permission set smpeconomy.admin true', 'permission set smpeconomy.admin.eco false')
    t = bot.cmdTree
    assert(!has(t, 'eco') && has(t, 'smpeco', 'treasury') && has(t, 'holo'), 'admin + admin.eco false: only /eco blocked')
    const m4 = await walletCents(P)
    assert(!!(await reply(bot, `/eco give ${P} 5`, DENIED)), 'admin + admin.eco false: /eco give denied')
    assertEq(await walletCents(P), m4, 'admin + admin.eco false: no money through /eco')

    await lp(bot, 'clear')
    await rcon(`clear ${P}`)
    await give(P, 'minecraft:stone', 16)
    await rcon(`eco set ${P} 0`)
    let win = await expectWindow(bot, () => cmd(bot, '/shop blocks'))
    let stoneSlot = findSlot(win, 'stone', { to: 45 })
    assert(stoneSlot >= 0, 'Shop: stone in "Building Blocks"')
    if (stoneSlot >= 0) {
      assert(itemLore(win.slots[stoneSlot]).some((l) => /Right-click » Sell/.test(l)), 'shop with permission: lore "Right-click » Sell"')
      // open the sell dialog while the permission is still there, revoke it, and only then click
      const dlg = await expectWindow(bot, () => clickSlot(bot, stoneSlot, 1, 0))
      assert(/Sell/.test(windowTitle(dlg)), `sell dialog open (${windowTitle(dlg)})`)
      await lp(bot, 'permission set smpeconomy.shop.sell false')
      const refused = waitForMessage(bot, /not allowed to sell in the shop/, 5000).then(() => true, () => false)
      await clickSlot(bot, DLG.action)
      assert(await refused, 'shop.sell false: open sell dialog refuses the sale')
      await sleep(300)
      assertEq([await countItem(P, 'minecraft:stone'), await walletCents(P)], [16, 0], 'shop.sell false: stone stays, no money')

      win = await expectWindow(bot, () => clickSlot(bot, DLG.back))
      stoneSlot = findSlot(win, 'stone', { to: 45 })
      const lore = stoneSlot >= 0 ? itemLore(win.slots[stoneSlot]) : []
      assert(lore.some((l) => /Left-click » Buy/.test(l)) && !lore.some((l) => /Right-click » Sell/.test(l)),
        `shop.sell false: lore without "Right-click » Sell" (${lore.filter((l) => /click/.test(l)).join(' | ')})`)
      if (stoneSlot >= 0) {
        const msg = waitForMessage(bot, /not allowed to sell in the shop/, 5000).then(() => true, () => false)
        assert(await expectNoWindow(bot, () => clickSlot(bot, stoneSlot, 1, 0)), 'shop.sell false: right click opens no sell dialog')
        assert(await msg, 'shop.sell false: right click reports "not allowed to sell in the shop"')
        assertEq([await countItem(P, 'minecraft:stone'), await walletCents(P)], [16, 0], 'shop.sell false: right click sells nothing')
        await sleep(350)
        const buyDlg = await expectWindow(bot, () => clickSlot(bot, stoneSlot))
        assert(/Buy/.test(windowTitle(buyDlg)), `buy dialog (${windowTitle(buyDlg)})`)
        assert(!/Sell/.test(itemLabel(buyDlg.slots[DLG.mode])), `shop.sell false: no switch to "Sell" (slot ${DLG.mode}: ${itemLabel(buyDlg.slots[DLG.mode]) || 'empty'})`)
      }
    }
    await closeWindow(bot)

    await lp(bot, 'clear', 'permission set smpeconomy.sell false')
    await rcon(`clear ${P}`)
    await give(P, 'minecraft:wheat', 8)
    await hold(bot, 'wheat')
    t = bot.cmdTree
    assert(!has(t, 'sell') && has(t, 'worth') && has(t, 'shop'), 'sell false: /sell not in the tree, /worth and /shop are')
    assert(!!(await reply(bot, '/sell hand', DENIED)), 'sell false: /sell hand unknown')
    await sleep(300)
    assertEq([await countItem(P, 'minecraft:wheat'), await walletCents(P)], [8, 0], 'sell false: wheat stays, no money')
    win = await expectWindow(bot, () => cmd(bot, '/menu'))
    assert(/Main Menu/.test(windowTitle(win)), `main menu open (${windowTitle(win)})`)
    assertEq([win.slots[11]?.name, itemLabel(win.slots[11])], ['gray_dye', 'Sell'], 'sell false: menu icon "Sell" gray (slot 11)')
    assert(itemLore(win.slots[11]).some((l) => /Not available right now/.test(l)), 'sell false: Lore "Not available right now."')
    assertEq(win.slots[10]?.name, 'emerald', 'sell false: shop icon stays active')
    await closeWindow(bot)
    const trading = await reply(bot, '/commands', /Trading: .*/, 8000)
    assert(!!trading && /\/shop/.test(trading[0]) && /\/worth/.test(trading[0]) && !/\/sell\b/.test(trading[0]),
      `sell false: /commands without /sell (${trading?.[0] ?? 'no line'})`)

    await lp(bot, 'clear', 'permission set smpeconomy.ah.sell false')
    await rcon(`clear ${P}`)
    await give(P, 'minecraft:rabbit_hide', 3)
    await hold(bot, 'rabbit_hide')
    t = bot.cmdTree
    assert(has(t, 'ah') && !has(t, 'ah', 'sell') && has(t, 'ah', 'my'), 'ah.sell false: /ah without "sell"')
    assert(has(t, 'auction') && !has(t, 'auction', 'sell') && has(t, 'auction', 'my'),
      'ah.sell false: the alias /auction also without "sell"')
    assert(!!(await reply(bot, '/ah sell 100', DENIED)), 'ah.sell false: /ah sell denied')
    await sleep(300)
    assertEq([await ahActive(P), await countItem(P, 'minecraft:rabbit_hide')], [0, 3], 'ah.sell false: nothing listed, items stay')
    win = await expectWindow(bot, () => cmd(bot, '/ah'))
    assert(/Auction House/.test(windowTitle(win)), 'ah.sell false: /ah still opens')
    await closeWindow(bot)

    await lp(bot, 'clear', 'permission set smpeconomy.balance.others false', 'permission set smpeconomy.stats.others false')
    assert(has(bot.cmdTree, 'balance') && !has(bot.cmdTree, 'balance', 'player'),
      'balance.others false: /balance without player argument')
    assert(!!(await reply(bot, '/balance', /Balance: \$/)), 'balance.others false: own balance works')
    const other1 = await reply(bot, `/balance ${Q}`, new RegExp(`${DENIED.source}|${Q}'s balance`))
    assert(!!other1 && DENIED.test(other1[0]), `balance.others false: other balance denied (${other1?.[0] ?? 'no reply'})`)
    const statsMsg = waitForMessage(bot, /You can only view your own stats/, 6000).then(() => true, () => false)
    assert(await expectNoWindow(bot, () => cmd(bot, `/stats ${Q}`), 1500), 'stats.others false: no foreign statistics GUI')
    assert(await statsMsg, 'stats.others false: message "only your own statistics"')
    const own = await expectWindow(bot, () => cmd(bot, '/stats'))
    assert(!!own, 'stats.others false: own statistics open')
    await closeWindow(bot)

    // (9) Targeted market shocks (item, percent, duration) affect /sell for all players, like /smpeco pricemod.
    // The random variant is not run, since it could start an NPC bulk purchase that /news clear does not end.
    if (!(await moduleEnabled('news'))) {
      console.log('[E2E] INFO (9) skipped: module news is off')
      return
    }
    await lp(bot, 'clear', 'permission set smpeconomy.admin.news true')
    t = bot.cmdTree
    assert(!!node(t, 'news', 'trigger')?.exec && has(t, 'news', 'clear') && has(t, 'news', 'status'), 'admin.news: /news trigger|clear|status visible')
    for (const c of ['/news trigger minecraft:sugar_cane +1000 30', '/news trigger minecraft:sugar_cane']) {
      const r = await reply(bot, c, /smpeconomy\.admin\.prices|Started/)
      assert(!!r && /smpeconomy\.admin\.prices/.test(r[0]), `admin.news without admin.prices: "${c}" denied (${r?.[0] ?? 'no reply'})`)
    }
    assert(!/item=minecraft:sugar_cane/.test(await rcon('news status')), 'admin.news without admin.prices: no market shock on sugar cane')
    await lp(bot, 'permission set smpeconomy.admin.prices true')
    newsStarted = true
    const okNews = await reply(bot, '/news trigger minecraft:sugar_cane +50 1', /smpeconomy\.admin\.prices|Started: minecraft:sugar_cane/)
    assert(!!okNews && /Started: minecraft:sugar_cane/.test(okNews[0]), `admin.news + admin.prices: targeted market shock works (${okNews?.[0] ?? 'no reply'})`)
  } finally {
    await closeWindow(bot)
    if (newsStarted) {
      try { await rcon('news clear') } catch (err) { console.log(`[E2E] INFO news clear: ${err?.message ?? err}`) }
    }
    await cleanupPlayer()
  }
}, { timeoutMs: 240000 })
