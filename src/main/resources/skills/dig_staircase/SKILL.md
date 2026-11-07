---
name: "dig_staircase"
description: "Dig a safe 1-wide descending staircase from the drone or player position in a cardinal or diagonal direction, clearing the block at each step plus its headroom. Returns the entry point, the lowest step and the blocks mined."
tags: "dig staircase descend mine shaft cave tunnel down spiral access"
triggers:
  - "dig a staircase down"
  - "make a stairway to the mine"
  - "dig down safely"
  - "tunnel down at an angle"
requires: []
version: 1
uses: 0
code: code.js
---

# Dig staircase

A staircase is the safe way down: every step drops exactly one block, so the
drone never removes the block under itself and never opens a sheer drop. Each
step clears the walking block plus the headroom block above it.

## Parameters

- `direction` - `north`, `south`, `east`, `west` or `down`. Default `north`.
  `north` is -Z, `south` +Z, `east` +X, `west` -X. `down` is a vertical shaft.
- `length` - number of steps 1..64. Default 16. With `down`, the shaft depth.
- `start` - optional `{x,y,z}` first walking block. Defaults to the drone's
  current block, one block ahead in `direction`.
- `headroom` - clear this many blocks above each step, 1..3. Default 2.
- `width` - steps wide, 1..3. Default 1.
- `max_blocks` - safety cap 1..512. Default 256.
- `allow_dig` - forwarded to `goto` for the approach. Default `false` (the
  drone should fly into the already-open step, not tunnel to it).

## Returns

`{ ok, direction, steps, requestedSteps, mined, failed, entry, exit, blocks,
stopped, hint }`

## Notes

- Mining runs in travel order, so a failure part-way leaves a usable staircase
  up to that point; `exit` is the deepest completed step.
- The block under each step is never touched, which keeps the floor stable for
  the player walking down behind the drone.
- For a spiral shaft use `direction:"down"` for one cell then rotate manually;
  this skill only produces straight runs.
- If the staircase hits a chest, machine or protected block the break returns
  `not_mineable`; the step is counted as failed and the run continues. Call
  `repair_trail` at the end only if `goto` had to tunnel on approach.
