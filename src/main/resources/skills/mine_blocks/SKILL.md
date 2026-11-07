---
name: "mine_blocks"
description: "Find matching blocks or veins near the drone, fly to each safe standoff and break a bounded set; returns the mined coordinates, failures and remaining count. The mining primitive every other mining skill calls."
tags: "mining mine ore block vein dig gather tunnel primitive"
triggers:
  - "mine iron"
  - "dig ore"
  - "gather stone"
  - "tunnel to ore"
  - "break blocks"
requires: []
version: 2
uses: 0
code: code.js
---

# Mine blocks

Use this before any mining workflow. It scans connected veins first and prefers
exposed clusters, then falls back to a flat block scan.

## Parameters

- `filter` - block, registry name, ore tag or category (`ores`, `logs`, `stone`, ...). Default `ores`.
- `radius` - search radius 4..32. Default 24.
- `count` - maximum blocks to break, 1..256. Default 8.
- `veins` - set `false` to skip the vein pass and use `scan_blocks` only.
- `allow_dig` - allow navigation to tunnel to a buried target. Default `true`;
  set `false` for a strictly non-destructive pass.
- `max_dig_blocks` - tunnel budget per target, 0..512. Default 96.

## Returns

`{ ok, filter, mined, blocks:[{x,y,z}], failed, remaining }`

## Notes

- Stops before breaking anything when the drone inventory is full.
- Each target is approached at a safe standoff; if the target is buried, the
  standoff navigation tunnels the covering blocks and stops as soon as the
  target is visible and within reach. Unreachable targets count as `failed`,
  not an exception.
- Run `collect_drops` afterwards if drops may have fallen.
