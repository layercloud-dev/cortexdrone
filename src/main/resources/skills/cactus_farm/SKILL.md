---
name: "cactus_farm"
description: "Harvest cactus columns by cutting the block above the base so the base keeps growing, then collect the drops. Cactus needs sand below, air on all four sides and grows to three blocks."
tags: "cactus farm harvest sand desert green dye column cut"
triggers:
  - "harvest the cactus"
  - "farm cactus"
  - "get cactus for green dye"
  - "cut the cactus"
requires:
  - "collect_drops"
version: 1
uses: 0
code: code.js
---

# Cactus farm

Harvest cactus columns while keeping the bottom block so the column regrows.
Vanilla 1.7.10 cactus grows one block at a time on a 16-step counter, stops at
three blocks, needs sand below and an empty block on each of its four sides,
and pops when any solid neighbour appears. Break above the base and the whole
upper column pops at once.

## Parameters

- `radius` - search radius 4..32. Default 24.
- `limit` - maximum columns to cut, 1..64. Default 24.
- `keep` - how many base blocks to keep per column. Default 1.
- `point` - optional `{x,y,z}` scan center.

## Returns

`{ ok, harvested, columns, failed, keep, cactusLeft, collected, stopped?, hint }`

## Notes

- `break_block` returns the drops straight to the drone inventory; the extra
  `collect_drops` sweep catches pieces that fell beside the column.
- Never place a solid block next to a cactus column: it instantly breaks the
  whole plant into items.
- Cactus damages mobs, items and the drone if it touches one, so approach from
  above the column rather than beside it.
- Cactus cannot be bone-mealed. A farm can only be harvested on its own growth
  cycle; calling this repeatedly in one minute returns small batches.
