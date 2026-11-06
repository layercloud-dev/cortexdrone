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
- `standoffOf`/`nearBlock` calls `FlightPathfinder.findMiningStandoff`, with a
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
- Sets `Mode.STAY`.
- Returns `{arrived:true, phase:"stopped"}`.

## 2. Session state

`NavigationManager` stores one `Session` per controller:

| Field | Purpose |
| --- | --- |
| `goalX/Y/Z` | final goal |
| `phase` | `PLAN`, `FLIGHT`, `DIG` |
| `leg` | current retained action + context |
| `trailRejected` | trail suggestion failed and must not be retried |
| `usingTrail` | current flight leg targets a trail endpoint |
| `ticks` | total session ticks |
| `replanCooldown` | delay before next decision |
| `digAttempts` | escalation count (max 12) |
| `reason` | human-readable status reason |

Constants:

- arrival radius `0.85`;
- stuck escalation `12` ticks;
- max session duration `12000` ticks (10 minutes);
- max dig attempts `12`.

If a digest route is active, the manager waits while `escapeResume != null` or
`mode == MINE`. When control returns to `GOTO`, it replans. If the mode changed
to something else, the session fails as interrupted.

## 3. Decision pipeline

`tick(controller)`:

1. If a leg exists, tick it.
2. If a dig leg just finished, return to `PLAN`.
3. If mode is not `GOTO`, cancel the session.
4. If within `0.85` of goal, arrive.
5. If `PLAN`, decide the next leg.

`decide`:

1. If trail not rejected, ask `BreadcrumbService.planBacktrack`.
2. If a useful suggestion exists, set the leg target to its endpoint and start a
   normal `FLIGHT` leg. This is **not** a trail action.
3. Otherwise start a `FLIGHT` leg to the final goal.
4. If the flight leg reports blocked/partial exhaustion, escalate to dig.

`handleProgress`:

- `needsDig`/`blocked` on a trail leg: mark trail rejected and replan without
  immediately digging.
- `needsDig`/`blocked` otherwise: escalate to a dig route.
- `legDone`: return to `PLAN` after a short cooldown.

`handleResult`:

- `STOP` removes the session.
- `DIG_*`/`TUNNEL` sets phase `DIG` and waits for building.
- `FLIGHT` handles blocked/done as above.

## 4. Breadcrumb service

`service/navigation/impl/BreadcrumbService` is the owner of the trail. It is not
an action and it never changes mode/goals.

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

Read-only no-dig planner plus geometry helpers:

- `findFlightPath(world, ctrl, start, goal)` — 3D A* / Theta* over passable
  voxels; rejects body-clipping (checks feet + head), liquid costs, corner
  cutting; uses Baritone multi-coefficient partial-path backoff; returns
  waypoints and sets `lastPathPartial`/`lastPathNodesExpanded` on the controller.
- Path cache: signature of start/goal buckets + tick freshness reuses the
  previous path for up to 60 ticks.
- `findNearestPassableVoxel`, `isFlightLineClear`, `findMiningStandoff`,
  `findBlockingTunnelBlock`, `describeNearestObstruction`,
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
- mining standoff search;
- blocking tunnel block search;
- player-look detection;
- headroom checks.

## 6. Dig planning

`DigPathService.findPath(world, sx,sy,sz, gx,gy,gz, toOpenSky)`:

- weighted A* over a 6-connected grid;
- 450 expansion cap;
- passable step cost 1.0;
- solid mineable step cost `4.0 + hardness * 2.5`;
- rejects non-mineable blocks and blocks adjacent to liquid;
- Y bounds 2..253;
- target: open sky (when `toOpenSky`) or Manhattan distance ≤1 to goal;
- returns ordered `int[]{x,y,z,dig}` steps; `dig=1` means the block must be
  broken;
- if no full path, returns the best partial route.

`NavigationManager.escalate` chooses:

- `DIG_UP` when underground, goal is at/above surface, and vertical gain >4;
- `DIG_DOWN` when the goal is >6 below and vertical dominates horizontal;
- `TUNNEL` otherwise;
- retries as `TUNNEL` if the chosen route is empty.

`DigRouteAction` calls `BuildingManager.startDigRoute`, which:

- snapshots the current mode/goal/mining state into `escapeResume`;
- clears the queue;
- queues only `dig=1` steps in path order (front insertion with duplicate guard);
- sets `mineFilter=all`, `mineExactQueueOnly=true`;
- switches to `Mode.MINE`;
- restores the snapshot after the queue drains.

The navigation session stays alive across `MINE` and replans after `GOTO`
returns. If the building manager needs to drop/deposit due to a full inventory,
the session waits while `escapeResume` is set.

## 7. Non-harness callers

Follow, drop/deposit and mining standoffs call:

- `NavigationManager.flyToward(ctrl, x,y,z, label)` — runs one `NavigateAction`
  tick (no session), returns `NavigateResult`.
- `NavigationManager.hold(ctrl, label)` — runs `HoldAction`.

This keeps all motion inside action executors and avoids a second flight engine.

## 8. Follow / stay / drop / deposit

`TickManager` handles these modes:

- `FOLLOW`: anchor solve (amortized 6 ticks), fly to anchor when far, hold when
  close; look-hold when the player looks at the drone.
- `STAY`: hold position; look-hold when the owner looks at the drone.
- `DROP`: fly to owner, give/drop inventory within 3.5 blocks, then resume
  `MINE` if a queue exists, else `FOLLOW`.
- `DEPOSIT`: find an explicit or nearest chest with space, fly to standoff,
  deposit within 3.8, then resume mining or follow.

These modes do not create a navigation session; they use the same flight/hold
action executors.

## 9. Status and diagnostics

`state` exposes:

- `navigationActive`, `navigationPhase`, `navigationReason`, `navigationError`;
- `distanceToGoal`, `arrived`, `mode`;
- path waypoints, current target, stuck counters via `/debug`.

Relevant debug categories: `NAV`, `PATH`, `STUCK`, `ESCAPE`, `MODE`.

## 10. Failure behavior

- A blocked/partial flight → dig escalation.
- A failed trail leg → trail rejected, normal flight/dig continues.
- No dig route → navigation fails with `navigationError`.
- 12 dig attempts exhausted → failure.
- 12000 ticks → timeout failure.
- Explicit `stop`/`cancel` → session removed, mode `STAY`.
- Mode changed away from `GOTO`/`MINE` → session interrupted.
