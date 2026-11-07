---
name: "mine_and_deliver"
description: "Mine a bounded batch with mine_blocks, then return to the player and hand it over with deliver_items. A thin composition, not a second mining implementation."
tags: "mine ore deliver bring haul return player iron stone gather"
triggers:
  - "mine and bring me"
  - "gather ore and return"
  - "come back with iron"
  - "mine ore for me"
requires:
  - "mine_blocks"
  - "deliver_items"
version: 2
uses: 0
code: code.js
---

# Mine and deliver

Thin composition of `mine_blocks` and `deliver_items`. Prefer this over writing a
second mining loop.

## Parameters

- `filter` - what to mine. Default `iron_ore`.
- `radius` - scan radius. Default 24.
- `count` - blocks to mine. Default 8.
- `deliver_filter` - what to hand over. Default `all`.
- `player` - optional delivery target.

## Returns

`{ ok, mined, blocks, delivered, delivery }`

## Notes

- If mining fails, the delivery step is skipped and the mining result is returned.
- Use this when the player asked for both the resource and the trip back.
