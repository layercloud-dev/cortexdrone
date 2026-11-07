---
name: "guard_scan"
description: "One defensive sweep: list hostile mobs within the guard radius, fly to the nearest and attack until the attack budget is spent. Returns threats, attacks and kills. Used by guard and patrol."
tags: "guard defend attack mob hostile combat sweep protect"
triggers:
  - "clear the mobs"
  - "check for monsters"
  - "defend the area"
requires: []
version: 1
uses: 0
code: code.js
---

# Guard scan

One defensive sweep: list hostiles within the guard radius, fly to the nearest
and attack until the attack budget is spent.

## Parameters

- `radius` - threat radius 4..48. Default 16.
- `max_attacks` - maximum attacks per sweep. Default 6.
- `target` - entity filter. Default `hostile`.

## Returns

`{ ok, threats, attacks, kills, targets, radius }`

## Notes

- Called by `guard` and `patrol`; run it directly for a single check.
- Only attacks reachable targets; unreachable mobs are left for the next sweep.
