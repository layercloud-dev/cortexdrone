---
name: "harvest_crops"
description: "Harvest mature wheat, carrots or potatoes: break only fully grown crops, then replant with seeds or the crop item on the farmland below and collect the drops. Uses crop metadata so young plants are left alone."
tags: "farm harvest replant wheat carrots potatoes crops seeds food agriculture"
triggers:
  - "harvest the farm"
  - "replant the wheat"
  - "collect the crops"
  - "tend the farm"
requires:
  - "collect_drops"
version: 1
uses: 0
code: code.js
---

# Harvest crops

Harvest only fully grown wheat, carrots or potatoes, then replant on the farmland
below and collect the drops.

## Parameters

- `crop` - `wheat`, `carrots` or `potatoes`. Default `wheat`.
- `radius` - farm search radius. Default 24.
- `limit` - maximum mature plants to harvest. Default 16.
- `replant` - default `true`.

## Returns

`{ ok, crop, harvested, replanted, failed, growing, seedsLeft, collected }`

## Notes

- Maturity is metadata 7 in 1.7.10; young plants are left alone.
- Crops need light 9+; a seed planted in darkness is destroyed.
- Replanting uses `wheat_seeds`, `carrot` or `potato` from the drone inventory.
- Calls `collect_drops` at the end.
