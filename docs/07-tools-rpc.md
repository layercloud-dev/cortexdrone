# 07 — Master Tools, Inspect Ops and RPC/HTTP

## 1. The master tool surface

Canonical list: `harness/ToolRegistry.master()`.

```text
state
inspect
goto
stop
break_block
place_block
interact_block
use_item
pick_entity
unpick_entity
transfer
craft
visuals
skills
cancel
```

The registry is the single source of truth: `MasterRpcHandler` returns
`ToolRegistry.master().names()` as its supported methods, and each entry also
carries a short description for introspection. Removed composite tools are
documented in `docs/removed-tasks.md` and the surface test rejects them. They are
not callable through any alias.

## 2. `drone.state()`

Returns live status. Important fields:

- identity: `id`, `name`, `owner`, position;
- task: `activeTask`, `taskPrompt`, `taskSummary`, `taskAgeMs`, `taskNotes`;
- navigation: `navigationActive`, `navigationPhase`, `navigationReason`,
  `navigationError`, `distanceToGoal`, `arrived`;
- work: `busy` (navigation or any retained action);
- lighting: `lightActive`, `lightRadius`, `lightLevel`, `lightColor`;
- inventory: `inventory`, `freeSlots`, `totalItems`, `isFull`;
- loop: `aborted` when the calling loop ID was cancelled.

## 3. `drone.inspect(op, args)`

Read-only query namespace. Ops:

| Op | Purpose |
| --- | --- |
| `block {x,y,z}` | block id/name/meta/hardness/passable/liquid/light |
| `box {x1,y1,z1,x2,y2,z2,includeAir}` | blocks in a box |
| `top {x,z}` | surface Y/material/biome |
| `room {x?,y?,z?,maxRadius}` | floor/walls/ceiling geometry |
| `blocks {filter,radius,x?,y?,z?}` | matching blocks; large scans time-sliced |
| `veins {filter,radius}` | grouped ore/vein clusters |
| `distant {target,maxRadius}` | distant resource/biome search |
| `entities {radius,filter}` | nearby entities |
| `drops {radius,filter}` | dropped items |
| `raycast {maxDist}` | player look target |
| `player {}` / `playerInventory {}` | player position/look/held/inventory |
| `recipe {filter}` | vanilla crafting recipes |
| `chest {x,y,z}` | one chest/container contents |
| `chests {radius,filter}` | scan chests for items |
| `findItem {filter,radius}` | item search everywhere nearby |
| `findItemInChests {filter,radius}` | item search only in chests |
| `furnaces {radius}` | furnace scan |
| `machineRecipes {filter,machine?}` | machine recipe list |
| `machineRecipe {filter,machine?,radius?}` | best machine recipe |
| `machines {radius,machine?}` | machine tile scan/description |
| `machineIngredients {filter,machine?,radius?}` | ingredient availability |
| `breadcrumbs {limit}` | trail snapshot + surface anchor |
| `buildSite {...}` | find a build site |
| `plan {...}` | structure blueprint |
| `doc {query,mod?}` | local/web documentation lookup |

Unknown op returns `{ok:false,error:"Unknown inspect op: ..."}`.

### Large block scans

If op is `blocks`/`scanblocks` and `radius > 32`:

- returns `{ok:true,queued:true,pending:true,jobId,radius,cappedRadius,
  truncated:true,scanMode:"time_sliced"}`;
- SDK auto-polls `poll_scan` with backoff;
- one job at a time; concurrent request gets `busy` + active `jobId`;
- cap 32 by default, absolute 64; max 400 blocks; 30 s age-out;
- result includes `scannedVoxels`, `truncated`, `done`, `blocks`.

The sync inspect path still exists for small scans and for already-on-server-thread
calls.

## 4. Movement tools

### `drone.goto(x,y,z,opts)`

- Goal-driven; manager chooses trail/flight.
- `standoffOf`/`nearBlock` resolves a safe position beside a block.
- Waits for `navigationActive === false`.
- Returns `{started,arrived,phase,goal,...state}`.
- `navigationError` explains failure.

### `drone.stop()`

- Clears navigation/path and holds position.
- Leaves the drone idle; there is no mode to set.

