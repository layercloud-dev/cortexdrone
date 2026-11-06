# 03 — Actions, Contexts and Managers

## 1. Action contract

`action/Action.java`:

```java
public interface Action<C extends ActionContext> {
    void execute(C context);
    default void tick(C context) {}
    default void cancel(C context) {}
}
```

An action is a **single-use command**. It may be retained and ticked, but it is
never reused for a different request. It must not call another action, route
JSON, or choose a navigation strategy.

## 2. Context contract

`context/ActionContext.java` + `ActionContextBase`:

| Member | Purpose |
| --- | --- |
| `controller()` | DroneController for this execution |
| `player()` | Requesting player (may be null for internal/automation) |
| `operation()` | Operation name |
| `startedTick()` | Controller tick at creation |
| `isActive()` | Not completed/cancelled |
| `isRetained()` | Active and retained for ticking |
| `retain()` | Keep action/context after call returns |
| `complete()` | Normal finish; invokes `onComplete()` |
| `cancel()` | Abort/replacement; invokes `onCancel()` |

Concrete contexts live in `context/impl/`:

- `BlockContext` — result, target, breakOperation, tick count.
- `InteractionContext` — result, target/entity/slot data as needed.
- `InventoryContext` — result, operation options.
- `NavigationContext` — action kind, target, dig path, label, result, tick count.
- `VisualsContext` — result.

## 3. Manager base

`manager/ActionManager<C,R,T>`:

1. `handle(controller, player, operation, request)` validates `operation`.
2. Creates a context via `createContext`.
3. Creates one action via `createAction`.
4. Executes it once.
5. Reads the typed result via `result(context)`.
6. Retains it if `context.isRetained()`, otherwise completes it.
7. On failure cancels action/context and rethrows.

`run(...)` is the internal variant for callers that already built context/action.

`tick(controller)` ticks retained actions and reaps inactive contexts.
`cancelAll(controller)` cancels every execution for that controller.

A newer invocation for the same `(controller, operation)` cancels the previous
execution before retaining the new one.

## 4. Action registry

`manager/ActionRegistry`:

- `register(manager)` maps every `manager.operations()` name to that manager;
- duplicate operation names throw `IllegalStateException`;
- `tick(controller)` ticks every registered manager;
- `cancelAll(controller)` cancels every manager;
- `find(operation)` returns the owning manager;
- `activeContextCount(controller)` totals retained contexts.

Navigation is **not** registered because it owns a goal session rather than a
single retained operation; its `goto`/`stop` are routed directly by
`ActionRpcTranslator`.

## 5. Managers and operations

### NavigationManager (`manager/navigation/impl`)

Operations: `goto`, `stop` only.

- Resolves explicit coordinates or `standoffOf`/`nearBlock`.
- Creates a `Session` (goal, phase, current leg, trail state, dig attempts).
- Keeps the session until arrival, explicit stop, or proven failure.
- Uses `BreadcrumbService` suggestion, `FlightPathfinder`, `DigPathService`.
- Delegates to `NavigateAction`, `DigRouteAction`, `HoldAction`, `StopAction`.
- `flyToward`/`hold` are internal helpers for deposit/delivery/mining
  standoffs; they still execute through actions.

Full detail: [04-navigation.md](04-navigation.md).

### BuildingManager (`manager/building/impl`)

Operations: `break_block`, `place_block`.

Responsibilities:

- one-block break with tool-aware mining ticks and crack particles;
- block-drop calculation (reflection `Block.getDrops`, fallback
  `quantityDropped`/`getItemDropped`);
- the mining loop over `mineQueue`:
  - unloaded chunk targets are kept, not purged;
  - exposed target selection with distance/line-of-sight/layer scoring;
  - buried target handling via `findBlockingTunnelBlock`;
  - standoff selection, headroom checks, entombed-target fallback;
  - inventory-full handling: stop mining and report it (no hidden deposit trip);
  - block break progress, fortune, drops into inventory, leftover entity drops;
- `startDigRoute(ctrl, blocks, label)` for navigation-created dig routes;
- one-block placement from drone/chest/player inventory, with withdrawal/take
  fallback and metadata selection.

Actions: `BreakBlockAction` (retained; ticks the target until it is gone),
`PlaceBlockAction` (one-shot).

### InteractionManager (`manager/interact/impl`)

Operations: `interact_block`, `use_item`, `pick_entity`, `unpick_entity`.

- `interact_block` requires coordinates; right-clicks/activates one block.
  `InteractBlockAction` also handles liquid scooping via `InventoryService`.
- `use_item`: if `entityId`/`attack` is present, `AttackEntityAction`; otherwise
  `UseItemAction` for one held-item use, optionally on a block.
