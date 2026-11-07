# 03 — Actions, contexts, managers

## Action

An `Action<C>` performs exactly one operation on a context and returns:

```java
public interface Action<C extends ActionContext> {
    void execute(C context);
    default void tick(C context) {}
    default void cancel(C context) {}
}
```

Actions are solo: they never call another action. Block work is one immediate
break or one place. There is no mining queue, target selection, dig planner or
progress state in Java.

## Context

`ActionContextBase` tracks:

- controller and player;
- operation name;
- active/retained state;
- completion/cancellation hooks.

Long-running work lives in a context, not on `DroneController`. A drone is idle
exactly when no navigation session and no retained action exist.

## Manager

`ActionManager<C, R, T>`:

1. receives a typed request;
2. creates a context;
3. creates exactly one action;
4. executes it once;
5. returns a typed result;
6. retains the action/context for ticking if requested;
7. completes, reaps or cancels it later.

`ActionRegistry` maps operation names to managers, rejects duplicates and ticks
or cancels every managed context for a controller. It contains no behavior.

| Manager | Operations |
| --- | --- |
| `NavigationManager` | `goto`, `stop` |
| `BuildingManager` | `break_block`, `place_block` |
| `InteractionManager` | `interact_block`, `use_item`, `attack_entity`, `pick_entity`, `unpick_entity` |
| `InventoryManager` | `move_item`, `give_to_player`, `take_from_player`, `withdraw_chest`, `deposit_chest`, `drop_items`, `suck_items`, `craft` |
| `VisualsManager` | `set_led`, `set_light`, `spawn_particles`, `play_sound`, `play_note`, `say`, `set_status_text` |
| `OcManager` | `oc_call`, `oc_machine` |

## Lifecycle example

```text
ToolInvoker.invoke("break_block")
  -> BlockTools (reach check)
  -> BuildingView (JSON -> BlockRequest)
  -> BuildingManager.handle
  -> BreakBlockAction.execute(context)
  -> BlockResult -> JSON envelope -> model
```

If the target is out of reach, the tool returns `needs_movement` plus a safe
`standoff`. The JavaScript binding moves there and retries; a direct model call
should `goto` the standoff and retry once.
