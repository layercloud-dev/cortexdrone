# CortexDrone Architecture

## Layers

CortexDrone is split into four execution layers plus transport:

```text
action/              one-shot commands; no decisions, no JSON
context/             mutable state for one action execution
manager/             long-lived decision and orchestration layer
service/inventory/   inventory data, containers and crafting support
service/machine/     machine recipes, machine tiles and furnaces
service/navigation/  breadcrumbs and navigation plans
service/world/       world/item/entity recon and documentation
harness/             Code Mode agent and single-session task logic
harness/rpc/         JSON/HTTP transport; parsers and serializers only
```

The rule is simple:

- a **service** answers questions and never changes the world, mode, inventory or
  navigation state;
- a **manager** owns goals, chooses what to execute and drives actions until the
  goal completes or fails;
- an **action** does exactly one physical thing and is discarded afterwards;
- a **translator** is the only place that sees JSON.

`AIDroneManager` is the root manager. `TickManager` is the 20-Hz runtime
pipeline. Neither contains task-specific behavior.

## Managers

| Manager | Responsibility |
| --- | --- |
| `manager.navigation.impl.NavigationManager` | The only automatic goal loop: `goto` / `stop` |
| `manager.building.impl.BuildingManager` | One-block break/place and the mining loop |
| `manager.interact.impl.InteractionManager` | Block interaction, item use, entity pick/unpick |
| `manager.inventory.impl.InventoryManager` | Transfer and craft operations |
| `manager.visuals.impl.VisualsManager` | LED, lighting aura, particles, sounds, notes, chat/status |
| `manager.TickManager` | Per-tick runtime pipeline |
| `manager.ActionRegistry` | Operation-to-manager lookup |

`manager.ActionManager<C,R,T>` is the common lifecycle base for managers that
execute one retained/one-shot action per request. It creates the context, creates
one action, executes it, returns the typed result and reaps retained executions.
It contains no JSON.

## Services

| Service | Responsibility |
| --- | --- |
| `service.navigation.impl.BreadcrumbService` | Records the traversal trail, surface anchor and trail backtrack candidates |
| `navigation.service.FlightPathfinder` | No-dig flight A* and geometry queries |
| `navigation.service.DigPathService` | Weighted dig A*; returns a route, never executes it |
| `service.inventory.InventoryService` | Inventory snapshots, container lookup, item moves and crafting support |
| `service.machine.MachineRecipeService` | Machine recipe introspection and ranking |
| `service.machine.MachineTileInspector` | Machine tile discovery/description |
| `service.machine.FurnaceScanner` | Furnace discovery |
| `service.world.WorldScanner` | World/resource/room/entity recon |
| `service.world.*` | Item classification, structure, target and documentation readers |

Services may cache results, but they do not choose routes, set modes, switch
tools or start actions.

## Navigation

The `harness` package owns the Code Mode agent loop, prompt/context assembly,
single-session task state, skill library, RPC dispatcher and the JSON transport
under `harness/rpc`. There is no separate `codemode` package.

The harness has exactly two movement calls:

- `drone.goto(x,y,z,{standoffOf,maxWaitMs})`
- `drone.stop()`

Everything else is internal. `NavigationManager` resolves the target, creates a
session and keeps it alive until arrival, explicit stop or proven failure. The
SDK waits on `navigationActive`, not on `mode`.

Decision order:

1. **Trail check** — `BreadcrumbService.planBacktrack` compares the nearest
   reconnect point, the first open-sky point and the point closest to the goal
   inside at most half of the recorded trail. A trail is used only when it makes
   real progress or exits a cave toward a surface goal.
2. **No-dig flight** — `FlightPathfinder` computes a safe 3D A* path. The manager
   follows it while it is complete or making progress.
3. **Dig escalation** — when the flight path is partial, blocked or exhausted,
   the manager chooses `tunnel`, `dig-up` or `dig-down`, asks `DigPathService`
   for the route and hands the blocking blocks to `BuildingManager`. The
   navigation session survives the dig and replans when control returns to
   `GOTO`.

The trail is owned by `BreadcrumbService`, not by actions or the controller.
Sampling, dedupe and loop pruning happen in the service; `inspect('breadcrumbs')`
reads the same service through `BreadcrumbRpcTranslator`.

