---
name: "withdraw_items"
description: "Find an item in the drone, the player or nearby chests and pull the requested amount into the drone inventory; reports the source used. Used to gather build materials."
tags: "withdraw take chest player items retrieve restock primitive"
triggers:
  - "get me some cobblestone"
  - "withdraw iron from the chest"
  - "restock materials"
requires: []
version: 2
uses: 0
code: code.js
---

# Withdraw items

Find a material in the drone, the player or nearby chests and pull the requested
amount into the drone inventory. This is the material step for building and
crafting workflows.

## Parameters

- `filter` - item/block/ore tag. Required in practice.
- `count` - how many items to obtain. Default 64.
- `radius` - chest/player search radius. Default 32.

## Returns

`{ ok, withdrawn, filter, sources, available, hint }`

## Notes

- Takes from the player first, then from the best matching chest.
- Reports `ok:false` when the item exists but cannot be moved (reach/access).
- Does not craft; use `craft` afterwards if only ingredients are available.
