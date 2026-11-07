---
name: "patrol"
description: "Fly a waypoint route (or a square around a center) and run a defensive sweep at each stop with guard_scan. Reports waypoints reached, failures and kills."
tags: "patrol route waypoint guard sweep explore secure perimeter"
triggers:
  - "patrol the base"
  - "fly a patrol route"
  - "check the perimeter"
requires:
  - "guard_scan"
version: 1
uses: 0
code: code.js
---

# Patrol

Fly a waypoint route (or a square around a center) and run a defensive sweep at
each stop.

## Parameters

- `waypoints` - array of `{x,y,z}`.
- `center` - alternative to waypoints; generates a square.
- `radius` - square half-size when using `center`. Default 12.
- `loops` - number of route repetitions, 1..6.
- `guard_radius` - sweep radius at each waypoint. Default 12.
- `allow_dig` - allow tunnelling between waypoints. Default `false`.

## Returns

`{ ok, loops, waypoints, reached, failed, kills }`

## Notes

- A failed waypoint is counted and the route continues.
- Use `allow_dig:true` only for routes that may pass through terrain.
