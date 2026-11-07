# 04 — Navigation

## Goal

The harness exposes only `goto` and `stop`.

`NavigationManager` creates a goal session and keeps it alive until arrival,
explicit stop, or proven failure. It never contains flight or waypoint code; it
delegates to actions.

## Decision order

Navigation is **automatic**: a single `goto` resolves its own route failures.
The model only supplies the destination (or a standoff).

1. **Direct** — when the fat-ray from the drone to the goal is clear, fly
   straight there. No trail lookup and no A* expansion.
2. **Trail suggestion** — `BreadcrumbService` records the last 256 passable
   positions, prunes loops, keeps the surface anchor, and suggests a backtrack
   endpoint (reconnect/surface/goalward) inside at most half the trail.
3. **Flight** — `FlightPathfinder` computes a safe A*/Theta* route. The
   manager follows while it is complete or progressing.
4. **Automatic recovery** — `NavigationManager.recover` keeps the session alive
   instead of returning a failure to the model:
   - keep an open tunnel; after two legs that never moved, dig immediately;
   - reject the trail and replan;
   - try detour goals above the goal, beside it, and around the drone;
   - **auto-tunnel** the first obstruction toward the goal
     (`TunnelPlanner`), flying to its standoff and breaking one voxel at a time;
   - fail only when the attempt budget, dig budget or recovery ceiling is
     exhausted.

## Per-call recovery arguments

Recovery policy belongs to the `goto` call, not to global config. The model
chooses it for each destination:

```js
goto({ x, y, z, allow_dig: false })                 // never damage terrain
goto({ x, y, z, max_dig_blocks: 16 })               // cheap tunnel budget
goto({ x, y, z, recovery_attempts: 12 })            // stubborn cave
```

- `allow_dig` (default `true`) — permit automatic tunnelling;
- `max_dig_blocks` (default `128`) — voxels this call may clear;
- `recovery_attempts` (default `12`) — detours (and recovery cycles without progress) before failing.

With `allow_dig:false` navigation stays non-destructive and uses only the direct
line, trail, flight and detour recovery. When tunnelling is allowed it is also
bounded by:
- `TunnelPlanner.safeToDig`: never liquids, protected containers/machines, the
  world floor/ceiling, blocks harder than 50, or a falling block directly above
  the owner player. It does not blanket-protect every block near the player,
  because the drone often carries or stands beside them while tunnelling;
- the same reach + line-of-sight rules as manual `break_block`;
- each rejected voxel is remembered for the session, so the drone never loops
  on the same wall.

`allow_dig` is decided by the model per request (for example the player says
"don't break anything"), so no server-wide switch is needed.

## Waiting semantics

`ToolInvoker` waits for `goto` on the **agent thread** until the navigation
session ends. This is safe: the server thread continues ticking navigation and
physics. The final result includes:

- `arrived:true`, or
- `ok:false, code:"navigation_failed", error:<reason>` only after direct,
  trail, flight, automatic tunnelling and detour recovery were all exhausted.

`stop` cancels the session immediately.

## Standoffs

`goto({standoffOf:{x,y,z}})` asks for a safe reachable position beside a block.
When an open face exists the returned point is within interaction range. When
the block is buried, navigation tunnels the covering voxels and stops as soon
as the block is visible and within reach; the target block itself is never dug.
Block/interaction tools use the same helper
(`FlightPathfinder.findBlockStandoff`) when a target is too far.

## Trail and escape

`get_breadcrumbs` returns the recorded trail and the last surface anchor. The
model uses it to plan a backtrack out of a cave or to reconnect with the path
that entered an area.

## Physics

`DronePhysicsService` and `FlightPathfinder.runServerSidePhysicsStep` keep the
drone from coasting or clipping. `TickManager` applies the safety step when the
drone is stuck, long-range flying, or the OC machine is not running.