- `pick_entity` mounts one entity/player; `UnpickEntityAction` dismounts.
- Movement into reach is handled by the SDK/RPC `needsMovement` standoff contract,
  not by chaining another action.

Actions: `InteractBlockAction`, `UseItemAction`, `AttackEntityAction`,
`PickEntityAction`, `UnpickEntityAction`.

### InventoryManager (`manager/inventory/impl`)

Operations: `transfer`, `craft`.

`transfer` operation mapping:

| op | Action | Behavior |
| --- | --- | --- |
| `move` | `MoveSlotAction` | move a filtered item between slots/inventories |
| `give` | `GiveToPlayerAction` | give drone inventory to player; flies to them and completes when in reach |
| `take` | `TakeFromPlayerAction` | take filtered items from player |
| `withdraw` | `WithdrawChestAction` | pull from chest/containers |
| `deposit` | `DepositChestAction` | push drone inventory into a chest/container; flies to it and completes on arrival |
| `drop` | `DropAction` | hand the inventory to the owner, or drop it in place when they are offline |
| `suck` | `SuckItemsAction` | vacuum nearby dropped items |

`craft` uses `CraftAction` and `InventoryService.craftItem` (vanilla crafting
recipe resolution, ingredient withdrawal/take fallback, recursive crafting).

### VisualsManager (`manager/visuals/impl`)

Operations: `set_led`, `led_effect`, `spawn_particles`, `play_sound`,
`play_note`, `say`, `set_status_text`.

- `SetLedAction` sets fixed color, LED effect name, or clears it.
- `SpawnParticlesAction` spawns a particle burst.
- `PlaySoundAction` plays sound/note.
- `SayAction` sends chat through the root manager.
- `SetStatusTextAction` updates drone status text.
- `LightingAura` is owned by the manager (not an action) and runs every tick
  when `autoTorch` is enabled or cells remain; it applies/restores block light and
  syncs affected chunks.

## 6. Action inventory

### Block

| Action | Behavior |
| --- | --- |
| `BreakBlockAction` | starts one explicit break; retained while target remains |
| `PlaceBlockAction` | places one explicit block from inventory |

DTOs: `BlockRequest(x,y,z,filter,meta)`, `BlockResult(ok,error,started/placed,
x,y,z,block)`. `BlockContext` tracks target/breakOperation/ticks.

### Interaction

| Action | Behavior |
| --- | --- |
| `InteractBlockAction` | one block activation; liquid scoop support |
| `UseItemAction` | one held-item use, optional block target |
| `AttackEntityAction` | one melee attack using selected item, optional fire aspect/damage |
| `PickEntityAction` | mount one entity/player |
| `UnpickEntityAction` | dismount rider/entity |

`InteractionSupport` copies the drone transform/state to the fake player used by
item use/attack. DTOs: `InteractionRequest`, `InteractionResult`.

### Inventory

`CraftAction`, `DepositChestAction`, `DropAction`, `GiveToPlayerAction`,
`MoveSlotAction`, `SuckItemsAction`, `TakeFromPlayerAction`,
`WithdrawChestAction`. DTOs: `InventoryRequest`, `InventoryResult`.

### Navigation

`NavigateAction`, `DigRouteAction`, `HoldAction`, `StopAction`.
DTOs: `NavigationMode`, `NavigateResult`, `NavigationContext`.

### Visuals

`PlaySoundAction`, `SayAction`, `SetLedAction`, `SetStatusTextAction`,
`SpawnParticlesAction`. DTOs: `VisualsRequest`, `VisualsResult`.

## 7. Action retention examples

- `BreakBlockAction` calls `context.trackBreak(x,y,z)` and retains. Each tick it
  advances the block through `BuildingManager.tickMining` and completes when the
  target is gone, the drone is dead, or `currentMineTarget` no longer matches.
- `NavigateAction` retains while the flight leg is active. It completes on
  arrival, blocked waypoint, partial-path exhaustion, or failure.
- Inventory/visual/interaction actions are one-shot unless they must travel:
  `give`, `deposit` and `drop` retain while they fly and complete on delivery.
- `DigRouteAction` queues the route, retains, and ticks the blocks itself until
  the queue and current target are empty.

## 8. Adding a category

1. Add request/result DTOs under `action/<category>/`.
2. Add context under `context/impl/`.
3. Add action classes under `action/<category>/impl/`.
4. Add manager under `manager/<category>/impl/` extending `ActionManager`.
5. Register it in `AIDroneManager`.
6. Add a translator under `harness/rpc/translate/`.
7. Route the operation in `ActionRpcTranslator` or `MasterRpcHandler`.
8. Add a master tool only if it is a genuinely generic primitive.
9. Update docs and tests.

See [11-extending.md](11-extending.md) for the full checklist.
