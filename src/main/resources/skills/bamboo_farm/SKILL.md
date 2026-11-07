---
name: "bamboo_farm"
description: "Harvest bamboo stalks by cutting the first block above the base, so the whole upper stalk pops at once and the base regrows, then collect the drops. Shoots are left alone."
tags: "farm bamboo stalk shoot scaffold harvest wood middle cut"
triggers:
  - "harvest the bamboo"
  - "farm bamboo"
  - "get bamboo"
  - "collect bamboo for scaffolds"
requires:
  - "collect_drops"
version: 1
uses: 0
code: code.js
---

# Bamboo farm

Harvest bamboo stalks. The bottom block is kept so the stalk regrows. For each
column the skill cuts the **middle** block — the first block above the ground
base — instead of working top-down from the top.

## Why the middle cut

Bamboo pops every block above the block that is broken in the same update.
Cutting the first block above the base therefore removes the entire upper stalk
in one `breakBlock`, while the base stays in place and regrows. Breaking from
the top down needs one `goto` + `breakBlock` per block, is slower, and can leave
stubs behind when a reach or line-of-sight check fails on one of the upper
blocks.

## Parameters

- `radius` - search radius. Default 24.
- `limit` - maximum bamboo blocks to harvest. Default 64.

## Returns

`{ ok, harvested, columns, skipped, saplings, failed, collected }`

## Notes

- Columns shorter than two blocks are skipped (`skipped` in the result) so the
  base can grow; `column[0]` (the base) is never broken.
- Bamboo shoots/saplings are never broken; they are reported as `saplings`.
- No replanting is needed; bamboo regrows from its base.
- Calls `collect_drops` at the end.
