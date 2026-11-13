---
name: "tnt_strike"
description: "Bomb a ground target with TNT from the air: fly above the impact, confirm the fall is clear and no rider is aboard, release fused charges, escape the blast, then report the crater. Uses the tnt_drop tool for the release so the drone is never inside its own explosion."
tags: "tnt bomb bombing strike ordnance drop demolish crater blast military drone aerial explode dynamite"
triggers:
  - "bomb that base"
  - "drop tnt on it"
  - "tnt strike"
  - "blow that up from the air"
  - "demolish it with tnt"
  - "carpet bomb the area"
  - "tnt drop"
requires: []
version: 1
uses: 0
source: "refined"
code: code.js
---

# TNT strike

An aerial TNT release. The drone is the bomber: it climbs above the impact
block, checks the fall column, releases one or more fused charges with
`tnt_drop`, then flies out of the blast radius before the fuse ends.

`tnt_drop` refuses when the drone is inside the damage radius, when it is not
roughly above the target, when a rider is aboard, when the impact is in a
protected zone, or when the drone carries no TNT. This skill turns those
refusals into a plan instead of retrying blindly.

## Parameters

- `x`, `y`, `z` - impact block. Required unless `target` is given.
- `target` - `{x, y, z}` alternative to the three fields. When only x/z are
  known, `y` is resolved with `get_top_y`.
- `count` - charges to release, `1..8`. Default `1`.
- `fuse_ticks` - fuse in ticks, `10..200`. Default `60` (three seconds).
- `spread` - horizontal spread between several charges, `0..3`. Default `0`.
- `collect` - vacuum the dropped blocks after the blast. Default `true`.
- `player` - who to take TNT from when the drone has none. Defaults to the
  task player or the drone owner.

## Returns

`{ok, dropped, impact, crater, collected, remaining_tnt, plan, error, hint}`

## Notes

- TNT is not placed as a block. A placed charge becomes a normal block; this
  skill uses the entity release so the fuse is exact and the drone keeps flying.
- The drone must be at least 9 blocks from the impact (blast damage reaches
  about twice the 4-block destruction radius) and within 2 blocks horizontally.
  When it is not, the tool returns `too_close`/`off_target` with a `hover`
  point; this skill flies there and retries once.
- Riders are never carried on a bombing run: the tool refuses while anyone is
  mounted, because the passenger sits inside the blast.
- Protected zones are absolute. A base the player marked as no-break cannot be
  bombed even with a direct request; ask the player to remove the zone first.
- After the release the drone must leave immediately. `tnt_drop` returns
  `escape_to` and `detonation_in_seconds`; the skill flies out, waits the fuse
  plus one second, then scans the crater.
