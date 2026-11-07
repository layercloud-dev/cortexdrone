---
name: "quarry"
description: "Clear a rectangular pit layer by layer, top layer first, keeping the walls vertical and the floor flat. Reports layers, blocks mined, blocks that refused to break and the final floor level."
tags: "quarry pit excavate clear area dig mining box layer"
triggers:
  - "dig a quarry"
  - "clear this area down"
  - "excavate a pit"
  - "mine out a 10 by 10 hole"
  - "dig down to bedrock here"
requires:
  - "collect_drops"
version: 1
uses: 0
code: code.js
---

# Quarry

A layered rectangular excavation. Every layer is completed before the next one
starts, so the drone always has a flat, open floor to hover over and the walls
stay vertical instead of turning into a staircase.

## Parameters

- `x1,z1,x2,z2` - pit footprint (inclusive). Defaults to a `width`x`length` box
  centered on the drone.
- `width`, `length` - footprint when corners are omitted, 1..33. Default 5.
- `top_y` - first (highest) layer. Defaults to the drone's Y minus one.
- `depth` - pit height in layers (alias `layers`). Default 3, max 32.
- `filter` - only break matching blocks (`all` default). Use `stone` to leave
  dirt and gravel in place.
- `max_blocks` - safety cap 1..1024. Default 512.
- `collect_every` - vacuum drops every N layers. Default 3; 0 disables.
- `allow_liquid` - default `false`: liquid columns are skipped, not drained.

## Returns

`{ ok, layers, requestedLayers, mined, skipped, failed, layerY,
pit:{x1,z1,x2,z2,topY}, stopped, hint }`

## Notes

- Mining is top-down per layer, and within a layer nearest-first, so the drone
  never mines the block it is hovering over.
- A block that is protected (chest, machine, OC block) or liquid is skipped and
  counted, not destroyed; `skipped` tells the player why the pit has holes.
- Very large pits should be split: the drone inventory fills after a few
  hundred blocks. `stopped:"inventory full"` means unload and re-run with a
  lower `top_y` to continue the same pit.
- This skill is deliberately not a build-plan consumer: use `mine_box` for one
  exact volume with a filter, and `quarry` when the player asked for a hole.
