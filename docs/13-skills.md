# 13 — Skill suite

Bundled workflows live in `resources/skills/`. Each skill is a small metadata
JSON plus a sibling JavaScript body, and skills call each other with
`skills.call(name, params)`. Run one with:

```json
skill_run({ "name": "guard", "params": { "duration_s": 120, "radius": 16 } })
```

Inside `execute`, the same call is `skills.call("guard", { duration_s: 120 })`.
Search with `skill({ action: "search", query: "..." })`; load the code and its
`requires` list with `action: "load"`.

## Composition

```text
mine_and_deliver -> mine_blocks + deliver_items
farm_cycle       -> harvest_crops / sugarcane_farm / melon_pumpkin_farm
                    + collect_drops + deliver_items / store_items
guard            -> guard_scan
patrol           -> guard_scan
build_structure  -> survey_build_site + place_build_plan
                    + withdraw_items + mine_blocks
build_wall       -> survey_build_site + place_build_plan
build_platform   -> survey_build_site + place_build_plan
```

`requires` is validated: `skill` action=load reports missing dependencies and
`skill_run` refuses to start with an incomplete set. Fix a primitive once and
every compound skill inherits the fix.

## Parameters

| Skill | Parameters (defaults) |
| --- | --- |
| `mine_blocks` | `filter=ores`, `radius=24`, `count=8`, `veins=true` |
| `deliver_items` | `filter=all`, `count=512`, `player`, `only_if_holding=true` |
| `mine_and_deliver` | `filter=iron_ore`, `radius`, `count`, `deliver_filter=all` |
| `collect_drops` | `radius=16`, `filter=all`, `max_clusters=12` |
| `store_items` | `radius=28`, `chest:{x,y,z}`, `filter` |
| `withdraw_items` | `filter`, `count`, `radius=32` |
| `sort_chests` | `radius=24`, `max_moves=48`, `dry_run=false` |
| `place_double_chest` | `x,y,z` or `player` |
| `hunt` | `target=hostile`, `radius=24`, `max_kills=4`, `duration_s=120` |
| `guard_scan` | `radius=16`, `max_attacks=6`, `target=hostile` |
| `guard` | `duration_s=120`, `radius=16`, `point`, `anchor=player`, `hold=true` |
| `patrol` | `waypoints:[{x,y,z}]` or `center:{x,y,z}` + `radius=12`, `loops=1`, `guard_radius=12`, `allow_dig=false` |
| `carry_player` | `player`, `destination:{x,y,z}` (defaults to the crosshair block) |
| `transport_entity` | `entity=animal`, `destination:{x,y,z}`, `radius=8` |
| `harvest_crops` | `crop=wheat` (or carrots/potatoes), `radius=24`, `limit=16`, `replant=true` |
| `sugarcane_farm` | `radius=24`, `limit=48` |
| `melon_pumpkin_farm` | `kind=melon` (or pumpkin), `radius=24`, `limit=32` |
| `chop_tree` | `radius=20`, `limit=24`, `replant=true` |
| `farm_cycle` | `crop`, `radius`, `limit`, `deliver=false`, `store=false` |
| `survey_build_site` | `x1,z1,x2,z2` or `x,z,width,depth`; `height=4`, `y` |
| `place_build_plan` | `plan`, `dry_run=false`, `allow_substitute=true`, `verify=true`, `max_place` |
| `build_structure` | `style=house`, `width=7`, `depth=7`, `height=3`, `roof_style=flat`, `windows=true`, `x/z` or `x1/z1/x2/z2`, `y`, `floor/walls/corners/roof/foundation/window_block`, `door_side`, `allow_liquid=false`, `allow_substitute=true`, `dry_run=false`, `max_place` |
| `build_wall` | `length=6`, `height=3`, `block=cobblestone`, or explicit corners |
| `build_platform` | `width=7`, `depth=7`, `block`, `bridge=false`, `y` |
| `light_area` | `radius=8`, `spacing=4`, `point` |
| `return_to_player` | `player`, `allow_dig=false` |
| `rainbow_show` | `steps=36`, `delay_ms=120` |

## Examples

```json
skill_run({ "name": "guard",
  "params": { "duration_s": 180, "radius": 20, "anchor": "player" } })
```

```json
skill_run({ "name": "patrol",
  "params": { "center": { "x": 120, "y": 64, "z": -40 }, "radius": 16, "loops": 2 } })
```

```json
skill_run({ "name": "sort_chests", "params": { "radius": 24, "dry_run": true } })
```

```json
skill_run({ "name": "farm_cycle",
  "params": { "crop": "wheat", "radius": 20, "deliver": true } })
```

```json
skill_run({ "name": "build_structure",
  "params": { "style": "house", "width": 7, "depth": 7, "height": 3,
              "roof_style": "gable", "walls": "oak_planks",
              "roof": "oak_planks", "dry_run": true } })
```

A `build_structure` dry run never touches the world. It returns the surveyed
site, slope, floor Y, clearing list, blueprint task count and the exact
material deficit. Re-run the same call without `dry_run` to withdraw materials,
clear, place and verify.

## Building safety

- `survey_build_site` reports protected columns (chests, machines, OC blocks)
  and liquid columns. `build_structure` refuses those sites instead of
  destroying them.
- The floor is placed on the highest ground in the footprint, with a foundation
  under dips, so the building never floats.
- Clearing runs top-down, then placement runs foundation -> floor -> corner ->
  wall -> window -> railing -> roof -> detail. Occupied targets are verified
  instead of overwritten.
- Substitution is opt-in and recorded. Pass `allow_substitute:false` when the
  exact materials matter.
- `place_build_plan` verifies the finished volume with a non-air box count.

## Minecraft 1.7.10 farming notes

The bundled farm skills follow vanilla 1.7.10 mechanics:

- Wheat, carrots and potatoes are mature at metadata 7; harvesting with a tool
  leaves the farmland and replanting works on the block below the crop.
- Crops need light level 9 or higher to grow and a seed planted in darkness is
  destroyed.
- Sugar cane grows up to three blocks tall and regrows from the bottom block.
  Keep the base and harvest the upper blocks; it must stay directly beside
  water and on dirt/sand.
- Melon and pumpkin stems persist and do not produce another fruit until the
  existing fruit block is removed. Break only the fruit, never the stem.
- Drops are collected by flying to each cluster and vacuuming with
  `suck_items`; a full drone stops mining instead of dropping items.
