---
name: "place_build_plan"
description: "Execute a structure plan safely: clear listed obstacles top-down, then place every task in phase order (foundation, floor, corner, wall, window, railing, roof), verify occupied targets instead of overwriting them, withdraw missing materials automatically and optionally substitute a similar block. Supports dry_run to check material totals without building."
tags: "build place plan structure foundation wall roof clear verify materials substitute"
triggers:
  - "place the blueprint"
  - "build the plan"
  - "execute the structure plan"
requires:
  - "withdraw_items"
version: 1
uses: 0
code: code.js
---

# Place build plan

Execute a `plan_structure` blueprint safely.

## Parameters

- `plan` - the object returned by `plan_structure`.
- `dry_run` - return the material audit without touching the world.
- `allow_substitute` - use any placeable block when the requested one is missing.
  Default `true`.
- `verify` - count the finished volume. Default `true`.
- `max_place` - placement cap. Defaults to the task count.
- `max_failures` - abort after this many consecutive failures. Default 12.
- `clear` - clear listed obstacles first. Default `true`.

## Returns

`{ ok, cleared, placed, alreadyThere, failed, substituted, materialsUsed,
missingMaterials, verification }`

## Notes

- Clears top-down, then places foundation -> floor -> corner -> wall -> window ->
  railing -> roof -> detail.
- An occupied target is verified with `get_block` instead of overwritten.
- Missing materials are withdrawn from nearby chests/player when possible.
