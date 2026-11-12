# CortexDrone

**An autonomous OpenComputers drone operator for Minecraft 1.7.10 — driven by an LLM with native tool calling, an in-process JavaScript Code Mode, and a self-expanding skill library.**

CortexDrone turns a player request typed in chat into real drone work: flying, mining, building, farming, fighting, sorting chests, using machines, and reporting back with evidence. The model does not emit a JSON command envelope and there is no separate intent bot. It receives native tool schemas, decides, executes, verifies, and remembers.

<p>
  <img alt="Minecraft" src="https://img.shields.io/badge/Minecraft-1.7.10-62b47a">
  <img alt="Forge" src="https://img.shields.io/badge/Forge-1.7.10-1f6feb">
  <img alt="OpenComputers" src="https://img.shields.io/badge/OpenComputers-1.12.70--GTNH-8957e5">
  <img alt="Java" src="https://img.shields.io/badge/Java-8-orange">
  <img alt="Version" src="https://img.shields.io/badge/version-2.5.0-blue">
</p>

---

## What it is

CortexDrone is a server-side Forge mod that binds an AI agent to an OpenComputers drone.

- The player talks to the drone with `@drone ...` or `@<droneName> ...`.
- The agent inspects the live world, plans, and writes a JavaScript workflow.
- That workflow runs **inside the Minecraft server JVM** on Mozilla Rhino and calls the same tool registry the model sees.
- Results come back as real evidence: coordinates, counts, block ids, item stacks, navigation outcomes.
- Successful workflows can be compiled into reusable, parameterized skills. Facts, named locations, and hard-won lessons persist across sessions.

There is **no Node.js process, no temp script file, no RPC hop, and no hidden instruction queue.**

---

## Highlights

- **Native OpenAI tool calling** — 74 declared tools sent as real `tools[]` schemas, with results appended as `tool` messages.
- **Code Mode** — one `execute` script performs a whole multi-step workflow inside the JVM instead of dozens of slow round trips.
- **RLM skill compilation** — successful trajectories are recursively rewritten into stable, parameterized skills that can call each other.
- **Persistent memory layers** — facts and named places (`memory`), procedural rules (`lesson`), and executable workflows (`skill`).
- **Real navigation** — A* flight, trail backtracking, detours, tunnelling as a last resort, on-demand chunk loading, configurable speed up to 5×, passenger safety.
- **World understanding** — block/ore scans, vein clustering, room scans, chest and machine queries, OreDictionary resolution, recipe introspection, structure planning.
- **OpenComputers integration** — read live OC state, introspect component methods, call callbacks, start/stop/pause machines.
- **Evidence-oriented safety** — reach and line-of-sight checks, dig-with-a-floor rules, container protection, bounded dig budgets, `repair_trail`.
- **Durable sessions** — every task is journaled as JSON + Markdown under an outcome directory.
- **Server-thread discipline** — LLM and JavaScript run off-thread; every world mutation crosses an explicit `main(fn)` bridge.

---

## Execution model

```text
Minecraft chat: @drone <message> or @<droneName> <message>
        |
        v
TaskCoordinator          one live task per drone; later messages join the same generation
        |
        v
AgentService             system prompt + live context + native tool loop
        |
        +--> LlmClient     OpenAI-compatible /chat/completions with tool_calls
        |
        +--> ToolInvoker   schema validation, thread routing, timeouts, cancellation
                |
                +--> CodeTools -> JsEngine (Rhino) -> drone / tools / skills globals
                |
                +--> Tool handlers -> managers -> atomic actions
                                            |
                                            v
                              Minecraft world / OC drone / inventories / chunks
```

The agent works in four phases:

1. **Recon** — read position, inventory, blocks, chests, recipes, player aim, memory, lessons.
2. **Plan** — choose coordinates, counts, order, abort conditions, and a verification signal.
3. **Act** — execute the whole plan in one script, with per-step verification and retries.
4. **Verify + learn** — report evidence, then refine a skill or record a lesson if the workflow was new.

---

## Tech stack

