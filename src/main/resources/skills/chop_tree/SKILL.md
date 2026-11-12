---
name: "chop_tree"
description: "Fell nearby trees: break logs from the top down, collect the drops, and replant a sapling at the base when one is available. Leaves are left to decay on their own."
tags: "chop tree wood logs lumber sapling forest replant"
triggers:
  - "chop down a tree"
  - "get wood"
  - "fell that tree"
  - "gather logs"
  - "go chop 2 stacks of jungle wood"
  - "chop 2stack of jungle wood"
  - "chop wood until full"
  - "find a jungle and chop wood"
requires:
  - "collect_drops"
version: 1
uses: 0
code: code.js
---

# Chop tree

Fell nearby trees by breaking logs from the top down, collect the drops, and
replant a sapling at the base when one is available.

## Parameters

- `radius` - tree search radius. Default 20.
- `limit` - maximum logs to break. Default 24.
- `replant` - default `true`.

## Returns

`{ ok, logs, failed, saplingsPlanted, collected }`

## Notes

- Leaves are left to decay naturally and drop saplings.
- The base position of each column is remembered for replanting.
- Calls `collect_drops` at the end.
