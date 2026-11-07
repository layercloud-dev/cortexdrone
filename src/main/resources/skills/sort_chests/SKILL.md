---
name: "sort_chests"
description: "Categorise nearby chest contents and move each stack into a chest that already belongs to that category (ores, food, gear, blocks, redstone, misc). Reads every chest once, plans the moves, then executes them and reports what changed."
tags: "sort chests storage organise category ores food gear blocks redstone inventory"
triggers:
  - "sort the chests"
  - "organise the storage"
  - "put ores in one chest"
  - "clean up the chests"
requires: []
version: 1
uses: 0
code: code.js
---

# Sort chests

Read nearby chests once, infer each chest's category from its contents, then
move misplaced stacks into a chest that already belongs to the same category.

Categories: `ores`, `food`, `gear`, `blocks`, `redstone`, `misc`.

## Parameters

- `radius` - chest search radius. Default 24.
- `max_moves` - maximum stack moves. Default 48.
- `dry_run` - plan and return the moves without executing them.

## Returns

`{ ok, moved, failed, byCategory, chests, plannedMoves }` or a dry-run plan.

## Notes

- Needs at least two chests; otherwise it explains that more storage is required.
- A chest with three or more category types counts as unsorted and is the first source.
- Use labels by placing one example item in each destination chest before sorting.
