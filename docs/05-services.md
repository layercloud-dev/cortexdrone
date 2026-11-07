# 05 — Services

Services are read-only (or explicitly-invoked low-level helpers). They never
pick a goal, start an action or retain work state.

## Inventory

`InventoryService`
: inventory summaries/counts, insertion, withdrawal, deposit, player transfer,
  fluid scoop, crafting entry points, nearby inventory discovery.

`ContainerSearchService`
: `searchChestsForItem` and `findItemEverywhere`; ranks containers and reports
  per-source counts.

`CraftingService` / `CraftResult`
: vanilla crafting-table resolution and execution from drone inventory items.

`PlayerInventoryOps` / `FluidOpsService` / `ToolInspector`
: player-side transfers, fluid placement/pickup helpers, tool suitability.

## Machine

`MachineRecipeService`
: machine recipe lookup/ranking and ingredient availability.

`MachineRecipeHook` (`hbm-json`, `hbm-live`), `MachineRecipeParser`,
`MachineRecipeRanker`, `MachineRecipeReflection`
: source adapters, parsing and ranking for HBM/GT machine recipes.

`MachineTileInspector` / `FurnaceScanner`
: live tile classification, power/fluid/progress and furnace contents.

## World

`WorldScanner`
: room scans, connected veins, distant resource/biome search and nearby resource
  summaries.

`RoomScanner`, `VeinScanner`, `PlayerTargetScanner`, `ChunkAccess`,
`StructureArchitect`, `SiteSurvey`, `BuildTask`, `RegistryIntrospector`, `ItemClassifier`,
`LexicalItemMatcher`, `EntityAndBiomeClassifier`
: geometry, connectivity, targeting, registry introspection, item matching and
  structure planning.

`DocLookupService`
: live registry, ore dictionary, vanilla crafting and local machine recipe
  search. `include_web` is opt-in; internet lookups are separate tools.

## Web

`WebLookupService`
: keyless DuckDuckGo HTML search with a MediaWiki fallback, safe page fetch
  (private/local addresses blocked, scripts/styles stripped, output capped), and
  MediaWiki extract lookup for `minecraft.wiki` / `ntm.fandom.com`.

Runs on the agent thread. It never touches Minecraft state.

## Navigation

`BreadcrumbService` / `Breadcrumb` / `TrailPlan`
: trail sampling, loop pruning, surface anchor, backtrack planning.

`FlightPathfinder`
: no-dig A*/Theta* route search, standoff search, block packing helpers and the
  server-side physics step.

`DronePhysicsService`
: drone motion safety helpers.
