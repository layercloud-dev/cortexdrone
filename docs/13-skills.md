# 13 — Skill suite

Bundled workflows live in `resources/skills/<name>/`: a `SKILL.md`
(frontmatter + usage docs) plus `code.js`. Skills call each other with
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
gather_distant   -> mine_blocks + deliver_items
ore_to_ingots    -> withdraw_items + smelt_items + deliver_items
replace_surface  -> replace_block primitive
replace_box      -> replace_block primitive
farm_cycle       -> harvest_crops / sugarcane_farm / bamboo_farm / melon_pumpkin_farm
                    + collect_drops + deliver_items / store_items
plant_farm       -> craft_items (wooden hoe) + hoe/seed use_item
fertilize_crops  -> craft_items (bone meal) + use_item
craft_items      -> withdraw_items
fetch_materials  -> withdraw_items
strip_mine       -> collect_drops
dig_shaft        -> collect_drops
quarry           -> collect_drops
build_nether_portal -> survey_build_site
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
| `mine_blocks` | `filter=ores`, `radius=24`, `count=8`, `veins=true`, `allow_dig=true`, `max_dig_blocks=96` |
| `mine_box` | `anchor=under_player`, `width=3`, `height=3`, `length=3`, `filter=all`, `max_blocks=256` or explicit `x1..z2` |
| `deliver_items` | `filter=all`, `count=512`, `player`, `only_if_holding=true` |
| `mine_and_deliver` | `filter=iron_ore`, `radius`, `count`, `deliver_filter=all` |
| `gather_distant` | `target`, `max_radius=1200`, `count=32`, `deliver=true`, `allow_dig=false` |
| `collect_drops` | `radius=16`, `filter=all`, `max_clusters=12` |
| `store_items` | `radius=28`, `chest:{x,y,z}`, `filter` |
| `withdraw_items` | `filter`, `count`, `radius=32` |
| `fetch_materials` | `materials:[{filter,count}]` or `{name:count}`, `count=64`, `radius=32` |
| `craft_items` | `filter`, `count=1`, `withdraw=true`, `radius=32`, `dry_run=false` |
| `smelt_items` | `filter`, `count=6`, `furnace:{x,y,z}`, `radius=24`, `fuel`, `clear=true`, `wait=true`, `max_wait_s` |
| `ore_to_ingots` | `ore=iron_ore`, `count=8`, `ingot`, `furnace`, `radius`, `fuel`, `deliver=true` |
| `sort_chests` | `radius=24`, `max_moves=48`, `dry_run=false` |
| `place_double_chest` | `x,y,z` or `player` |
| `hunt` | `target=hostile`, `radius=24`, `max_kills=4`, `duration_s=120` |
| `guard_scan` | `radius=16`, `max_attacks=6`, `target=hostile` |
| `guard` | `duration_s=120`, `radius=16`, `point`, `anchor=player`, `hold=true` |
| `follow_player` | `player`, `duration_s=120`, `distance=2.2`, `height=1.1`, `auto_pickup=true`, `allow_dig=false` |
| `patrol` | `waypoints:[{x,y,z}]` or `center:{x,y,z}` + `radius=12`, `loops=1`, `guard_radius=12`, `allow_dig=false` |
| `carry_player` | `player`, `destination:{x,y,z}` (defaults to the crosshair block) |
| `transport_entity` | `entity=animal`, `destination:{x,y,z}`, `radius=8` |
| `harvest_crops` | `crop=wheat` (or carrots/potatoes), `radius=24`, `limit=16`, `replant=true` |
| `sugarcane_farm` | `radius=24`, `limit=48`, middle cut above the base |
| `bamboo_farm` | `radius=24`, `limit=64`, middle cut above the base |
| `melon_pumpkin_farm` | `kind=melon` (or pumpkin), `radius=24`, `limit=32` |
| `nether_wart_farm` | `radius=24`, `limit=24`, `replant=true`, `point` |
| `cactus_farm` | `radius=24`, `limit=24`, `keep=1`, `point` |
| `plant_farm` | `crop=wheat` (or carrots/potatoes/melon/pumpkin), `x1/z1/x2/z2` or `x/z`+`width`/`length`, `y`, `max_columns=64`, `craft_hoe=true` |
| `fertilize_crops` | `radius=16`, `limit=32`, `target=crops` (or saplings/stems), `craft_meal=true` |
| `chop_tree` | `radius=20`, `limit=24`, `replant=true` |
| `farm_cycle` | `crop`, `radius`, `limit`, `deliver=false`, `store=false` |
| `survey_build_site` | `x1,z1,x2,z2` or `x,z,width,depth`; `height=4`, `y` |
| `place_build_plan` | `plan`, `dry_run=false`, `allow_substitute=true`, `verify=true`, `max_place` |
| `build_structure` | `style=house`, `width=7`, `depth=7`, `height=3`, `roof_style=flat`, `windows=true`, `x/z` or `x1/z1/x2/z2`, `y`, `floor/walls/corners/roof/foundation/window_block`, `door_side`, `allow_liquid=false`, `allow_substitute=true`, `dry_run=false`, `max_place` |
| `build_nether_portal` | `x/z`, `y`, `axis=x|z`, `clear=true`, `ignite=true`, `dry_run=false`; 14 obsidian, 2x3 interior |
| `build_wall` | `length=6`, `height=3`, `block=cobblestone`, or explicit corners |
| `build_platform` | `width=7`, `depth=7`, `block`, `bridge=false`, `y` |
| `replace_surface` | `surface=floor` (ceiling/walls/north_wall/.../all), `block`, `only_replace=all`, `max_blocks=256`, `dry_run=false` |
| `replace_box` | `x1,y1,z1,x2,y2,z2`, `block`, `only_replace=all`, `max_blocks=256`, `dry_run=false` |
| `light_area` | `radius=8`, `spacing=4`, `point` |
| `dig_staircase` | `direction=north` (or south/east/west/down), `length=16`, `start:{x,y,z}`, `headroom=2`, `width=1`, `max_blocks=256` |
| `dig_shaft` | `start:{x,y,z}`, `depth=20`, `target_y`, `size=1`, `headroom=2`, `stop_at_bedrock=true`, `collect=true` |
| `strip_mine` | `direction=north`, `length=32`, `height=2`, `branches=4`, `branch_spacing=8`, `branch_length=12`, `branch_side=left`, `start`, `max_blocks=512`, `ore_scan=true` |
| `quarry` | `x1/z1/x2/z2` or `width`/`length`, `top_y`, `depth=3`, `filter=all`, `max_blocks=512`, `collect_every=3`, `allow_liquid=false` |
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
- To change an existing floor/wall/ceiling, use `replace_surface` or
  `replace_box`, or the `replace_block` primitive directly. It restores the old
  block when placement fails, so a replacement never leaves a hole.

