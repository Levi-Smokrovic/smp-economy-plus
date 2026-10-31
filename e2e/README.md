# End-to-end tests

The tests drive a test server with [mineflayer](https://github.com/PrismarineJS/mineflayer) bots and RCON. They change the
world and mod data, so only point them at a throwaway server.

## Test server

1. Install a Fabric server for the Minecraft version under test. Put the mod jar from `tools/build-all.sh`, Fabric API and
   LuckPerms in `mods/`.
2. In `server.properties` set `online-mode=false`, `enable-rcon=true`, `server-port=25631`, `rcon.port=25632` and
   `rcon.password=smpeco`.
3. Add this `start.sh` to the server folder. The restart tests run it, and without the flag `/smpeco selftest` does not exist:
   ```sh
   #!/bin/sh
   cd "$(dirname "$0")"
   exec java -Xmx2G -Dsmpeconomy.selftest=true -jar fabric-server-launch.jar nogui
   ```
4. Enable the extra modules in `config/smpeconomy/modules.json`. `run-all.mjs` skips the tests of modules that are off.

## Run

```sh
cd e2e && npm ci
E2E_SERVER_DIR=/path/to/server node run-all.mjs          # add feature names to run only those
node run-all.mjs --no-restart                         # skips the tests that restart the server
```

## Environment

- `E2E_HOST`, `E2E_PORT`: server address, default `127.0.0.1`, `25631`
- `E2E_RCON_PORT`, `E2E_RCON_PASSWORD`: default `25632`, `smpeco`
- `E2E_MC_VERSION`: bot client version, default `1.21.11`; use `26.1` for a 26.1.2 server
- `E2E_TICK_END=1`: required for 26.2 and 26.3, together with `E2E_MC_VERSION=26.1` and ViaFabric, ViaVersion and
  ViaBackwards on the server, because mineflayer has no protocol for them
- `E2E_NO_RESTART=1`: same as `--no-restart`
- `E2E_SERVER_DIR`: server folder. Required for the restart tests and for scripts that read the server's log or config files
- `E2E_WHEEL_RESTART=1`, `E2E_LOTTO_RESTART=1`: also run the slow restart parts of `casino-wheel.mjs` and `events.mjs`, which are off by default
- `E2E_RESTORE=1`: runs `restore.mjs` even if its weekly run is not due
- `E2E_FUZZ_AT=<n>`: forces rotation `n` of `crash-fuzz.mjs` without changing the counter
- `E2E_MONEY_CMD`: command `events.mjs` uses to set a balance, default `eco set {name} {amount}`
- `E2E_MINIGAMES_ONLY=mega`: runs only the mega challenge part of `minigames.mjs`
- `E2E_TEXT_DUMP=<file>`: appends all text a bot gets to read to that file
- `E2E_SERVER_JAR_PATTERN`: `pgrep -f` pattern for the Java process, default `fabric-(server|launcher)`
