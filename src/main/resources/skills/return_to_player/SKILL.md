---
name: "return_to_player"
description: "Navigate back to the owner (or a named player) with the self-recovering goto; reports whether the drone actually arrived and where it ended up."
tags: "return come back player escape cave underground tunnel navigate home"
triggers:
  - "come back"
  - "return to me"
  - "get out of the cave"
  - "fly back"
requires: []
version: 2
uses: 0
code: code.js
---

# Return to player

Navigate back to the owner (or a named player) using the self-recovering `goto`.

## Parameters

- `player` - optional player name.
- `allow_dig` - allow tunnelling out of caves. Default `false`.

## Returns

`{ ok, arrived, player, position }`

## Notes

- Prefers a no-dig route; set `allow_dig:true` for enclosed caves.
- On failure, returns the navigation error instead of guessing.
