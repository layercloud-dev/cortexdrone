# 12 — Source inventory

## Root

| Class | Role |
| --- | --- |
| `CortexDroneMod` | Forge entry point, tick events, chat, agent pool |
| `AIDroneManager` | Lifecycle, spawn/link/despawn, controller registry, config |
| `DroneController` | Per-drone identity + entity link |
| `TickProfiler` | Server tick timing diagnostics |

## Harness

| Class | Role |
| --- | --- |
| `Harness` | Composition root: registry, invoker, JS, LLM, refiner, agent |
| `harness.agent.AgentService` | Native tool-calling loop, context, chat output |
| `harness.agent.LlmClient` | OpenAI-compatible HTTP client with tool calls |
| `harness.agent.LlmResponse` / `ToolCall` | Parsed assistant turn / call |
| `harness.agent.Conversation` | Bounded message log, compaction, result caps |
| `harness.agent.ContextRenderer` | Markdown live context + tool catalog |
| `harness.agent.LoopTracer` | Structured turn/supersession logging |
| `harness.agent.AgentResources` | Prompt resource loader |
| `harness.js.JsEngine` | Rhino runtime, bindings, budgets, error envelopes |
| `harness.js.JsSource` | await/async, modern-operator, spread compatibility |
| `harness.js.TypeScriptEraser` | Common TypeScript annotation erasure |
| `harness.js.JsConvert` | Rhino ↔ Gson conversion and argument mapping |
| `harness.js.JsBudget` | Instruction/time/cancellation budget |
| `harness.tool.ToolSpec` / `Tool` / `ToolSchema` | Declarations and JSON schema |
| `harness.tool.ToolRegistry` | Single source of truth, duplicate/bind checks |
| `harness.tool.ToolInvoker` | Validation, MAIN/ASYNC dispatch, wait, errors |
| `harness.tool.ToolValidator` | Required/type/enum/coercion checks |
| `harness.tool.ScanJobs` | Time-sliced large scan job |
| `harness.tool.ToolCatalog` | Merges provider declarations |
| `harness.tool.ToolContext` | Per-call services/controller/loop |
| `harness.tool.ToolJson` / `ToolArgs` | JSON helpers and camel aliases |
| `harness.lesson.Lesson` | Learned rule model, dedupe metadata, directory path |
| `harness.lesson.LessonMarkdown` | LESSON.md reader/writer |
| `harness.lesson.LessonStore` | LESSON.md directories, dedupe, search, context selection |
| `harness.memory.MemoryEntry` | Persistent fact/location model |
| `harness.memory.MemoryCapture` | Deterministic remember/location extraction |
| `harness.memory.MemoryStore` | Memory storage, dedupe, search, context selection |
| `harness.skill.Skill` | Skill model, docs, version/usage metadata |
| `harness.markdown.Frontmatter` | Shared YAML frontmatter parser (skills, lessons) |
| `harness.skill.SkillMarkdown` | SKILL.md reader/writer |
| `harness.skill.SkillLibrary` | Bundled + learned SKILL.md directories, composition |
| `harness.skill.SkillSearch` | BM25 + fuzzy retrieval |
| `harness.skill.SkillRefiner` | Recursive skill compiler (RLM) |
| `harness.session.DroneSession` | Active task, loop generation, notes, thoughts |
| `harness.session.SessionManager` | One session per controller |
| `harness.session.SessionJournal` | Per-task transcript files, outcome/topic index |
| `harness.session.SessionOutcome` | success/failure/cancelled/interrupted/incomplete/error |
| `harness.js.SkillRuntime` | Compiles and runs a skill body with `params`, depth-guarded |
| `service.world.SiteSurvey` | Read-only build-site columns, slope, headroom, fixtures |
| `service.world.BuildTask` | One phased blueprint placement |
| `service.world.StructureArchitect` | Pad search + phased house/wall/tower/platform/bridge plans |
| `harness.task.TaskCoordinator` | Single-session intent policy |
| `harness.task.TaskIntentClassifier` / `TaskIntent` | Deterministic intent |

## Tool providers

