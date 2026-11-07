---
name: "fertilize_crops"
description: "Bone-meal immature crops, saplings, melon/pumpkin stems or sugar cane to finish them fast. Applies bone meal only where the world actually accepts it and reports how many plants advanced and how much meal was spent."
tags: "bone meal bonemeal fertilize grow speed crops sapling stem farm"
triggers:
  - "bone meal the crops"
  - "speed up the farm"
  - "fertilize the wheat"
  - "grow the saplings faster"
requires:
  - "craft_items"
version: 1
uses: 0
code: code.js
---

# Fertilize crops

Use bone meal where vanilla 1.7.10 accepts it. Each successful use consumes one
bone meal. The skill checks the block before spending meal, so a fully grown
crop or an unsupported plant does not waste the stack.

## Parameters

- `radius` - search radius 4..32. Default 16.
- `limit` - maximum successful applications, 1..128. Default 32.
- `target` - `crops`, `saplings`, `stems`, `cane`, `wart` or a block name.
  Default `crops` (wheat, carrots, potatoes).
- `point` - optional `{x,y,z}` scan center.
- `craft_meal` - default `true`: craft bone meal from bones when none is aboard.

## Returns

`{ ok, applied, attempted, spent, mealLeft, target, hint }`

## Notes

- Vanilla rules: bone meal works on crops below age 7, saplings (45% chance per
  use), melon/pumpkin stems, and some modded plants. It does **not** work on
  sugar cane, cactus or nether wart in 1.7.10 — those are skipped by design and
  the result says so instead of burning meal.
- One bone meal item is not one bone: `craft_items` with `filter:"bone_meal"`
  converts bones 3:1 through the vanilla recipe.
- Use `apply_to` wording in your reply ("fertilized 12 wheat") rather than the
  raw counters.
