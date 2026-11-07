---
name: "transport_entity"
description: "Pick up the nearest matching entity (animal or mob), fly to a destination, then dismount it. Refuses players by default; use carry_player for those."
tags: "entity transport pick unpick animal mob move carry pasture"
triggers:
  - "carry a mob"
  - "transport this animal"
  - "move the cows"
  - "pick up and move"
requires: []
version: 2
uses: 0
code: code.js
---

# Transport entity

Pick up the nearest matching animal or mob, fly to a destination, then dismount.
Use `carry_player` for players.

## Parameters

- `entity` - entity filter, e.g. `cow`, `animal`, `zombie`. Default `animal`.
- `destination` - `{x,y,z}`; defaults to a spot near the player.
- `radius` - pickup search radius. Default 8.
- `player` - used only to derive the default destination.

## Returns

`{ ok, entity, entityId, destination, arrived, dismounted }`

## Notes

- Only non-player entities are considered.
- If navigation fails the entity is dismounted where it is.