| Class | Tools |
| --- | --- |
| `harness.tools.SessionTools` | `state`, `cancel` |
| `harness.tools.NavigationTools` | `goto`, `stop`, `get_breadcrumbs` |
| `harness.tools.BlockTools` | `break_block`, `place_block` |
| `harness.tools.InteractionTools` | `interact_block`, `use_item`, `attack_entity`, `pick_entity`, `unpick_entity` |
| `harness.tools.InventoryActionTools` | transfer/craft tools |
| `harness.tools.InventoryQueryTools` | player/chest/recipe/machine inspection |
| `harness.tools.VisualsTools` | LED/light/particles/sound/chat/status |
| `harness.tools.WorldTools` | block/room/vein/entity/build/doc tools |
| `harness.tools.MemoryTools` | `memory` |
| `harness.tools.LessonTools` | `lesson` |
| `harness.tools.SkillTools` | `skill`, `skill_refine` |
| `harness.tools.WebTools` | `web_search`, `web_fetch`, `wiki_lookup` |
| `harness.tools.CodeTools` | `execute` |
| `harness.tools.ScanTools` | `poll_scan` |

## JSON views

`harness.tools.view.*`: `StateView`, `NavigationView`, `BreadcrumbView`,
`BuildingView`, `InteractionView`, `InventoryView`, `VisualsView`. They convert
`JsonObject` to typed requests and serialize typed results. No view starts work.

## Actions and contexts

| Package | Contents |
| --- | --- |
| `action.block` | `BreakBlockAction`, `PlaceBlockAction`, request/result |
| `action.interact` | block use, item use, attack, pick/unpick + support, `InteractionState` |
| `action.inventory` | move/give/take/withdraw/deposit/drop/suck/craft actions, `CraftResult` |
| `action.navigation` | `NavigateAction`, `StopAction`, `ClearLeafObstructionAction`, `FlightController`, mode/result |
| `action.visuals` | LED/light/particles/sound/note/say/status actions, `LightingAura`, `DroneChat`, light config/result/state, `VisualsState` |
| `action.Action` | one-operation interface |
| `context.*` | `ActionContextBase` + block/navigation/interaction/inventory/visuals contexts |

## Managers

| Package | Contents |
| --- | --- |
| `manager.ActionManager` / `ActionRegistry` | lifecycle/registry |
| `manager.TickManager` | per-tick pipeline |
| `manager.navigation.*` | goal/request/result + `NavigationManager` |
| `manager.chunk.*` | `ChunkLoadPolicy`, `PlayerChunkOwnership`, `ChunkLoadingManager`, navigation chunk planner |
| `manager.building.impl` | `BuildingManager` |
| `manager.interact.impl` | `InteractionManager` |
| `manager.inventory.impl` | `InventoryManager` |
| `manager.visuals.*` | `VisualsManager` |

## Services

| Package | Contents |
| --- | --- |
| `service.inventory` | `InventoryService`, `ContainerSearchService`, `CraftingService`, `PlayerInventoryOps`, `ToolInspector` |
| `service.machine` | `MachineRecipeService`, hooks/parser/ranker/reflection, `MachineTileInspector`, `FurnaceScanner` |
| `service.navigation` | `NavigationState`, `Breadcrumb`, `TrailPlan`, `BreadcrumbService`, `FlightPathfinder`, `DronePhysicsService`, `TunnelPlanner` |
| `service.world` | `WorldScanner`, `RoomScanner`, `VeinScanner`, `PlayerTargetScanner`, `BlockSight`, `ChunkAccess`, `StructureArchitect`, `SiteSurvey`, `BuildTask`, `RegistryIntrospector`, `ItemClassifier`, `LexicalItemMatcher`, `EntityAndBiomeClassifier`, `DocLookupService`, `WebLookupService`, `raytrace/*` |

## Config, command, OC

| Class | Role |
| --- | --- |
| `config.ModConfig` | Persistent AI/agent/drone config |
| `config.ModPaths` | `config/cortexdrone/` layout |
| `config.ModPaths` | Canonical config directory and skill/lesson/session paths |
| `config.DroneLogger` | Structured in-memory + file log |
| `config.ChatRecord` / `DebugEntry` | Log records |
| `command.BaseCommand` / `command.impl.DroneCommand` | `/drone` |
| `oc.OcHardwareBridge` / `oc.impl.InertMachineArchitecture` | OC machine state bridge |

## Tests

`harness/tool/ToolRegistryTest`, `harness/tool/ToolValidatorTest`,
`harness/js/JsSourceTest`, `harness/js/JsEngineTest`,
`harness/lesson/LessonStoreTest`,
`harness/memory/MemoryStoreTest`, `harness/memory/MemoryCaptureTest`,
`harness/skill/BundledSkillSuiteTest`,
`service/world/BlockSightTest`,
`harness/skill/SkillLibraryTest`, `harness/session/DroneSessionTest`,
`harness/session/SessionJournalTest`,
`harness/task/TaskIntentClassifierTest`, `action/ActionRegistryTest`.
