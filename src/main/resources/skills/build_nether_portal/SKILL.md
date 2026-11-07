---
name: "build_nether_portal"
description: "Build and light a standard 4x5 nether portal frame (14 obsidian, 2x3 interior) against flat ground, clearing the interior first and igniting it with flint and steel. Reports missing obsidian, blocked interior cells and whether the portal block appeared."
tags: "nether portal obsidian flint steel ignite build frame travel dimension"
triggers:
  - "build a nether portal"
  - "make a portal to the nether"
  - "light the portal"
  - "set up a nether portal here"
requires:
  - "survey_build_site"
version: 1
uses: 0
code: code.js
---

# Build nether portal

A 4-wide by 5-tall obsidian frame with a 2x3 interior, the smallest vanilla
portal. Fourteen obsidian blocks are needed: 4 across the bottom, 4 across the
top, and 3 on each side between them. Lighting any interior bottom block with
flint and steel converts the interior to portal blocks.

## Parameters

- `x,z` - center of the portal footprint. Defaults to three blocks in front of
  the player.
- `y` - the ground level the frame stands on (the frame's first layer is
  `y+1`). Defaults to the surveyed floor.
- `axis` - `x` or `z`, the direction the frame runs. Default follows the
  player's facing so the portal faces them.
- `clear` - default `true`: break the 2x3 interior before placing.
- `ignite` - default `true`: use flint and steel on the bottom interior block.
- `dry_run` - default `false`: report the material audit and site only.

## Returns

`{ ok, dryRun?, frame:{x1,z1,x2,z2,y,axis}, placed, failed, missing, cleared,
ignited, portalBlocks, audit, survey?, hint }`

## Notes

- Obsidian is not craftable: it must be mined with a diamond pickaxe or taken
  from storage. The audit reports the exact deficit before anything is placed.
- The frame must sit on solid ground and the 4x5 area behind it must be clear.
  A `survey_build_site` pass rejects chests, machines, liquid and unloaded
  chunks under the footprint.
- Lighting is a `use_item` on the bottom interior block with side 1. If the
  world already contains a portal at that spot the call still succeeds and
  reports the existing `portalBlocks`.
- Breaking a portal block does not drop obsidian; removing the frame is slow
  and pointless, so choose the site deliberately.
- Nether travel itself is the player's job (walk in). The drone can follow
  through only if its chunk stays loaded; ask before sending it through.
