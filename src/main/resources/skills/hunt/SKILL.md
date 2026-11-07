---
name: "hunt"
description: "Hunt nearby mobs for a bounded time: pick the closest matching entity, attack until it dies, refresh the target list, then vacuum the drops. target accepts hostile, animal, a mob name, or all. Never attacks players."
tags: "hunt kill attack mob hostile animal combat loot drops meat"
triggers:
  - "hunt mobs"
  - "kill the zombies"
  - "clear the monsters"
  - "get me meat"
requires: []
version: 1
uses: 0
code: code.js
---

# Hunt

Bounded combat loop: repeatedly pick the nearest matching entity, attack until it
dies, then re-scan. Never attacks players.

## Parameters

- `target` - `hostile`, `animal`, a mob name, or `all`. Default `hostile`.
- `radius` - target search radius. Default 24.
- `max_kills` - stop after this many kills. Default 4.
- `duration_s` - time budget 15..240 seconds. Default 120.
- `collect` - vacuum drops at the end. Default `true`.

## Returns

`{ ok, target, kills, attacks, hunted, remainingSeconds }`

## Notes

- A target that moves out of reach is dropped and the next scan re-targets.
- Pair with `collect_drops` for a larger pickup radius.
