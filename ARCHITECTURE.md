# CortexDrone Architecture

CortexDrone is an autonomous OpenComputers drone controller for Minecraft 1.7.10.
A player request is handled by an LLM through **native OpenAI-style tool
calling**; multi-step work is performed by JavaScript that runs **inside the
server JVM on Rhino**. There is no Node.js process, no temporary script file and
no HTTP RPC hop between the model and the world.

The design goal is a clean split between **data**, **decisions**, **atomic
actions**, **tools** and **transport**.

Detailed chapters live in `docs/`:

- [docs/README.md](docs/README.md) — index and reading order
- [docs/01-overview.md](docs/01-overview.md) — what the project is and how it runs
- [docs/02-layers.md](docs/02-layers.md) — layer rules and package map
- [docs/03-actions-managers.md](docs/03-actions-managers.md) — actions, contexts, managers
- [docs/04-navigation.md](docs/04-navigation.md) — goal navigation and movement
- [docs/05-services.md](docs/05-services.md) — inventory, machine, world, web services
- [docs/06-harness.md](docs/06-harness.md) — agent loop, LLM client, runtime, skills
- [docs/07-tools.md](docs/07-tools.md) — tool registry, schemas, JavaScript surface
- [docs/08-runtime.md](docs/08-runtime.md) — lifecycle, tick pipeline, config, logging
- [docs/09-resources.md](docs/09-resources.md) — prompts, JavaScript bootstrap, skills
- [docs/10-build-deploy.md](docs/10-build-deploy.md) — Gradle, tests, validation, deploy
- [docs/11-extending.md](docs/11-extending.md) — adding tools, actions, services, skills
- [docs/12-source-inventory.md](docs/12-source-inventory.md) — class-by-class inventory
- [docs/removed-tasks.md](docs/removed-tasks.md) — deliberately removed legacy paths

## 1. System at a glance

```text
Minecraft chat / command
        |
        v
TaskCoordinator          one task, intent classification, no queue
        |
        v
AgentService             native tool-calling loop
        |
        v
LlmClient  <-------->  OpenAI-compatible gateway (tools + tool_calls)
        |
        v
ToolInvoker              schema validation, MAIN/ASYNC dispatch, error envelope
        |
        +--> domain providers --> managers --> actions --> world
        |
        +--> JsEngine (Rhino) --> `drone.*` bindings --> ToolInvoker
        |
        +--> WebLookupService / SkillLibrary / SkillRefiner
```

The model never receives raw Java objects. It either calls a declared tool or
calls `execute`, which runs a JavaScript body in the same JVM. Tool results are
plain JSON/objects; failures are returned as `{ok:false, code, error, hint,
retryable}` and are also thrown as catchable JS errors inside `execute`.

## 2. Package map

```text
src/main/java/ir/layercloud/cortexdrone/
  CortexDroneMod.java       Forge entry point, tick events, chat, thread pools
  AIDroneManager.java       lifecycle, spawn/link/despawn, controller registry
  DroneController.java      per-drone identity + entity link
  TickProfiler.java         tick timing diagnostics

  action/                   atomic one-shot commands
    block/ interact/ inventory/ navigation/ visuals/

  context/                  mutable state for one action execution

  manager/                  long-lived decision/orchestration layer
    ActionManager, ActionRegistry, TickManager
    building/ interact/ inventory/ navigation/ visuals/

  service/                  read-only data, recon and planning
    inventory/ machine/ navigation/ world/
    world/WebLookupService  internet search/fetch/wiki (no Minecraft access)

  harness/                  agent + tool + code runtime
    Harness.java            composition root (registry, invoker, js, llm, agent)
    agent/                  AgentService, LlmClient, Conversation, ContextRenderer
    js/                     JsEngine (Rhino), JsSource, TypeScriptEraser
    memory/                 MemoryEntry, MemoryCapture, MemoryStore
    tool/                   ToolSpec, ToolRegistry, ToolInvoker, ScanJobs, schemas
    tools/                  one provider per domain + view/ JSON boundaries
    skill/                  Skill, SkillLibrary, SkillSearch, SkillRefiner
    session/                DroneSession, SessionManager
    task/                   TaskCoordinator, TaskIntentClassifier

  command/                  /drone command
  config/                   ModConfig, ModPaths, ConfigMigrator, DroneLogger
  oc/                       OpenComputers reflection bridge
```

