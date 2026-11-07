---
name: "sugarcane_farm"
description: "Harvest sugar cane columns next to water by cutting the first block above the base, so the whole upper column pops at once and the base regrows, then collect the drops."
tags: "farm sugarcane sugar cane reed paper harvest water middle cut"
triggers:
  - "harvest the sugarcane"
  - "farm sugar cane"
  - "get sugar cane"
  - "make paper"
requires:
  - "collect_drops"
version: 2
uses: 0
code: code.js
---

# Sugarcane farm

Harvest sugar cane columns that stand beside water. The bottom block is kept so
the column regrows. For each column the skill cuts the **middle** block — the
first block above the ground/base block — instead of working top-down from the
top.

## Why the middle cut

Sugar cane pops every block above the block that is broken in the same update.
Cutting the first block above the base therefore removes the entire upper
column in one `breakBlock`, while the base stays in place and regrows. Breaking
from the top down needs one `goto` + `breakBlock` per block, is slower, and can
leave stubs behind when a reach or line-of-sight check fails on one of the upper
blocks.

## Parameters

- `radius` - search radius. Default 24.
- `limit` - maximum cane blocks to harvest. Default 48.

## Returns

`{ ok, harvested, columns, skipped, failed, collected }`

## Notes

- Columns shorter than two blocks are skipped (`skipped` in the result) so the
  base can grow; `column[0]` (the base) is never broken.
- No replanting is needed; sugar cane regrows from its base.
- The base must stay directly beside water and on dirt/sand.
- Calls `collect_drops` at the end.
