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

`resources/skills/index.json` lists files loaded by `SkillLibrary`:

| Skill | Purpose |
| --- | --- |
| `mine_blocks` | bounded scan/vein/move/break primitive |
| `deliver_items` | fly to the player and hand over a filter |
| `mine_and_deliver` | composes `mine_blocks` + `deliver_items` |
| `collect_drops` | fly to drop clusters and vacuum them |
| `store_items` | fly to chest and deposit cargo |
| `withdraw_items` | find/withdraw a material from player or chests |
| `sort_chests` | categorise and move stacks between chests |
| `place_double_chest` | craft two chests and place them |
| `hunt` | bounded combat loop with drop pickup |
| `guard_scan` | one defensive sweep (attack nearest hostiles) |
| `guard` | hold a position, repeat `guard_scan` |
| `patrol` | fly a route and sweep at each waypoint |
| `carry_player` | pick the player up, fly, dismount |
| `transport_entity` | pick/move/unpick an animal or mob |
| `harvest_crops` | wheat/carrot/potato harvest + replant |
| `sugarcane_farm` | keep the base, harvest the upper cane |
| `melon_pumpkin_farm` | break fruit, leave stems |
| `chop_tree` | fell logs top-down, replant a sapling |
| `farm_cycle` | harvest + collect + deliver/store |
| `survey_build_site` | read-only ground/headroom/obstacle survey |
| `place_build_plan` | clear + place a plan in phase order + verify |
| `build_structure` | survey -> plan -> materials -> place -> verify |
| `build_wall` | wall via planner + `place_build_plan` |
| `build_platform` | platform/bridge via the planner |
| `light_area` | torch grid on solid floors |
| `return_to_player` | goal navigation back to the owner |
| `rainbow_show` | LED animation via static primitive |

Saved/refined skills override bundled skills with the same normalized name and
increment `version`. Storage is `config/cortexdrone/skills.json`, never the
world save.

## Skill JSON shape

```json
{
  "name": "mine_and_deliver",
  "description": "when to use and what it delivers",
  "tags": "space separated retrieval keywords",
  "triggers": ["player-style phrase", "another phrase"],
  "version": 1,
  "uses": 0,
  "requires": ["skill_this_code_calls"],
  "source": "bundled|saved|refined",
  "codeFile": "optional bundled .js sibling (bundled skills only)",
  "code": "JavaScript body using synchronous drone tools and `params`"
}
```

A bundled JSON file may keep `code` empty and point at `codeFile`; the loader
inlines the `.js` body. Saved/refined skills always store `code` inline.

## Config files

```text
config/cortexdrone/
  config.json   gateway, agent limits, saved drone bindings
  skills.json   learned/refined skills
  memory.json   player facts and named locations
  lessons.json  learned procedural rules
  sessions/     task transcripts grouped by outcome + index.jsonl
  logs/         cortexdrone.log (+ .1 rotation)
```

## Removed resources

The old `system-prompt.md`, `sdk/core-rpc.mjs` and `sdk/drone-actions.mjs` were
deleted. The tool surface is now generated from `ToolRegistry`, and JavaScript
runs in-JVM.
