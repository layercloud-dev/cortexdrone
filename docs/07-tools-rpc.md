# 07 — Tools, RPC and HTTP

## 1. Tool surface

There is no catch-all tool and no router. Every tool is one explicit
operation, owned by exactly one domain handler. Handlers declare their tools in
their own `ToolRegistry`; `RpcDispatcher` merges the registries and rejects
duplicate names.

| Handler | Tools |
| --- | --- |
| `SessionRpcHandler` | `state`, `cancel` |
| `NavigationRpcHandler` | `goto`, `stop`, `get_breadcrumbs` |
| `BlockRpcHandler` | `break_block`, `place_block` |
| `InteractionRpcHandler` | `interact_block`, `use_item`, `attack_entity`, `pick_entity`, `unpick_entity` |
| `InventoryActionRpcHandler` | `move_item`, `give_to_player`, `take_from_player`, `withdraw_chest`, `deposit_chest`, `drop_items`, `suck_items`, `craft` |
| `VisualsRpcHandler` | `set_led`, `set_light`, `spawn_particles`, `play_sound`, `play_note`, `say`, `set_status_text` |
| `SkillRpcHandler` | `search_skills`, `list_skills`, `save_skill` |
| `WorldRpcHandler` | `get_block`, `get_blocks_in_box`, `get_top_y`, `scan_room`, `scan_blocks`, `scan_veins`, `find_distant`, `get_entities`, `get_drops`, `raycast_player`, `lookup_doc`, `find_build_site`, `plan_structure` |
| `InventoryRpcHandler` | `get_player`, `get_player_inventory`, `get_chest`, `scan_chests`, `find_item_in_chests`, `find_item`, `get_recipes`, `scan_furnaces`, `get_machine_recipes`, `get_machine_recipe`, `scan_machines`, `machine_ingredients` |
| `ScanRpcHandler` | `poll_scan` |

Removed router tools (`inspect`, `visuals`, `skills`, `transfer`) and removed
composite tasks (`mine`, `hunt`, `farm`, `buildStructure`, `sortChests`, ...)
must not be reintroduced. `ToolRegistryTest` asserts both lists.

The SDK method names are camelCase versions of the RPC names
(`break_block` → `drone.breakBlock`, `scan_blocks` → `drone.scanBlocks`).

## 2. `drone.state()`

Returns live status. Important fields:

- identity: `id`, `name`, `owner`, position;
- task: `activeTask`, `taskPrompt`, `taskSummary`, `taskAgeMs`, `taskNotes`;
- navigation: `navigationActive`, `navigationPhase`, `navigationReason`,
  `navigationError`, `distanceToGoal`, `arrived`, `blockedWaypoints`;
- work: `busy` (navigation or any retained action);
- lighting: `lightActive`, `lightRadius`, `lightLevel`, `lightColor`;
- inventory: `inventory`, `freeSlots`, `totalItems`, `isFull`;
- loop: `aborted` when the calling loop ID was cancelled.

## 3. World inspection tools

| Tool | Purpose |
| --- | --- |
| `get_block {x,y,z}` | block id/name/meta/hardness/passable/liquid/light |
| `get_blocks_in_box {x1,y1,z1,x2,y2,z2,includeAir}` | blocks in a box |
| `get_top_y {x,z}` | surface Y/material/biome |
| `scan_room {x?,y?,z?,maxRadius}` | floor/walls/ceiling geometry |
| `scan_blocks {filter,radius,x?,y?,z?}` | matching blocks; large scans time-sliced |
| `scan_veins {filter,radius}` | grouped ore/vein clusters |
| `find_distant {target,maxRadius}` | distant resource/biome search |
| `get_entities {radius,filter}` | nearby entities |
| `get_drops {radius,filter}` | dropped items |
| `raycast_player {maxDist}` | player look target |
| `lookup_doc {query,modId?,includeWeb?}` | local/web documentation |
| `find_build_site {width,depth,maxRadius}` | flat build pad |
| `plan_structure {...}` | structure blueprint |

Unknown tool names are rejected by the dispatcher before any handler runs.

### Large block scans

If `scan_blocks` has `radius > 32`:

- returns `{ok:true,queued:true,pending:true,jobId,radius,cappedRadius,
  truncated:true,scanMode:"time_sliced"}`;
- SDK auto-polls `poll_scan` with backoff;
- one job at a time; concurrent request gets `busy` + active `jobId`;
- cap 32 by default, absolute 64; max 400 blocks; 30 s age-out;
- result includes `scannedVoxels`, `truncated`, `done`, `blocks`.

The sync path still runs for small scans and already-on-server-thread calls.

## 4. Navigation tools

### `goto(x,y,z,opts)` / `goto({x,y,z,standoffOf,maxWaitMs})`

- Goal-driven; manager chooses trail/flight.
- `standoffOf`/`nearBlock` resolves a safe position beside a block.
- Waits for `navigationActive === false`.
- Returns `{started,arrived,phase,goal,...state}`.
- `navigationError` explains failure.

### `stop()`

- Clears navigation/path and holds position.
- Leaves the drone idle; there is no mode to set.

### `get_breadcrumbs({limit})`

