# 07 — Tools

## Registry

`ToolSpec` declares:

- `name` (snake_case, OpenAI-compatible);
- `description` (written for the model: semantics, defaults, filters, failure
  modes);
- `parameters` (JSON Schema object, `additionalProperties:false`);
- `affinity` (`MAIN` = server thread, `ASYNC` = agent thread);
- `timeoutMs`;
- `internal` (hidden from the model, still callable from JS/transport).

`ToolRegistry` merges all providers. Duplicate declarations, duplicate bindings
and unbound declarations are startup errors. `Harness.create` calls
`requireAllBound()`.

The registry generates:

- the OpenAI `tools[]` array (`toOpenAiTools`);
- the compact prompt catalog (`toPromptCatalog` / `Harness.registryCatalog`);
- the JavaScript `drone` methods.

## Providers

| Provider | Tools |
| --- | --- |
| `SessionTools` | `state`, `cancel` |
| `NavigationTools` | `goto`, `stop`, `get_breadcrumbs` |
| `BlockTools` | `break_block`, `place_block`, `replace_block`, `repair_trail` |
| `InteractionTools` | `interact_block`, `use_item`, `attack_entity`, `pick_entity`, `unpick_entity` |
| `CombatTools` | `get_entity_health`, `attack_plan` |
| `InventoryActionTools` | `move_item`, `give_to_player`, `take_from_player`, `withdraw_chest`, `deposit_chest`, `drop_items`, `suck_items`, `craft` |
| `InventoryQueryTools` | `get_player`, `get_player_inventory`, `get_chest`, `scan_chests`, `find_item_in_chests`, `find_item`, `get_recipes`, `scan_furnaces`, `get_machine_recipes`, `get_machine_recipe`, `scan_machines`, `machine_ingredients` |
| `VisualsTools` | `set_led`, `set_light`, `spawn_particles`, `play_sound`, `play_note`, `say`, `set_status_text` |
| `QuestionTools` | `ask_player` |
| `WorldTools` | `get_block`, `get_blocks_in_box`, `get_top_y`, `scan_room`, `scan_blocks`, `scan_veins`, `find_distant`, `get_entities`, `get_drops`, `raycast_player`, `find_oredict`, `doc_lookup`, `find_build_site`, `survey_build_site`, `plan_structure` |
| `MemoryTools` | `memory` (remember/search/recall/list/forget) |
| `LessonTools` | `lesson` (add/search/list/forget/use) |
| `SkillTools` | `skill`, `skill_run`, `skill_refine` |
| `WebTools` | `web_search`, `web_fetch`, `wiki_lookup` |
| `CodeTools` | `execute` |
| `ScanTools` | `poll_scan` |
| `OcTools` | `oc_state`, `oc_methods`, `oc_call`, `oc_machine` |

## OpenComputers surface

`OcTools` exposes the whole OpenComputers component API rather than a fixed
subset. `oc_methods` enumerates the live `@Callback` annotations on the drone's
component, so OC callbacks and addon components appear without a mod update.

| Tool | Kind | Purpose |
| --- | --- | --- |
| `oc_state` | read | machine state, component names/count, energy, tier, status text, LED |
| `oc_methods` | read | discover/search the callback surface (`query`, `limit`, `docs`) |
| `oc_call` | action | invoke one callback: `move`, `detect`, `compare`, `suck`, `drain`, `place`, `use`, ... |
| `oc_machine` | action | machine lifecycle: `start`, `stop`, `pause`, `signal` |

Reads use `OcStateService`; writes use `OcManager` -> `OcCallAction` /
`OcMachineAction`. `oc_call` refuses any name that is not in the discovered
callback set, so the full OC API is reachable without exposing arbitrary Java.
Arguments are positional and follow the OC Lua call order:

```js
return drone.ocCall({ method: 'move', args: { 1: 10, 2: 1, 3: 0 } });
return drone.ocCall({ method: 'compare', arguments: '[1, "minecraft:stone"]' });
return drone.ocMachine({ control: 'signal', value: 'cortexdrone', payload: { 1: 'ping' } });
```