| Layer | Technology |
|---|---|
| Game | Minecraft 1.7.10 |
| Mod loader | Minecraft Forge (1.7.10) |
| Host API | OpenComputers `1.12.70-GTNH` |
| Language | Java 8 |
| Build | RetroFuturaGradle `2.0.6`, Gradle 9.2, JDK 25 build daemon, JDK 8 compile toolchain |
| Script runtime | Mozilla Rhino `1.7.15` (newest Rhino that still emits Java 8 bytecode) |
| Script surface | ES6-ish JavaScript with a TypeScript eraser and compatibility preprocessor |
| LLM transport | Plain `HttpURLConnection` against any OpenAI-compatible chat-completions gateway |
| Serialization | Gson |
| Boilerplate | Lombok |
| Tests | JUnit 4 — 635+ tests |
| Optional client | Xaero minimap markers and an `ask_player` answer screen |

---

## Code Mode

`execute` is the primary tool for multi-step work. It runs a JavaScript function body in an **in-process** Rhino scope. All drone tools are synchronous; `await` is accepted for model convenience but adds nothing.

```js
const found = drone.scanBlocks({ filter: "iron_ore", radius: 24 });
if (!found.count) return { ok: false, error: "no iron ore within 24m" };

let broken = 0;
for (var i = 0; i < found.blocks.length && i < 8; i++) {
  const b = found.blocks[i];
  const r = drone.breakBlock({ x: b.x, y: b.y, z: b.z });
  if (r.ok !== false && r.broken) broken++;
}

const p = drone.getPlayer();
drone.goto({ x: p.blockX, y: p.blockY + 2, z: p.blockZ });
return { broken: broken, given: drone.giveToPlayer({ filter: "iron_ore", count: 512 }) };
```

### Script globals

| Global | Purpose |
|---|---|
| `drone.<tool>(args)` | Every registered tool, in camelCase or snake_case, object args or positional args |
| `tools.call(name, args)` / `tools.list()` | Registry escape hatches |
| `skills.<name>(params)` | Every stored skill as a callable function |
| `skills.search/load/call/run/fn/names` | Discovery, lazy loading, runtime invocation |
| `ctx` | `droneId`, `droneName`, `owner`, `player`, `loopId`, `toolCount` |
| `log(...)` / `console.log(...)` | Buffered script logs returned in the result |
| `sleep(ms)` | Pause, capped at 10 seconds; tools already wait for completion |
| `hsvToRgb(h, s, v)` | LED colour helper |
| `main(fn)` | Run a callback on the Minecraft server thread and return its value |
| `Java.type(...)`, `Java.from`, `Java.to`, `importClass`, `Packages.*` | Java interop when `allowJavaReflection` is enabled |

The preprocessor handles common model syntax: `async`/`await`, optional chaining, nullish coalescing, `for (const x of ...)`, simple spread, and optional `catch` bindings. TypeScript annotations are erased before execution.

**Threading rule:** scripts run on an agent thread. Read-only inspection is safe there; any mutation of live Minecraft objects must go through `main(fn)`.

---

## RLM: recursive skill compilation

RLM in CortexDrone is the **recursive language-model step** that turns one successful run into reusable capability. It is implemented by `skill_refine`, `SkillRefiner`, `SkillCodeGuard`, and `SkillLibrary`.

```text
player task
   |
   v
agent writes + runs one execute script
   |
   v
success + evidence
   |
   v
skill_refine(task, working code, notes)
   |
   v
compiler model  --rewrites-->  name + description + triggers + tags
                               + requires + docs + parameterized code
   |
   v
SkillCodeGuard  --rejects-->  usernames, coordinates, entity ids, slots, dimensions
   |
   v
config/cortexdrone/skills/<name>/{SKILL.md, code.js}
   |
   v
BM25 retrieval -> skills.call(...) -> composition with other skills
```

Key properties:

- **The compiler is the same gateway/model**, called with a dedicated `skill-refine.md` prompt and JSON output.
- **No recordings.** Concrete values from the run must become `params`, `ctx`, or live state. Offending code is refused, not silently patched.
- **Fallback is safe.** If compilation fails, the working code is saved verbatim only when it also passes the hardcode guard.
- **Auto-save safety net.** If the model forgets to call `skill_refine`, a successful script that performed a mutating action and passed the guard is saved automatically.
- **Skills compose recursively.** A skill can call other skills through `skills.call(name, params)`; `requires` declares the dependency so retrieval and validation can check it.
- **Versioned and searchable.** Reusing a name increments its version. Search scores name, description, tags, and trigger phrases.
- **Learned skills override bundled ones** with the same normalized name.