No `tunnel`, `digUp`, `digDown`, or `navigate` tools exist.

## 5. Block tools

### `drone.breakBlock(x,y,z,opts)`

- Exactly one explicit block.
- If out of reach: `{needsMovement:true,standoff,action:"break",distance,target}`.
- SDK goto standoff then retries.
- If no standoff: SDK goto the block coordinates, then retries.
- Breaks the block immediately; the call returns after it is gone.
- Returns `{broken:true, block}` or `{ok:false, error}`.

### `drone.placeBlock(x,y,z,block,meta,opts)`

- Exactly one block from drone/chest/player inventory.
- Checks replaceable; out-of-reach returns standoff.
- No blueprint, queue, or substitution.
- Returns `{placed,block,target}`.

## 6. Interaction tools

### `drone.interactBlock(x,y,z,side,opts)`

- One right-click/activation.
- Does not chain held-item use.
- `InteractBlockAction` also supports liquid scooping via bucket.

### `drone.useItem(itemOrSlot, x?,y?,z?,side?)`

- One held-item use, optional block target.
- Does not activate the block first.
- With `{entityId, item}` or `{attack:id}` → one melee attack, optionally fire
  aspect.
- Out-of-reach returns standoff.

### `drone.pickEntity(id)` / `drone.unpickEntity({entityId?})`

- Mount/dismount one entity/player.
- Movement into reach is handled by the SDK standoff loop.

## 7. Inventory tools

### `drone.transfer({op,...})`

Ops: `move`, `give`, `take`, `withdraw`, `deposit`, `drop`, `suck`.

- `give`/`drop`/`deposit` retain an action that flies to the target; the SDK
  waits for `busy === false`.
- Results include counts and a `flyingToPlayer`/`flyingToChest` flag while the
  action is still travelling.

### `drone.craft(filter, count)`

- Vanilla crafting primitive.
- Uses only the drone inventory. Missing ingredients return an error instead of
  pulling from chests or the player.
- Returns `CraftResult` fields (`crafted`, `item`, error).

## 8. Visuals and skills

### `drone.visuals(kind,args)`

Kinds:

| kind | Operation |
| --- | --- |
| `led` / `color` | `set_led` (fixed color or clear) |
| `ledEffect` | `led_effect` (RAINBOW/DISCO/POLICE/PULSE/BREATHE/STROBE/FIRE/OCEAN/HEARTBEAT/MATRIX) |
| `light` / `aura` | `set_light` (enable/disable, radius, level, color) |
| `particles` | `spawn_particles` |
| `sound` | `play_sound` |
| `note` | `play_note` |
| `status` | `set_status_text` |
| `say` | chat via `SayAction` |

### `drone.skills(op,args)`

Ops: `search`, `list`, `save`.

- `save` requires `name` + `code`; optional description/tags.
- `search` uses BM25 over bundled + saved skills.
- `list` returns top/bounded entries.

### `drone.cancel()`

- Cancels other loops for this controller.
- Clears action state (all managers + navigation).
- Cancels task session.
- Stops navigation/hovers.
- Returns `{cancelled:true,cancelledLoops}`.

## 9. Transport: HTTP

Server binds `127.0.0.1:<httpPort>` (default 25580).

### `GET /state`

Cached snapshot (rebuilt every 100 ticks / 5 s, on demand if empty). Includes:

- mod identity/version/timestamp;
- players (name/dimension/position/yaw/pitch/health/auth);
- drones (id/entity/uuid/name/owner/thought/target player/dimension/position/
  target/status/LED/energy/light aura/task/navigation/path/
  inventory/nearby blocks/entities);
- recent chat (50);
- recent debug (25, capped);
- TPS estimate;
- AI config (url/model/hasKey).

### `GET /debug`

Diagnostic snapshot:

- drone positions/velocity/targets/anchors/lookHold/stuck/path/nodes;
- active navigation path and retained actions;
- thought history;
- inventory summary;
- debug log (150 entries, capped to 1600 chars each).

### `POST /rpc`

Body:

```json
{
  "droneId": 1,
  "loopId": 7,
  "player": "Steve",
  "method": "goto",
  "args": { "x": 100, "y": 70, "z": -40 }
}
```

