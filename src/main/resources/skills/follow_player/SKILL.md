---
name: "follow_player"
description: "Follow a player while staying at a stable nearby hover point instead of hiding behind their back. Holds still while the player looks at the drone (so they can right-click it), repositions only when they walk away, and vacuums nearby dropped items when the drone has room. Use for 'follow me', 'stay near me', 'come with me' or escorting a player."
tags: "follow escort companion nearby stay close hover bodyguard pickup follow me escort player"
triggers:
  - "follow me"
  - "stay near me"
  - "come with me"
  - "follow the player"
  - "don't hide behind me"
  - "stay with me"
requires: []
version: 1
uses: 0
code: code.js
---

# Follow player

Follow one player for a bounded time while keeping a clear, stable hover point
near them.

## Parameters

- `player` - player name; defaults to the task player.
- `duration_s` - follow time 5..240 seconds. Default 120.
- `distance` - preferred horizontal distance 1.6..4.0. Default 2.2.
- `height` - hover height above the player's feet 0.5..2.0. Default 1.1.
- `auto_pickup` - vacuum nearby drops when the drone has room. Default `true`.
- `allow_dig` - allow short tunnelling moves when following through terrain.
  Default `false`.

## Returns

`{ ok, followedSeconds, player, moves, heldWhileAimed, pickedUp, errors, anchor, position }`

## Notes

- While the player's look vector points at the drone, it holds position so
  right-click inventory access is not interrupted by camera rotation.
- The anchor is only recomputed when the player walks more than 3.8 blocks
  away, moves closer than 1.2 blocks, or the hover cell becomes obstructed.
- This is a bounded task, not a permanent mode. Re-run it for a longer escort.
