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

The entire `behavior` package was removed. What remains are small harness
primitives: `BlockBreakAction` (one explicit block plus the navigator's
transient dig path), `BlockPlacePrimitive` (one block), `LightingAura`,
`DroneTickPipeline` (movement/single-action plumbing), and the task/session
classes. No pre-made mining, building, farming, hunting, smelting, machine-craft
or sorting executor is exposed. The remaining `mineQueue` field is only the
transient internal tunnel/self-rescue dig path; it is never populated by batched
AI commands and there is no AI-facing instruction queue.

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

`PlayerChunkWatchRegistry` tracks Forge `ChunkWatchEvent.Watch/UnWatch`, i.e.
the exact chunks the server is currently sending/keeping for players.
`PlayerChunkOwnership` also protects the vanilla view radius plus a small border
margin. Any protected chunk is never forced, loaded, re-ordered, unforced, or
released by the drone; ticket releases containing watched chunks are deferred.

## Verification

```bash
./gradlew compileJava validate test
```

`validate` enforces the modular line limits, package layout, and JS syntax.
`test` runs the intent-classifier suite.
