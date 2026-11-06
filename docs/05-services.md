# 05 — Services

Services hold domain knowledge, caches, scans, path planning and low-level storage
operations. They do not own goals, modes or action lifecycle. Where a service
exposes a mutating storage primitive (inventory moves, deposits, crafting), the
**manager/action** is still the thing that decided to do it.

## 1. Inventory services (`service/inventory`)

### `InventoryService`

Central inventory/container service. Provides:

**Static data helpers**

- `isInventoryFull`, `countInventoryItems`, `formatInventorySummary`;
- `insertIntoInventory` (stack merging, empty-slot filling, returns inserted
  count);
- `findPlaceableItemSlot`;
- `syncPlayerInventory`;
- `canInventoryAcceptAny`;
- `withdrawFromInventory` (filtered, max count);
- `suckNearbyItems` (vacuum dropped items within radius);
- `dropAllDroneItemsAt`.

**Instance storage/container operations**

- `findAllInventoryTiles(world, x,y,z, radius)` — loaded tile entities that are
  inventories, sorted by distance;
- `findNearestInventoryWithSpace` / `findNearestInventoryTile`;
- `withdrawMatchingFromNearbyChests(ctrl, owner, filter, maxCount)`;
- `depositDroneItemsIntoTile(drone, te)`;
- `takeFromPlayerInventory` / `giveToPlayerInventory` (delegated to
  `PlayerInventoryOps`);
- `moveInventorySlot` (delegated);
- `scoopLiquidAt` (delegated to `FluidOpsService`);
- `getRecipesForFilter` / `craftItem` (delegated to `CraftingService`).

It constructs and owns:

- `CraftingService` — vanilla crafting recipe lookup inside the drone inventory,
  sub-crafting, 30 s recipe cache. Never pulls from chests or the player.
- `FluidOpsService` — bucket/container discovery, liquid scoop, filled-container
  resolution.
- `ContainerSearchService` — chest/item search across nearby inventories/player,
  fuzzy item matching, distance sorting.
- `PlayerInventoryOps` — take/give/move between drone and player inventories.
- `MachineRecipeService` — machine recipe introspection (also exposed here).
- `ToolInspector` — fortune level, attack damage, fire aspect.

`CraftResult` is the crafting result DTO.

## 2. Machine services (`service/machine`)

### `MachineRecipeService`

Dynamic HBM/modified machine recipe introspection:

- parses every flat or live-edited HBM recipe JSON under the server config
  `hbmRecipes` directory (directory listing, not a hardcoded file list);
- falls back to live `GenericRecipes` / `AnvilRecipes` via reflection;
- normalizes recipe shapes into `NormalizedRecipe` and computes `Availability`;
- `getMachineRecipesForFilter(filter, machine)` — ranked/filtered recipes;
- `getBestMachineRecipe(world, player, filter, machine, radius)` — best recipe by
  output match + nearby ingredient availability;
- 3 s recipe cache.

Helpers:

- `MachineRecipeParser` — JSON schema parsing for Generic/Anvil/ArcFurnace/
  Compact/Compressor/Mixer/Coker/Electrolyser/Fractions etc., nested Mixer and
  pedestal inputs, fluid-only entries.
- `MachineRecipeRanker` — output/filter scoring, ranking, ingredient availability,
  nearby item counting with a 1 s cache.
- `MachineRecipeReflection` — reflective read of live HBM recipe objects.

### `MachineTileInspector`

- machine keyword table and discovery by class/registry/inventory/power/fluid
  capabilities;
- `isMachineTile`, `machineKind`, `normalizeMachineAlias`,
  `recipeFileToMachine`;
- `findMachineTiles(world, x,y,z, radius, machineFilter)`;
- `describeMachineTile` (JSON: kind, inventory, power/fluid state, etc.);
- `hasPower`, `hasFluid`.

### `FurnaceScanner`

Read-only furnace discovery sorted by distance. No smelting executor exists.

## 3. World services (`service/world`)

### `WorldScanner`

Facade for world recon:

- `scanRoom3D` → `RoomScanner`;
- `scanConnectedVeins` → `VeinScanner`;
- `raycastPlayerLook` / `findPlayerSupportBlock` → `PlayerTargetScanner`;
- `findDistantResourceOrBiome` — radial/chunk search for distant ores, logs,
  crops or biome affinity;
- `scanNearbyResources(world, x,y,z, radius)` — cached summary of nearby ores/
  logs/chests/etc. (5 s TTL, 4-block cells, radius cap 24);
- `findBaseLocation` — player support/base candidate search.

### `ItemClassifier`

Central block/item/entity classification and matching:

- ore dictionary names/prefixes;
- liquids, replaceable blocks, passable voxels, protected tile entities,
  mineable blocks, protected fixtures;
- ore/log/leaf/crop detection;
- underground-query heuristic;
- humanized names/identifiers;
- held-item filter resolution;
- fuzzy block/item match scoring (`scoreBlockOrItemMatch`,
  `matchesBlockOrItemName`, `matchesMineFilter`);
