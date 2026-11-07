---
name: "build_platform"
description: "Build a supported platform or bridge deck with a foundation, optional railings and a clear deck. Uses the structure planner and place_build_plan."
tags: "build platform bridge deck pad floor railing foundation"
triggers:
  - "build a platform"
  - "make a bridge"
  - "build a deck"
  - "flatten a pad"
requires:
  - "survey_build_site"
  - "place_build_plan"
version: 1
uses: 0
code: code.js
---

# Build platform

Supported platform or bridge deck with a foundation and optional railings.

## Parameters

- `width`, `depth` - 3..19. Default 7.
- `block` - deck material. Default `cobblestone`.
- `bridge` - build piers instead of a ground foundation. Default `false`.
- `x,z` - center; defaults to in front of the player.
- `y` - deck level; defaults to the surveyed floor.
- `allow_liquid` - default `false`.

## Returns

`{ ok, site, floorY, kind, placed, failed, missingMaterials, detail }`

## Notes

- Surveys first and refuses protected/liquid sites.
- Railings leave a gate gap on the door side.