- Read-only trail snapshot plus `lastSurfaceAnchor`.

No `tunnel`, `digUp`, `digDown` or `navigate` tools exist.

## 5. Block tools

### `break_block {x,y,z}`

- Exactly one explicit block.
- If out of reach: `{needsMovement:true,standoff,action:"break",distance,target}`.
- SDK flies to the standoff, then retries.
- If no standoff: SDK flies to the block coordinates, then retries.
- Breaks the block immediately; the call returns after it is gone.
- Returns `{broken:true, block}` or `{ok:false, error}`.

### `place_block {x,y,z,block,meta}`

- Exactly one block from the drone inventory.
- Checks replaceable; out-of-reach returns standoff.
- No blueprint, queue, or substitution.
- Returns `{placed,block,target}`.

## 6. Interaction tools

### `interact_block {x,y,z,side}`

- One right-click/activation.
- Does not chain held-item use.
- `InteractBlockAction` also supports liquid scooping via bucket.

### `use_item {item|slot,x?,y?,z?,side?}`

- One held-item use, optional block target.
- Does not activate the block first.
- Out-of-reach returns standoff.

### `attack_entity {entityId,item?,slot?}`

- One melee attack with the selected/held item.
- Fire aspect and attack damage come from `ToolInspector`.
- Refuses to attack players.

### `pick_entity {entityId}` / `unpick_entity {entityId?}`

- Mount/dismount one entity/player.
- Movement into reach is handled by the SDK standoff loop.

## 7. Inventory tools

One tool per operation; there is no `op` field.

| Tool | Behavior |
| --- | --- |
| `move_item` | move filtered items between inventories/slots |
| `give_to_player` | give drone items to a player; retains while flying |
| `take_from_player` | take filtered items from a player |
| `withdraw_chest` | pull from one chest, or the nearest matching chests |
| `deposit_chest` | push the drone inventory into a chest; retains while flying |
| `drop_items` | hand inventory to the owner, or drop it when offline |
| `suck_items` | vacuum nearby dropped items |
| `craft` | vanilla crafting from drone inventory only |

`give_to_player`, `drop_items` and `deposit_chest` retain an action that flies to
the target; the SDK waits for `busy === false` and merges the final state.

`craft` uses only the drone inventory. Missing ingredients return an error
instead of pulling from chests or the player.

## 8. Visuals, skills and cancellation

### Visual tools

| Tool | Operation |
| --- | --- |
| `set_led {color}` | one static color |
| `set_light {enabled,radius,level,color,toggle}` | illumination aura |
| `spawn_particles {type,count,spread,speed,x?,y?,z?}` | particle burst |
| `play_sound {sound,volume,pitch}` | sound effect |
| `play_note {semitone}` | note |
| `say {message}` | chat message |
| `set_status_text {text}` | drone status text |

Animated LED effects are not a Java feature: compose them in a skill
(`rainbow_show` is the bundled example) by looping `drone.setLed`.

### Skill tools

- `search_skills {query,limit}` — BM25 over bundled + saved skills.
- `list_skills {limit}`.
- `save_skill {name,code,description,tags}`.

### `cancel()`

- Cancels other loops for this controller.
- Clears action state (all managers + navigation).
- Cancels the task session.
- Stops navigation and hovers.
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
- RPC runs on the main thread with a per-tool budget.
- 60 s HTTP wait; timeout → 504.
- Returns JSON result.

### `POST /action`

Actions:

- `js`/`execute_js` — run raw JS (bounded 60 s);
- `say`/`chat` — speak;
- `spawn` — spawn drone for player or at coordinates;
- `despawn`/`remove` — despawn and drop inventory;
- `instruct`/`command` — natural-language task;
- any other action name falls through to `executeSync` (direct tool name).

Non-JS actions run on the server thread with a 2.5 s wait; if no result yet, the
HTTP response says `{ok:true,queued:true}`.

## 10. Handlers and translators

Every handler implements `RpcHandler`:

```java
ToolRegistry toolRegistry();
JsonObject handle(DroneController ctrl, EntityPlayerMP player,
                  String method, JsonObject args);
```

`RpcDispatcher`:

1. merges each handler's tools on construction;
2. maps `method` → owning handler;
3. rejects duplicate names at startup.

Translators (the only JSON boundary):

| Translator | Responsibility |
| --- | --- |
| `StateRpcTranslator` | `state` snapshot |
| `NavigationRpcTranslator` | parse goto/stop, serialize result |
| `BuildingRpcTranslator` | parse block request/result |
| `InteractionRpcTranslator` | parse interaction/entity request |
| `InventoryRpcTranslator` | parse inventory request |
| `VisualsRpcTranslator` | parse visuals request |
| `SkillRpcTranslator` | skill search/list/save |
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

The `drone` object exposes one method per tool, grouped as session, navigation,
blocks, interaction, inventory, visuals, skills, world inspection and inventory
inspection. `_awaitMovement` waits for `state.navigationActive === false`.
Block/interaction helpers loop up to 3–4 times on `needsMovement`, calling
`goto(standoff)`. Transfer helpers wait for idle when a flight is in progress.

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
