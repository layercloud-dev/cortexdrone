---
name: "sugarcane_farm"
description: "Harvest sugar cane columns next to water: keep the bottom block so the column regrows, break the upper blocks top-down, then collect the drops. Optionally plants spare cane on valid water-adjacent soil."
tags: "farm sugarcane sugar cane reed paper harvest replant water"
triggers:
  - "harvest the sugarcane"
  - "farm sugar cane"
  - "get sugar cane"
  - "make paper"
requires:
  - "collect_drops"
version: 1
uses: 0
code: code.js
---

# Sugarcane farm

Harvest sugar cane columns that stand beside water. The bottom block is kept so
the column regrows; upper blocks are broken top-down.

## Parameters

- `radius` - search radius. Default 24.
- `limit` - maximum cane blocks to harvest. Default 48.

## Returns

`{ ok, harvested, columns, failed, collected }`

## Notes

- One-block columns are skipped so they can grow to three.
- No replanting is needed; sugar cane regrows from its base.
- The base must stay directly beside water and on dirt/sand.
- Calls `collect_drops` at the end.
