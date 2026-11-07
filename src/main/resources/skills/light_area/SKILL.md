---
name: "light_area"
description: "Make an area mob-safe by placing torches on a grid where the floor is solid and the space above is empty. Crafts torches from the drone inventory when needed and reports torches placed."
tags: "light torch mob spawn safe dark cave floor illuminate"
triggers:
  - "light up the area"
  - "place torches"
  - "make it safe at night"
  - "stop mobs spawning"
requires: []
version: 1
uses: 0
code: code.js
---

# Light area

Make an area mob-safe by placing torches on a grid where the floor is solid and
the space above is empty.

## Parameters

- `radius` - 2..16. Default 8.
- `spacing` - grid spacing 2..8. Default 4.
- `point` - optional `{x,y,z}` center; defaults to the drone.

## Returns

`{ ok, placed, skipped, torchesLeft }`

## Notes

- Crafts torches from the drone inventory when none are carried.
- Reads each candidate cell; only solid-floor, empty-above cells are used.
- Repeat one level up in tall rooms.