## Minecraft 1.7.10 farming notes

The bundled farm skills follow vanilla 1.7.10 mechanics:

- Wheat, carrots and potatoes are mature at metadata 7; harvesting with a tool
  leaves the farmland and replanting works on the block below the crop.
- Crops need light level 9 or higher to grow and a seed planted in darkness is
  destroyed.
- Sugar cane grows up to three blocks tall and regrows from the bottom block.
  Keep the base and cut the first block above it; the whole upper column pops
  in the same update. It must stay directly beside water and on dirt/sand.
- Bamboo regrows from its bottom block like sugar cane. Cut the first block
  above the base so the whole upper stalk pops at once, and leave shoots alone.
- Melon and pumpkin stems persist and do not produce another fruit until the
  existing fruit block is removed. Break only the fruit, never the stem.
- Drops are collected by flying to each cluster and vacuuming with
  `suck_items`; a full drone stops mining instead of dropping items.

- Farmland hydrates when water sits within four blocks horizontally (including
  diagonals) at the same level or one above; the blocks between do not matter.
  Hydrated farmland grows crops noticeably faster, and an unplanted dry block
  eventually reverts to dirt. `plant_farm` reports `waterNearby` instead of
  placing water, because bucket placement is a look-ray action.
- Nether wart grows only on soul sand (not soul soil) through four stages and
  is ready at metadata 3, where it drops 2-4 wart. It ignores light, never
  needs water and cannot be bone-mealed. `nether_wart_farm` harvests only
  mature plants.
- Cactus grows to three blocks on sand with air on all four sides, cannot be
  bone-mealed, and pops the whole column when a solid neighbour appears.
  `cactus_farm` cuts above the kept base so the plant regrows; never place a
  solid block beside it.
- Bone meal works on crops below age 7 and on saplings (45% per use), not on
  sugar cane, cactus or nether wart. `fertilize_crops` checks age first so no
  meal is wasted.

## Minecraft 1.7.10 production notes

- One furnace item takes 200 ticks (10 s). Fuel burn times in ticks: coal or
  charcoal 1600 (8 items), coal block 16000, blaze rod 2400, lava bucket
  20000, planks/logs/wood 300, wooden slab 150, stick or sapling 100. Sticks,
  saplings and slabs cannot finish a 200-tick item on their own.
- Furnace slots are 0 = input, 1 = fuel, 2 = output. The mod never advances
  `furnaceCookTime`; `smelt_items` loads the slots and sleeps while the world
  ticks the furnace, then withdraws the output.
- A nether portal frame is 4 wide by 5 tall of obsidian with a 2x3 interior;
  that is 14 obsidian blocks. Lighting any interior bottom cell with flint and
  steel fills the interior. Obsidian drops nothing without a diamond pickaxe.
