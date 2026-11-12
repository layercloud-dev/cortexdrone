---
name: "escape_cave"
description: "Open a 2x2 vertical escape shaft from the drone (and any carried passenger) to the surface, then fly up through it. Refuses liquids, protected blocks and unbreakable rock. Use when the player or drone is underground and a destination is on the surface."
tags: "cave escape shaft 2x2 suffocate buried underground surface ascend dig out"
triggers:
  - "get me out of this cave"
  - "you are underground"
  - "dig out"
  - "i am stuck in a cave"
  - "escape the cave"
  - "tunnel up first"
  - "get out of the mining cave"
requires:
  - "collect_drops"
version: 1
uses: 0
code: code.js
---

# Escape cave

Opens a **2x2** vertical shaft from the drone's feet to open sky and flies up
through it. A 1-wide hole is not an escape: a carried player suffocates, so the
skill always clears a two-block-wide footprint and never reports success on a
one-block tunnel.

## Parameters

- `x`, `z` - footprint origin (defaults to the drone's own column).
- `top_y` - optional known surface Y; otherwise `get_top_y` is used.
- `max_blocks` - maximum vertical distance, default 128.
- `collect` - sweep nearby drops afterwards, default true.

## Returns

`{ ok, x, z, fromY, surfaceY, reachedY, goalY, mined, stopped, onSurface }`

## Notes

- Stops instead of opening a flooded shaft (water or lava in a layer).
- Stops at a container, machine or other protected block rather than leaving a
  partial 1-wide hole.
- Re-checks each layer after breaking it, so falling gravel or sand cannot
  refill the shaft above the drone.
- A false `ok` with `stopped` set means the drone is still underground.
