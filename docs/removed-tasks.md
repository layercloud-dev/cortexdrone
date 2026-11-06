# Removed Premade Tasks

These tasks were removed from the runtime command surface on purpose. They are
not current features and must not be re-added as hidden/hard-coded paths.
Re-implement them only when explicitly requested, using the action/context/
service model and composing them from primitives where possible.

## Removed

| Task | Previous entry point | Current replacement |
| --- | --- | --- |
| `follow` | `/drone follow` + `FollowAction` | `navigate`/`goto` style movement primitives |
| `stay` | `/drone stay|stop|hover` + `StayAction` | `cancel` (stop) or a future hold skill |
| `guard` | `/drone guard` + `CombatService` guard loop | inspect entities + attack primitives, composed by AI |
| `patrol` | `/drone patrol` + `CombatService` patrol loop | inspect entities + movement/attack composition |
| `goto` | `/drone goto` + `GotoAction` | `navigate` with `route:auto|direct|tunnel|up|down` |
| `come` | `/drone come` + `ComeAction` | `navigate`/`moveTo` to a point in front of the player |
| `carry` | `/drone carry` + `PlayerTransportService` | `pick_entity` -> `moveTo` -> `unpick_entity` skill |

The code for these premade task implementations has been deleted. The help text,
tab completion and command handlers for them are also removed.

## Entity transport

Carrying a player or mob is **not** a movement action anymore. It is a skill
composed from interaction primitives:

1. `inspect('entities')` to find the target entity,
2. `pickEntity(entityId)` to mount it on the drone,
3. `moveTo(destination)` to fly there,
4. `unpickEntity()` to dismount.

See `docs/skills/entity-transport.md` for the JavaScript skill.

## Future re-implementation rules

When these tasks return:

- put operation logic in the action class, not in a service;
- keep the service as the action lifecycle manager;
- put long-running state in a context under `context/<category>/impl/`;
- prefer composing existing primitives over adding another monolithic task;
- if a task is AI-composed, save it through the skill library instead of
  hard-coding it in Java.
