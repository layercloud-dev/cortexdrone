# 04 — Navigation

Navigation is the only autonomous goal loop in CortexDrone. The harness calls
`drone.goto(...)` or `drone.stop()`; everything else is internal.

## 1. Public surface

### `goto`

```js
await drone.goto(x, y, z, { maxWaitMs })
await drone.goto({ x, y, z, standoffOf: { x, y, z }, maxWaitMs })
```

- Explicit coordinates or a block standoff.
- `standoffOf`/`nearBlock` calls `FlightPathfinder.findBlockStandoff`, with a
  nearest-passable fallback.
- Returns immediately with `{started:true, phase:"planning", goal:{x,y,z}}`.
- The SDK polls `state` until `navigationActive === false`, then reports
  `arrived:true` or `navigationError`.

### `stop`

```js
await drone.stop()
```

- Cancels the session.
- Clears path state.
- Applies a hold target at the current position.
- Leaves the drone idle (no session, no action).
- Returns `{arrived:true, phase:"stopped"}`.

## 2. Session state

`NavigationManager` stores one `Session` per controller:

| Field | Purpose |
| --- | --- |
| `goalX/Y/Z` | final goal |
| `phase` | `PLAN`, `FLIGHT` |
| `leg` | current retained action + context |
| `trailRejected` | trail suggestion failed and must not be retried |
| `usingTrail` | current flight leg targets a trail endpoint |
| `ticks` | total session ticks |
| `replanCooldown` | delay before next decision |
| `reason` | human-readable status reason |

Constants:

- arrival radius `0.85`;
- stuck escalation `12` ticks;
- max session duration `12000` ticks (10 minutes).

The session exists until it arrives, is stopped, or fails. Nothing else can
interrupt it; there is no mode and no hidden work to wait for.

## 3. Decision pipeline

`tick(controller)`:

1. If a leg exists, tick it.
2. If within `0.85` of goal, arrive.
3. If `PLAN`, decide the next leg.

`decide`:

1. If trail not rejected, ask `BreadcrumbService.planBacktrack`.
2. If a useful suggestion exists, set the leg target to its endpoint and start a
   normal `FLIGHT` leg. This is **not** a trail action.
3. Otherwise start a `FLIGHT` leg to the final goal.
4. If the flight leg reports blocked/partial exhaustion, navigation fails with
   `navigationError`. It never digs on its own.

`handleProgress`:

- `needsDig`/`blocked` on a trail leg: mark trail rejected and replan on the
  normal flight path.
- `needsDig`/`blocked` otherwise: fail the session.
- `legDone`: return to `PLAN` after a short cooldown.

`handleResult`:

- `STOP` removes the session.
- `FLIGHT` handles blocked/done as above.

## 4. Breadcrumb service

`service/navigation/impl/BreadcrumbService` is the owner of the trail. It is not
an action and it never changes navigation goals.

### Recording

- called every tick by `TickManager`;
- throttled to every 4 ticks;
- only passable voxel positions are recorded;
- skips moves smaller than 2 blocks squared;
- loop pruning: if the new point revisits a point from the last 28 within ±1
  block, points after that match are popped;
- keeps max 256 points;
- records `skyVisible` and updates the last surface anchor.

### Suggestion

`planBacktrack(controller, targetX,Y,Z)`:

1. If the current position already has a clear fat line to the target, return
   `null` (no detour).
2. Walk backward through at most half the history (min 4, max 64 steps).
3. For each passable point, build the waypoint list from the current end back to
   that point.
4. Candidate endpoints:
   - `reconnect`: first point with a clear fat line to the target;
   - `surface`: first point with open sky;
   - `goalward`: point closest to the target.
5. Return the best useful candidate:
   - surface candidate when underground and target is at/above surface;
   - reconnect when it makes at least −1 progress;
   - goalward when it improves distance by >1.5.
6. Otherwise `null`.

The manager uses only the endpoint as a flight target; the waypoint list is
internal scoring data.

## 5. Flight planning and execution

### `FlightPathfinder` (`service/navigation/impl`)

Read-only planner plus geometry helpers:

