# 02 — Layers and package map

## Layer direction

```text
command/http
     |
     v
harness (task, agent, tool, js, skill)
     |
     v
manager  ->  action  ->  context
     |
     v
service
     |
     v
Minecraft / OpenComputers
```

Rules:

- **action**: one physical operation, typed request/result, no JSON, no routing.
- **context**: mutable state for one action execution, lifecycle and progress.
- **manager**: owns a category, creates actions/contexts, retains/ticks/cancels,
  decides category-level behavior. Only navigation has a goal loop.
- **service**: read-only recon/planning/data; may expose low-level storage
  primitives that a manager explicitly invokes; never starts work.
- **harness**: decides and translates. JSON boundary is `harness/tools/view`;
  `ToolInvoker` validates and dispatches. `JsEngine` runs model code in-JVM.
- **command/http**: thin entry points; delegate to the manager/harness.

No lower layer imports a higher layer. `service` never imports `manager` or
`action` (apart from DTO-free helper calls), and `action` never imports `harness`.

## Package map

```text
ir.layercloud.cortexdrone
  CortexDroneMod          Forge lifecycle, tick events, chat, pools
  AIDroneManager         root manager, lifecycle, spawn/link/despawn
  DroneController        identity + entity link
  TickProfiler           timing diagnostics

  action/                atomic operations
  context/               action execution state
  manager/               orchestration
  service/               read-only data/recon/planning

  harness/
    Harness              composition root
    agent/               AgentService, LlmClient, Conversation, ContextRenderer
    js/                  JsEngine, JsSource, TypeScriptEraser
    tool/                ToolSpec, ToolRegistry, ToolInvoker, ScanJobs, ToolSchema
    tools/               providers + view/ JSON boundaries
    skill/               Skill, SkillLibrary, SkillSearch, SkillRefiner
    session/             DroneSession, SessionManager
    task/                TaskCoordinator, TaskIntentClassifier

  command/               /drone
  config/                ModConfig, ModPaths, ConfigMigrator, DroneLogger
  http/                  telemetry + /tool + /action
  oc/                    OpenComputers reflection bridge
```

## Dependency examples

- `BlockTools` (harness) imports `BuildingView` → `BuildingManager` → action.
- `ToolInvoker` imports `AIDroneManager` only to marshal onto the server thread.
- `JsEngine` imports `ToolInvoker` and tool specs, never a manager.
- `ContextRenderer` imports read-only services and the registry catalog.
- `SkillRefiner` imports `LlmClient` and `SkillLibrary`; it does not touch the
  world.

## JSON boundary

Typed requests/results inside `action`, `context`, `manager` and `service` are
plain Java objects. `harness/tools/view/*` parses `JsonObject` into those typed
objects and serializes results. `ToolInvoker` adds the common error envelope.
The LLM and HTTP protocols are JSON by definition.
