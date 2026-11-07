---
name: "fetch_materials"
description: "Restock several materials in one trip: takes a list of {filter, count} (or a shorthand map), withdraws each into the drone from the player and nearby chests, and reports per-material results plus the materials still short."
tags: "materials restock withdraw batch build delivery shopping list haul"
triggers:
  - "bring me 64 cobblestone and 32 planks"
  - "restock the building materials"
  - "get everything on this list"
  - "gather materials for the build"
requires:
  - "withdraw_items"
version: 1
uses: 0
code: code.js
---

# Fetch materials

One batched restock pass for a building or crafting session. It calls the
`withdraw_items` primitive once per material and keeps going when one item is
unavailable, so a single missing plank does not abort the whole trip.

## Parameters

- `materials` - list of entries:
  - `[{ "filter": "cobblestone", "count": 128 }, { "filter": "oak_planks", "count": 64 }]`
  - or a map: `{ "cobblestone": 128, "oak_planks": 64 }`
  - or a list of names (defaults to `count`).
- `count` - default amount when an entry has no count. Default 64.
- `radius` - chest/player search radius 4..64. Default 32.

Every `filter` goes through the OreDictionary too, so `{ "iron": 64 }` counts
any `ingotIron` variant and `{ "tin": 32 }` finds the mod's actual tin, even
when its display name differs. Use `#oreTin` to pin an exact tag.

## Returns

`{ ok, requested, satisfied, fetched:[{filter, wanted, got, have}],
totalFetched, blockers, inventoryItems }`

## Notes

- Entries are processed in order; `ok` is true when at least one material ends
  up holding its full requested amount.
- `have` is the drone count after that material's withdrawal, so a material
  that was already aboard reports `got:0` but a healthy `have`.
- `blockers` lists the materials still short, with the primitive's hint. Report
  those to the player instead of retrying blindly.
- The drone has a small inventory (8 slots without upgrades), so fetch what the
  next workflow needs rather than an entire build in one call.