---

## Bundled skills

48 workflows ship in `src/main/resources/skills/`, each as `SKILL.md` + `code.js`.

| Area | Skills |
|---|---|
| Mining & hauling | `mine_blocks`, `mine_box`, `mine_and_deliver`, `gather_distant`, `deliver_items`, `collect_drops` |
| Digging | `dig_staircase`, `dig_shaft`, `strip_mine`, `quarry` |
| Storage | `store_items`, `withdraw_items`, `fetch_materials`, `sort_chests`, `place_double_chest` |
| Crafting & smelting | `craft_items`, `smelt_items`, `ore_to_ingots` |
| Combat | `hunt`, `attack_to_death`, `guard_scan`, `guard`, `patrol` |
| Transport & survival | `follow_player`, `carry_player`, `transport_entity`, `escape_cave` |
| Farming | `harvest_crops`, `sugarcane_farm`, `bamboo_farm`, `melon_pumpkin_farm`, `nether_wart_farm`, `cactus_farm`, `farm_cycle`, `plant_farm`, `fertilize_crops`, `chop_tree` |
| Building | `survey_build_site`, `place_build_plan`, `build_structure`, `build_wall`, `build_platform`, `build_nether_portal`, `replace_surface`, `replace_box`, `light_area` |
| Utility | `return_to_player`, `rainbow_show` |

Skills are intentionally small. `mine_and_deliver` composes `mine_blocks` and `deliver_items`; `build_structure` composes `survey_build_site` and `place_build_plan`.

---

## Tool surface

The registry is the single source of truth. Adding a tool spec and a handler exposes it to the model, the `drone` JavaScript object, and the prompt catalog automatically.

| Domain | Tools |
|---|---|
| Session & state | `state`, `get_player`, `get_player_inventory`, `get_breadcrumbs`, `cancel` |
| Navigation | `goto`, `stop`, `find_safe_landing`, `repair_trail` |
| Blocks | `break_block`, `place_block`, `replace_block` |
| Interaction | `interact_block`, `use_item`, `attack_entity`, `pick_entity`, `unpick_entity` |
| Combat | `attack_plan`, `get_entity_health` |
| Inventory | `move_item`, `give_to_player`, `take_from_player`, `withdraw_chest`, `deposit_chest`, `drop_items`, `suck_items`, `craft`, `scan_chests`, `get_chest`, `find_item`, `find_item_in_chests` |
| World & scanning | `scan_blocks`, `poll_scan`, `scan_veins`, `scan_room`, `get_block`, `get_blocks_in_box`, `get_top_y`, `get_entities`, `get_drops`, `raycast_player`, `find_distant`, `find_build_site`, `plan_structure`, `survey_build_site`, `scan_furnaces`, `scan_machines` |
| Knowledge | `get_recipes`, `get_machine_recipes`, `get_machine_recipe`, `machine_ingredients`, `find_oredict`, `doc_lookup`, `wiki_lookup`, `web_search`, `web_fetch` |
| Memory & learning | `memory`, `lesson`, `skill`, `skill_run`, `skill_refine` |
| Feedback | `say`, `set_status_text`, `set_led`, `set_light`, `spawn_particles`, `play_sound`, `play_note` |
| Dialogue | `ask_player` |
| OpenComputers | `oc_state`, `oc_methods`, `oc_call`, `oc_machine` |
| Code | `execute` |

---

## Requirements

**Runtime**

- Minecraft 1.7.10 with Forge
- OpenComputers `1.12.70-GTNH` (or a compatible 1.7.10 build)
- Java 8 server

**Build**

- JDK 25 for the Gradle 9.2 / RetroFuturaGradle daemon
- JDK 8 available as a toolchain for compilation
- Network access to GTNH Maven and Maven Central for the first build

---

## Build

