---
name: "store_items"
description: "Fly to the nearest chest with free space and deposit the whole drone inventory; reports the chest and the number of items moved."
tags: "chest deposit store items inventory storage put away unload"
triggers:
  - "store the items"
  - "put this in a chest"
  - "deposit your inventory"
  - "unload"
requires: []
version: 2
uses: 0
code: code.js
---

# Store items

Fly to the nearest chest with free space and deposit the whole drone inventory.

## Parameters

- `chest` - optional `{x,y,z}` target; otherwise scans for the nearest chest with space.
- `radius` - chest search radius when auto-selecting. Default 28.
- `filter` - accepted for compatibility; the deposit primitive stores everything.

## Returns

`{ ok, chest, deposited }`

## Notes

- An empty inventory returns `ok` with `deposited: 0`.
- The chest must be reachable and must have at least one free slot.
