# CortexDrone Architecture

CortexDrone is an autonomous OpenComputers drone controller for Minecraft 1.7.10.
An LLM writes JavaScript; the JavaScript runs in Rhino and calls a small master-tool
surface on the Minecraft server. The design keeps data, decisions, atomic actions
and transport separate.

## Execution path

```text
Minecraft chat / command / HTTP
        |
        v
TaskCoordinator (one task/session)
        |
        v
AgentService (LLM loop, prompt, heartbeat)
        |
        v
JsEngine (Rhino) -> tools -> MainThreadCall
        |
        v
ToolRegistry -> handlers -> managers -> actions
        |
        v
Minecraft world / OpenComputers Drone / inventory / navigation
```

The model never calls Java directly. Tools receive JSON, translators build typed
requests, managers execute them, and results return as JSON.

## Package map

```text
src/main/java/ir/layercloud/cortexdrone/
  CortexDroneMod.java       Forge entrypoint, tick events, chat
  AIDroneManager.java       root manager, lifecycle, spawn/link/despawn/config
  DroneLifecycle.java       drone spawn/link/despawn orchestration
  DroneReconnector.java     reconnect persisted world drones
  DroneTickLoop.java        per-controller tick pipeline
  InstructionRouter.java    chat/HTTP instruction hand-off
  TickProfiler.java         server tick timing diagnostics

  action/                   atomic one-shot operations
  context/                  mutable state for one action execution
  manager/                  long-lived orchestration per category
  service/                  read-only queries, scanning and planning
    inventory/              InventoryLimits, InventoryService, CraftingService,
                            FluidOpsService, ContainerSearchService,
                            PlayerInventoryOps, ToolInspector
    machine/                machine recipe introspection and tile inspection
    navigation/             flight/dig path planning and physics
    world/                  block, ore, structure, entity and web lookup
    oc/                     OpenComputers state and component introspection
    question/               ask_player dialog transport and state

  harness/                  agent loop and tool transport
    agent/                  LLM client, prompt compaction, turn recovery
    session/                session journal, index and markdown export
    skill/                  skill library, refiner and code guard
    lesson/                 learned lessons
    memory/                 player facts and named locations
    tool/                   tool specs, registry, invocation, scan jobs
    tools/                  concrete tool handlers
    js/                     Rhino engine, globals and Java interop
  network/                  optional client map/question synchronization
  config/                   configuration, paths and logging
```

`InventoryLimits` is the single owner of inventory policy constants. Actions,
tools and services must read limits from their owning policy class rather than
keeping local copies; the source lives in `service/inventory/InventoryLimits`.

Detailed subsystem notes live next to the code in package/class Javadoc.