Every movement leg is an action executor under
`action/navigation/impl/`: `NavigateAction` (no-dig flight), `TrailAction`
(breadcrumb backtrack), `DigRouteAction` (hands a dig route to
`BuildingManager`), `HoldAction` and `StopAction`. `NavigationManager` only
chooses the next leg and retains its context; managers contain no flight or
waypoint code. The same executors are used by follow/drop/deposit and mining
standoffs through `NavigationManager.flyToward` / `hold`.

## Actions

```text
action/<category>/impl/      one-shot actions
context/<category>/impl/     action contexts
```

An action is active while it runs and is released when the call returns unless
it calls `context.retain()`. Retained action/context pairs are ticked by their
owning manager and removed when they complete. `ActionRegistry` only routes
operations; it does not own behavior.

Block break/place behavior and the mining loop live in `BuildingManager`. Mining
itself is composed by the AI as `inspect -> goto -> breakBlock`; see
`src/main/resources/skills/mine_blocks.json`.

Inventory mutations (`transfer` and `craft`) are actions under
`action/inventory/impl/`, managed by `InventoryManager`. Read-only inventory,
chest, furnace and machine inspection stays in
`harness/rpc/impl/InventoryRpcHandler`.

## Transport

`harness/rpc/translate/ActionRpcTranslator` routes operations to one translator
per category. Translators parse JSON into typed requests and serialize typed
results. Action classes, contexts, managers, services and `ActionRegistry` never
import Gson.

Project convention: interfaces and abstract bases live at the package root;
concrete implementations live under an `impl` subpackage. Request and result
DTOs are immutable Lombok `@Value @Builder` objects, contexts use Lombok
accessors, and managers/services use constructor injection.

## Master tool contract

| Tool | Purpose |
| --- | --- |
| `drone.state()` | Live mode/position/task/navigation/inventory status |
| `drone.inspect(op,args)` | One read-only query namespace |
| `drone.goto(x,y,z,{standoffOf})` | Goal-driven navigation; manager chooses trail/flight/dig |
| `drone.stop()` | Cancel navigation and hover |
| `drone.breakBlock(x,y,z)` | Break exactly one explicit block |
| `drone.placeBlock(x,y,z,block,meta)` | Place exactly one explicit block |
| `drone.interactBlock(x,y,z,side)` | Activate exactly one block |
| `drone.useItem(item,x,y,z,side)` | Use one item or make one melee attack |
| `drone.pickEntity(id)` | Mount one entity |
| `drone.unpickEntity({entityId?})` | Dismount the rider or explicit entity |
| `drone.transfer({op,...})` | move / give / take / drop / withdraw / deposit / suck |
| `drone.craft(filter,count)` | Vanilla crafting primitive |
| `drone.visuals(kind,args)` | LED / particles / sound / note / status / chat |
| `drone.skills(op,args)` | Optional reusable-skill memory |
| `drone.cancel()` | Cancel the current session |

There are no duplicate SDK aliases and no `tunnel`/`digUp`/`digDown` tools.
`drone` is the only tool global. `sleep`, `log`, and `hsvToRgb` are utilities.

## Move then act

Mining is not a command. The AI:

1. inspects the world to choose coordinates,
2. calls `goto` and waits for arrival,
3. calls `breakBlock` for the one chosen block,
4. repeats.

`breakBlock` / `placeBlock` / `interactBlock` / `useItem` return
`{needsMovement:true, standoff}` when the target is out of reach. The SDK flies
to that standoff and retries, so the physical action only runs after arrival.

## Single task, single session

`DroneController` owns one `TaskSession`. `TaskCoordinator` classifies every
incoming message before starting a model loop:

| Intent | Behavior while a task is active |
| --- | --- |
| `QUESTION` | Reply from live state; task continues |
| `BY_THE_WAY` | Attach a note to the running task; task continues |
| `MODIFY` | Cancel the running loop, start one replacement session |
| `CANCEL` | Cancel the loop, clear action state, stop navigation |
| `NEW_TASK` | Treated as a replacement (one session at a time) |

No instruction is queued for later. A modification replaces the session instead
of stacking behind it. The classifier lives in
`harness/TaskIntentClassifier.java` and is covered by JUnit tests.

## Removed premade tasks

`follow`, `stay`, `guard`, `patrol`, `goto` as a task, `come` and `carry` were
removed. See `docs/removed-tasks.md` for the current composed replacements and
the rules for re-implementing them.
