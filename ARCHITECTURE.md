# CortexDrone Architecture

CortexDrone is an autonomous OpenComputers drone controller for Minecraft 1.7.10.
The drone is driven by an LLM that writes JavaScript, executed by Node.js, which
calls a small master-tool RPC surface on the Minecraft server. The design goal is
a clean split between **data**, **decisions**, **atomic actions**, and
**transport**.

This file is the master map. The detailed chapters live in `docs/`:

- [docs/README.md](docs/README.md) — documentation index and reading order
- [docs/01-overview.md](docs/01-overview.md) — what the project is and how it runs
- [docs/02-layers.md](docs/02-layers.md) — layer rules and package map
- [docs/03-actions-managers.md](docs/03-actions-managers.md) — actions, contexts, managers
- [docs/04-navigation.md](docs/04-navigation.md) — goal navigation and movement engine
- [docs/05-services.md](docs/05-services.md) — inventory, machine, world, navigation services
- [docs/06-harness.md](docs/06-harness.md) — LLM loop, Code Mode runtime, task/session, RPC dispatcher
- [docs/07-tools-rpc.md](docs/07-tools-rpc.md) — master tools, inspect ops, RPC/HTTP, SDK
- [docs/08-runtime.md](docs/08-runtime.md) — lifecycle, tick pipeline, modes, spawn, config, logging
- [docs/09-resources.md](docs/09-resources.md) — prompts, SDK resources, skills
- [docs/10-build-deploy.md](docs/10-build-deploy.md) — Gradle, validation, tests, deploy, operations
- [docs/11-extending.md](docs/11-extending.md) — how to add actions, services, tools, skills
- [docs/12-source-inventory.md](docs/12-source-inventory.md) — class-by-class source inventory
- [docs/removed-tasks.md](docs/removed-tasks.md) — deliberately removed premade tasks

## 1. System at a glance

```text
Minecraft chat / command / HTTP
		|
		v
TaskCoordinator  (one session, no queue)
		|
		v
LlmAgentService  (LLM loop, prompt, heartbeat)
		|
		v
LlmResponseParser -> CodeModeRuntime (Node.js JavaScript)
		|
		v
SDK `drone.*` -> POST /rpc -> RpcDispatcher
		|
		v
MasterRpcHandler -> RPC translators -> managers -> actions
		|
		v
Minecraft world / OpenComputers Drone / inventory / navigation
```

The LLM never calls Java directly and never receives raw Java objects. It calls
the JavaScript SDK; the SDK calls JSON RPC; translators parse JSON into typed
request DTOs; managers execute actions; action classes perform one physical
operation; results travel back as JSON.

## 2. Package map

```text
src/main/java/ir/layercloud/cortexdrone/
  CortexDroneMod.java       Forge mod entrypoint, tick events, chat, pools
  AIDroneManager.java       root manager, lifecycle, spawn/link/despawn/config
  TickProfiler.java         server tick timing diagnostics

  action/                   atomic one-shot commands
    Action.java             action interface
    block/                  break/place actions + request/result
    interact/               block use, item use, attack, pick/unpick
    inventory/              transfer/craft actions
    navigation/             flight/hold/stop actions
    visuals/                LED/particles/sound/chat actions

  context/                  mutable state for one action execution
    ActionContext.java
    ActionContextBase.java
    impl/                   BlockContext, InteractionContext,
                            InventoryContext, NavigationContext,
                            VisualsContext

  manager/                  long-lived decision/orchestration layer
    ActionManager.java      action lifecycle base
    ActionRegistry.java     operation -> manager lookup
    TickManager.java        20-Hz runtime pipeline
    building/impl/          BuildingManager
    interact/impl/          InteractionManager
    inventory/impl/         InventoryManager
    navigation/             NavigationRequest/Result/Target + NavigationManager
    visuals/                LightConfig/LightResult + LightingAura + LedEffects
                            + VisualsManager (light/LED are retained actions)

  service/                  read-only data/recon/planning
    inventory/              InventoryService, CraftingService, FluidOpsService,
                            ContainerSearchService, PlayerInventoryOps,
                            ToolInspector, CraftResult
    machine/                MachineRecipeService + MachineRecipeHook
                            (hbm-json / hbm-live), parser/ranker,
                            MachineTileInspector, FurnaceScanner
    navigation/             Breadcrumb, TrailPlan, BreadcrumbService,
                            FlightPathfinder, DronePhysicsService
    world/                  WorldScanner, ItemClassifier, RegistryIntrospector,
                            LexicalItemMatcher, RoomScanner, VeinScanner,
                            StructureArchitect, BuildTask, PlayerTargetScanner,
                            EntityAndBiomeClassifier, DocLookupService,
                            ChunkAccess, raytrace/*
  harness/                  Code Mode agent + transport
    agent/                  LlmAgentService (LLM loop/prompt)
                            DroneContextBuilder (initial world state)
                            LlmResponseParser, PromptCompactor
                            AgentHeartbeat, AgentLoopHeuristics, LoopTracer
    task/                   TaskCoordinator, TaskIntentClassifier, TaskIntent
    session/                DroneSession, SessionManager (task/loop/thought state)
    CodeModeRuntime.java     Node.js runner and SDK bundle loader
    SkillLibrary.java        bundled + saved skills, BM25 retrieval
    RpcDispatcher.java       main-thread RPC + time-sliced large scans
    ToolRegistry.java         master tool registry (names + descriptions)
    rpc/                     RpcHandler + handlers + translators

  drone/                    DroneController (identity + entity link only)
  command/                  /drone command
  config/                   AIConfig + DroneLogger + ChatRecord/DebugEntry
  http/                     DroneHttpServer + DroneTelemetrySerializer
  oc/                       OpenComputers reflection bridge
```

