---
name: "smelt_items"
description: "Load a vanilla furnace with input and fuel from the drone inventory, wait while the world smelts at normal speed, then withdraw the finished output. Reports the produced item, fuel used and any stall (out of fuel, output full)."
tags: "smelt furnace cook ore ingot food glass fuel coal charcoal vanilla wait"
triggers:
  - "smelt the ore"
  - "cook the food"
  - "turn iron ore into ingots"
  - "make glass from sand"
  - "use the furnace"
requires: []
version: 1
uses: 0
code: code.js
---

# Smelt items

Load a vanilla furnace and wait for the world to do the cooking. The mod never
advances `furnaceCookTime` or burns fuel itself; this skill only moves items in
and out and sleeps while the server ticks the furnace.

Vanilla timings used by the planner: one item takes 10 seconds (200 ticks); one
coal or charcoal burns 80 seconds (8 items), a coal block 80 items, a blaze rod
12, a lava bucket 100, one plank or log about 1. Items that burn 100 ticks
(sticks, saplings) or 150 ticks (wooden slabs) cannot finish a 200-tick item, so
they are not selected as fuel.

## Parameters

- `filter` - item to smelt, e.g. `iron_ore`, `sand`, `raw fish`, `porkchop`.
- `count` - items to smelt this run, 1..64. Default 6.
- `furnace` - optional `{x,y,z}`; otherwise the nearest furnace with a free
  output slot within `radius`.
- `radius` - furnace search radius 4..36. Default 24.
- `fuel` - optional fuel name (`coal`, `charcoal`, `planks`, ...). Default picks
  the best fuel already in the drone inventory.
- `clear` - default `true`: move a different item out of the input slot first.
- `wait` - default `true`. `false` loads the furnace and returns immediately.
- `max_wait_s` - safety cap 5..300; defaults to `count * 10 + 20`.

## Returns

`{ ok, smelted, batch, loaded, furnace, preCollected, fuel: {name, used,
ticksEach, itemsPerFuel}, inputLeft, waitedSeconds, stalled, outputName,
inventoryItems, hint }`

## Notes

- Only the drone inventory is used as the source. Call `withdraw_items` first
  when the ore is in a chest, or use `ore_to_ingots`.
- The output slot is withdrawn after smelting; if it was already occupied those
  items are returned too and reported in `preCollected`.
- Long runs should be split. A 6-item run is ~60 s; `skill_run` allows up to
  about 4 minutes, so pass a larger `timeout_ms` for 16+ items.
- A stall with `stalled:"fuel"` means the furnace has input but no usable fuel;
  `stalled:"timeout"` means the batch did not finish inside `max_wait_s` and the
  remaining items are still in the furnace.
