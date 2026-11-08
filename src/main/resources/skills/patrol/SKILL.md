---
name: "patrol"
description: "Fly a waypoint route (or a square around a center) and run a defensive sweep at each stop with guard_scan. guard_scan finishes mobs with attack_to_death, so kills are health-based rather than a fixed swing count. Reports waypoints reached, failures and kills."
tags: "patrol route waypoint guard sweep explore secure perimeter kill death combat"
triggers:
  - "patrol the base"
  - "fly a patrol route"
  - "check the perimeter"
requires:
  - "guard_scan"
version: 2
uses: 0
code: code.js
---

# Patrol

Fly a waypoint route (or a square around a center) and run a defensive sweep at
each stop. The sweep is `guard_scan`, which finishes each mob through
`attack_to_death`, so the attack count comes from health and the drone's real
damage per hit.

## Parameters

- `waypoints` - array of `{x,y,z}`.
- `center` - alternative to waypoints; generates a square.
- `radius` - square half-size when using `center`. Default 12.
- `loops` - number of route repetitions, 1..6.
- `guard_radius` - sweep radius at each waypoint. Default 12.
- `guard_attacks` - swing ceiling passed to `guard_scan`. Default 40.
- `allow_dig` - allow tunnelling between waypoints. Default `false`.

## Returns

`{ ok, loops, waypoints, reached, failed, kills, blocked }`

## Notes

- A failed waypoint is counted and the route continues.
- Use `allow_dig:true` only for routes that may pass through terrain.
- `blocked` aggregates the reasons mobs could not be finished, so a patrol
  never silently reports "0 kills" when the weapon cannot damage the target.
