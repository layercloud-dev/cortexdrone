# Removed Premade Tasks

These tasks were removed from the runtime command surface on purpose. They are
not current features and must not be re-added as hidden/hard-coded paths.
Re-implement them only when explicitly requested, using the action/context/
service model and composing them from primitives where possible.

## Removed

| Task | Previous entry point | Current replacement |
| --- | --- | --- |
| `follow` | `/drone follow` + `FollowAction` | `goto` with a player position |
| `stay` | `/drone stay|stop|hover` + `StayAction` | `cancel` (stop) or a future hold skill |
| `hunt` | `/drone hunt` + `CombatService` hunt loop | inspect entities + `useItem({attack:id})`, composed by AI |
| `guard` | `/drone guard` + removed combat loop | inspect entities + attack primitives, composed by AI |
| `patrol` | `/drone patrol` + removed combat loop | inspect entities + movement/attack composition |
| `goto` | `/drone goto` + `GotoAction` | `drone.goto(x,y,z)`; the navigation manager chooses the route |
| `come` | `/drone come` + `ComeAction` | `goto` to a point in front of the player |
| `carry` | `/drone carry` + `PlayerTransportService` | `pick_entity` -> `goto` -> `unpick_entity` skill |

The code for these premade task implementations has been deleted. The help text,
tab completion and command handlers for them are also removed.

## Entity transport

Carrying a player or mob is **not** a movement action anymore. It is a skill
composed from interaction primitives:

1. `inspect('entities')` to find the target entity,
2. `pickEntity(entityId)` to mount it on the drone,
3. `goto(destination)` to travel there,
4. `unpickEntity()` to dismount.

See `src/main/resources/skills/transport_entity.json` for the JavaScript skill.

## Mining

`MiningEngine` was removed. Block break/place behavior and the mining loop now
live in `manager/building/impl/BuildingManager`. Mining itself is a skill composed by
the AI from `inspect -> goto -> breakBlock`; see
`src/main/resources/skills/mine_blocks.json`.

## Future re-implementation rules

When these tasks return:

- put operation logic in the action class, not in a service;
- keep the service as the action lifecycle manager;
- put long-running state in a context under `context/impl/`;
- prefer composing existing primitives over adding another monolithic task;
- if a task is AI-composed, save it through the skill library instead of
  hard-coding it in Java.