```bash
git clone https://github.com/layercloud-dev/cortexdrone.git
cd cortexdrone

# Compile, validate, test, reobfuscate, and deploy to a server
./gradlew build -PmodsDir=/path/to/server/mods
```

On Windows:

```powershell
.\gradlew.bat build "-PmodsDir=C:\path\to\server\mods"
```

Without `-PmodsDir`, the deploy task writes to `build/mods/` instead of touching a live server. The deploy step replaces the jar atomically and retires older `CortexDrone-*.jar` files from the target directory.

Useful tasks:

| Command | Purpose |
|---|---|
| `./gradlew validate` | Line-limit and package-layout validation |
| `./gradlew test` | JUnit suite |
| `./gradlew compileJava` | Compile against MCP-named Minecraft |
| `./gradlew reobfJar` | Production (SRG-named) jar |
| `./gradlew deploy -PmodsDir=...` | Reobfuscate and copy to a mods directory |
| `./build.sh` | Build wrapper that selects a JDK 25 if installed |

The build gate enforces a **400-line ceiling per Java file** and exact package/directory agreement, so large classes are split rather than allowed to grow back into monoliths.

---

## Configuration

Configuration lives in `config/cortexdrone/config.json` (never inside the world save).

```jsonc
{
  "apiUrl": "http://127.0.0.1:7002/v1/chat/completions",
  "model": "deepseek/deepseek-v4.1-flash",
  "apiKey": "",
  "maxTurns": 60,
  "taskDeadlineSeconds": 1800,
  "maxTokens": 8192,
  "temperature": 0.2,
  "executeTimeoutMs": 120000,
  "maxToolResultChars": 8000,
  "maxContextChars": 96000,
  "allowJavaReflection": true,
  "webSearchEnabled": true,
  "droneSpeedMultiplier": 5.0,
  "savedDrones": []
}
```

Environment overrides are read at startup:

| Variable | Meaning |
|---|---|
| `CORTEXDRONE_API_URL` | Chat-completions endpoint |
| `CORTEXDRONE_MODEL` | Model id |
| `CORTEXDRONE_API_KEY` | Bearer token |

In game (operator only):

```text
/drone config
/drone config url <url>
/drone config model <model>
/drone config key <key>
/drone config speed <1..5>
```

The gateway must support OpenAI-style `tools` / `tool_calls`. No JSON-envelope fallback is implemented.

---

## Usage

### Spawn and link

```text
/drone spawn [name]     spawn a drone for yourself
/drone link             link the nearest OpenComputers drone within 8 blocks
/drone list             show active drones, owners, positions
/drone status           show drone/agent status
/drone despawn [name]   remove a drone
/drone stop             cancel the running task
```

### Talk to the drone

```text
@drone mine 16 iron ore and bring it back
@drone what are you doing?
@drone remember this place as my base
@<droneName> build a 7x7 stone platform here
```

`@drone` alone is treated as `status`. If the sender has no drone, CortexDrone tries to link a nearby one and otherwise spawns one.

Messages that arrive while a task is running are **queued into the same generation**. The running model decides whether they are a question, a note, a change, or a cancellation — there is no keyword router.

### Inspect state

```text
/drone memory [list|search <q>|remember <text>|here <name>|forget <id|name>]
/drone lesson [list|search <q>|forget <id|title>]
/drone sessions [list [success|failure|...] [n]|stats|show <id>]
/drone skills [list [n]|search <q>|show <name>]
/drone debug [on|off|tail|clear]
/drone js <code>            operator-only Rhino console
```

---

## Data layout

```text
config/cortexdrone/
  config.json               LLM gateway, limits, saved drone bindings
  protected-zones.json      player-owned no-break cylinders (base, farm, ...)
  memory.json               player facts, preferences and named locations
  skills/<name>/            learned skills (SKILL.md + code.js)
  skills-disabled/<name>/   retired learned skills (not loaded)
  lessons/<id>/             learned rules (LESSON.md)
  sessions/
    index.jsonl             compact run index
    success/                JSON + Markdown transcripts
    failure/
    cancelled/
    interrupted/
    incomplete/
    error/
  logs/cortexdrone.log      debug log
```

