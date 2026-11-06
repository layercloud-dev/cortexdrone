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
- the JavaScript `drone` methods;
- the JavaScript `drone` methods.

## Providers

| Provider | Tools |
| --- | --- |
| `SessionTools` | `state`, `cancel` |
| `NavigationTools` | `goto`, `stop`, `get_breadcrumbs` |
| `BlockTools` | `break_block`, `place_block` |
| `InteractionTools` | `interact_block`, `use_item`, `attack_entity`, `pick_entity`, `unpick_entity` |
| `InventoryActionTools` | `move_item`, `give_to_player`, `take_from_player`, `withdraw_chest`, `deposit_chest`, `drop_items`, `suck_items`, `craft` |
| `InventoryQueryTools` | `get_player`, `get_player_inventory`, `get_chest`, `scan_chests`, `find_item_in_chests`, `find_item`, `get_recipes`, `scan_furnaces`, `get_machine_recipes`, `get_machine_recipe`, `scan_machines`, `machine_ingredients` |
| `VisualsTools` | `set_led`, `set_light`, `spawn_particles`, `play_sound`, `play_note`, `say`, `set_status_text` |
| `WorldTools` | `get_block`, `get_blocks_in_box`, `get_top_y`, `scan_room`, `scan_blocks`, `scan_veins`, `find_distant`, `get_entities`, `get_drops`, `raycast_player`, `doc_lookup`, `find_build_site`, `plan_structure` |
| `MemoryTools` | `memory` (remember/search/recall/list/forget) |
| `SkillTools` | `skill`, `skill_refine` |
| `WebTools` | `web_search`, `web_fetch`, `wiki_lookup` |
| `CodeTools` | `execute` |
| `ScanTools` | `poll_scan` |

## Argument conventions

- The public schema uses `snake_case`. Domain views read camelCase, so
  `ToolInvoker` adds camelCase aliases after validation. Both styles work.
- Numeric strings and boolean aliases are coerced.
- `filter` accepts item/block names, registry names, ore dictionary tags, and
  category words (`ores`, `logs`, `liquid`, `all`, ...).
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