Water is a valid flight corridor: A* prices it but does not block it, and the
physics step caps water speed at 0.34 without adding artificial upward
buoyancy, so the drone enters or leaves a pool instead of bobbing on its
surface. Lava is a **last-resort** corridor: A* prices it at 8.0 (water is 2.0)
so dry routes win, but it stays passable so a drone in a lava-bound cave can
escape. Lava is never a standoff or a dig target: `isDangerousLiquid` is checked
by `DronePhysicsService.hasHeadroom` and `BlockSight.isOpenForDrone`.

## Chunks

`ChunkLoadingManager` gives a navigating drone a rolling loaded corridor. It
uses Forge 1.7.10 tickets (the same API ChickenChunks builds on) and
`World.getChunkFromChunkCoords`; it never edits `loadedChunkHashMap` directly.
CodeChickenCore only exposes per-chunk tile hooks (`IChunkLoadTile`,
`ChunkExtension`), not a force-load API, so Forge tickets are the interoperable
choice even when ChickenChunks is installed.

- no AI loading for the first 10 seconds after a player joins;
- chunks inside any player's render/view distance are left to vanilla and are
  never forced or generated by the drone;
- the ticket holds the drone's 3x3, the next path waypoints and a short line
  toward the goal, capped at 25 chunks;
- chunks are generated with a small per-update budget so pathfinding does not
  stall on world generation;
- a claim is released only when the drone is more than two chunks away and no
  player can see the chunk, then `unloadChunksIfNotNearSpawn` hands it to
  vanilla's normal unload queue;
- after the last logout AI chunks are kept for 10 seconds, then released.

`ChunkCacheContext` and the direct-ray passability check call
`ChunkAccess.ensureLoaded`, so A* can read a fresh chunk only when the policy is
open. With the policy closed, unloaded chunks are treated as solid.


## Recovery order

`NavigationManager.recover()` tries, in order:

1. **Keep an open tunnel going** — once at least one voxel has been cleared,
   a trail backtrack would fly away from the hole, so the next obstruction is
   cleared first. The same dig-first path is taken after two legs that never
   moved, because detours have clearly failed.
2. reject the trail and replan (once; no world change);
3. a detour waypoint (over, around, diagonally or a local escape);
4. straight-line tunnelling of the first obstruction
   (`TunnelPlanner.nextObstruction` — a fat ray that covers the drone's
   0.75-wide body, ignoring the block the caller asked to stand beside);
5. a **vertical step** toward a goal far above or below — mine one block down
   (or up) and descend/ascend into it, exactly like a player;
6. the **3x3x4 body shell, floor included**
   (`TunnelPlanner.nextLocalObstruction`). The floor matters: a drone
   hovering at y=18.25 over deepslate at y=17 with open air above is wedged
   by the descent column, and a shell scan starting at `dy = 0` reported
   "nothing to dig" while the floor was mineable. A floor candidate is only
   accepted when the freed voxel leaves a body-sized column to sink into;
7. a **dig-aware A\*** (`DigPathfinder`) through rock, used when the drone
   sits in a pocket wider than the straight ray and taller than the shell.
   Breaking is priced by hardness (`6 + 2 * hardness`) plus 18 for falling
   blocks, 8 for lava and 2 for water; bedrock, protected tile entities and
   hardness above 50 stay walls. It is bounded to 900 expansions / 2 ms and
   clamped to a 40-block radius, so it cannot stall the server tick. The
   first solid node on the returned route is the block to break;
8. an **escape step** (`TunnelRecovery.escapeStep`) when nothing is mineable
   at all — a flooded pocket or an open drop. The candidate must be at least
   0.75 blocks closer to the goal than the drone, so this is guaranteed
   progress rather than a random walk;
9. fail only when every attempt is exhausted, the `max_dig_blocks` budget is
   spent, or the per-call `recovery_attempts` is exhausted without progress.

A successful dig resets the soft recovery counter, so a 20-block shaft is one
continuous tunnel rather than twenty failures. The hard ceiling (96 recovery
cycles) still prevents an infinite loop.

### Circling tunnels