There is no `codemode` package and no top-level `inventory`, `world`, or
`navigation` package. Their content is under `harness`, `service/inventory`,
`service/world`, and `service/navigation`.

## 3. The four execution layers

- **Action** — one physical command. Created for one execution, may be ticked
  while retained, then discarded. No JSON, no routing, no goal decisions.
- **Context** — mutable state for one action execution. Tracks lifecycle:
  active/retained, target, result, progress. Long-running work lives here, not on
  the drone: there is no `Mode` enum and no FOLLOW/STAY/GOTO/MINE/DEPOSIT/DROP
  switch. A drone is idle exactly when no navigation session and no retained
  action exist.
- **Manager** — long-lived owner of a category. Parses typed requests, creates
  contexts/actions, retains/tick/cancels them, and decides category-level
  behavior. Navigation is the only manager with a goal loop.
- **Service** — read-only query/recon/planning. Records data, computes paths,
  ranks recipes, scans the world, reads inventories. Some services expose
  low-level storage primitives (inventory moves, deposits, crafting) that an
  action or manager explicitly invokes; a service never chooses a goal, starts
  an action, or owns work state.

`AIDroneManager` is the root manager. `TickManager` is the per-tick runtime. The
`harness` is the agent/transport layer. JSON is confined to `harness/rpc`.

## 4. Actions and managers

`ActionManager<C, R, T>` owns one category:

1. receives a typed request;
2. creates a context;
3. creates exactly one action;
4. executes it once;
5. returns a typed result;
6. retains the action/context for ticking if the context requested retention;
7. completes, reaps or cancels it later.

`ActionRegistry` maps operation names to managers and ticks/cancels all managers
for a controller. It contains no behavior.

Current managers:

| Manager | Operations | Notes |
| --- | --- | --- |
| `NavigationManager` | `goto`, `stop` | Goal loop; chooses trail/flight |
| `BuildingManager` | `break_block`, `place_block` | One immediate break or place |
| `InteractionManager` | `interact_block`, `use_item`, `pick_entity`, `unpick_entity` | One interaction/attack/use |
| `InventoryManager` | `transfer`, `craft` | transfer ops: move/give/take/withdraw/deposit/drop/suck |
| `VisualsManager` | `set_led`, `led_effect`, `spawn_particles`, `play_sound`, `play_note`, `say`, `set_status_text` | Owns lighting aura |

Action classes are solo: they never call another action. Block work is one
immediate break or one place; there is no mining queue, target selection, dig
planner or progress state in Java. The model decides which blocks to touch and
calls the primitive for each one.

## 5. Navigation summary

The harness exposes only `drone.goto(...)` and `drone.stop()`.

`NavigationManager` creates a session and keeps it alive until arrival, explicit
stop, or proven failure. It never contains flight/waypoint code; it delegates to
actions:

