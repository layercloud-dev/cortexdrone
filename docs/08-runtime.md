# 08 — Runtime Lifecycle and Operations

## 1. Mod entrypoint

`CortexDroneMod`:

- `@Mod(modid="cortexdrone", name="CortexDrone", version="1.4.0",
  dependencies="required-after:OpenComputers;after:layercloudsmp")`
- `preInit` registers Forge/FML event buses and creates `AIDroneManager`.
- `serverStarting` resolves `<world>/world`, calls `AIDroneManager.onServerStarting`.
- `serverStopping` calls `AIDroneManager.onServerStopping`.
- START/END server tick events drive `onServerTickStart`/`onServerTickEnd`.
- chat event checks LayerCloudSMP authentication (reflection, optional) then
  forwards to `onPlayerChat`.

Two executor pools:

- `HTTP_POOL` (`CortexDrone-Agent-*`) — LLM loops, JS runtime, async log flush;
- `HTTP_SERVER_POOL` (`CortexDrone-Http-*`) — HTTP request handling.

They are separate so a hung agent cannot starve telemetry/RPC and vice versa.

## 2. Startup

`AIDroneManager.onServerStarting`:

1. `configFile = <world>/layercloud-ai.json`;
2. create `SkillLibrary(worldDir)`;
3. set debug log file `<world>/layercloud-drone-debug.log`;
4. `loadConfig` (env defaults then file values);
5. `reconnectUnlinkedWorldDrones` (adopt existing OC drones, restore saved
   name/owner/light, deduplicate per owner);
6. log initialization;
7. register `/drone`;
8. start HTTP server.

## 3. Tick pipeline

### START

- `TickProfiler.onTickStart`;
- ensure OpenComputers machine running state for every live drone.

### END

1. drain main-thread task queue;
2. `RpcDispatcher.onServerTickEnd` (advance one large-scan slice);
3. increment tick counter;
4. every 40 ticks: reconnect unlinked world drones;
5. for each controller: `TickManager.tickController` (errors logged every 40
   ticks);
6. `TickProfiler.onTickEnd`;
7. every 100 ticks: refresh cached `/state` and `/debug` snapshots.

### Per-controller tick (`TickManager`)

1. decrement the interaction attack cooldown;
2. every 40 ticks refresh OC machine state; otherwise use `drone.isRunning()`;
3. `BreadcrumbService.observe` (service throttles to every 4 ticks);
4. `NavigationManager.tick` — advances the nav tick clock and path cooldown,
   samples movement, detects/logs stalls, clears leaf obstructions, then runs
   the active goal session;
5. `ActionRegistry.tick` (deposit, delivery, LED animation, light aura);
6. idle hold: when no session and no retained action, cancel leftover velocity
   and pin the flight target to the current position;
7. complete the session task if no loop/script/task work;
8. server-side physics step when needed (blocked, long-range, machine not
   running).

## 4. Work state (no modes)

There is no `Mode` enum and no `setDroneMode`. A drone is doing exactly one of:

| Work | Owner | How it is represented |
| --- | --- | --- |
| navigation | `NavigationManager` | an entry in its `sessions` map |
| deposit / delivery | a retained inventory action | the action context itself |
| idle | nobody | none of the above: hold position |

Every operation is started by a tool call and finishes on its own. A new tool
call replaces the previous work through the normal `ActionManager` retention
rule (one execution per operation) or through `goto`/`stop` for navigation.
`cancel` clears every retained context and the navigation session. There is no
mining queue or break progress to cancel: `break_block` completes inside the
call.

## 5. Spawn, link, despawn, reconnect

### Spawn for player

- despawn existing player drone;
- create OC `Drone`, name `<player>-AI` or custom;
- set owner name/UUID;
- position 2 blocks in front, fallback to player position if blocked;
- 8 inventory slots, cyan light, status "Following";
- set flight target, ensure machine running, spawn entity;
- register controller, save config, log `SPAWN`.

### Link nearest

- search OC drones within 8 blocks;
- choose nearest;
- set owner/UUID, ensure machine running, 8 slots, green light, "Linked";
- reuse controller or register, save config.

### Despawn

- drop all drone inventory at owner/current position;
- clear lighting aura, navigation session, breadcrumbs;
- mark despawned UUID, remove from saved config;
- `setDead`, remove controller/owner mapping, refresh HTTP snapshot;
- save config/log `DESPAWN`.

### Reconnect

Every 40 ticks and at startup, unlinked live OC drones are adopted:

- skip dead/known/despawned;
- match saved drone by UUID, fallback by owner;
- restore name/owner/autoTorch/LED effect/custom color;
- deduplicate multiple drones for one owner (merge inventory into existing drone,
  despawn duplicate);
- ensure machine running, 8 slots, register controller, log `LINK`.

## 6. Configuration

`AIConfig` fields and defaults:

| Field | Default |
| --- | --- |
| `apiUrl` | `http://127.0.0.1:7002/v1/chat/completions` |
| `model` | `deepseek/deepseek-v4.1-flash` |
| `apiKey` | built-in default / env override |
| `httpPort` | `25580` |
| `savedDrones` | list |