- `findFlightPath(world, ctrl, start, goal)` — 3D A* / Theta* over passable
  voxels; rejects body-clipping (checks feet + head), liquid costs, corner
  cutting; uses Baritone multi-coefficient partial-path backoff; returns
  waypoints and sets `lastPathPartial`/`lastPathNodesExpanded` on the controller.
- Path cache: signature of start/goal buckets + tick freshness reuses the
  previous path for up to 60 ticks.
- `findNearestPassableVoxel`, `isFlightLineClear`, `findBlockStandoff`,
  `describeNearestObstruction`,
  `clearNearbyLeafObstruction`, `setDroneFlightTarget`,
  `runServerSidePhysicsStep`, `isPlayerLookingAtDrone`,
  `computeStableFollowAnchor`.
- `clearNavigation(controller)` clears path state.

`NavigateAction` executes a flight leg:

1. Computes an effective target:
   - long-range chunking: step 16 blocks (open sky) or 24 blocks (underground);
   - underground → surface goals: use the breadcrumb surface anchor or nearest
     open voxel above.
2. Repaths when path empty, exhausted, goal bucket moved, or stuck.
3. Advances waypoints within 0.75; last waypoint advances only when partial path
   or further than 0.90.
4. If the active waypoint is solid/blocked: hold, mark stuck, clear path, return
   `needsDig`.
5. Applies `setDroneFlightTarget` to the active waypoint.
6. Completes on arrival within 0.85, partial exhaustion, blocked waypoint, or
   failure.

The runtime applies `DronePhysicsService.runServerSidePhysicsStep` at the end of
the tick when needed (blocked, long-range, or machine not running).

### `DronePhysicsService`

- server-side physics step (collision-safe motion, max velocity);
- nearest obstruction description;
- clear nearby leaf obstruction;
- stable follow anchor computation;
- block standoff search;
- player-look detection;
- headroom checks.

## 6. Blocked routes

Navigation never breaks blocks. When `NavigateAction` reports a blocked
waypoint or a partial path that cannot continue, the session ends with
`navigationError` and the drone holds position. To get through, the model
composes the work itself:

1. `inspect('block')` / `inspect('blocks')` to find the obstruction;
2. `breakBlock(x,y,z)` for each block, one call per block;
3. `goto(...)` again once the path is open.

The same applies to vertical shafts: the model picks the blocks and breaks
them; `goto` only flies.

## 7. Non-harness callers

Deposit, delivery and block standoffs call:

- `NavigationManager.flyToward(ctrl, x,y,z, label)` — runs one `NavigateAction`
  tick (no session), returns `NavigateResult`.
- `NavigationManager.hold(ctrl, label)` — runs `HoldAction`.

This keeps all motion inside action executors and avoids a second flight engine.

## 8. Deposit and delivery

There is no DROP or DEPOSIT mode. `transfer` ops are retained actions:

- `deposit` resolves an explicit chest or the nearest one with space, flies to
  its standoff, deposits within 3.8 blocks, then completes.
- `give` flies to the player and hands over matching items within 3.5 blocks.
- `drop` flies to the owner and delivers; if the owner is offline it drops the
  inventory in place.

Each uses `NavigationManager.flyToward` internally, so all motion still goes
through the flight action executor. When the action completes the drone is idle
and holds position.

## 9. Status and diagnostics

`state` exposes:

- `navigationActive`, `navigationPhase`, `navigationReason`, `navigationError`;
- `distanceToGoal`, `arrived`, `busy`;
- path waypoints, current target, stuck counters via `/debug`.

Relevant debug categories: `NAV`, `PATH`, `STUCK`, `ESCAPE`.

## 10. Failure behavior

- A blocked/partial flight → session fails with `navigationError`.
- A failed trail leg → trail rejected, normal flight continues.
- No route to the goal → failure; the model may break blocks and retry.
- 12000 ticks → timeout failure.
- Explicit `stop`/`cancel` → session removed, drone idle.
- The drone is never "interrupted by a mode change": only `goto`, `stop`, or a
  proven failure ends a session.
