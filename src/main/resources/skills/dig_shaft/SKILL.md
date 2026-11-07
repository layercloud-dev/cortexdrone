---
name: "dig_shaft"
description: "Dig a vertical shaft straight down with a ring of safety: every layer clears a 3x3 walkable area around the ladder column and reports depth, mined blocks and the bottom Y. Stops before bedrock."
tags: "dig shaft vertical down mine ladder access pit well"
triggers:
  - "dig a shaft straight down"
  - "dig a mine shaft here"
  - "make a vertical tunnel"
  - "dig down to y 12"
requires:
  - "collect_drops"
version: 1
uses: 0
code: code.js
---

# Dig shaft

A vertical shaft for reaching a mining level without a staircase. Each step
clears the shaft cell plus headroom; the drone flies down one layer at a time so
it never free-falls into an unlit hole.

## Parameters

- `start` - optional `{x,y,z}` shaft top. Defaults to the drone's current block,
  one layer below the drone.
- `depth` - layers to dig 1..128. Default 20.
- `target_y` - optional absolute bottom Y; wins over `depth` when lower.
- `size` - shaft footprint 1..3 (1 = single column). Default 1.
- `headroom` - blocks cleared per layer, 1..3. Default 2.
- `stop_at_bedrock` - default `true`: stop instead of failing on bedrock.
- `collect` - default `true`: vacuum the drops before returning.

## Returns

`{ ok, depth, fromY, bottomY, size, mined, failed, reachedBedrock, collected,
stopped, hint }`

## Notes

- The shaft is dug from the top down; the drone hovers beside the column and
  descends with it, so there is never an unsupported block under the drone.
- This leaves an open hole. For a player to use it safely, place ladders on one
  wall (`place_block` with `ladder`) or a water column. The skill deliberately
  does not auto-place ladders: ladder orientation depends on the wall face.
- Digging straight down through a cave can drop the drone into lava or water.
  The skill stops at a liquid layer and reports it rather than opening it.