Environment variables:

- `CORTEXDRONE_API_URL`
- `CORTEXDRONE_MODEL`
- `CORTEXDRONE_API_KEY`

File: `<world>/layercloud-ai.json`.

Saved drones store UUID/name/owner/autoTorch/LED effect/custom color/dimension/
position. Work state is never saved: a restarted drone comes back idle. Server
drones are not saved.

Config changes:

- `/drone config <url|model|key> <value>` (operator only) updates and saves;
- HTTP `/action` can spawn/despawn;
- all saves are synchronized.

## 7. Commands and chat

### `/drone`

Subcommands:

- `help` (default)
- `spawn [name]`
- `link`
- `despawn [name]`
- `list`
- `status`
- `debug [on|off|tail|clear]`
- `config [url|model|key] [value]`
- `js <code>`

Unknown subcommands become a natural-language task. Legacy shortcuts (`take`,
`give`, `torch`, `drop`, `chest`, `scan`) are removed.

### Chat

- `@drone <message>` — natural language for your drone;
- `@<droneName> <message>` — target a specific owned drone by name;
- chat is recorded in `DroneLogger.recentChat`;
- LayerCloudSMP authentication is checked when available.

## 8. Logging

`DroneLogger`:

- in-memory debug ring: max 400 entries;
- chat ring: max 50;
- debug file: `<world>/layercloud-drone-debug.log`;
- async coalesced flush (600 ms), max 64 KB per flush, 200 KB pending buffer cap;
- line cap 4096 chars; chat cap 300 chars;
- file rotation at 64 MB (`.1`), checked every 3.5 s;
- subscribers receive live `[DroneDebug:CATEGORY]` chat lines;
- `/debug` JSON is capped (message/details max chars).

Common categories: `SYSTEM`, `PATH`, `NAV`, `STUCK`, `ESCAPE`, `MINE`,
`BUILD`, `INV`, `LIGHT`, `LLM`, `CODEMODE`, `HEARTBEAT`, `TRACE`, `TASK`, `CMD`,
`SPAWN`, `LINK`, `DESPAWN`, `WARN`, `ERROR`.

## 9. Tick profiler

`TickProfiler`:

- measures START→END duration;
- ring buffer 600 ticks (30 s at 20 TPS);
- avg/p95/max/TPS estimate;
- warns when over threshold, with cooldown;
- env overrides:
  - `CORTEXDRONE_TICK_WARN_MS` (default 50);
  - `CORTEXDRONE_WARN_COOLDOWN_MS` (default 30000);
- refreshes env every 100 ticks.

## 10. OpenComputers bridge

`OcHardwareBridge` uses reflection to keep drones operational:

- join network if unconnected;
- set local/global energy buffer to 100000;
- set machine max components 64;
- install `InertMachineArchitecture` when architecture is null (prevents save
  NPE for paused ghost machines);
- force `Machine.State.Paused` and `remainingPause=Integer.MAX_VALUE`;
- `drone.setRunning(true)`;
- create a proxied `Component`/`Drone` control when missing (no-op buffer,
  address, visibility).

`InertMachineArchitecture` is a no-op architecture so paused machines save
cleanly.

## 11. Lighting aura and LED (manager actions)

`LightingAura` is owned by `VisualsManager` and driven by the retained
`SetLightAction`:

- radius 2–12, light level 4–15;
- applies every 10 ticks when moved/anchor changed;
- only air blocks are lit;
- remembers original block-light values in `VisualsState.lightCells`;
- restores exactly on disable/despawn/shutdown;
- syncs affected chunk sections via `S21PacketChunkData` to players within 96
  blocks;
- 1.7.10 light is scalar; RGB is presentation color (drone glow/particles).

`LedEffects` (manager component, driven by the retained `SetLedAction`):

- parses named/hex colors;
- HSV→RGB;
- procedural effects: RAINBOW, DISCO, POLICE, PULSE/BREATHE, STROBE, FIRE, OCEAN,
  HEARTBEAT, MATRIX;
- `resolveEffectiveLedColor` uses tick + custom color + fallback;
- the retained action advances `ledEffectTick` and emits disco note particles.

## 12. State ownership

`DroneController` keeps only identity and the entity link: `id`, `drone`,
`entityUuid`, `name`, `ownerName`. Everything else is owned by the manager for
its domain and keyed by the controller:

| State | Owner |
| --- | --- |
| goal/error, path, stall counters, path cache, nav tick clock | `NavigationManager` (`NavigationState`) |
| aura radius/level/cells, LED effect/color/tick | `VisualsManager` (`VisualsState`) |
| attack cooldown | `InteractionManager` (`InteractionState`) |
| task, loop generation, script count, thoughts | `SessionManager` (`DroneSession`) |

Managers drop their per-controller entries on despawn. Work state is derived
(`NavigationManager.isActive`, retained action count), never stored as an enum.
Long-running action state lives in the action context.

## 13. Server shutdown

`onServerStopping`:

- log stop;
- restore lighting for every drone with light cells;
- save config;
- stop HTTP server.

The server process wrapper may auto-restart; config/skills survive in the world
directory.
