---
name: "guard_scan"
description: "One defensive sweep: list hostile mobs within the guard radius and finish the nearest ones with attack_to_death, which reads each mob's health and the drone's damage per hit to compute the exact number of attacks. Returns threats, attacks, kills and blockers. Used by guard and patrol."
tags: "guard defend attack mob hostile combat sweep protect kill death"
triggers:
  - "clear the mobs"
  - "check for monsters"
  - "defend the area"
requires:
  - "attack_to_death"
version: 2
uses: 0
code: code.js
---

# Guard scan

One defensive sweep: list hostiles within the guard radius and finish the
nearest ones. Swings are health-based, not a blind budget: each target goes
through `attack_to_death`, which asks `attack_plan` how many hits the drone
actually needs.

## Parameters

- `radius` - threat radius 4..48. Default 16.
- `max_attacks` - safety cap on total swings for the sweep, 1..200. Default 40.
  This is a ceiling, not a target: a 20 HP zombie with a sword dies in 2 hits.
- `max_targets` - maximum mobs engaged per sweep, 1..16. Default 4.
- `duration_s` - wall-clock cap for the sweep, 3..120. Default 30.
- `target` - entity filter. Default `hostile`.
- `collect` - loot each kill (valuable drops only). Default `false`; pass
  `true` when the sweep is also a supply run.

## Returns

`{ ok, threats, attacks, kills, targets, blocked, radius }`

## Notes

- Called by `guard` and `patrol`; run it directly for a single check.
- Only attacks reachable targets; unreachable mobs are left for the next sweep.
- With `collect:true` each kill is looted through `pickupNearbyLoot`, which
  keeps valuable drops only.
- `blocked` lists targets that could not be finished (full armor, invulnerable,
  out of reach) so a caller can report a real reason instead of "0 kills".
