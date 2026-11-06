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
| `mine_blocks` | bounded scan/move/break loop |
| `mine_and_deliver` | mine a batch, return, hand over |
| `store_items` | fly to chest and deposit cargo |
| `place_double_chest` | craft two chests and place them |
| `return_to_player` | goal navigation back to the owner |
| `rainbow_show` | LED animation via static primitive |
| `build_wall` | line placement with inventory blocks |
| `transport_entity` | pick, fly, unpick template |

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
  "source": "bundled|saved|refined",
  "code": "JavaScript body using synchronous drone tools"
}
```

## Config files

```text
config/cortexdrone/
  config.json   gateway, agent limits, saved drone bindings
  skills.json   learned/refined skills
  memory.json   player facts and named locations
  logs/         cortexdrone.log (+ .1 rotation)
```

## Removed resources

The old `system-prompt.md`, `sdk/core-rpc.mjs` and `sdk/drone-actions.mjs` were
deleted. The tool surface is now generated from `ToolRegistry`, and JavaScript
runs in-JVM.
