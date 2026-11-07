---
name: "farm_cycle"
description: "One full farm pass: harvest the chosen crop (or sugar cane, melon or pumpkin), collect the drops, then deliver to the player or store in a chest. Composes the harvesting skills instead of duplicating them."
tags: "farm cycle harvest collect deliver store wheat carrots potatoes cane melon pumpkin"
triggers:
  - "do a farm run"
  - "harvest and bring me the crops"
  - "farm everything and store it"
requires:
  - "harvest_crops"
  - "sugarcane_farm"
  - "melon_pumpkin_farm"
  - "collect_drops"
  - "deliver_items"
  - "store_items"
version: 1
uses: 0
code: code.js
---

# Farm cycle

One full farm pass: harvest the chosen crop (or sugar cane, melon, pumpkin),
collect drops, then optionally deliver or store the produce.

## Parameters

- `crop` - `wheat`, `carrots`, `potatoes`, `sugarcane`, `melon` or `pumpkin`. Default `wheat`.
- `radius`, `limit` - passed to the harvesting skill.
- `deliver` - hand everything to the player.
- `store` - deposit everything into a chest instead.
- `chest_radius` - chest search radius for storage. Default 28.

## Returns

`{ ok, crop, harvested, replanted, collected, delivered, stored, detail }`

## Notes

- Composes the harvesting skills, `collect_drops` and the delivery/storage primitives.
- `deliver` wins if both `deliver` and `store` are true.
