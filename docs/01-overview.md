# 01 — Overview

## 1. What CortexDrone is

CortexDrone is an autonomous AI drone controller for **Minecraft 1.7.10** on
**Forge**, built around **OpenComputers** drones. A player gives a natural-language
instruction in chat, a language model writes a short JavaScript program, Node.js
executes that program, and the program calls a small set of drone primitives
(JSON RPC) executed on the Minecraft server thread.

The drone is not a catalog of pre-made tasks. The model must compose behavior:

```text
inspect -> goto -> breakBlock / placeBlock / interactBlock / useItem / transfer
```

Only navigation is goal-driven inside Java: the model says "go to X/Y/Z" and the
navigation manager chooses trail/flight routes itself.

## 2. Runtime topology

```text
Minecraft server thread
  ├─ CortexDroneMod (Forge events)
  ├─ AIDroneManager (root manager)
  │   ├─ managers (navigation/building/interaction/inventory/visuals)
  │   ├─ services (inventory/machine/world/navigation)
  │   ├─ TickManager (20 Hz pipeline)
  │   └─ DroneHttpServer (127.0.0.1:25580)
  ├─ main-thread queue
  └─ RPC dispatcher (time-sliced scans)

Agent threads (CortexDrone-Agent-*)
  └─ LlmAgentService -> CodeModeRuntime -> node process
                        └─ SDK `drone` -> HTTP POST /rpc
```

HTTP server threads (`CortexDrone-Http-*`) are separate from agent threads so a
hung agent loop cannot starve `/state`, `/debug` or `/rpc`, and a hung HTTP call
cannot wedge the model loop.

## 3. Core design goals

1. **One session, no queue.** A drone runs one task. A new instruction replaces
   the old one; a side note is attached; a question is answered from live state.
2. **Tools are primitives.** No hidden `mine`, `hunt`, `farm`, `buildStructure`,
   `sortChests`, or batched task queue.
3. **Managers decide, services answer, actions execute.** A service must not
   choose a goal, start an action, or own work state. Some services expose
   low-level storage primitives that an action/manager explicitly invokes. An
   action must not decide a route or call another action. A manager owns the
   lifecycle and the goal.
4. **Only movement is autonomous.** `goto` keeps working until arrival or proven
   failure. Every other operation is one explicit primitive.
5. **No drone modes.** There is no `Mode` enum or FOLLOW/STAY/GOTO/MINE/DEPOSIT/
   DROP switch. Work is expressed by an active navigation session or a retained
   action context; when neither exists the drone is idle and holds position.
6. **JSON only at the edge.** Actions, contexts, managers and services use typed
   DTOs. `harness/rpc/translate` is the only place that parses/serializes JSON.
7. **Server-thread safety.** World reads/writes and entity mutation are marshalled
   onto the server thread. Large scans are sliced across ticks.

## 4. Technology stack

| Item | Value |
| --- | --- |
| Minecraft | 1.7.10 |
| Mod loader | Forge 10.13.4.1614 |
| Host mod | OpenComputers `1.12.70-GTNH` |
| Build plugin | RetroFuturaGradle `2.0.6` |
| Gradle | 9.2 |
| Build daemon | Java 25 (`graalvm-jdk-25` in the server setup) |
| Compile target | Java 8 |
| JSON | Gson (1.7.10 bundled + project Gson) |
| DTOs | Lombok `@Value`, `@Builder`, `@Data`, `@Getter` |
| Tests | JUnit 4.13.2 |
| JS runtime | Node.js at `/usr/local/bin/node` |

The mod ID is `cortexdrone`; the version is `1.4.0`; the declared dependency
string is `required-after:OpenComputers;after:layercloudsmp`.

## 5. Feature set

- Spawn/link/despawn AI drones; reconnect world drones after restart.
- Natural-language chat control (`@drone ...` or `@<droneName> ...`).
- LLM-backed Code Mode session with bounded turns/time and self-correction.
- Master tools for status, inspection, navigation, block break/place,
  interaction/item use, entity pick/unpick, inventory transfer, crafting,
  visuals, skill memory and cancellation.
- Goal navigation: breadcrumb backtracking and 3D flight A*.
- `break_block` destroys exactly the block you name, yields its drops (Fortune
  aware) and reports; there is no mining queue, target selection or dig planner.
- Inventory/chest/furnace/machine inspection, item search, crafting, fluid
  scooping and machine recipe introspection.
- Torch-free illumination aura that restores original light values.
- LED effects and procedural color animation.
- Persistent AI config, saved drones and skills.
- HTTP telemetry (`/state`, `/debug`), RPC (`/rpc`) and direct action (`/action`).
- Tick profiler with configurable warning threshold/cooldown.

## 6. Terminology

| Term | Meaning |
| --- | --- |
| **Master tool** | One of the 15 tools exposed to Code Mode (`state`, `inspect`, ...) |
| **SDK** | `resources/sdk/*.mjs` injected into every Node script as `drone` |
| **Code Mode** | LLM writes async JavaScript; Node runs it; SDK calls RPC |
| **Session** | One active task for one drone; owned by `DroneSession` |
| **Loop** | One LLM/agent execution with a loop ID; cancellable and supersedable |
| **Action** | One-shot physical command class |
| **Context** | Mutable state for one action execution |
| **Manager** | Long-lived category owner and orchestrator |
| **Service** | Read-only data/recon/planning provider |
| **Leg** | One navigation action execution inside a goal session |
| **Trail** | Breadcrumb history recorded by `BreadcrumbService` |
| **Standoff** | Safe reachable position beside a block |

## 7. What is deliberately absent

- A pre-made task catalog or instruction queue.
- JSON in actions/contexts/managers/services.
- Hidden aliases or duplicate SDK globals (`world`, `player`, `kb`, `harness`).
- `/ai` and `@ai` command aliases; `/drone` and `@drone` remain.
- Legacy `/drone` shortcuts (`take`, `give`, `torch`, `drop`, `chest`, `scan`).
- `CombatService`, hunt/guard/patrol/follow/stay premade tasks, and the
  `Mode` state machine that drove them.
- Top-level `codemode`, `inventory`, `world`, `navigation` packages.

See [removed-tasks.md](removed-tasks.md) for the historical list and replacement
rules.
