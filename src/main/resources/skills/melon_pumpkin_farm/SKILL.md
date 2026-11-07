---
name: "melon_pumpkin_farm"
description: "Harvest melons or pumpkins by breaking only the fruit blocks (stems are skipped so they regrow), then collect drops. kind is melon or pumpkin."
tags: "farm melon pumpkin harvest stem fruit agriculture slices"
triggers:
  - "harvest the melons"
  - "pick the pumpkins"
  - "collect melon slices"
  - "melon farm"
requires:
  - "collect_drops"
version: 1
uses: 0
code: code.js
---

# Melon and pumpkin farm

Harvest melons or pumpkins by breaking only the fruit. Stems are left in place so
each one can grow another fruit.

## Parameters

- `kind` - `melon` or `pumpkin`. Default `melon`.
- `radius` - search radius. Default 24.
- `limit` - maximum fruits to harvest. Default 32.

## Returns

`{ ok, kind, harvested, stems, failed, collected }`

## Notes

- A stem does not produce again until its fruit is removed.
- Melon and pumpkin growth needs light 9+.
- Calls `collect_drops` at the end.
