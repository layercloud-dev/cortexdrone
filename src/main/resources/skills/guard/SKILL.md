---
name: "guard"
description: "Hold a position and defend it for a bounded time: repeatedly sweep with guard_scan, return to the anchor when it drifts away, and report threats seen and kills. Uses guard_scan so the combat logic exists once."
tags: "guard protect defend hold position watch mobs night base safe"
triggers:
  - "guard this area"
  - "protect the base"
  - "keep watch"
  - "defend me"
requires:
  - "guard_scan"
version: 1
uses: 0
code: code.js
---

# Guard

Hold a position for a bounded time and defend it. Each cycle runs `guard_scan`,
then returns to the anchor when the drone drifts more than four blocks away.

## Parameters

- `duration_s` - guard time 10..240 seconds. Default 120.
- `radius` - threat radius. Default 16.
- `point` - explicit `{x,y,z}` anchor.
- `anchor` - `player` to anchor on the task player.
- `hold` - default `true`; set `false` to fight without returning to the anchor.

## Returns

`{ ok, guardedSeconds, sweeps, threatsSeen, kills, anchor }`

## Notes

- Uses `guard_scan` so combat logic exists once.
- The loop is bounded by the task/execute budget; re-run for a longer watch.
