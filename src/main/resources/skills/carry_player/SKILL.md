---
name: "carry_player"
description: "Pick the player (or another passenger) up with pick_entity, climb high above the terrain, cruise, then dismount only on a scanned safe landing cell. Refuses to move a player who is not the task player unless explicitly named."
tags: "carry player transport ride passenger fly move unpick mount safe landing airlift"
triggers:
  - "carry me"
  - "fly me there"
  - "take me to"
  - "give me a ride"
  - "pick me up and move me"
requires: []
version: 3
uses: 0
code: code.js
---

# Carry player

Pick the player up, climb to a safe cruise altitude, fly to the destination,
scan for a landing cell, descend, dismount.

## Parameters

- `player` - optional player name; defaults to the task player.
- `destination` - `{x,y,z}`. Defaults to the block the player is looking at.

## Returns

`{ ok, player, destination, landedAt, cruiseY, arrived, dismounted, movedToSafeSpot, playerAfter }`

## Safety rules

- **Climb before moving.** The drone rises to `max(startY, destY) + 12` and at
  least 8 blocks above the tallest column on the route before any horizontal
  travel. Flying at ground level is what clipped mountains and trees.
- **Cruise, then descend.** Horizontal travel happens only at cruise altitude;
  the descent is a separate short leg.
- **Scan before dismount.** `find_safe_landing` must report `safe:true` for a
  solid floor with a clear 2-block pocket, no water/lava, no canopy and no
  falling block overhead. The cell is re-checked after the descent.
- **Never abandon the rider mid-air.** Any failure runs the safe-release path,
  which carries the player down to the nearest safe column before letting go;
  if no safe column exists they stay onboard and the result says so.

## Notes

- The player must be within 8 blocks for pickup; `goto` them first.
- The entity id is re-resolved right before pickup, so a stale id cannot fail
  the trip.
- Refuses a player who is already riding something.
- Pass `force_land:true` only when the player explicitly accepts the risk; the
  normal path never skips the landing scan.
