---
name: "attack_to_death"
description: "Kill one entity with certainty: read its health and the drone's damage per hit from attack_plan, then attack exactly that many times, waiting out hurt-resistance between hits and re-planning after a run of no-damage swings. Refuses players and reports the exact blocker when a target cannot be damaged. Use this from hunt, guard_scan, patrol and any script that needs an actual kill instead of a fixed number of swings."
tags: "attack kill death combat finish mob hostile guard hunt damage hp health loop"
triggers:
  - "kill this mob"
  - "attack until it dies"
  - "finish the target"
  - "make sure it dies"
  - "spam attacks until dead"
requires: []
version: 1
uses: 0
code: code.js
---

# Attack to death

One target, one guaranteed kill (or an explicit reason it is impossible).

Combat is not "swing N times and hope". Vanilla 1.7.10 ignores a hit while the
target is still inside its hurt-resistance window, so a fast loop burns attacks
without dealing damage. This skill turns health into the actual number of
landed hits and paces them.

## Parameters

- `entity_id` - target id from `get_entities` (or a hunt/guard scan). Required
  unless `entity` is passed.
- `entity` - an entity object from `getEntities` instead of an id.
- `item` - optional weapon filter; the plan then evaluates that item.
- `slot` - optional drone inventory slot to use as the weapon.
- `max_attacks` - hard safety cap, 1..200. Default 60.
- `max_seconds` - wall-clock cap, 1..180. Default 60.
- `collect` - vacuum drops at the end. Default `false` (hunt handles pickup).

## Returns

`{ ok, killed, entity_id, name, attacks, healthBefore, healthAfter,
   attacksToKill, damagePerHit, seconds, blocker }`

## Notes

- Uses `attack_plan` for the hit count and the inter-hit wait, so a sword,
  armor, Resistance and absorption all change the answer automatically.
- After each swing it re-reads `healthAfter`; if three consecutive hits dealt
  no damage it re-plans (fresh weapon/armor/absorption) instead of spamming.
- Stops with `blocker` for players, invulnerable targets, missing weapons and
  out-of-reach targets. Never attacks a player.
- Called by `hunt`, `guard_scan` and `patrol`; run it directly for one duel.
