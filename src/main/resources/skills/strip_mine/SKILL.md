---
name: "strip_mine"
description: "Drive a straight 1x2 access tunnel at the current depth, optionally branching side tunnels every N blocks, and report mined blocks plus any ore veins exposed along the way. The bread-and-butter branch-mining workflow."
tags: "strip mine branch tunnel shaft ore mining access corridor depth"
triggers:
  - "strip mine here"
  - "dig a branch mine"
  - "make a mining tunnel"
  - "tunnel forward at this level"
  - "branch mine for diamonds"
requires:
  - "mine_blocks"
  - "collect_drops"
version: 1
uses: 0
code: code.js
---

# Strip mine

A straight access tunnel (1 wide, `height` tall) with optional side branches.
Tunnels are dug in travel order so the drone always has a cleared cell to fly
into; the block beneath the path is never removed.

## Parameters

- `direction` - `north`, `south`, `east`, `west` or `down`. Default `north`.
- `length` - main tunnel length 1..96. Default 32.
- `height` - tunnel height 1..3. Default 2 (standing room for the player).
- `branches` - side branches 0..16. Default 4.
- `branch_spacing` - every N main-tunnel blocks a branch starts. Default 8.
- `branch_length` - branch length 1..32. Default 12.
- `branch_side` - `left`, `right` or `both`. Default `left`.
- `start` - optional `{x,y,z}` first walking block; defaults to the drone's
  current block plus one step in `direction`.
- `max_blocks` - safety cap 1..1024. Default 512.
- `ore_scan` - default `true`: after digging, scan the tunnel area for ore
  veins and report what was exposed.
- `collect` - default `true`: vacuum the drops at the end.

## Returns

`{ ok, direction, mined, failed, branches, length, planned, start, exit,
ore:{count,veins}, collected, stopped, hint }`

## Notes

- For diamonds and other deep ores dig at Y 5-12; the drone does not choose the
  depth, so fly to the level first (`dig_staircase`) or pass `start.y`.
- One branch per 8 blocks on one side keeps the pattern readable and leaves
  pillars supporting the roof.
- The drop sweep at the end uses `collect_drops`; if the inventory filled up
  mid-run, `stopped` says so and the drops stay on the floor.
- Ore report counts connected veins found *after* digging, which is what the
  drone actually exposed; it is not a prospect map.
