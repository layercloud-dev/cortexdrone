# 02 — Layers and Dependency Rules

## 1. The layers

```text
┌─────────────────────────────────────────────────────────────┐
│ harness/       agent loop, Code Mode, task/session, transport │
│ harness/rpc/   JSON handlers + translators                   │
└───────────────────────────┬─────────────────────────────────┘
                            │ typed request / typed result
┌───────────────────────────▼─────────────────────────────────┐
│ manager/       goals, routing, action lifecycle, orchestration│
└───────────────────────────┬─────────────────────────────────┘
                            │ execute / tick / cancel
┌───────────────────────────▼─────────────────────────────────┐
│ action/        one physical operation                        │
│ context/       state for that operation                      │
└───────────────────────────┬─────────────────────────────────┘
                            │ reads / computes
┌───────────────────────────▼─────────────────────────────────┐
│ service/       read-only data, recon, planning               │
└─────────────────────────────────────────────────────────────┘
```

`model/`, `config/`, `oc/`, `http/`, `command/` are support packages used by the
layers; they are not a fifth execution layer.

## 2. Hard rules

### Services

- May read the world, inventory, entities, registries, config and controller data.
- May compute paths, scores, recipe candidates, scans and snapshots.
- May cache results.
- Must not choose goals, set `Mode`, change navigation/path state, or start actions.
- May expose low-level storage primitives (inventory moves, deposits, crafting)
  that an action or manager explicitly invokes; the service still does not decide
  that the operation should happen.
- May record passive data (e.g. `BreadcrumbService.observe`) but that data is
  observation, not execution.

### Managers

- Own a category's operation names and lifecycle.
- Parse only typed requests, never JSON.
- Create one context/action per operation and retain/tick/cancel it.
- May coordinate another manager to complete a larger goal (navigation asks
  building to dig).
- Must not contain flight/waypoint algorithms; those belong to actions/services.
- Must not call another manager's action class directly unless the action is the
  explicit executor for a leg (navigation uses `DigRouteAction`).

### Actions

- One physical operation.
- Receive a context and a typed request.
- May call services/managers that provide the operation's mechanics.
- Must not reference another action.
- Must not decide a route/strategy.
- Must not parse JSON.
- Are discarded after execution; retained only while the context says so.

### Contexts

- Mutable per-execution state only.
- Immutable identity: controller, player, operation, start tick.
- Lifecycle flags: active/retained; complete/cancel callbacks.
- No business decisions beyond state helpers.

### Transport

- `harness/rpc/translate` is the only JSON boundary.
- Translators parse `JsonObject` into request DTOs and serialize result DTOs.
- Handlers route RPC methods; `RpcDispatcher` executes on the server thread.
- No action/context/manager/service imports Gson.

## 3. Dependency direction

Allowed:

```text
harness -> manager -> action -> context
harness -> service (read-only helpers/translators)
manager -> service
action  -> service
context -> action types (result DTOs)
```

Discouraged/forbidden:

```text
service -> manager
service -> action
action  -> action
action  -> harness transport
context -> JSON
```

`AIDroneManager` wires everything and is the root composition point. It is the
only place that constructs managers, services and the harness.

## 4. Request lifecycle

Example: `drone.breakBlock(10, 64, -3)`.

1. SDK sends `POST /rpc` with method `break_block`.
2. `DroneHttpServer` routes to `RpcDispatcher.executeOnMainThread`.
3. `MasterRpcHandler` checks reach; if out of reach it returns
   `{needsMovement:true, standoff}`.
4. If in reach, `ActionRpcTranslator` routes to `BuildingRpcTranslator`.
5. Translator builds `BlockRequest` and calls `BuildingManager.handle`.
6. `ActionManager` creates `BlockContext`, then `BreakBlockAction`.
7. Action calls `BuildingManager.startBreak`, which sets the mining target/mode.
8. Context is retained; result `BlockResult.breakStarted` is serialized.
9. Each tick, `ActionRegistry.tick` ticks `BreakBlockAction`.
10. When the target is gone/mode changes, the action completes and is reaped.

Example: `drone.goto(100, 70, -40)`.

1. Translator builds `NavigationRequest`.
2. `NavigationManager.gotoGoal` resolves target and creates a session.
3. `TickManager` calls `navigationManager.tick` every tick.
4. Manager picks a leg and creates `NavigationContext`.
5. `NavigateAction` or `DigRouteAction` executes/tick.
6. `NavigateResult` drives next decision until arrival/failure.
7. SDK polls `state.navigationActive` until false, then returns.

## 5. Retention model

`ActionContextBase`:

- `retain()`: keeps action/context after the initial call.
- `complete()`: normal finish; active=false; retained=false.
- `cancel()`: replacement/abort; active=false; retained=false.

`ActionManager`:

- one execution per `(controller, operation)` key;
- a newer invocation cancels the previous one;
- `tick` ticks retained actions and reaps finished ones;
- `cancelAll` clears all executions for a controller.

Navigation uses the same context retention for its own legs, but keeps its
session in `NavigationManager`; the session is separate from the action context.

## 6. JSON boundary

`harness/rpc/translate/`:

| Translator | Operations |
| --- | --- |
| `ActionRpcTranslator` | routes all category operations |
| `NavigationRpcTranslator` | `goto`, `stop` |
| `BuildingRpcTranslator` | `break_block`, `place_block` |
| `InteractionRpcTranslator` | `interact_block`, `use_item`, `pick_entity`, `unpick_entity` |
| `InventoryRpcTranslator` | `transfer`, `craft` |
| `VisualsRpcTranslator` | `set_led`, `led_effect`, particles/sound/note/say/status, `state` status, skills |
| `BreadcrumbRpcTranslator` | `inspect('breadcrumbs')` |
| `RpcArgs` / `RpcJson` | safe JSON helpers |

The `MasterRpcHandler` is a thin router: it routes the 15 master tools, delegates
read-only inspect ops, and injects the `needsMovement` standoff contract.

## 7. Package conventions

- Abstract bases/interfaces at package root (`action/Action.java`,
  `manager/ActionManager.java`, `service/navigation/NavContext.java`).
- Concrete implementations in `impl/`.
- DTOs are immutable Lombok `@Value`/`@Builder` or `@Data` where the model is
  intentionally mutable.
- Utilities use Lombok `@UtilityClass` where appropriate.
- Constructors use `@RequiredArgsConstructor` or explicit constructor injection
  from `AIDroneManager`.
- Package declaration must match directory exactly; `validate` enforces it.

## 8. Error handling

- Translators return `{ok:false, error:"..."}` instead of throwing to the SDK.
- Managers throw only for programming errors (unsupported operation); dispatcher
  catches and returns JSON errors.
- Action execution failures cancel the context and rethrow to the manager; the
  dispatcher converts to JSON.
- Agent JS failures are fed back into the next LLM turn by `LlmAgentService`.
- RPC timeouts return explicit errors; no silent hang.
- Large scans return `busy` + active job ID rather than queueing unbounded work.

## 9. Why this shape

- **Testability**: manager lifecycle and tool surface are testable without Forge.
- **Safety**: one JSON boundary + one server-thread RPC entrypoint.
- **Extensibility**: a new category adds one action package, one context, one
  manager, one translator.
- **No hidden automation**: only navigation has a goal loop; every other tool is
  explicit.
- **Observability**: debug categories (`MODE`, `NAV`, `PATH`, `MINE`, `STUCK`,
  `ESCAPE`, `LLM`, `CODEMODE`, `HEARTBEAT`, `TRACE`, `WARN`, `ERROR`) make silent
  stops visible.
