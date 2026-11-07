---
name: "survey_build_site"
description: "Read-only build-site check: ground height, slope, headroom, liquids, trees and protected fixtures, plus the exact blocks that would need clearing. Returns a build verdict and the recommended floor Y. Always run this before build_structure."
tags: "survey build site scan ground slope flat clear protected liquid foundation"
triggers:
  - "check the building site"
  - "is this flat enough to build"
  - "survey the ground"
requires: []
version: 1
uses: 0
code: code.js
---

# Survey build site

Read-only ground survey. Run this before any build.

## Parameters

- `x1,z1,x2,z2` - site box, or
- `x,z,width,depth` - centered site.
- `height` - headroom to inspect above the ground, 1..24. Default 4.
- `y` - reference ground level; defaults to the player's Y.
- `allow_liquid` - treat water/lava as a blocker when false. Default `false`.

## Returns

`{ ok, suitable, blockers, floorY, slope, clearCount, clearMaterials,
groundMaterials, warnings, site, recommendation }`

## Notes

- Refuses sites with protected fixtures (chests, machines, OC blocks) or unloaded chunks.
- `suggestedFloorY` is the highest ground in the footprint, so dips get a foundation.
- `clearMaterials` is the exact material histogram that must be removed first.
