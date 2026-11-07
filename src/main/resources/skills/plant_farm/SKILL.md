---
name: "plant_farm"
description: "Prepare and sow a crop plot: till dirt or grass into farmland with a hoe, plant wheat/carrot/potato/melon/pumpkin seeds on it, and report hydration, planted count and any missing tool or seeds. Crafts a wooden hoe when none is aboard."
tags: "farm plant sow till hoe farmland seeds wheat carrot potato melon pumpkin setup"
triggers:
  - "make a wheat farm"
  - "plant these seeds"
  - "till the ground and plant"
  - "set up a carrot farm"
  - "start a farm here"
requires:
  - "craft_items"
version: 1
uses: 0
code: code.js
---

# Plant farm

Turn bare ground into a working crop plot. The drone tills each dirt/grass block
with a hoe (top face click), then plants one seed on the fresh farmland.

## Parameters

- `crop` - `wheat`, `carrots`, `potatoes`, `melon` or `pumpkin`. Default `wheat`.
- `x1,z1,x2,z2` - explicit plot corners, or `x,z` plus `width`/`length`
  centered on that point. Defaults to a plot in front of the player.
- `y` - the soil level (the block that gets tilled). Defaults to the ground
  under the plot center.
- `width`, `length` - plot size 1..33. Default 5.
- `max_columns` - safety cap on tilled columns, 1..256. Default 64.
- `hoe` - hoe filter. Default `hoe`.
- `craft_hoe` - default `true`: craft a wooden hoe when the drone has none.

## Returns

`{ ok, crop, tilled, planted, skipped, columns, dry, waterNearby, seedsLeft,
area:{x1,z1,x2,z2,y}, hint }`

## Notes

- Vanilla 1.7.10 farmland needs water within four blocks horizontally at the
  same level or one above. Tilled dry soil still grows crops, just slowly, and
  an unplanted dry block eventually reverts to dirt. `waterNearby:false` means
  bring a bucket; this skill does not place water, because bucket placement is
  a look-ray action while `place_block` is an explicit coordinate.
- The hoe loses one durability per block; a wooden hoe lasts 59 tills.
- Carrots and potatoes plant the vegetable itself, not a separate seed item.
- Melon and pumpkin seeds plant stems; fruit appears on an adjacent dirt/grass
  block later and is harvested by `melon_pumpkin_farm`.
- Crops need light level 9+ above the plant to grow. A seed planted in a dark
  cave is destroyed, so light the plot first (`light_area`) if it is indoors.
