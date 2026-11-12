---
name: "collect_drops"
description: "Sweep dropped item entities near the drone: fly to each nearby drop cluster and vacuum it with suck_items. Used after mining, harvesting, hunting and tree felling."
tags: "collect drops pickup items loot suck vacuum gather primitive"
triggers:
  - "pick up the drops"
  - "collect the loot"
  - "gather the items on the ground"
  - "gather dropped items"
requires: []
version: 2
uses: 0
code: code.js
---

# Collect drops

Fly to nearby dropped item clusters and vacuum them. Use after mining,
harvesting, hunting or felling trees, or when items were dropped by a broken
chest.

## Parameters

- `radius` - search radius 2..64. Default 16.
- `filter` - item name/registry/ore tag, `valuable` (ores, ingots, gems, dusts,
  food, logs), `junk`, or `all`. Default `all`.
- `max_clusters` - maximum pickup stops. Default 12.

## Returns

`{ ok, collected, clusters, inventoryItems }`

## Notes

- Consecutive drops in the same block column are picked up once.
- `filter:'valuable'` is the cheap option after mining or hunting: it only
  walks to loot worth keeping and vacuums with the same filter, so junk that sat
  next to the ore stays on the ground.
- Unreachable clusters are skipped; items that do not fit remain on the ground.
