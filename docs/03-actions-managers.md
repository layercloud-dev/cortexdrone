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
- `NavigationContext` — action kind, target, label, result, tick count.
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
single retained operation; its `goto`/`stop` are routed by
`NavigationRpcHandler`.

## 5. Managers and operations

### NavigationManager (`manager/navigation/impl`)

Operations: `goto`, `stop` only.

- Resolves explicit coordinates or `standoffOf`/`nearBlock`.
- Creates a `Session` (goal, phase, current leg, trail state, attempts).
- Keeps the session until arrival, explicit stop, or proven failure.
- Uses the `BreadcrumbService` suggestion and `FlightPathfinder`.
- Delegates to `NavigateAction`, `StopAction`.
- `flyToward`/`hold` are internal helpers for deposit/delivery and block
  standoffs; they still execute through actions.

Full detail: [04-navigation.md](04-navigation.md).

### BuildingManager (`manager/building/impl`)

Operations: `break_block`, `place_block`.

Responsibilities, and nothing more:

- `break_block` removes exactly one block immediately, computes its drops
  (reflection `Block.getDrops`, fallback `quantityDropped`/`getItemDropped`,
  Fortune aware), inserts them into the drone inventory and spills the rest;
- `place_block` places one block from the drone inventory only. If the block is
  not there, it fails and the model must gather or take it explicitly.

There is no mining queue, no target selection, no standoff search, no dig
planner and no break-progress state. The model chooses each block and calls the
primitive once per block. Both actions are one-shot.

Actions: `BreakBlockAction`, `PlaceBlockAction`.

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

Operations: `move_item`, `give_to_player`, `take_from_player`,
`withdraw_chest`, `deposit_chest`, `drop_items`, `suck_items`, `craft`.

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

`craft` uses `CraftAction` and `CraftingService.craftItem` (vanilla recipes from
the drone inventory only; missing ingredients fail the call, no chest/player
pull; sub-crafting stays inside the inventory).

### VisualsManager (`manager/visuals/impl`)

Operations: `set_led`, `set_light`, `spawn_particles`, `play_sound`,
`play_note`, `say`, `set_status_text`.

- `SetLedAction` sets one static colour (one-shot). There are no named or
  animated Java effects: a skill or inline Code Mode loop animates by calling
  this primitive repeatedly.
- `SetLightAction` toggles/configures the illumination aura and, while it is on,
  retains itself and re-applies the light.
- `SpawnParticlesAction` spawns a particle burst.
- `PlaySoundAction` plays sound/note.
- `SayAction` sends chat through the root manager.
- `SetStatusTextAction` updates drone status text.
- `LightingAura` is the only manager-owned visual component; there is no
  background lighting/LED loop in `TickManager`.

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

`NavigateAction`, `StopAction`.
DTOs: `NavigationMode`, `NavigateResult`, `NavigationContext`.

### Visuals

`PlaySoundAction`, `SayAction`, `SetLedAction`, `SetStatusTextAction`,
`SpawnParticlesAction`. DTOs: `VisualsRequest`, `VisualsResult`.

## 7. Action retention examples

- `BreakBlockAction` is one-shot: the block is already gone when it returns.
- `NavigateAction` retains while the flight leg is active. It completes on
  arrival, blocked waypoint, partial-path exhaustion, or failure.
- Inventory/visual/interaction actions are one-shot unless they must travel:
  `give`, `deposit` and `drop` retain while they fly and complete on delivery.

## 8. Adding a category

1. Add request/result DTOs under `action/<category>/`.
2. Add context under `context/impl/`.
3. Add action classes under `action/<category>/impl/`.
4. Add manager under `manager/<category>/impl/` extending `ActionManager`.
5. Register it in `AIDroneManager`.
6. Add a translator under `harness/rpc/translate/`.
7. Route the operation in your domain `RpcHandler`.
8. Register the tool in that handler's `ToolRegistry` with a description.
9. Update docs and tests.

See [11-extending.md](11-extending.md) for the full checklist.
