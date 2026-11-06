# Removed Tasks and Legacy Surface

These features were removed from the runtime surface on purpose. They are not
current features and must not be re-added as hidden or hard-coded paths.
Re-implement them only when explicitly requested, using the action/context/
manager/service model and composing them from primitives where possible.

## Removed premade tasks

| Task | Previous entry point | Current replacement |
| --- | --- | --- |
| `follow` task | `/drone follow` + `FollowAction` + `Mode.FOLLOW` | `goto` to a player position, repeated by the model if continuous following is wanted |
| `stay` task | `/drone stay|stop|hover` + `StayAction` + `Mode.STAY` | `drone.stop()`; an idle drone already holds position |
| `hunt` | `/drone hunt` + `CombatService` hunt loop | inspect entities + `useItem({attack:id})`, composed by AI |
| `guard` | `/drone guard` + removed combat loop | inspect entities + attack primitives, composed by AI |
| `patrol` | `/drone patrol` + removed combat loop | inspect entities + movement/attack composition |
| `goto` task | `/drone goto` + `GotoAction` | `drone.goto(x,y,z)`; the navigation manager chooses the route |
| `come` | `/drone come` + `ComeAction` | `goto` to a point in front of the player |
| `carry` | `/drone carry` + `PlayerTransportService` | `pick_entity` → `goto` → `unpick_entity` skill |

The premade task implementations, help text, tab completion and command handlers
for them are deleted.

## Removed command and alias surface

- `/ai` and `@ai` — only `/drone` and `@drone` remain.
- Chat prefixes `drone,` and `drone:` — only `@drone <message>` and
  `@<droneName> <message>` are recognized.
- `/drone` legacy shortcuts:
  - `take`, `give`, `torch`/`light`, `drop`, `chest`/`deposit`/`store`,
    `scan`.
- `/drone` alias subcommands:
  - `remove` (use `despawn`), `info`/`inv`/`inventory` (use `status`),
    `eval`/`code` (use `js`).
- Management commands that remain: `spawn`, `link`, `despawn`, `list`,
  `status`, `debug`, `config`, `js`, `help`.

Unknown `/drone` subcommands fall through to natural-language task handling.

## Removed architecture and packages

| Removed | Replacement |
| --- | --- |
| `codemode/` package | `harness/` (transport in `harness/rpc`) |
| top-level `inventory/` | `service/inventory/` |
| top-level `world/` | `service/world/` |
| top-level `navigation/` | `service/navigation/` + `service/world/raytrace/` |
| `service.ActionService` | `manager.ActionManager` |
| `service.*.*Service` execution classes | `manager.*.*Manager` |
| `action/movement/` + `context/movement/` | `action/navigation/` + `context/impl/NavigationContext` |
| `MovementService` / `MovementRpcTranslator` | `NavigationManager` / `NavigationRpcTranslator` |
| `BreadcrumbsAction` / `TrailAction` | `BreadcrumbService` (service data, not an action) |
| `CaveEscapeService`, `DigPathService`, `DigRouteAction` | `break_block` composed by the model, then `goto` again |
| `FlightPilot` | `NavigateAction` executor |
| `CombatService`, `Mode.HUNT` | interaction attack primitive + AI composition |
| `Mode` enum + `setDroneMode` | derived work state: navigation session + retained action |
| `TaskSnapshot` / `escapeResume` self-rescue restore | no internal dig work exists |
| `NavContext`, `HoldAction`, `NavigationMode.HOLD` | controller path fields + `StopAction` |
| `builtCount`, `scoopedCount` | no per-drone counters |
| `AIConfig.playerBases`, `AIConfig.savedLocations` | no location memory; use `goto` coordinates |
| `mineUntilFull`, `returnToOwnerAfterMine`, `mineFilter`, `mineExactQueueOnly` | explicit tool calls composed by the model |
| `FOLLOW` / `STAY` / `GOTO` / `MINE` / `DEPOSIT` / `DROP` behavior switch | actions + navigation session |
| per-category context packages | `context/impl/` |

## Entity transport

Carrying a player or mob is **not** a movement action. It is a skill composed
from interaction primitives:

1. `get_entities` to find the target,
2. `pickEntity(entityId)` to mount it,
3. `goto(destination)` to travel,
4. `unpickEntity()` to dismount.

See `src/main/resources/skills/transport_entity.json`.

## Mining

`MiningEngine`, the mining queue, target selection, standoff search and the dig
planner were removed. `break_block` now removes exactly one block and returns;
the model composes mining from `inspect -> goto -> breakBlock`; see
`src/main/resources/skills/mine_blocks.json`.

There is no `mineQueue`, no `currentMineTarget`, no break progress and no
navigation digging left in Java.

## Future re-implementation rules

When these tasks return:

- put operation logic in the action class, not in a manager or service;
- keep the manager as the action lifecycle owner;
- put long-running state in a context under `context/impl/`;
- put read-only data/recon/planning in `service/`;
- prefer composing existing primitives over adding another monolithic task;
- if a task is AI-composed, save it through the skill library instead of
  hard-coding it in Java;
- do not add aliases or hidden command paths.
