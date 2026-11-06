# 08 — Runtime, config and logging

## Lifecycle

```text
preInit
  resolve config/cortexdrone/ (ModPaths.ensure)
  create AIDroneManager (services, managers, HTTP server object)
  register Forge/FML events

serverStarting
  ConfigMigrator.migrate(worldDir, configRoot)
  ModConfig.load(config/cortexdrone/config.json)
  Harness.create(...)
  logger.debugLogFile = config/cortexdrone/logs/cortexdrone.log
  reconnect saved world drones
  register /drone
  start HTTP on 127.0.0.1:<httpPort>

server tick START
  ensure OC machine running state per drone
  tick profiler start

server tick END
  drain main-thread queue
  drain time-sliced scan jobs
  tick every controller
  reconnect unlinked drones every 40 ticks
  snapshot HTTP state every 100 ticks
  tick profiler end

serverStopping
  restore light auras
  save config (including saved drone bindings)
  stop HTTP
```

## Per-controller tick

`TickManager.tickController`:

1. interaction cooldown;
2. OC machine state (amortized every 40 ticks);
3. breadcrumb sampling;
4. navigation session tick;
5. retained action contexts;
6. idle hold (kill stale flight targets);
7. complete the session when no loop/action remains;
8. physics safety step.

## Configuration

`config/cortexdrone/config.json`:

```json
{
  "apiUrl": "http://127.0.0.1:7002/v1/chat/completions",
  "model": "deepseek/deepseek-v4.1-flash",
  "apiKey": "...",
  "httpPort": 25580,
  "maxTurns": 60,
  "taskDeadlineSeconds": 480,
  "maxTokens": 2048,
  "temperature": 0.2,
  "executeTimeoutMs": 120000,
  "maxToolResultChars": 8000,
  "maxContextChars": 96000,
  "allowJavaReflection": true,
  "webSearchEnabled": true,
  "savedDrones": []
}
```

Environment overrides: `CORTEXDRONE_API_URL`, `CORTEXDRONE_MODEL`,
`CORTEXDRONE_API_KEY`.

`/drone config <url|model|key> <value>` updates the live object and saves the
file. `config/cortexdrone/skills.json` holds learned/refined skills.

## Legacy migration

`ConfigMigrator` runs once:

- `world/layercloud-ai.json` → `config/cortexdrone/config.json`;
- `world/layercloud-drone-skills-v2.json` → `config/cortexdrone/skills.json`;
- each old file is renamed `*.migrated`.

No AI state is written into the world save.

## Logging

`DroneLogger` keeps the last 400 structured entries in memory and appends to
`config/cortexdrone/logs/cortexdrone.log`. Log flushes are coalesced and bounded;
the file rotates at 64 MB. `/drone debug on` streams entries to a player;
`/drone debug tail` and `/drone debug clear` work from console.

Categories: `SYSTEM`, `SPAWN`, `LINK`, `DESPAWN`, `NAV`, `BUILD`, `INV`, `LIGHT`,
`LLM`, `AGENT`, `TOOL`, `CODEMODE`, `TRACE`, `TASK`, `CMD`, `SKILL`, `SUPERSEDED`,
`HTTP`, `WARN`, `ERROR`.

## Task cancellation and supersession

A new task increments the loop generation and cancels the previous one:

- the agent checks the generation before every turn and tool call;
- `ToolInvoker` rejects calls from a cancelled context;
- `JsEngine`'s instruction observer aborts the script;
- navigation waiting loops check cancellation every 100 ms;
- retained action contexts are cleared.

## Spawning and linking

- `/drone spawn [name]` spawns a new OC drone near the player.
- `/drone link` links the nearest OC drone within 8 blocks.
- Unlinked world drones reconnect every 40 ticks using saved UUID/owner/name and
  restore light aura/LED colour.
- Duplicate drones for one owner are merged into the first and despawned.
