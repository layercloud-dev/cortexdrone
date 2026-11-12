---
name: "deliver_items"
description: "Fly back to the owner (or a named player) and hand over items matching a filter; verifies that the transfer happened and reports the exact count. Used by mining, farming and hunting workflows."
tags: "deliver give hand over player return items transfer deposit primitive"
triggers:
  - "give me the items"
  - "bring it back to me"
  - "hand over the loot"
  - "deliver the haul"
  - "come and give items to me"
  - "drop it to the base chests"
requires:
  - "return_to_player"
version: 3
uses: 0
code: code.js
---

# Deliver items

Return to the owner (or a named player) and hand over a filtered part of the
drone inventory. Used by mining, farming and hunting workflows.

## Parameters

- `filter` - item/block/ore tag or `all`. Default `all`.
- `count` - maximum items to hand over. Default 512.
- `player` - optional player name; defaults to the task player.
- `only_if_holding` - default `true`; set `false` to fly over even when the filter
  matches nothing.

## Returns

`{ ok, filter, given, player, holdingBefore }`

## Notes

- Navigation is self-recovering; no manual routing is needed.
- A full player inventory drops the overflow at the player's feet.
