// Lists all top-level commands of the running test server (vanilla and all mods) from the declare_commands
// packet an op bot receives. Run from e2e/: node ../tools/list-commands.mjs [bot-name] > commands.txt
import mineflayer from '../e2e/node_modules/mineflayer/index.js'
import { HOST, PORT, VERSION, rcon, sleep, fixResourcePackAnswer } from '../e2e/lib.mjs'

const name = process.argv[2] ?? 'BotCmdDump'
await rcon(`op ${name}`)
const bot = mineflayer.createBot({ host: HOST, port: PORT, username: name, version: VERSION, auth: 'offline' })
fixResourcePackAnswer(bot) // accept a required resource pack on login
const packets = []
bot._client.on('declare_commands', (p) => packets.push(p))
await new Promise((res) => bot.once('spawn', res))
await sleep(3000)
await rcon(`deop ${name}`)
const all = new Set()
for (const p of packets) {
  const root = p.nodes[p.rootIndex]
  for (const i of root.children) {
    const n = p.nodes[i]?.extraNodeData
    const nm = typeof n === 'string' ? n : n?.name
    if (nm) all.add(nm)
  }
}
console.log([...all].sort().join('\n'))
bot.quit()
await sleep(300)
process.exit(0)
