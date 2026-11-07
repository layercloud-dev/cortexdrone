# 09 — Packaged resources

## Prompts

`resources/prompts/system.md`
: The real system prompt: identity, mission, tool model, `execute` semantics,
  working rules, response style and safety. It contains no live state and no
  debug log.

`resources/prompts/skill-refine.md`
: The skill-compiler prompt used by `SkillRefiner` (the RLM step).

`resources/prompts/*` are loaded by `AgentResources.load`.

## JavaScript bootstrap

`resources/js/bootstrap.js`
: ES5-compatible helpers evaluated in every fresh Rhino scope:
  `hsvToRgb`, `pick`, `clamp`, `distance3`, `summarizeItems`. It never touches
  Minecraft or the tool registry.

## Bundled skills

`resources/skills/index.json` lists directory names loaded by `SkillLibrary`.
Each skill is a self-contained directory in the normal `SKILL.md` layout:

```text
resources/skills/
  index.json          ["guard", "patrol", "build_structure", ...]
  guard/
    SKILL.md          YAML frontmatter + usage documentation
    code.js           JavaScript function body (`params` in scope)
  patrol/
    SKILL.md
    code.js
```

`SKILL.md` starts with frontmatter, followed by documentation:

```markdown
---
name: "guard"
description: "Hold a position and defend it for a bounded time."
tags: "guard protect defend hold position"
triggers:
  - "guard this area"
  - "protect the base"
requires:
  - "guard_scan"
version: 1
uses: 0
code: code.js
---

# Guard

## Parameters

- `duration_s` - guard time 10..240 seconds. Default 120.
...
```

The Markdown body is kept as skill `docs` and returned by
`skill({ action: "load" })`, so parameters, return values and pitfalls are
available without reading the code first. `code` may name another JavaScript
file in the same directory; it defaults to `code.js`.

| Skill | Purpose |
| --- | --- |
| `mine_blocks` | bounded scan/vein/move/break primitive |
| `mine_box` | exact 3D volume mining (3x3 under player, tunnel, explicit box) |
| `deliver_items` | fly to the player and hand over a filter |
| `mine_and_deliver` | composes `mine_blocks` + `deliver_items` |
| `gather_distant` | find a far resource/biome, harvest, optionally deliver |
| `collect_drops` | fly to drop clusters and vacuum them |
| `store_items` | fly to chest and deposit cargo |
| `withdraw_items` | find/withdraw a material from player or chests |
| `fetch_materials` | batched multi-material restock with per-item blockers |
| `craft_items` | ingredient-audited batch crafting |
| `smelt_items` | load a furnace, wait at vanilla speed, withdraw output |
| `ore_to_ingots` | withdraw ore + smelt + deliver ingots |
| `sort_chests` | categorise and move stacks between chests |
| `place_double_chest` | craft two chests and place them |
| `hunt` | bounded combat loop with drop pickup |
| `guard_scan` | one defensive sweep (attack nearest hostiles) |
| `guard` | hold a position, repeat `guard_scan` |
| `patrol` | fly a route and sweep at each waypoint |
| `carry_player` | pick the player up, fly, dismount |
| `transport_entity` | pick/move/unpick an animal or mob |
| `harvest_crops` | wheat/carrot/potato harvest + replant |
| `sugarcane_farm` | keep the base, middle-cut so the upper cane pops at once |
| `bamboo_farm` | keep the base, middle-cut so the upper stalk pops at once |
| `melon_pumpkin_farm` | break fruit, leave stems |
| `nether_wart_farm` | harvest age-3 wart on soul sand, replant |
| `cactus_farm` | keep the base, cut above it so the column pops |
| `plant_farm` | till, sow and report hydration for a new plot |
| `fertilize_crops` | bone meal where vanilla accepts it |
| `chop_tree` | fell logs top-down, replant a sapling |
| `farm_cycle` | harvest + collect + deliver/store |
| `survey_build_site` | read-only ground/headroom/obstacle survey |
| `place_build_plan` | clear + place a plan in phase order + verify |
| `build_structure` | survey -> plan -> materials -> place -> verify |
| `build_wall` | wall via planner + `place_build_plan` |
| `build_platform` | platform/bridge via the planner |
| `build_nether_portal` | 4x5 obsidian frame, clear interior, ignite |
| `replace_surface` | atomic room floor/wall/ceiling replacement |
| `replace_box` | atomic 3D box replacement with source filter |
| `light_area` | torch grid on solid floors |
| `dig_staircase` | safe 1-wide descending staircase keeping the floor |
| `dig_shaft` | vertical shaft, stops at liquid and bedrock |
| `strip_mine` | straight tunnel with side branches + ore report |
| `quarry` | layered rectangular excavation, flat floor |
| `follow_player` | bounded follow with stable hover and drop pickup |
| `return_to_player` | goal navigation back to the owner |
| `rainbow_show` | LED animation via static primitive |

Saved/refined skills override bundled skills with the same normalized name and
increment `version`. They are written to `config/cortexdrone/skills/<name>/` as
`SKILL.md` + `code.js`. The retired single-file `skills.json` is not read; only
directories are supported. Parameter examples for every skill are in
[13-skills.md](13-skills.md).

## Learned skill layout

```text
config/cortexdrone/skills/guard/
  SKILL.md   frontmatter + docs (written from the skill metadata)
  code.js    inline skill body
```

`SkillLibrary.save` writes both files atomically; `delete` removes the
directory. Deleting an override of a bundled skill restores the bundled version.

## Learned lesson layout

Lessons use the same frontmatter + Markdown format and one directory per entry:

```text
config/cortexdrone/lessons/mining-keep-a-floor-1a2b3c4d/
  LESSON.md   frontmatter (id, scope, title, outcome, tags, pinned, uses,
              source, version, created, updated) + symptom -> cause -> rule body
```

`LessonStore.add` deduplicates by `scope + title`, updates the same directory
and increments `version`; `recordUse` rewrites the frontmatter; `forget` deletes
the directory. The retired single-file `lessons.json` is not read; only
directories are supported.

## Config files

```text
config/cortexdrone/
  config.json   gateway, agent limits, saved drone bindings
  skills/       learned/refined skills (SKILL.md + code.js per skill)
  memory.json   player facts and named locations
  lessons/      learned procedural rules (LESSON.md per lesson)
  sessions/     task transcripts grouped by outcome + index.jsonl
  logs/         cortexdrone.log (+ .1 rotation)
```

## Removed resources

The old `system-prompt.md`, `sdk/core-rpc.mjs` and `sdk/drone-actions.mjs` were
deleted. The tool surface is now generated from `ToolRegistry`, and JavaScript
runs in-JVM.
