---
name: "guard"
description: "Hold a position and defend it for a bounded time: repeatedly sweep with guard_scan, return to the anchor when it drifts away, and report threats seen, kills and blockers. guard_scan finishes each mob with attack_to_death, so a kill takes the health-based number of hits instead of a fixed swing cap."
tags: "guard protect defend hold position watch mobs night base safe combat kill"
triggers:
  - "guard this area"
  - "protect the base"
  - "keep watch"
  - "defend me"
requires:
  - "guard_scan"
version: 2
uses: 0
code: code.js
---

# Guard

Hold a position for a bounded time and defend it. Each cycle runs `guard_scan`,
then returns to the anchor when the drone drifts more than four blocks away.
Combat itself is `attack_to_death` inside `guard_scan`: the number of swings is
derived from the mob's health and the drone's damage per hit.

## Parameters

- `duration_s` - guard time 10..240 seconds. Default 120.
- `radius` - threat radius. Default 16.
- `max_attacks` - swing ceiling per sweep, 1..200. Default 40. A ceiling, not
  a target: a low-health mob dies in the hits the plan computes.
- `point` - explicit `{x,y,z}` anchor.
- `anchor` - `player` to anchor on the task player.
- `hold` - default `true`; set `false` to fight without returning to the anchor.

## Returns

`{ ok, guardedSeconds, sweeps, threatsSeen, kills, blocked, anchor }`

## Notes

- Uses `guard_scan` so combat logic exists once.
- `blocked` names threats that could not be finished (armor, invulnerability,
  unreachable) so the caller knows more swings would not help.
- The loop is bounded by the task/execute budget; re-run for a longer watch.
