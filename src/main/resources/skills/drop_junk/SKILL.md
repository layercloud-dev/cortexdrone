---
name: "drop_junk"
description: "Free drone inventory slots by selecting each junk stack on the OpenComputers drone and dropping it in place. Keeps ores, ingots, gems, food and anything in the keep list. Uses the 1-based ocSlot from state.inventory and the drone's drop(side, count) callback."
tags: "drop junk inventory full slots free discard trash mining cobblestone dirt gravel sapling seeds oc opencomputers"
triggers:
  - "drop the junk"
  - "drop non ores and junk"
  - "free inventory slots"
  - "my inventory is full"
  - "throw away the trash"
  - "don't come back to drop wastes"
requires: []
version: 1
uses: 0
source: "refined"
code: code.js
---

# Drop junk

The drone has a small inventory (often 8 slots). Mining fills it with
cobblestone, dirt, gravel and saplings, and then ore drops are left on the
ground. This skill drops the junk in place so the next mining round has room.

## Parameters

- `keep` - comma/space separated name fragments that are never dropped. Defaults
  to ores, ingots, gems, crystals, dusts, coal/lignite, clay, food and the
  useful mod ores from the live world.
- `also_drop` - extra name fragments to treat as junk.
- `limit` - maximum stacks to drop this call. Default `8`.
- `count` - items per dropped stack. Default `64`; pass `0` to drop the whole
  selected stack.
- `side` - OC drop side name or number. Default `down`.
- `dry_run` - report what would be dropped without touching the inventory.

## Returns

`{ ok, dropped: [{name, count}], kept, freeSlots, failed, hint }`

## Notes

- OC inventory callbacks are 1-based. `state.inventory` entries expose
  `ocSlot`; the list index is **not** a slot number.
- The drone's drop callback is `drop(side, count)`: select the slot first, then
  pass the side (down=0, up=1, north=2, south=3, west=4, east=5) and the count.
- Items are dropped at the drone's current position. Do this away from a chest
  or lava; the skill refuses to drop into lava directly below.