## Combat reconnaissance

`get_entity_health` and `attack_plan` are read-only. The first returns the live
health pool (health, max, absorption, armor, Resistance level, hurt-immunity
ticks); the second adds the drone's selected weapon, damage per hit after
armor/resistance, `attacks_to_kill`, `next_attack_in_ms` and a seconds estimate.
`can_damage:false` plus `blocker` means more swings cannot help — a player,
an invulnerable target, or damage fully absorbed.

`attack_entity` still performs exactly one physical hit; vanilla ignores hits
inside the hurt-resistance window, so a script should pace swings with
`attack_plan.next_attack_in_ms`. The `attack_to_death` skill packages that loop
and is used by `hunt`, `guard_scan`, `guard` and `patrol`.

## Skills

`skill` answers what exists (search/list/load), `skill_run` executes a stored
skill with `params`, and every stored skill is also a callable function inside
`execute`:

```js
// all three forms run the same workflow
skills.call('carry_player', { destination: dest })
skills.carry_player({ destination: dest })
skills.carryPlayer({ destination: dest })
```

`skills.fn(name)` returns the function without running it, so it can be stored
or passed to `map`/`filter`; `skills.names()` lists every loaded skill;
`skills.search/load` return metadata and code. Names are matched ignoring case
and separators, and the helper names (`search`, `load`, `call`, `run`, `fn`,
`names`) always win over a skill that shares one. `skill_refine` compiles a
successful workflow into a new version with a `requires` list.

A bundled skill is a small metadata JSON plus a sibling `.js` body. Skills call
each other instead of duplicating logic:

```js
// mine_and_deliver
var mined = skills.call('mine_blocks', { filter: params.filter, count: params.count });
return skills.call('deliver_items', { filter: 'all' });
```

This keeps each workflow readable and makes one fix (for example better drop
pickup) propagate to every compound skill. The full suite is listed in
[09-resources.md](09-resources.md).

## Building workflow

`survey_build_site` is read-only: per-column ground height, slope, headroom,
liquids, trees, protected fixtures and the exact clearing list. `plan_structure`
returns ordered tasks with an explicit phase, door coordinates and material
totals. `place_build_plan` clears top-down, places foundation -> floor -> corner
-> wall -> window -> railing -> roof, verifies occupied targets and substitutes
only when allowed. `build_structure` chains them and performs a material audit
with `dry_run` before touching the world.

## Working style

The system prompt requires recon -> plan -> one `execute` script -> verify.
Use `execute` for anything with more than one step or more than one target; a
chain of single tool calls costs a full model round trip per call. After a
successful workflow call `skill_refine`; record a `lesson` when a failure or a
non-obvious workaround taught a rule.

## Argument conventions

- The public schema uses `snake_case`. Domain views read camelCase, so
  `ToolInvoker` adds camelCase aliases after validation. Both styles work.
- Numeric strings and boolean aliases are coerced.
- `filter` accepts item/block names, registry names, ore dictionary tags, and
  category words (`ores`, `logs`, `liquid`, `all`, ...).
- OreDictionary matching is automatic and explicit tags are supported:
  `tin` resolves to `oreTin`/`ingotTin`, `iron ingot` to `ingotIron`, and
  `#oreTin` / `oredict:plankWood` / `dict:logWood` pin the exact tag.
  `find_oredict` lists the tags and stacks a name maps to; `doc_lookup` and
  `scan_blocks` results carry the resolved `oreDict` tags.
- Coordinates are block coordinates unless documented as decimals.
- Block and interaction targets must be within 4.5 blocks **and** have a clear
  voxel line of sight. `BlockSight` rejects mining/placing/activating through a
  wall even when the centre is close, returning `block_occluded` with the
  covering block or `needsMovement` with a visible standoff.
- `count=0` on transfers means "as much as fits/possible".
- Radius/limit values are clamped by the tool.

## Error envelope

Every failure is data:

```json
{
  "ok": false,
  "code": "invalid_arguments|unknown_tool|timeout|cancelled|navigation_failed|...",
  "error": "human-readable reason",
  "hint": "what to change on the next attempt",
  "retryable": true
}
```

