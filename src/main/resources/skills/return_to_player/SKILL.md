---
name: "return_to_player"
description: "Navigate back to the owner (or a named player) with the self-recovering goto; reports whether the drone actually arrived and where it ended up."
tags: "return come back player escape cave underground tunnel navigate home"
triggers:
  - "come back"
  - "return to me"
  - "come here"
  - "come to me"
  - "come get me"
  - "where are you"
  - "get out of the cave"
  - "fly back"
requires:
  - "escape_cave"
version: 3
uses: 0
code: code.js
---

# Return to player

Navigate back to the owner (or a named player) with the self-recovering `goto`.
The route is evidence-driven: direct flight first, then the remembered base
shaft/exit point when the player is underground, then a 2x2 escape shaft only
when the drone is genuinely sealed in a cave far from the base. Automatic
tunnelling and `break_block` refuse player-protected zones, so the base cannot
be damaged by a rescue.

## Parameters

- `player` - optional player name.
- `allow_dig` - allow tunnelling when no no-dig route exists. Default `false`.
- `escape_cave` - open a 2x2 shaft when the drone is underground. Default `true`.
- `max_wait_ms` - per-leg navigation budget. Defaults to the tool maximum.

## Returns

`{ ok, arrived, player, position, attempts, route }` on success;
`{ ok:false, arrived:false, error, hint, attempts }` on failure. `attempts`
shows every leg tried and why it failed, so a caller can adapt instead of
repeating the same goto.
