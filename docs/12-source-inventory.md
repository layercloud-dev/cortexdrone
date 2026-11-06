# 12 — Source Inventory (class by class)

This appendix lists every production class and its responsibility. It is a map,
not a substitute for the subsystem chapters.

## Root

| Class | Responsibility |
| --- | --- |
| `CortexDroneMod` | Forge mod entrypoint, event registration, tick/chat events, executor pools, config/skill Gson, chat/player helpers |
| `AIDroneManager` | Root composition and lifecycle: managers/services/harness, config, spawn/link/despawn, reconnect, tick dispatch, status/chat |
| `TickProfiler` | Server tick timing ring buffer, avg/p95/max/TPS, env-tuned warnings |

## `action/`

| Class | Responsibility |
| --- | --- |
| `Action` | Single-use action interface (`execute`, optional `tick`/`cancel`) |
| `block/BlockRequest` | Block action request DTO |
| `block/BlockResult` | Block action result DTO (started/placed/error/coords) |
| `block/impl/BreakBlockAction` | Starts/ticks one explicit block break |
| `block/impl/PlaceBlockAction` | Places one explicit block |
| `interact/InteractionRequest` | Interaction request DTO (coords/entity/slot/item/side) |
| `interact/InteractionResult` | Interaction result DTO |
| `interact/InteractionSupport` | Syncs fake player to drone for item/attack |
| `interact/impl/AttackEntityAction` | One melee attack with selected item |
| `interact/impl/InteractBlockAction` | One block activation / liquid scoop |
| `interact/impl/PickEntityAction` | Mount one entity/player |
| `interact/impl/UnpickEntityAction` | Dismount one entity/rider |
| `interact/impl/UseItemAction` | One held-item use, optional block target |
| `inventory/InventoryRequest` | Transfer/craft request DTO |
| `inventory/InventoryResult` | Transfer/craft result DTO |
| `inventory/impl/CraftAction` | One crafting primitive |
| `inventory/impl/DepositChestAction` | Deposit into chest or enter DEPOSIT |
| `inventory/impl/DropAction` | Drop inventory or enter DROP |
| `inventory/impl/GiveToPlayerAction` | Give inventory to player |
| `inventory/impl/MoveSlotAction` | Move filtered items between inventories |
| `inventory/impl/SuckItemsAction` | Vacuum nearby dropped items |
| `inventory/impl/TakeFromPlayerAction` | Take filtered items from player |
| `inventory/impl/WithdrawChestAction` | Withdraw from chest/container |
| `navigation/NavigateResult` | One navigation leg result (`moved/legDone/blocked/partial/needsDig/error`) |
| `navigation/NavigationMode` | Flight/hold/tunnel/dig-up/dig-down/stop modes |
| `navigation/impl/DigRouteAction` | Hands a dig route to `BuildingManager` |
| `navigation/impl/HoldAction` | Applies hold target/motion damping |
| `navigation/impl/NavigateAction` | No-dig flight leg execution (A*, waypoints, blocked detection) |
| `navigation/impl/StopAction` | Clears path and stops/hovers |
| `visuals/VisualsRequest` | Visuals request DTO |
| `visuals/VisualsResult` | Visuals result DTO |
| `visuals/impl/PlaySoundAction` | Sound/note |
| `visuals/impl/SayAction` | Chat through root manager |
| `visuals/impl/SetLedAction` | LED color/effect/clear |
| `visuals/impl/SetStatusTextAction` | Drone status text |
| `visuals/impl/SpawnParticlesAction` | Particle burst |

## `context/`

| Class | Responsibility |
| --- | --- |
| `ActionContext` | Action execution state interface |
| `ActionContextBase` | Active/retained lifecycle, identity fields, complete/cancel callbacks |
| `impl/BlockContext` | Block result/target/break tracking |
| `impl/InteractionContext` | Interaction result/target data |
| `impl/InventoryContext` | Inventory result/options |
| `impl/NavigationContext` | Mode/target/dig path/result/ticks |
| `impl/VisualsContext` | Visuals result |

## `manager/`