Inside `execute`, `ok:false` throws a JavaScript `ToolError` with `message`,
`tool` and `toolResult`. It is catchable with `try/catch`.

## Thread affinity

- `MAIN` touches Minecraft: world reads, navigation, actions, inventory,
  telemetry, scans.
- `ASYNC` does not touch the world: `execute`, web tools, skill search/refine.

`ToolInvoker` marshals `MAIN` calls to the server thread with a per-tool
## Container access

A container slot is a physical interaction, not a remote read.

- **Reach + line of sight.** Writes use the same 4.5-block reach and visible-face
  rule as block actions (`ContainerAccess`). Out of reach returns
  `needsMovement` + `standoff`; the JS binding flies there and retries.
- **No player-relative search.** `withdraw_chest` / `move_item` only consider
  containers the drone can open. The old version also searched around the
  player, so the drone could take items from a chest dozens of blocks away.
- **Animation.** Transfers go through `IInventory.openInventory()` /
  `closeInventory()`, the same hook a `ContainerChest` uses. A chest lid opens
  with the `random.chestopen` sound; other containers run their own open hook.
  The drone also swings the fake player's arm for targets with no inventory
  hook.
- **Furnaces stay vanilla.** The mod never writes `furnaceCookTime` or
  `currentItemBurnTime` and never ticks a furnace. Fuel and input go in from
  within reach; the world does the smelting at normal speed.

## Damage journal and repair

Navigation tries a detour before tunnelling, so an obstacle is flown around
when possible. When it does dig, `NavigationManager` records the block, metadata
and tile NBT in `DamageJournal` *before* the break.

`repair_trail` restores those blocks newest-first, re-creating tile entities. It
skips any slot that is no longer air, so it never overwrites newer changes.

## Visit memory

When the drone actually reaches a container or machine, `PlaceMemory` stores a
`machine` entry with its position and the last few breadcrumb points. Entries
gained through a tunnel are tagged `damaged` and point at `repair_trail`. Only
reachable targets are recorded — a scan hit the drone never visited is not a
trail.

## Scan origins

Every scan and radius starts at the **drone**: `scan_blocks`, `scan_room`,
`scan_veins`, `find_distant`, `get_entities`, `find_build_site`,
`scan_machines`, and the recipe-availability sweep. The drone is where the
machine, its energy and its loaded chunks are, so a drone-centred scan does not
silently miss everything because the player walked away.

Each of those tools accepts `anchor: "drone"` (default) or `"player"`. An
explicit `x`/`y`/`z` always wins. `get_drops` was already drone-centred.

`scan_blocks` is the advanced search:

| Option | Meaning |
| --- | --- |
| `filter` | block/registry/ore-tag/category |
| `shape` | `cube`, `sphere`, `cylinder`, `shell` (outer layer) |
| `radius` | 1..64, capped at 32 for time-sliced jobs |
| `min_y` / `max_y` | vertical window |
| `limit` | max blocks returned, 1..2000 |
| `include_air` | include air in the sample |
| `x` / `y` / `z` | explicit centre |

`shell` finds surface ores and cave walls; `cylinder` is a vertical column
around the drone; `sphere` keeps a fair Euclidean distance.

timeout. Large `scan_blocks` calls become a time-sliced `ScanJobs` job and return
`pending:true` + `job_id`; JS `drone.scanBlocks` auto-polls, direct model calls
should use `poll_scan`.

## JavaScript surface

```js
const st = drone.state();
const found = drone.scanBlocks({ filter: 'iron_ore', radius: 24 });
for (var i = 0; i < found.blocks.length && i < 8; i++) {
  const b = found.blocks[i];
  drone.breakBlock({ x: b.x, y: b.y, z: b.z });
}
const p = drone.getPlayer();
drone.goto({ x: p.blockX, y: p.blockY + 2, z: p.blockZ });
return drone.giveToPlayer({ filter: 'iron_ore', count: 512 });
```

`drone.breakBlock` retries after moving to the returned standoff; `goto` waits
for arrival; `scanBlocks` polls large jobs.