JSON is confined to the tool boundary (`harness/tools/view`) and the LLM
protocol. Actions, managers and services work with typed request/result classes.

## 3. The four execution layers

- **Action** — one physical command. Created for one execution, may be ticked
  while retained, then discarded. No JSON, no routing, no goal decisions.
- **Context** — mutable state for one action execution. Tracks lifecycle,
  target, result and progress. Long-running work lives here, not on the drone.
- **Manager** — long-lived owner of a category. Parses typed requests, creates
  contexts/actions, retains/tick/cancels them, and decides category-level
  behavior. Navigation is the only manager with a goal loop.
- **Service** — read-only query/recon/planning. Records data, computes paths,
  ranks recipes, scans the world, reads inventories, performs web lookups. A
  service never chooses a goal, starts an action, or owns work state.

`AIDroneManager` is the root manager. `TickManager` is the per-tick runtime.
`Harness` owns the agent and tool surface.

## 4. Actions and managers

`ActionManager<C, R, T>` owns one category:

1. receives a typed request;
2. creates a context;
3. creates exactly one action;
4. executes it once;
5. returns a typed result;
6. retains the action/context for ticking if requested;
7. completes, reaps or cancels it later.

`ActionRegistry` maps operation names to managers and ticks/cancels all managers
for a controller. It contains no behavior.

| Manager | Operations | Notes |
| --- | --- | --- |
| `NavigationManager` | `goto`, `stop` | Goal loop; chooses trail/flight |
| `BuildingManager` | `break_block`, `place_block` | One immediate break or place |
| `InteractionManager` | `interact_block`, `use_item`, `pick_entity`, `unpick_entity`, `attack_entity` | One interaction/attack/use |
| `InventoryManager` | `transfer`, `craft` | transfer ops: move/give/take/withdraw/deposit/drop/suck |
| `VisualsManager` | `set_led`, `set_light`, `spawn_particles`, `play_sound`, `play_note`, `say`, `set_status_text` | Owns lighting aura |

Action classes are solo: they never call another action. Block work is one
immediate break or one place; there is no mining queue, target selection, dig
planner or progress state in Java. The model decides which blocks to touch and
calls the primitive for each one.

## 5. Navigation summary

The harness exposes `goto` and `stop`.

`NavigationManager` creates a session and keeps it alive until arrival, explicit
stop, or proven failure. It never contains flight/waypoint code; it delegates to
actions:

- `NavigateAction` — 3D A* flight, long-range stepping, surface anchor,
  blocked-waypoint detection.
- `StopAction` — clear navigation and hover.

Decision order:

1. **Trail suggestion** — `BreadcrumbService` records the last 256 passable
   positions, prunes loops, keeps the surface anchor, and suggests a backtrack
   endpoint inside at most half the trail.
2. **Flight** — `FlightPathfinder` computes a safe no-dig A*/Theta* route.
3. **No route** — the session fails with `navigation_error`. Navigation never
   breaks blocks; the model digs its own tunnel with `break_block` and retries.

`ToolInvoker` waits for `goto` to finish on the agent thread (not the server
thread), so a direct tool call and `drone.goto(...)` both return after arrival
or failure.

## 6. Harness summary

`TaskCoordinator` implements one task/session:

- deterministic intent classification (`QUESTION`, `BY_THE_WAY`, `MODIFY`,
  `CANCEL`, `NEW_TASK`);
- no instruction queue;
- a new/modified task cancels the old generation and action state;
- a side note is attached to the current task;
- a question is answered from live state.

`AgentService` sends a real system prompt, a live context message and the user
request, with the full tool schemas. It loops on native `tool_calls`, executes
each through `ToolInvoker`, appends a `tool` message, and stops when the model
answers in text or the turn/time budget ends. Tool payloads are recursively
capped; old turns are compacted into a digest without orphaning tool results.

