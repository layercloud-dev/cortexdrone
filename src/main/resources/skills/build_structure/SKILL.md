---
name: "build_structure"
description: "Build a correct, detailed structure in one controlled workflow: resolve or find the site, survey the ground (slope, liquid, protected blocks, clearing list), pick a safe floor level, generate the phased blueprint, check and gather every needed material, clear obstacles, place in build order, then verify the result. Supports dry_run to review the plan and material deficit before touching the world. Use for houses, huts, towers, walls, platforms and bridges."
tags: "build structure house hut tower wall platform bridge blueprint survey foundation roof windows detailed construct"
triggers:
  - "build me a house"
  - "build a shelter"
  - "construct a tower"
  - "make a platform"
  - "build a bridge"
  - "build here"
requires:
  - "survey_build_site"
  - "place_build_plan"
  - "withdraw_items"
  - "mine_blocks"
version: 1
uses: 0
code: code.js
---

# Build structure

End-to-end, correctness-first building workflow.

## Steps

1. Resolve the site: explicit box, explicit center, or `find_build_site`.
2. Survey it with `survey_build_site`; refuse protected or liquid sites.
3. Pick the floor on the highest ground and generate a phased blueprint.
4. Audit materials with a `place_build_plan` dry run.
5. Withdraw missing materials and mine stone only when still missing.
6. Clear, place in phase order, verify the volume.

## Parameters

- `style` - `house`, `hut`, `wall`, `tower`, `platform`, `bridge`.
- `width`, `depth` - 3..19.
- `height` - wall height 2..12.
- `roof_style` - `flat`, `gable`, `hip`, `pyramid`.
- `windows`, `door_side` - openings.
- `floor`, `walls`, `corners`, `roof`, `foundation`, `window_block` - materials.
- `x,z` or `x1,z1,x2,z2` and `y` - location and floor level.
- `radius` - pad search radius when no location is given.
- `allow_liquid` - default `false`.
- `allow_substitute` - default `true`.
- `dry_run` - review site, plan and material deficit without changing the world.
- `max_place` - placement cap.

## Returns

A full report: site, floor, door, survey warnings, material audit, placed/failed
counts, substitutions and the final volume verification.

## Notes

- A dry run never withdraws, mines or places anything.
- Re-run the same call without `dry_run` after gathering missing materials.
- Prefer this over placing blocks by hand for any structure larger than a few blocks.
