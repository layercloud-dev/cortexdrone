---
name: "replace_surface"
description: "Replace a whole room surface atomically: floor, ceiling, all walls or one directional wall. Scans the 3D room, optionally filters which existing blocks may be replaced, and uses replace_block so a failed placement restores the old block instead of leaving a hole. Use for 'change this floor to sky stone', 'replace the walls', 'redo the ceiling' or 'replace only the cobblestone in this room'."
tags: "replace room floor wall ceiling surface remodel swap skystone atomic directional"
triggers:
  - "replace this floor"
  - "change the walls to"
  - "redo the ceiling"
  - "replace the room surface"
  - "swap the cobblestone in this room"
requires: []
version: 1
uses: 0
code: code.js
---

# Replace surface

Replace a room's floor, ceiling or walls with one target block.

## Parameters

- `surface` - `floor` (default), `ceiling`, `walls`, `north_wall`,
  `south_wall`, `east_wall`, `west_wall`, or `all`.
- `block` - replacement block/item name or ore dictionary tag. Required.
- `only_replace` - only replace existing blocks matching this filter, e.g.
  `cobblestone`. Default `all`.
- `meta` - target metadata, -1 = auto.
- `max_blocks` - safety cap 1..1200. Default 256.
- `dry_run` - report counts without changing the world.
- `x,y,z,max_radius` - scan center/radius; defaults to the player and 18.

## Returns

`{ ok, surface, block, replaced, alreadyMatching, skipped, failed,
missingRemaining, planned, total, remaining, bounds, inventoryLeft }`

## Notes

- Uses the atomic `replace_block` primitive: the old block is restored if the
  new one cannot be placed.
- Protected fixtures (chests, machines, OpenComputers blocks) and bedrock are
  never selected by `scan_room`.
- `only_replace` is what makes "replace only the cobblestone in the walls"
  safe; everything else is left untouched.
- A dry run is the safe first step for a large room.
