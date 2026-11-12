---
name: "mine_box"
description: "Mine an exact 3D volume instead of a filtered radius: a 3x3 under the player, a tunnel in front of them, a volume around them, or explicit x1/y1/z1..x2/y2/z2 coordinates. Works top-down and stops before the drone inventory overflows. Use for 'mine a 3x3 under me', 'dig a tunnel', 'clear this box' or 'excavate that region'."
tags: "mine box rect volume 3x3 under me tunnel dig excavate clear region exact coordinates"
triggers:
  - "mine a 3x3 under me"
  - "dig a tunnel in front of me"
  - "clear this region"
  - "excavate the box"
  - "mine exactly here"
requires: []
version: 1
uses: 0
code: code.js
---

# Mine box

Mine an exact volume, not a radius search.

## Parameters

- `anchor` - `under_player` (default), `front_of_player`, `around_player`, or
  omit by passing explicit `x1,y1,z1,x2,y2,z2`.
- `width` - X size / tunnel width. Default 3.
- `length` - Z size / tunnel length. Default 3 (also accepts `depth`).
- `height` - Y size. Default 3 (also accepts `depth` when no length is given).
- `filter` - block name/registry/ore tag; `all` mines every non-liquid block.
- `max_blocks` - safety cap 1..1600. Default 256.
- `include_liquids` - mine liquids too. Default `false`.
- `allow_dig` - allow navigation to tunnel to a buried target. Default `false`;
  top-down order exposes most layers without side tunnelling.

## Returns

`{ ok, anchor, filter, mined, failed, pickedUp, remaining, blocks, box, hint }`

## Notes

- Explicit coordinates always win over the anchor.
- The list is sorted top-down, then nearest-first, so each layer exposes the
  next and the drone does not mine itself into a sealed pocket.
- Stops immediately when the drone inventory is full and reports `remaining`.
- Use `mine_blocks` when you want "find 8 iron ore nearby"; use this skill when
  the player gives a shape or exact corners.
