---
name: "carry_player"
description: "Pick the player (or another passenger) up with pick_entity, fly to a destination, then dismount with unpick_entity. Refuses to move a player who is not the task player unless explicitly named."
tags: "carry player transport ride passenger fly move unpick mount"
triggers:
  - "carry me"
  - "fly me there"
  - "take me to"
  - "give me a ride"
requires: []
version: 1
uses: 0
code: code.js
---

# Carry player

Pick the player (or another passenger) up, fly to a destination, then dismount.

## Parameters

- `player` - optional player name; defaults to the task player.
- `destination` - `{x,y,z}`. Defaults to the block the player is looking at.

## Returns

`{ ok, player, destination, arrived, dismounted, navigation }`

## Notes

- The player entity must be within 8 blocks of the drone for pickup.
- Always attempts to dismount, even when navigation fails.
- Refuses to proceed without a destination.
