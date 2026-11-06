# 04 — Navigation

## Goal

The harness exposes only `goto` and `stop`.

`NavigationManager` creates a goal session and keeps it alive until arrival,
explicit stop, or proven failure. It never contains flight or waypoint code; it
delegates to actions.

## Decision order

1. **Trail suggestion** — `BreadcrumbService` records the last 256 passable
   positions, prunes loops, keeps the surface anchor, and suggests a backtrack
   endpoint (reconnect/surface/goalward) inside at most half the trail.
2. **Flight** — `FlightPathfinder` computes a safe no-dig A*/Theta* route. The
   manager follows while it is complete or progressing.
3. **No route** — the session fails with `navigation_error`. Navigation never
   breaks blocks. The model digs its own tunnel with `break_block` and calls
   `goto` again.

## Waiting semantics

`ToolInvoker` waits for `goto` on the **agent thread** until the navigation
session ends. This is safe: the server thread continues ticking navigation and
physics. The final result includes:

- `arrived:true`, or
- `ok:false, code:"navigation_failed", error:<reason>` with a hint to dig or
  choose a closer standoff.

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