- `NavigateAction` — 3D A* flight, long-range stepping, surface anchor,
  blocked-waypoint detection.
- `StopAction` — clear navigation and hover.

Decision order:

1. **Trail suggestion** — `BreadcrumbService` (service, not action) records the
   last 256 passable positions, prunes loops, keeps the surface anchor, and
   suggests a backtrack endpoint (reconnect/surface/goalward) inside at most half
   the trail. The manager uses that endpoint as a normal flight target.
2. **Flight** — `FlightPathfinder` computes a safe no-dig A*/Theta* route. The
   manager follows while it is complete or progressing.
3. **No route** — if flight is blocked, partial, or exhausted, the session fails
   with `navigationError`. Navigation never breaks blocks. The model can dig its
   own tunnel with `break_block` and call `goto` again.

Deposit, delivery and block standoffs reuse `NavigationManager.flyToward` /
`hold`, so all motion still goes through action executors.

## 6. Harness summary

`TaskCoordinator` implements one task/session:

- deterministic intent classification (`QUESTION`, `BY_THE_WAY`, `MODIFY`,
  `CANCEL`, `NEW_TASK`);
- no instruction queue;
- a new/modified task cancels the old loop and action state;
- a side note is attached to the current task;
- a question is answered from live state.

`LlmAgentService` runs up to 100 turns / 8 minutes, with HTTP retries, prompt
compaction, self-correction, anti-requery/anti-loiter checks, heartbeat
continuations, and premature-`done` rejection.

`CodeModeRuntime` writes a temporary `.mjs` script, prepends
`sdk/core-rpc.mjs` + `sdk/drone-actions.mjs`, runs Node.js, and returns parsed
JSON. The SDK's `drone` object is the only tool global.

`RpcDispatcher` marshals every RPC onto the server thread with per-method
timeouts. Large `inspect('blocks')` scans are time-sliced across ticks as a
single job (`poll_scan`) instead of blocking a tick.

## 7. Master tool surface

```text
state
inspect
goto
stop
break_block
place_block
interact_block
use_item
pick_entity
unpick_entity
transfer
craft
visuals
skills
cancel
```

Removed composite tools (`mine`, `hunt`, `farm`, `buildStructure`, etc.) are not
coming back as hidden paths. See `docs/removed-tasks.md`.

## 8. Runtime lifecycle

1. `CortexDroneMod.preInit` registers events and creates `AIDroneManager`.
2. `serverStarting` loads `world/layercloud-ai.json`, creates the skill library,
   reconnects world drones, registers `/drone`, and starts HTTP on
   `127.0.0.1:25580`.
3. START tick: ensure OC machine state, start tick profiler.
4. END tick: drain main-thread queue, drain scan jobs, tick every controller,
   reconnect unlinked world drones every 40 ticks, snapshot HTTP state every 100
   ticks.
5. `serverStopping` restores light auras, saves config, stops HTTP.

Per-controller tick: breadcrumbs, stall detection, lighting aura, the active
navigation session, every retained action, idle hold, LED animation, physics.

## 9. Persistence and configuration

`world/layercloud-ai.json` stores:

- API URL, model, API key, HTTP port;
- saved drones (UUID/name/owner/light/position).

Environment variables override defaults:
`CORTEXDRONE_API_URL`, `CORTEXDRONE_MODEL`, `CORTEXDRONE_API_KEY`.

Skills persist in `world/layercloud-drone-skills-v2.json`; bundled skills come
from `resources/skills/`.

## 10. Build path

- Forge 1.7.10 + RetroFuturaGradle 2.0.6, Gradle 9.2, Java 25 daemon, Java 8
  compile target.
- OpenComputers `1.12.70-GTNH` is the compile-only host API.
- `./gradlew build` runs compile, tests, `validate`, reobfuscation and `deploy`.
- `validate` enforces a 696-line Java file limit, package/directory consistency,
  and `node --check` for every `.mjs`.
- `deploy` copies the reobfuscated jar to `/root/forge-smp/mods` and removes old
  CortexDrone jars.
- Tests cover action lifecycle/registry, master tool surface, skill library,
  task intent and task session.

Detailed build/operations instructions: [docs/10-build-deploy.md](docs/10-build-deploy.md).
