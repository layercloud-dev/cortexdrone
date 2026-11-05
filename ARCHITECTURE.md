# CortexDrone Master-Tool Harness (v1.4.0)

## What changed

The drone is no longer driven by a catalog of pre-made commands. Code Mode gets a
small **master tool surface** and the model composes the requested behavior in
JavaScript.

Removed from the tool surface:

- `mine`, `mineMany`, `mineRect`, `mineBox`, `gatherDistant`, `mine_filter`
- `hunt`, `attackEntity`, `farm`, `chop`, `fertilize`
- `buildStructure`, `placeMany`, `placeBox`, `replaceRoom`, `replaceBox`,
  `replaceBlocks`, `plugLiquids`, `sortChests`, `transferContainers`
- the AI-facing instruction queue (`queue_mine`, `queue_build`, SDK batching,
  `waitUntilIdle` task queues)
- the legacy tool-call fallback in the agent loop

The entire `behavior` package was removed. Operational code now lives in the
categorized `action` package behind the common `Action` interface and an
`ActionRegistry` that resolves master operations to their owning category:

- `action.movement.NavigationService` — route selection and breadcrumbs
- `action.block.BlockActionService` — one-block break and one-block place
- `action.interact.InteractionActionService` — block interaction and item use
- `action.effect.EffectActionService` — LED, lighting aura, particles, sounds, notes, chat/status
- `action.combat.CombatService` — internal guard/patrol/hunt ticking (not a tool)
- `service.TickService` — the single-task 20-Hz pipeline

The legacy one-shot executors (`AnvilService`, `MachineCraftExecutor`,
`SmeltingService`, `ChestSorter`) and their batch queues (fluid scoop/place,
TSP route optimization) were deleted. Machine recipes, furnaces and containers
remain read-only inspect data; there is no pre-made smelt, sort, anvil-drive or
machine-craft action. The remaining `mineQueue` field is only the transient
internal tunnel/self-rescue dig path; it is never populated by batched AI
commands and there is no AI-facing instruction queue.

## Master tool contract

| Tool | Purpose |
| --- | --- |
| `drone.state()` | Live mode/position/task/action/inventory status |
| `drone.inspect(op,args)` | One read-only query namespace (block, blocks, room, veins, entities, chests, recipes, player, machines, docs, ...) |
| `drone.moveTo(x,y,z,{route,standoffOf})` | Movement primitive; waits until arrival |
| `drone.breakBlock(x,y,z)` | Break exactly one explicit block; no filter/count/queue |
| `drone.placeBlock(x,y,z,block,meta)` | Place exactly one explicit block |
| `drone.interactBlock(x,y,z,side)` | Activate exactly one block; never chains held-item use |
| `drone.useItem(item, x,y,z,side)` | Use one item; never activates the target block first |
| `drone.transfer({op,...})` | move / give / take / drop / withdraw / deposit / suck |
| `drone.craft(filter,count)` | Vanilla crafting primitive |
| `drone.effect(kind,args)` | LED / particles / sound / note / status / chat |
| `drone.skills(op,args)` | Optional reusable-skill memory |
| `drone.cancel()` | Cancel the current session |

There are no duplicate SDK aliases: no `harness`, `world`, `player`, `kb`, or
`skills` globals; no `say`/`setStatusText` shorthand. `drone` is the only tool
global. `sleep`, `log`, and `hsvToRgb` are utilities.

## Movement then action

Mining is no longer a command. The AI:

1. inspects the world to choose coordinates,
2. calls `moveTo` (route `auto`, `tunnel`, `up`, or `direct`) and waits,
3. calls `breakBlock` for the one chosen block,
4. repeats.

`breakBlock` / `placeBlock` / `interactBlock` / `useItem` return
`{needsMovement:true, standoff}` when the target is out of reach. The SDK flies
to that standoff and retries the action, so the physical action only runs after
the navigator has arrived. The navigator can fly, carve a tunnel, route out
through the surface, or self-rescue around an obstruction.

## Single task, single session

`DroneController` owns one `TaskSession`. `TaskCoordinator` classifies every
incoming message deterministically before starting a model loop:

| Intent | Behavior while a task is active |
| --- | --- |
| `QUESTION` | Reply from live state; task continues |
| `BY_THE_WAY` | Attach a note to the running task; task continues |
| `MODIFY` | Cancel the running loop, start one replacement session |
| `CANCEL` | Cancel the loop, clear action state, hover |
| `NEW_TASK` | Treated as a replacement (one session at a time) |

No instruction is queued for later. A modification replaces the session instead
of stacking behind it. The classifier lives in
`harness/TaskIntentClassifier.java` and is covered by JUnit tests.

## Chunk ownership

The AI chunk manager is player-driven:

- after a player logs in, AI loading stays closed for 10 seconds;
- while loading is open, AI chunks are loaded/forced only inside a player's
  server view distance;
- AI-loaded chunks outside every player's view distance are dropped;
- after the last player logs out, AI chunks are kept for 10 seconds, then all
  AI tickets and AI-loaded chunks are released.

Only chunks explicitly loaded by the AI are candidates for unloading; chunks
loaded by players or other mods are never dropped by this manager. Chunks are
loaded with `World.getChunkFromChunkCoords` (chunk coordinates). Feeding chunk
coordinates to `World.getChunkFromBlockCoords` is wrong because it shifts right
by four internally.

Forge ticket semantics: `forceChunk` only records a claim and never loads;
`reorderChunk` only changes eviction order; `releaseTicket` and `unforceChunk`
only drop the mod's own claims. The drone's current chunk is still claimed while
the policy is open because Forge only ticks an entity in a non-forced chunk if a
large surrounding chunk box is loaded.

Instruction handling and world-context scans are dispatched to the server
thread, so no chunk/world object is read from the LLM or heartbeat threads.

## Verification

```bash
./gradlew compileJava validate test
```

`validate` enforces the modular line limits, package layout, and JS syntax.
`test` runs the intent-classifier suite.
