# 05 — Services

Services are read-only (or explicitly-invoked low-level helpers). They never
pick a goal, start an action or retain work state.

## Inventory

`InventoryService`
: inventory summaries/counts, insertion, withdrawal, deposit, player transfer,
  nearby inventory discovery. Crafting and fluid scooping are action jobs.

`ContainerSearchService`
: `searchChestsForItem` and `findItemEverywhere`; ranks containers and reports
  per-source counts.

`CraftingService`
: read-only vanilla crafting-table introspection: recipe discovery, ingredient
  extraction and relevance ranking. `CraftAction` does the actual crafting.

`PlayerInventoryOps` / `ToolInspector`
: player-side transfers and tool suitability. Fluid scooping is the
  `InteractBlockAction` job, not a service.

## Machine

`MachineRecipeService`
: machine recipe lookup/ranking and ingredient availability.

`MachineRecipeHook` (`hbm-json`, `hbm-live`), `MachineRecipeParser`,
`MachineRecipeRanker`, `MachineRecipeReflection`
: source adapters, parsing and ranking for HBM/GT machine recipes.

`MachineTileInspector` / `FurnaceScanner`
: live tile classification, power/fluid/progress and furnace contents.

## OpenComputers

`OcStateService`
: read-only snapshot of one drone's OC machine: address, state, running/paused,
  component count and names, energy buffer, tier, status text, LED colour.

`OcComponentIntrospector`
: discovers the live `@Callback` surface from runtime annotations and invokes
  one callback by name. Nothing is hardcoded, so OC and addon components work.

## World

`WorldScanner`
: room scans, connected veins, distant resource/biome search and nearby resource
  summaries.

`RoomScanner`, `VeinScanner`, `PlayerTargetScanner`, `ChunkAccess`,
`StructureArchitect`, `SiteSurvey`, `BuildTask`, `RegistryIntrospector`, `ItemClassifier`,
`LexicalItemMatcher`, `EntityAndBiomeClassifier`
: geometry, connectivity, targeting, registry introspection, item matching and
  structure planning.

`ChunkAccess`
: loaded-chunk checks plus the policy-gated loading bridge used by A*. The
  navigation chunk manager installs its Forge-ticket loader here; with no loader
  or a closed player policy, `ensureLoaded` returns false and callers treat the
  chunk as solid.

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