`protected-zones.json` is deliberately separate from `config.json`: an older
build rewrites the main config from its own model on shutdown and would drop a
newly added field. The zone file is loaded in addition to any zones declared in
`config.json`, so the no-break rule survives a downgrade. A zone is a vertical
cylinder (`x`, `y`, `z`, `radius`, `verticalRadius`, `dimension`): the
horizontal radius bounds the footprint and the vertical radius covers the
base shaft up to the surface. Automatic tunnelling treats it as a wall, and
`break_block` refuses unless the current request explicitly authorises
modifying that place.

Session outcomes are classified as `success`, `failure`, `cancelled`, `interrupted`, `incomplete`, or `error`. A dropped or replaced run is still written, never lost.

---

## Architecture

See [`ARCHITECTURE.md`](ARCHITECTURE.md) for the execution path and package map.

At a glance:

```text
src/main/java/ir/layercloud/cortexdrone/
  CortexDroneMod.java       Forge entrypoint, tick events, chat hook
  AIDroneManager.java       root manager and lifecycle
  InstructionRouter.java    chat/command hand-off
  DroneTickLoop.java        per-drone tick pipeline

  action/                   atomic one-shot operations
  context/                  mutable state for one action execution
  manager/                  long-lived orchestration per domain
  service/                  read-only queries, scanning, planning
  harness/
    agent/                  LLM client, prompt, compaction, recovery
    js/                     Rhino engine, globals, interop, budgets
    tool/                   specs, registry, invoker, scan jobs
    tools/                  concrete tool handlers
    skill/                  library, refiner, code guard, search
    memory/                 facts and named locations
    lesson/                 learned rules
    session/                journal, index, transcripts
  command/                  /drone subcommands
  network/                  optional client map/question sync
  config/                   configuration, paths, logging
```

Design invariants:

- **One registry.** The model tool list, the JavaScript `drone` object, and the prompt catalog cannot drift.
- **One task per drone.** A new request either starts a generation or joins the running one.
- **No envelope parsing.** The model calls tools natively or talks to the player.
- **No hidden queue.** Chat is delivered to the active generation, and the model interprets it.
- **Capabilities are declared.** `ToolCapability` answers “does this mutate the world?”, “does this await completion?”, “is this time-sliced?” without hard-coded name lists.
- **Policy has one owner.** Inventory, scan, navigation, code, and visual limits live in their policy classes and are pinned by agreement tests.

---

## Safety and limits

- **Server thread is sacred.** World mutation goes through `main(fn)` or a main-affinity tool.
- **Bounded execution.** Per-script timeout (120 s default), per-task deadline (30 min), turn cap (60), instruction budget, cancellation checks.
- **No nested execution.** `skill_run` refuses to start inside `execute`; use `skills.call(...)` instead.
- **Time-sliced scans.** Large `scan_blocks` jobs return `pending:true` + `job_id`; `drone.scanBlocks` polls automatically.
- **Reach and sight.** Blocks are interactable within 4.5 blocks and only when visible; `block_occluded` names the covering block.
- **Protect property.** Automatic tunnelling never digs chests, furnaces, machines, or tile entities. `repair_trail` restores what navigation broke.
- **Passenger safety.** A carried player is not cargo: climb first, cruise high, descend, and land only at a verified safe spot.
- **Dig with a floor.** Shafts, staircases, tunnels, and quarries never remove the support block under the drone or player.
- **Parameterized skills only.** The hardcode guard rejects player names, coordinates, entity ids, slots, and dimensions in generated code.

---

## Testing

```bash
./gradlew test
```

The suite covers the JavaScript engine and preprocessor, tool registry and schema validation, LLM request construction and retries, session journal, memory/lesson/skill stores, the hardcode guard, navigation rules and regressions, passenger safety, inventory conservation, OreDictionary resolution, structure planning, scan limits, and bundled skill behavior.

---

## Status

`2.5.0` — actively developed. Known work items are tracked in [`TODO`](TODO):

- move HBM handling to a hook instead of a machine-specific path;
- continue expanding tests around new behavior.

---

## License

No license file is currently published in this repository. Contact LayerCloud for terms before redistributing or reusing the code.
