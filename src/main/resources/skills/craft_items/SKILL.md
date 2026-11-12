---
name: "craft_items"
description: "Craft a target item in batches with a full ingredient audit first: reads the recipe, counts what the drone holds, withdraws missing ingredients from the player or nearby chests, then crafts and reports what was made and what is still missing."
tags: "craft crafting recipe ingredients planks sticks tools batch restock"
triggers:
  - "craft some torches"
  - "make sticks"
  - "craft a pickaxe"
  - "make planks from logs"
  - "craft me 32 torches"
  - "craft an iron sword"
  - "craft an iron pickaxe"
  - "craft enough torches to light the room"
requires:
  - "withdraw_items"
version: 1
uses: 0
code: code.js
---

# Craft items

Craft from the drone inventory with an audit in front of it. Vanilla `craft`
only uses the drone's inventory and fails on missing ingredients; this skill
finds the deficit first, pulls it, then crafts.

## Parameters

- `filter` - result item to craft, e.g. `torch`, `stick`, `wooden_pickaxe`.
- `count` - number of result items wanted, 1..MAX_TRANSFER_COUNT. Default 1.
- `withdraw` - default `true`: pull missing ingredients from the player and
  nearby chests.
- `radius` - ingredient search radius 4..64. Default 32.
- `dry_run` - default `false`. `true` returns the ingredient audit only.

## Returns

`{ ok, item, requested, crafted, outputPerCraft, plannedCrafts, ingredients,
missing, failures, inventoryLeft, hint }`

## Notes

- Ingredients are aggregated per name and multiplied by `plannedCrafts`, so a
  shaped recipe that uses 8 cobblestone reports a deficit of 8 per craft, not 1.
  The audit names one representative ingredient per slot; alternative
  ingredients (any plank type, any wool colour) are honoured by vanilla
  crafting even when the audit names only one of them.
- Crafting consumes the whole ingredient set per recipe; a partial batch simply
  crafts fewer items and returns the exact `crafted` count.
- For deep chains (logs -> planks -> sticks) run this skill once per step in
  dependency order; it does not recursively sub-craft.
- `crafted` comes from the craft result and is cross-checked against the
  inventory delta, so a partially refused batch is reported honestly.