Every dig records whether the drone actually got closer to the goal
(`TunnelRecovery.trackDigProgress`, 0.75-block hysteresis). After 24
consecutive non-approaching digs the tunnel is declared circling:
`TunnelRecovery.circling` clears the dig target, resets the detour ladder and
rejects the trail, so recovery picks a new heading instead of draining the
whole `max_dig_blocks` budget on one spot. The live log showed exactly that
failure: 128 blocks broken in a zig-zag around a single pocket, then
`dig budget exhausted`.

### Pooled water

Water is a passable medium, never a wall. Two things follow:

- the no-dig A* prices water at 2.0 and may route through it;
- if no voxel is mineable, step 8 flies a short hop toward the goal instead of
  reporting "no tunnel direction found". A drone hovering *above* a pool also
  uses this path, because the vertical planner only accepts a mineable block
  and water is not one.

### Stuck legs (Baritone-style watchdog)

A flight leg is abandoned (and recovery starts) when any of these holds:

- no displacement for 40 ticks (2 s) — the drone is physically wedged;
- no progress toward the leg waypoint for 120 ticks (6 s) — it is circling;
- the leg exceeds 60 ticks per block of its initial length (bounded to
  2 min) — the movement is taking far longer than its estimate.

This mirrors Baritone's `PathExecutor` movement timeout. Without it a blocked
waypoint could stay "active" forever and every A* replan returned the same
blocked node.

### Partial paths

A* is budgeted by wall-clock time (4 ms) and node count (1400), not a fixed
400 expansions. Partial results follow Baritone's rule: the furthest node for
the first backoff coefficient that is at least **5 blocks** from the start.
If no coefficient reaches 5 blocks, the search accepts a short segment down
to **1.5 blocks**: a drone in a tight pocket has no farther node, and returning
nothing used to push recovery straight to "no tunnel direction found" even
though two open blocks of progress existed. Each short segment is re-planned
from its end, so progress accumulates. The start node itself is never returned
as a one-waypoint path.

### Failure diagnostics

Every failed `goto` writes one `NAV Navigation failure snapshot` line with the
drone position, goal, failed waypoint, path/recovery counters, the whole 3x3x4
neighbourhood (`pass`, `liquid`, `mineable`, `hard`) and a `guess=` cause. The
guess distinguishes the cases that actually occur:

- `body embedded in …` / `head/ceiling embedded in …`;
- `N mineable block(s) touch the body but none has a reachable standoff`;
- `only the floor is mineable (N voxel(s))` — the shaft was not opened;
- `N mineable block(s) nearby, none reachable without a longer tunnel`;
- `lava nearby (N voxels), no safe standoff`;
- `N protected/unbreakable block(s) block the line`;
- `open pocket with no mineable block in the 3x3x4 shell`.

When tunnelling gives up, a second `NAV Tunnel search exhausted` line records
what each planner returned (`straight`, `vertical`, `local`, `digFirst`,
`digExpanded`, `digGoal`, `rejected`, `floor`, `shellMineable`, `liquids`), so
an empty shell can be told apart from a rejected candidate or an A* budget
overrun. When a tunnel is re-routed, `NAV Tunnel circling` reports the useless
dig count and budget.

### Buried `standoff_of` targets

When `goto({standoffOf})` is called for a block with no open face (a buried
ore, a chest in a wall), the target is kept as a tunnel goal. The navigator
clears the covering voxels one at a time and succeeds as soon as the block is
visible and within reach. The target block itself is never dug by navigation.

### Request tokens

Every `goto` receives a monotonic `request_id`. The tool waiter resolves only
when *that* request reaches a terminal outcome (`arrived`, `failed`,
`stopped`, `replaced`, `cancelled`). A session replaced by a newer task can no
longer be reported as `arrived:true`.

Tunnelling never touches a protected container or machine: chests, crates,
furnaces, machines and OpenComputers blocks are property even when empty.
Decorative/modded stone and ore blocks that merely carry a tile entity are
mineable. Every block that is broken is recorded in `DamageJournal` for
`repair_trail`.

Defaults are `max_dig_blocks=128` and `recovery_attempts=12`; both are per-call
arguments on `goto`.