`JsEngine` runs Rhino 1.7.15 in interpreted mode with an instruction/time budget.
`JsSource` normalizes the modern syntax Rhino does not parse (`await`/`async`,
optional chaining, nullish coalescing, `for...of` with `const`, simple spread)
and erases common TypeScript annotations. Tool failures are catchable JS errors.

`ToolRegistry` is the single source of truth: it declares name, description,
JSON schema, thread affinity, timeout and internal visibility. The OpenAI tool
array, the JavaScript `drone` object and the prompt catalog are all generated
from it.

`MemoryStore` keeps durable player facts and named locations in
`config/cortexdrone/memory.json`. Explicit "remember ..." messages and place
definitions are captured deterministically, and the model can use the `memory`
tool to remember/search/forget. Relevant and pinned memories are injected into
the task context.

## 7. Tool surface

Each domain provider owns its declarations and handlers. Duplicate names or
unbound declarations are startup errors. There is no catch-all router.

```text
session      state cancel
navigation   goto stop get_breadcrumbs
block        break_block place_block
interaction  interact_block use_item attack_entity pick_entity unpick_entity
inventory    move_item give_to_player take_from_player withdraw_chest
             deposit_chest drop_items suck_items craft
visuals      set_led set_light spawn_particles play_sound play_note say
             set_status_text
code         execute
memory       memory
skills       skill skill_refine
world        get_block get_blocks_in_box get_top_y scan_room scan_blocks
             scan_veins find_distant get_entities get_drops raycast_player
             doc_lookup find_build_site plan_structure
query        get_player get_player_inventory get_chest scan_chests
             find_item_in_chests find_item get_recipes scan_furnaces
             get_machine_recipes get_machine_recipe scan_machines
             machine_ingredients
web          web_search web_fetch wiki_lookup
transport    poll_scan
```

Removed composite tools (`mine`, `hunt`, `farm`, `buildStructure`, etc.) are not
coming back as hidden paths. See `docs/removed-tasks.md`.

## 8. Runtime lifecycle

1. `CortexDroneMod.preInit` resolves `config/cortexdrone/`, creates
   `AIDroneManager`, registers events.
2. `serverStarting` migrates legacy world files, loads `config.json`, creates the
   skill library, builds the `Harness`, registers `/drone`, starts HTTP on
   `config/cortexdrone/`.
3. START tick: ensure OC machine state, start tick profiler.
4. END tick: drain main-thread queue, drain scan jobs, tick every controller,
   reconnect unlinked world drones every 40 ticks.
5. `serverStopping` restores light auras, saves config.

Per-controller tick: breadcrumbs, stall detection, lighting aura, active
navigation, retained actions, idle hold, physics.

## 9. Persistence and configuration

Everything AI-related lives in the mod config directory, **not** the world save:

```text
config/cortexdrone/
  config.json   gateway, model/key, agent limits, saved drone bindings
  skills.json   learned/refined skill overrides
  memory.json   player facts and named locations
  logs/         debug log
```

`ConfigMigrator` imports `world/layercloud-ai.json` and
`world/layercloud-drone-skills-v2.json` once, then renames them `.migrated`.

Environment overrides: `CORTEXDRONE_API_URL`, `CORTEXDRONE_MODEL`,
`CORTEXDRONE_API_KEY`.

## 10. Build path

- Forge 1.7.10 + RetroFuturaGradle 2.0.6, Gradle 9.2, Java 25 daemon, Java 8
  compile target.
- Rhino 1.7.15 is shaded into the mod jar; it is Java 8 bytecode, as required by
  the 1.7.10 class loader.
- OpenComputers `1.12.70-GTNH` is the compile-only host API.
- `./gradlew build` runs compile, tests, `validate`, reobfuscation and `deploy`.
- `validate` enforces a 696-line Java file limit and package/directory
  consistency. JavaScript syntax is covered by Rhino-backed unit tests.
- `deploy` copies the reobfuscated jar to `/root/forge-smp/mods` and removes old
  CortexDrone jars.
- Tests cover registry/schema invariants, JS preprocessing/runtime, tool
  validation, skill storage, action lifecycle, task intent and session state.

Detailed build/operations instructions: [docs/10-build-deploy.md](docs/10-build-deploy.md).
