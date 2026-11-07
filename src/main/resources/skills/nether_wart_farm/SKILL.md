---
name: "nether_wart_farm"
description: "Harvest mature nether wart (age 3) from soul sand, break only the fully grown plants, replant with nether wart and collect the drops. Works in the Nether or in an overworld soul-sand farm."
tags: "nether wart farm harvest replant soul sand potion brewing nether"
triggers:
  - "harvest the nether wart"
  - "farm nether wart"
  - "replant the nether wart"
  - "get wart for potions"
requires:
  - "collect_drops"
version: 1
uses: 0
code: code.js
---

# Nether wart farm

Harvest only fully grown nether wart. Vanilla 1.7.10 nether wart has four
stages (metadata 0..3); stage 3 is ready and drops 2-4 wart, while earlier
stages drop a single wart, so immature plants are skipped and left to grow.

## Parameters

- `radius` - search radius 4..32. Default 24.
- `limit` - maximum mature plants to harvest, 1..64. Default 24.
- `replant` - default `true`: replant one wart on each harvested soul sand.
- `point` - optional `{x,y,z}` scan center; defaults to the drone.

## Returns

`{ ok, harvested, replanted, growing, failed, wartLeft, collected, note?, hint }`

## Notes

- Nether wart grows on soul sand only (not soul soil, which is a later
  version). Growth ignores light, so dark nether rooms are fine.
- One harvested plant often yields 2-4 wart, so `replanted` can equal
  `harvested` while still leaving spare wart for potions.
- The plant is replanted on the soul sand *below* the harvested block, with the
  clicked face 'up' (`side:1`), the same call that plants wheat.
- Nether wart needs no water and never reverts; unlike farmland there is no
  hydration step.
