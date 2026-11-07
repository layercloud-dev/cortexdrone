---
name: "build_wall"
description: "Build a straight wall in front of the player (or between explicit corners) with a gate gap and a proper foundation. Uses the structure planner and place_build_plan instead of placing blocks blindly."
tags: "build wall fence shelter gate enclosure place stone"
triggers:
  - "build a wall"
  - "wall off this area"
  - "make a fence"
  - "enclose this"
requires:
  - "survey_build_site"
  - "place_build_plan"
version: 2
uses: 0
code: code.js
---

# Build wall

Straight wall with a gate gap and a real foundation, built through the planner.

## Parameters

- `length` - wall length 3..19. Default 6.
- `height` - 2..8. Default 3.
- `block` - wall material. Default `cobblestone`.
- `x1,z1,x2,z2` - explicit corners; otherwise builds in front of the player.
- `door_side`, `foundation`, `y` - optional plan overrides.

## Returns

`{ ok, site, floorY, placed, failed, missingMaterials, detail }`

## Notes

- Surveys the line first; refuses blocked or liquid sites.
- Wall blocks are placed corner -> wall; windows become arrow slits on long walls.
