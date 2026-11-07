---
name: "gather_distant"
description: "Find a resource or biome far beyond normal scan range, fly there, harvest the target, and optionally deliver the haul back to the player. Use for 'go mine jungle logs about 1000 blocks away', 'find sugarcane/cactus/sand', or 'fetch spruce wood and bring it back'."
tags: "distant long range gather fetch biome resource jungle cactus sand spruce bring back deliver travel"
triggers:
  - "go mine jungle logs far away"
  - "find sugarcane far away"
  - "fetch spruce wood"
  - "bring me cactus"
  - "gather from a distant biome"
requires:
  - mine_blocks
  - deliver_items
version: 1
uses: 0
code: code.js
---

# Gather distant

Search far terrain, travel to the first hit, harvest, and return.

## Parameters

- `target` - block, ore tag, category or biome name. Required. Accepts an
  OreDictionary tag (`#oreTin`, `oreIron`) or a plain material word (`tin`,
  `spruce`), which resolves through the dictionary.
- `max_radius` - search distance 64..1500. Default 1200.
- `count` - maximum item/block harvest 1..256. Default 32.
- `deliver` - return to the player and hand over the haul. Default `true`.
- `allow_dig` - allow tunnelling during the long flight. Default `false`.
- `mine_radius` - local harvest radius after arrival 4..32. Default 24.
- `player` - delivery player; defaults to the task player.

## Returns

`{ ok, target, locatedAt, distance, biome, foundBlocks, mined, blocks,
delivered, delivery, hint }`

## Notes

- `find_distant` samples loaded chunks first and then a bounded number of new
  chunks, so this is a search, not a guaranteed find. The drone itself may fly
  into unloaded terrain; `goto` force-loads the corridor as it advances.
- Travel uses one long `goto`; if it fails because the target is underground or
  walled in, retry with `allow_dig:true`.
- Composes `mine_blocks` then `deliver_items`; no separate travel/queue state.