| Class | Responsibility |
| --- | --- |
| `ActionManager` | Typed action lifecycle base (create/execute/retain/tick/cancel) |
| `ActionRegistry` | Operation→manager map, tick/cancel all, duplicate detection |
| `TickManager` | 20-Hz pipeline: breadcrumbs, stuck, pickup, aura, navigation, modes, actions, LED, physics |
| `building/impl/BuildingManager` | Block break/place, mining loop, drops, inventory-full handling, dig routes, self-rescue restore |
| `interact/impl/InteractionManager` | Interaction/attack/pick/unpick action dispatch |
| `inventory/impl/InventoryManager` | Transfer/craft action dispatch |
| `navigation/NavigationRequest` | Goto request DTO |
| `navigation/NavigationResult` | Goto/stop acknowledgement DTO |
| `navigation/NavigationTarget` | Resolved goal position |
| `navigation/impl/NavigationManager` | Goal session, trail/flight/dig decision, action legs, arrival/failure, internal fly/hold |
| `visuals/LightConfig` | Light aura configuration DTO |
| `visuals/LightResult` | Light aura result DTO |
| `visuals/impl/LightingAura` | Torch-free light application/restore, chunk sync |
| `visuals/impl/VisualsManager` | Visuals action dispatch + lighting aura ownership |

## `service/inventory/`

| Class | Responsibility |
| --- | --- |
| `InventoryService` | Inventory/container operations, static stack helpers, delegation to crafting/fluid/container/player/machine services |
| `CraftResult` | Crafting result DTO |
| `CraftingService` | Vanilla recipe lookup, ingredient extraction, recursive crafting, caches |
| `FluidOpsService` | Bucket/container discovery, liquid scooping |
| `ContainerSearchService` | Chest/item search and fuzzy matching |
| `PlayerInventoryOps` | Take/give/move between drone and player inventories |
| `ToolInspector` | Fortune/mining ticks/attack damage/fire aspect |

## `service/machine/`

| Class | Responsibility |
| --- | --- |
| `MachineRecipeService` | HBM/mod machine recipe JSON/live introspection, ranking, best recipe, caches |
| `MachineRecipeParser` | Recipe JSON schema normalization |
| `MachineRecipeRanker` | Output/filter ranking, ingredient availability, nearby count cache |
| `MachineRecipeReflection` | Live HBM recipe reflection fallback |
| `MachineTileInspector` | Machine tile discovery/aliases/description/power/fluid |
| `FurnaceScanner` | Furnace tile scan |

## `service/navigation/`

| Class | Responsibility |
| --- | --- |
| `NavContext` | Immutable start/goal/route/world/cache snapshot + live path helpers |
| `Breadcrumb` | Trail point DTO |
| `TrailPlan` | Trail suggestion DTO |
| `impl/BreadcrumbService` | Trail recording/dedupe/pruning/surface anchor/backtrack suggestion |
| `impl/DigPathService` | Weighted 6-neighbor dig A* |
| `impl/DronePhysicsService` | Physics step, obstruction, standoff, follow anchor, tunnel blocker |
| `impl/FlightPathfinder` | No-dig 3D A*/Theta*, path cache, geometry/standoff helpers |

## `service/world/`

| Class | Responsibility |
| --- | --- |
| `WorldScanner` | Room/vein/distant/nearby resource scans, base location |
| `ItemClassifier` | Block/item/entity classification, ore dict, filters, JSON item conversion |
| `RegistryIntrospector` | Registry/reflection discovery, token caches |
| `LexicalItemMatcher` | Tokenization, fuzzy matching, scoring |
| `RoomScanner` | 3D room geometry |
| `VeinScanner` | Connected ore/block veins |
| `StructureArchitect` | Build site + blueprint generation |
| `PlayerTargetScanner` | Player look raycast/support block |
| `EntityAndBiomeClassifier` | Entity type/hostile/passive, biome matching |
| `ChunkAccess` | Loaded chunk checks |
| `DocLookupService` | Local/web documentation lookup |
| `raytrace/ChunkCacheContext` | Chunk-local block/passability cache |
| `raytrace/VoxelRaycaster3D` | 3D DDA line-of-sight/raycast |

