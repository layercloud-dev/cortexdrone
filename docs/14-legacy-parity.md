# 14 — Legacy feature parity (old SMP chats → CortexDrone)

This chapter tracks the features requested in the earlier Forge SMP sessions
(the `LayerCloudSMP` AI-drone work) against the current CortexDrone codebase.
It answers one question: *what did the player ask for that is still missing?*

The old requests came from two surfaces:

- `Build OpenComputers AI Drone mod` (`ses_e5abcb2f0ffeFtZ17lfADkNIZi`) —
  drone behaviour, tools, Code Mode and world interaction.
- `Forge SMP server setup` / `Forge SMP server mng` — server operations
  (tunnel, auth, skins, whitelist, tab). Those are not part of this repository
  and are tracked separately.

## Parity matrix

| Old request | Current implementation | Status |
| --- | --- | --- |
| Connect an AI to the server as a player-like drone | `AIDroneManager`, `Harness`, `AgentService`, OC `Drone` bridge | present |
| Copy the LayerCloud gateway config, model `deepseek-v4.1-flash` | `ModConfig`, `/drone config`, `LlmClient` | present |
| Drone stuck in place / changing colour but not moving | OC machine proxy, `DronePhysicsService`, tick pipeline | present |
| Never teleport; real 3D flight pathfinding | `FlightPathfinder`, `TrailPlan`, `BreadcrumbService`, `NavigationManager` | present |
| Fly 1000+ blocks and load only the needed chunks | `ChunkAccess`, `ChunkLoadingManager` (Forge tickets + vanilla unload queue), long-range `goto` | present |
| Stay near the player, not behind their back, so they can rotate and click | **was missing** — added `follow_player` skill with a stable side hover, look-at hold and drop pickup | added |
| `@drone harvest and replant sugarcane/crops/trees` | `sugarcane_farm`, `harvest_crops`, `chop_tree`, `farm_cycle` | present |
| `@drone mine a 3x3 under me` / tunnel / exact rectangle | **was missing** as a workflow — added `mine_box` (anchors: under/front/around or explicit corners) | added |
| Mining returns `zero blocks detected` / drops lost | `mine_blocks`, `scan_veins`, `BreakBlockAction` drop fallback | present |
| Water/liquid must never trap the drone | `ItemClassifier.isPassableVoxel` + liquid-aware pathfinding | present |
| Multi-turn thinking, inspect before act, self-correct on errors | `AgentService` loop, tool-result envelopes, `LoopTracer` | present |
| Code Mode: write JS, see output/errors, fix and rerun | in-JVM Rhino `execute`, `JsEngine`, `SkillRuntime` | present (better: no Node/RPC hop) |
| All tools available in Code Mode | registry-generated `drone.*` surface | present |
| Sky Stone vs Sky Stone Block (metadata subtypes) | `LexicalItemMatcher`, `RegistryIntrospector`, `ItemClassifier` | present |
| Player inventory read / take / give / slot move | `get_player_inventory`, `take_from_player`, `give_to_player`, `move_item` | present |
| 3D room scan with floor/ceiling/directional walls | `RoomScanner`, `scan_room` | present |
| Replace a room surface / box / arbitrary coordinates atomically | **was missing** — added `replace_block` primitive plus `replace_surface` and `replace_box` skills | added |
| Auto restock during building from player or nearby chests | `find_item` + `withdraw_items`; `place_build_plan` calls it when a material runs out | present |
| Batch 1000 block actions in one script quickly | synchronous in-process primitives; no HTTP/queue hop. Large scans are time-sliced | superseded |
| Debug log, `/debug`, `/drone debug on/tail/clear` | `DroneLogger` ring buffer + file + `/drone debug` | present |
| Hunt animals/hostiles and collect drops | `hunt`, `guard_scan`, `attack_entity`, `get_entities`, `suck_items` | present |
| Find a distant resource/biome, mine it and bring it back | `find_distant` + `mine_blocks` + `deliver_items`; **added** `gather_distant` so it is one call | added |
| Auto torch / light the area while active, colourable | `set_light` aura (torch-free, restores light) + `light_area` skill | present (superseded the torch toggle) |
| `/drone spawn/link/despawn/list/status/js/config/debug` | `DroneCommand` | present |
| `/drone follow/stay/come/guard/mine/drop/chest` direct subcommands | natural-language `@drone` + skills (`follow_player`, `return_to_player`, `guard`, `mine_blocks`, `drop_items`, `deposit_chest`) | superseded by intent, not re-added as aliases |
| Local HTTP control server `127.0.0.1:25580` (`/state`, `/action`, `/rpc`, `/debug`) | removed in the harness rewrite; the agent now runs in-game with native tool calling | intentionally not restored |

## What was deliberately not restored

- **The `25580` HTTP control server.** The old design needed an external process
  to drive the drone. CortexDrone runs the agent and Rhino inside the server JVM,
  so `/state` is `state`/`get_*` and `/action` is a tool call. Re-adding an HTTP
  surface would be an external integration decision, not a missing drone feature.
- **High-level router tools** such as `mine_box`, `replace_room` or `hunt` as
  *tools*. The registry keeps primitives small; these are now composable
  skills, which is why `ToolRegistryTest` still forbids the old tool names.
- **Node.js and `/rpc`.** Replaced by in-process Rhino; that is the core of the
  rewrite, not a regression.

## Added in this parity pass

### Primitive

- `replace_block` — one atomic replacement: verify the target item, remove the
  old block, place the new one, restore the old block if placement fails. Refuses
  protected blocks, containers, machines and unreachable targets.

### Skills

| Skill | Why |
| --- | --- |
| `follow_player` | old "follow me / don't hide behind me / let me rotate and click" request |
| `mine_box` | old "mine a 3x3 under me", tunnel and exact-rectangle requests |
| `replace_surface` | old "replace the walls/floor/ceiling of this room" request |
| `replace_box` | old "replace this 3D region / only the cobblestone" request |
| `gather_distant` | old "go 1000 blocks away, mine it and bring it back" request |

## Verification

```bash
./gradlew test
./gradlew build
```

The bundled-skill suite compiles every `code.js` body with Rhino, checks that
every `requires` dependency exists, and asserts that the new capabilities are
present. `ToolRegistryTest` asserts that the new `replace_block` primitive is
bound while the retired high-level tool names stay removed.
