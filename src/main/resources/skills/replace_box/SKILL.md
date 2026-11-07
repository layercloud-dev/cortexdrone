---
name: "replace_box"
description: "Replace every matching block inside an explicit 3D box with one target block, atomically and top-down. Optionally restrict replacement to a source filter such as cobblestone, skip liquids, dry-run first, and stop before the drone runs out of material. Use for platforms, walls, floors or any rectangular region that is not a scanned room."
tags: "replace box region volume rectangular atomic swap remodel floor platform wall source filter"
triggers:
  - "replace this box"
  - "replace all cobblestone here"
  - "swap this platform to"
  - "replace every block in this region"
  - "remodel this rectangular area"
requires: []
version: 1
uses: 0
code: code.js
---

# Replace box

Replace the matching blocks in one explicit rectangular volume.

## Parameters

- `x1,y1,z1,x2,y2,z2` - inclusive corners. Required.
- `block` - replacement block/item name or ore tag. Required.
- `only_replace` - only replace blocks matching this source filter. Default `all`.
- `meta` - target metadata, -1 = auto.
- `max_blocks` - safety cap 1..1200. Default 256.
- `include_liquids` - replace liquids too. Default `false`.
- `dry_run` - return candidate counts without changing the world.

## Returns

`{ ok, block, onlyReplace, replaced, alreadyMatching, failed,
missingRemaining, planned, candidates, remaining, bounds, inventoryLeft }`

## Notes

- Uses `replace_block`, so a failed placement restores the original block.
- Top-down order and nearest-first keep the drone from sealing itself in.
- The box reader is capped at 33x33 per horizontal axis; split larger work
  into several calls.