## `harness/`

| Class | Responsibility |
| --- | --- |
| `LlmAgentService` | LLM loop, prompt/context, HTTP call, turn handling, self-correction, skill match |
| `CodeModeRuntime` | Node.js runner, SDK bundle, temp script, timeout/supersession, JSON parse |
| `LlmResponseParser` | Tolerant JSON/code salvage, think stripping, prose blocker detection |
| `PromptCompactor` | Bounded context + continuation digest |
| `AgentHeartbeat` | Premature-done rejection, continuation resurrection |
| `AgentLoopHeuristics` | Deadline, message pruning, loop/query fingerprints, nudges, progress detection |
| `LoopTracer` | Loop trace/superseded/error logging, key masking |
| `TaskCoordinator` | One-session intent handling |
| `TaskIntent` | Intent enum |
| `TaskIntentClassifier` | Deterministic intent classifier |
| `TaskSession` | Session state/notes/age |
| `SkillLibrary` | Bundled/saved skills, BM25 search, self-corrected skills |
| `RpcDispatcher` | Main-thread RPC, timeouts, time-sliced large scans, poll_scan |
| `MasterToolCatalog` | Canonical tool names + removed tools |
| `rpc/RpcHandler` | RPC handler interface |
| `rpc/impl/MasterRpcHandler` | Master tool router, state/inspect/cancel/standoff |
| `rpc/impl/WorldRpcHandler` | World/room/vein/entity/location/doc/build queries |
| `rpc/impl/InventoryRpcHandler` | Player/chest/furnace/recipe/machine queries |
| `rpc/translate/ActionRpcTranslator` | Category router |
| `rpc/translate/NavigationRpcTranslator` | goto/stop JSON |
| `rpc/translate/BuildingRpcTranslator` | break/place JSON |
| `rpc/translate/InteractionRpcTranslator` | interaction/entity JSON |
| `rpc/translate/InventoryRpcTranslator` | transfer/craft JSON |
| `rpc/translate/VisualsRpcTranslator` | visuals/state/skills/light JSON |
| `rpc/translate/BreadcrumbRpcTranslator` | breadcrumbs JSON |
| `rpc/translate/RpcArgs` | Safe JSON field helpers |
| `rpc/translate/RpcJson` | ok/error/merge helpers |

## `command/`, `config/`, `http/`, `model/`, `oc/`

| Class | Responsibility |
| --- | --- |
| `command/BaseCommand` | Command base/compareTo |
| `command/impl/DroneCommand` | `/drone` management + natural-language fallback |
| `config/AIConfig` | Env/file defaults, saved drones/bases/locations, persistence |
| `config/DroneLogger` | Debug/chat rings, subscribers, async file flush/rotation, JSON caps |
| `http/DroneHttpServer` | `/state`, `/debug`, `/rpc`, `/action`, cached snapshots |
| `http/DroneTelemetrySerializer` | State/debug JSON snapshots, TPS |
| `model/DroneController` | Per-drone mutable state, task snapshot, loops, LED/path/mining |
| `model/Mode` | FOLLOW/STAY/GOTO/MINE/DEPOSIT/DROP |
| `model/LedEffectEngine` | Color parsing, HSV, procedural LED effects |
| `model/BuildTask` | Build task DTO used by structure plans |
| `model/ChatRecord` | Chat history entry |
| `model/DebugEntry` | Debug log entry |
| `oc/OcHardwareBridge` | OC reflection: network/energy/paused/machine/component control |
| `oc/impl/InertMachineArchitecture` | No-op architecture for paused ghost machines |

## Tests

| Test | Responsibility |
| --- | --- |
| `action/ActionRegistryTest` | Manager lifecycle/registry invariants |
| `harness/MasterToolSurfaceTest` | Tool surface and removed-tool rejection |
| `harness/SkillLibraryTest` | Bundled skill load/search |
| `harness/TaskIntentClassifierTest` | Intent classification |
| `harness/TaskSessionTest` | Session lifecycle/notes |