- `droneId` or `player` selects drone; fallback any live drone.
- `args` defaults to the whole body.
- `loopId` copied into args for cancellation checks.
- RPC runs on the main thread with method budget.
- 60 s HTTP wait; timeout → 504.
- Returns JSON result.

### `POST /action`

Actions:

- `js`/`execute_js` — run raw JS (bounded 60 s);
- `say`/`chat` — speak;
- `spawn` — spawn drone for player or at coordinates;
- `despawn`/`remove` — despawn and drop inventory;
- `instruct`/`command` — natural-language task;
- any other action name falls through to `executeSync` (direct RPC method).

Non-JS actions run on the server thread with a 2.5 s wait; if no result yet, the
HTTP response says `{ok:true,queued:true}`.

## 10. RPC handlers and translators

### Handlers

`MasterRpcHandler` routes the 15 master tools:

- `state` → status translator;
- `inspect` → `WorldRpcHandler` / `InventoryRpcHandler` / breadcrumbs;
- `goto`/`stop` → `ActionRpcTranslator` → navigation;
- `break_block`/`place_block` → building;
- `interact_block`/`use_item`/`pick_entity`/`unpick_entity` → interaction;
- `transfer`/`craft` → inventory;
- `visuals`/`skills` → visuals manager/translator;
- `cancel` → cancel action state.

`WorldRpcHandler` methods:

```text
get_block, get_blocks_in_box, get_top_y, scan_room, scan_blocks,
scan_veins, find_distant, get_entities, get_drops, raycast_player,
lookup_doc,
find_build_site, plan_structure
```

`InventoryRpcHandler` methods:

```text
get_player, get_player_inventory, scan_chests, find_item_in_chests,
find_item, get_chest, get_recipes, scan_furnaces,
get_machine_recipes, get_machine_recipe, scan_machines,
machine_ingredients
```

### Translators

| Translator | Responsibility |
| --- | --- |
| `ActionRpcTranslator` | category router; status/skills/light delegation |
| `NavigationRpcTranslator` | parse goto/stop, serialize result |
| `BuildingRpcTranslator` | parse block request/result |
| `InteractionRpcTranslator` | parse interaction/entity request |
| `InventoryRpcTranslator` | parse transfer/craft options |
| `VisualsRpcTranslator` | parse visuals, LED/light, status, skills |
| `BreadcrumbRpcTranslator` | trail JSON |
| `RpcArgs` / `RpcJson` | safe field helpers, ok/error builders |

## 11. SDK behavior (`resources/sdk`)

### `core-rpc.mjs`

- captures `console.log` into `logs`;
- `sleep` (max 30 s), `hsvToRgb`, `log`;
- 175 s internal kill timer;
- `rpc(method,args,opts)` POSTs to `/rpc`, retries 3×, caps large JSON to 8 KB
  unless `raw`;
- `_state`, `_waitUntil`, `_waitIdle`, `_isPendingScan`, `_normalizeScanResult`,
  `_awaitScanJob`;
- `_finalizeScript` no-op hook;
- `_isObj`.

### `drone-actions.mjs`

The `drone` object:

- `state`, `inspect`;
- `goto`, `stop`;
- `breakBlock`, `placeBlock`, `interactBlock`, `useItem`;
- `pickEntity`, `unpickEntity`;
- `transfer`, `craft`;
- `visuals`, `skills`, `cancel`.

`_awaitMovement` waits for `state.navigationActive === false`, then returns
`arrived` unless `navigationError` is set. Block/interaction helpers loop up to
3–4 times on `needsMovement`, calling `goto(standoff)`.

## 12. Error contract

Success:

```json
{ "ok": true }
```

Failure:

```json
{ "ok": false, "error": "human-readable reason" }
```

Special cases:

- movement needed: `{ok:true,needsMovement:true,standoff:{x,y,z},...}`
- large scan: `{ok:true,queued:true,pending:true,jobId,...}`
- busy: `{ok:false,busy:true,jobId?}`
- timeout: `{ok:false,timeout:true,error:...}` from JS; HTTP 504 for RPC wait
- superseded: `{ok:true,superseded:true}` (JS loop replaced)
- cancelled: `{ok:true,cancelled:true,cancelledLoops:N}`
