# 10 — Build, Validation, Tests and Deployment

## 1. Toolchain

| Item | Value |
| --- | --- |
| Build system | Gradle 9.2 wrapper |
| Minecraft plugin | RetroFuturaGradle `2.0.6` |
| Minecraft | `1.7.10` |
| Mappings | MCP stable `12` |
| Compile Java | 8 (`sourceCompatibility`/`targetCompatibility`) |
| Gradle daemon | Java 25 (`graalvm-jdk-25` on this server) |
| OpenComputers | `1.12.70-GTNH` compile-only, deobfuscated by RFG |
| Lombok | `1.18.38` compile + annotation processor |
| Guava | `17.0` compile-only |
| Scala library | `2.11.1` compile-only |
| JUnit | `4.13.2` |

`build.sh` exports `JAVA_HOME=/usr/lib/jvm/graalvm-jdk-25` when present and runs
`./gradlew build`.

## 2. Standard commands

```bash
./build.sh                 # validate + test + reobf + deploy
./gradlew build            # same without the wrapper env setup
./gradlew compileJava      # compile only (MCP names)
./gradlew test             # JUnit
./gradlew validate         # structure + line limit + JS syntax
./gradlew reobfJar         # production SRG-named jar
./gradlew deploy           # reobf + copy to modsDir
./gradlew clean build
```

`build` depends on `validate` and `deploy`; it always runs tests and reobfuscates.

## 3. Validation task

`validate` enforces:

1. **Java line limit** — every `.java` file ≤ **696 lines**.
   The cap exists to keep files modular; split helpers/services rather than
   raising it.
2. **Package/directory consistency** — declared package must equal the directory
   path under `src/main/java`.
3. **JavaScript syntax** — `/usr/local/bin/node --check` for every
   `src/main/resources/**/*.mjs`.

Failure prints each problem and fails the build. Output marker:
`build/validate.done`.

## 4. Compiler settings

`compileJava`:

- UTF-8;
- `-Xlint:deprecation -Xlint:unchecked -Werror -Xlint:-options`;
- Java 8 source/target.

Warnings are errors. Do not suppress a warning unless the code is intentionally
using a legacy API and the suppression is local.

## 5. Tests

| Test | Covers |
| --- | --- |
| `action/ActionRegistryTest` | one-shot context release, retained ticking, newer invocation cancels previous, cancelAll, duplicate operation rejection, unknown operation rejection |
| `harness/MasterToolSurfaceTest` | exact master tool list, no removed composite tools, unique names |
| `harness/SkillLibraryTest` | bundled skill loading/search |
| `harness/TaskIntentClassifierTest` | deterministic intent classification |
| `harness/TaskSessionTest` | session lifecycle/notes/age |

Run:

```bash
./gradlew test
```

Tests are Java 8 and must not require a live Minecraft server.

## 6. Packaging

`jar` manifest includes Implementation-Title/Version/Vendor. The reobfuscated
artifact is `build/libs/CortexDrone-1.4.0.jar` (SRG names for production).

## 7. Deploy task

`deploy`:

1. depends on `reobfJar`;
2. resolves `modsDir` from `gradle.properties` or `-PmodsDir=...`
   (default `/root/forge-smp/mods`);
3. creates the directory;
4. deletes old `CortexDrone-*.jar` files except the current output;
5. copies the reobfuscated jar;
6. prints the deployed path.

Examples:

```bash
./gradlew build
./gradlew build -PmodsDir=/path/to/mods
./gradlew deploy
```

## 8. Server operations

Typical flow on this host:

```bash
cd /root/forge-smp/cortexdrone-src
./gradlew clean build
# server restart (tmux session "forge-smp")
tmux send-keys -t forge-smp "restart 10" Enter
# wait for startup
tail -f /root/forge-smp/logs/latest.log
curl -s http://127.0.0.1:25580/state | head
```

Startup is complete when the world log contains:

```text
CortexDrone v1.4.0 (Master-Tool Code Mode Harness) initialized
```

The mod writes `world/layercloud-drone-debug.log` for its own categories.

## 9. Runtime prerequisites

- OpenComputers installed on the server.
- Node.js at `/usr/local/bin/node` (path is hardcoded in `CodeModeRuntime`).
- Writable `/tmp/opencode` (or `/tmp`) for temporary `.mjs` scripts.
- LLM gateway reachable at `AIConfig.apiUrl` (default
  `http://127.0.0.1:7002/v1/chat/completions`).
- Port `25580` free on loopback (HTTP).
- World directory writable for config/skills/debug log.

## 10. Environment variables

| Variable | Effect |
| --- | --- |
| `CORTEXDRONE_API_URL` | LLM chat-completions URL |
| `CORTEXDRONE_MODEL` | model ID |
| `CORTEXDRONE_API_KEY` | bearer key |
| `CORTEXDRONE_TICK_WARN_MS` | slow-tick warning threshold (default 50 ms) |
| `CORTEXDRONE_WARN_COOLDOWN_MS` | warning cooldown (default 30000 ms) |

Config file values fill in when env vars are absent; env wins when set.

## 11. Common failures

| Symptom | Check |
| --- | --- |
| `validate` fails | file >696 lines, package/dir mismatch, `.mjs` syntax |
| Compile fails on Lombok | annotation processor / Java 8 toolchain |
| Drones do not move | OC machine state, navigation session, `STUCK` log |
| LLM errors | API URL/key/model, `/debug`, `LLM` log category |
| JS timeout | Node path, `/tmp` writable, script deadlock, 180 s budget |
| HTTP 504 | RPC method exceeded budget; check server thread |
| Large scan busy | another scan active; poll `jobId` |
| No skill results | `skills/index.json` files present; world skill override |
| Lighting not restored | `lightCells` state; shutdown/despawn restore path |

## 12. Release checklist

1. Update `gradle.properties` version if needed (`CortexDroneMod.VERSION` too).
2. Update `ARCHITECTURE.md` / docs if architecture changed.
3. `./gradlew clean build` (validate + tests + reobf + deploy).
4. Restart server and verify init + `/state`.
5. Test one task in-game or via `/rpc` if a drone/player is available.
6. Check `world/layercloud-drone-debug.log` for `ERROR`/`WARN`.
7. Commit/push if working in the git repository.
