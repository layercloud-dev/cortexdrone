---
name: "hunt"
description: "Hunt nearby mobs for a bounded time: pick the closest matching entity, finish it with attack_to_death (health-based hit count), refresh the target list, then vacuum the drops. target accepts hostile, animal, a mob name, or all. Never attacks players."
tags: "hunt kill attack mob hostile animal combat loot drops meat death hp"
triggers:
  - "hunt mobs"
  - "kill the zombies"
  - "clear the monsters"
  - "get me meat"
  - "we need food"
  - "go hunt"
  - "i am hungry"
  - "hunt for food"
requires:
  - "attack_to_death"
version: 2
uses: 0
code: code.js
---

# Hunt

Bounded combat loop: repeatedly pick the nearest matching entity, finish it
through `attack_to_death`, then re-scan. Every kill is health-based:
`attack_plan` computes the needed hits for the mob's health, armor and the
drone's current weapon, and the loop paces swings around hurt-resistance.
Never attacks players.

## Parameters

- `target` - `hostile`, `animal`, a mob name, or `all`. Default `hostile`.
- `radius` - target search radius. Default 24.
- `max_kills` - stop after this many kills. Default 4.
- `duration_s` - time budget 15..240 seconds. Default 120.
- `max_attacks` - per-mob safety cap on swings, 1..200. Default 60.
- `collect` - vacuum drops after each kill and sweep once at the end.
  Default `true`.
- `collect_radius` - vacuum radius 2..16. Default 5.

## Returns

`{ ok, target, kills, attacks, hunted, blocked, pickedUp, remainingSeconds }`

## Notes

- A target that moves out of reach is dropped and the next scan re-targets.
- Every kill is looted immediately, so meat and useful mob drops are picked up
  while the drone is already standing over the corpse; the final sweep walks to
  anything that fell outside vacuum range. Junk drops stay on the ground.
- `blocked` reports why a mob survived (damage fully absorbed, invulnerable),
  which is evidence that more swings would not help.
- Pair with `collect_drops` (`filter:'valuable'`) for a larger pickup radius.
