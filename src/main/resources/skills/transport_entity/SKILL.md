---
name: "transport_entity"
description: "Pick up the nearest matching entity (animal or mob), climb above the terrain, fly to a destination, scan for a safe landing cell, then dismount it. Refuses players by default; use carry_player for those."
tags: "entity transport pick unpick animal mob move carry pasture safe landing"
triggers:
  - "carry a mob"
  - "transport this animal"
  - "move the cows"
  - "pick up and move"
requires: []
version: 3
uses: 0
code: code.js
---

# Transport entity

Pick up the nearest matching animal or mob, climb, cruise, scan the landing
cell, descend, dismount. Use `carry_player` for players.

## Parameters

- `entity` - entity filter, e.g. `cow`, `animal`, `zombie`. Default `animal`.
- `destination` - `{x,y,z}`; defaults to a spot near the player.
- `radius` - pickup search radius. Default 10.
- `player` - used only to derive the default destination.

## Returns

`{ ok, entity, entityId, destination, landedAt, cruiseY, arrived, dismounted }`

## Safety rules

- Climbs to at least 10 blocks above the drone and 6 blocks above the highest
  column on the route before any horizontal travel.
- The destination is scanned with `find_safe_landing`; the entity is only
  released on a solid floor with a clear 2-block pocket.
- An underground destination gets a 2x2 `escape_cave` shaft first.
- On failure the entity is carried down to the nearest safe column rather than
  dropped mid-air.
