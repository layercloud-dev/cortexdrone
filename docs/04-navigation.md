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
   - reject the trail and replan;
   - **auto-tunnel** the first obstruction toward the goal
     (`TunnelPlanner`), flying to its standoff and breaking one voxel at a time;
   - try detour goals above the goal, beside it, and around the drone;
   - fail only when the attempt budget is exhausted.

## Per-call recovery arguments

Recovery policy belongs to the `goto` call, not to global config. The model
chooses it for each destination:

```js
goto({ x, y, z, allow_dig: false })                 // never damage terrain
goto({ x, y, z, max_dig_blocks: 16 })               // cheap tunnel budget
goto({ x, y, z, recovery_attempts: 12 })            // stubborn cave
```

- `allow_dig` (default `true`) — permit automatic tunnelling;
- `max_dig_blocks` (default `48`) — voxels this call may clear;
- `recovery_attempts` (default `8`) — dig/detour attempts before failing.

With `allow_dig:false` navigation stays non-destructive and uses only the direct
line, trail, flight and detour recovery. When tunnelling is allowed it is also
bounded by:
- `TunnelPlanner.safeToDig`: never liquids, protected tile entities, the world
  floor/ceiling, blocks harder than 50, or anything within 2.5 blocks of the
  owner player;
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
The returned point is within interaction range. Block/interaction tools use the
same helper (`FlightPathfinder.findBlockStandoff`) when a target is too far.

## Trail and escape

`get_breadcrumbs` returns the recorded trail and the last surface anchor. The
model uses it to plan a backtrack out of a cave or to reconnect with the path
that entered an area.

## Physics

`DronePhysicsService` and `FlightPathfinder.runServerSidePhysicsStep` keep the
drone from coasting or clipping. `TickManager` applies the safety step when the
drone is stuck, long-range flying, or the OC machine is not running.