- item category order;
- furnace/chest/hostile entity checks;
- entity filter matching;
- `itemStackToJson`.

### `RegistryIntrospector`

Registry/reflection discovery layer:

- block cache keys and metadata normalization;
- ore dictionary names/prefixes;
- fluid lookup and empty-container checks;
- log/leaf/crop/ore detection via reflection and registry identifiers;
- underground resource token cache and crop/plant token cache;
- furnace/storage-container tile detection;
- entity type/hostile/passive identifiers;
- biome matching and registered biome tokens;
- item category order.

### `LexicalItemMatcher`

Natural-language item matching:

- tokenization (lowercasing, separators, plural normalization);
- singularization;
- Damerau–Levenshtein distance;
- fuzzy token matching;
- token-list containment;
- score for display name/full ID/meta against a raw filter.

Used by item search, crafting filters, machine ranker and skill search.

### `RoomScanner`

3D room/shelter geometry: floor, walls, ceiling, door side, interior volume,
materials. Used by `inspect('room')`.

### `VeinScanner`

Connected-component scan of ore/block veins by filter, grouped clusters with
counts/centers/distance. Used by `inspect('veins')`.

### `StructureArchitect`

Build-site finding and blueprint generation:

- `StructurePlan` (bounds, floorY, maxY, door side, clear tasks, build tasks,
  material counts, JSON);
- `findBestBuildSite(world, player, ctrl, width, depth, maxRadius)`;
- `resolveDoorSideTowardPlayer`;
- `generateStructureBlueprint(world, player, ctrl, spec)`.

Used by `inspect('buildSite')` and `inspect('plan')`.

### `PlayerTargetScanner`

- `raycastPlayerLook(world, player, maxDist)` — exact 3D DDA look target with
  block metadata/face;
- `findPlayerSupportBlock` — block under/facing the player.

### `EntityAndBiomeClassifier`

Entity classification by `EnumCreatureType`, hostile/passive, entity identifier,
biome matching, biome tokens, biome affinity.

### `ChunkAccess`

Loaded-chunk checks used everywhere to avoid loading/generating chunks during
scans or pathfinding.

### `DocLookupService`

Documentation lookup for items/recipes/machines:

- local docs/recipes/item metadata;
- HBM/mod recipe config search;
- optional web wiki summary with cache.

Used by `inspect('doc')`.

### `raytrace/`

- `ChunkCacheContext` — chunk-local block/passability cache and BSI for A*;
- `VoxelRaycaster3D` — Amanatides–Woo 3D DDA line-of-sight / ray traversal,
  exact voxel and face tracking.

## 4. Navigation services (`service/navigation`)

### `Breadcrumb` / `TrailPlan`

- `Breadcrumb`: x, y, z, skyVisible.
- `TrailPlan`: waypoints, endpoint, reason, `isUseful()`.

### `BreadcrumbService`

See [04-navigation.md](04-navigation.md). Records the last 256 passable
positions, keeps the surface anchor, prunes loops, suggests reconnect/surface/
goalward endpoints.

### `FlightPathfinder`

3D A*/Theta* pathfinding and geometry: `findFlightPath`, path cache, nearest
passable voxel, line clear, block standoff, obstruction description, leaf
clearing, set drone flight target, server physics step, player look detection,
stable follow anchor, `clearNavigation`.

### `DronePhysicsService`

Low-level motion/physics: server-side physics step, obstruction description,
nearby leaf clearing, stable follow anchor, block standoff search, player-look
test, headroom.

## 5. Machine services (`service/machine`)

`MachineRecipeService` is read-only and composes optional `MachineRecipeHook`
implementations:

- `hbm-json` — reads `config/hbmRecipes/*.json` when the directory exists;
- `hbm-live` — reads live HBM recipe objects by reflection when the HBM classes
  exist.

With no HBM present both hooks are unavailable and the service simply returns no
recipes. There is no action for machines: nothing here mutates the world, so
`action/hbm` does not exist.

## 6. Service boundaries and caches

| Service | Cache/state |
| --- | --- |
| `WorldScanner` | nearby-resource summary, 5 s TTL, 4-block cells |
| `ItemClassifier` | block display/id caches |
| `RegistryIntrospector` | ore/log/leaf/storage class caches, token caches |
| `CraftingService` | ranked recipe cache, 30 s TTL |
| `MachineRecipeService` | normalized recipe cache, 3 s TTL |
| `MachineRecipeRanker` | nearby count cache, 1 s TTL |
| `DocLookupService` | web doc cache |
| `FlightPathfinder` | path signature cache on controller (60 ticks) |
| `BreadcrumbService` | per-controller trail (256 points) |
| `InventoryService` | container discovery is on demand; no global item cache |

Caches are intentionally short-lived. World changes are expected to invalidate
results quickly; callers should not treat scan results as authoritative beyond
one action.
