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

## Automatic tunnelling rules

Auto-tunnelling is on by default and disabled with
`navigationAllowDig=false` in `config/cortexdrone/config.json` ("damage
environment" off). When enabled it is bounded by:

- `navigationMaxDigBlocks` (default 48) cleared voxels per session;
- `navigationRecoveryAttempts` (default 8) dig/detour attempts per session;
- `TunnelPlanner.safeToDig`: never liquids, protected tile entities, the world
  floor/ceiling, blocks harder than 50, or anything within 2.5 blocks of the
  owner player;
- the same reach + line-of-sight rules as manual `break_block`;
- each rejected voxel is remembered for the session, so the drone never loops
  on the same wall.

With digging disabled, navigation still uses the direct line, trail, flight and
non-destructive detours, then fails with an explanation.

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
