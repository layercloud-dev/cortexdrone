---
name: "ore_to_ingots"
description: "End-to-end ore processing: withdraw raw ore from nearby storage, smelt it in a furnace at vanilla speed, return to the player and hand over the ingots. Composition of withdraw_items, smelt_items and deliver_items."
tags: "ore ingot smelt iron gold copper furnace deliver production chain"
triggers:
  - "smelt the iron ore"
  - "turn the ore into ingots"
  - "process the ore and bring it"
  - "get me iron ingots from the ore"
requires:
  - "withdraw_items"
  - "smelt_items"
  - "deliver_items"
version: 1
uses: 0
code: code.js
---

# Ore to ingots

One call that turns stored ore into ingots in the drone's hands.

1. `withdraw_items` pulls `count` of `ore` (from the drone, the player or a
   nearby chest).
2. `smelt_items` loads a furnace, waits at vanilla speed, and withdraws the
   output.
3. With `deliver:true` (default) `deliver_items` flies back and hands over the
   ingots.

## Parameters

- `ore` - ore filter. Default `iron_ore`.
- `count` - ores to process 1..64. Default 8.
- `ingot` - output filter used for delivery, e.g. `iron_ingot`. Default is
  `ore` with `_ore` replaced by `_ingot`; pass explicitly for unusual mods.
- `furnace`, `radius`, `fuel` - forwarded to `smelt_items`.
- `deliver` - default `true`.
- `player` - delivery target.

## Returns

`{ ok, ore, count, withdrawn, smelted, ingot, outputName, furnace, fuel,
delivered, stalled, smelt, hint }`

## Notes

- Mod ores whose ingot name is not `<base>_ingot` need `ingot` explicitly, or
  the delivery step finds nothing and reports `delivered:0` while the smelt
  result still reports the real output name.
- 8 ores is ~80 seconds of furnace time; keep batches at 16 or fewer per call
  to stay inside the task budget.
