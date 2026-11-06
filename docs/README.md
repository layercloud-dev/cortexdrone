# CortexDrone Documentation

This is the full project documentation for CortexDrone v1.4.0, a Forge 1.7.10
mod that connects an LLM-driven Code Mode agent to OpenComputers drones.

Read in order for a complete picture, or jump to a chapter.

| Chapter | Contents |
| --- | --- |
| [01-overview.md](01-overview.md) | Product purpose, runtime topology, constraints, terminology |
| [02-layers.md](02-layers.md) | Action/context/manager/service/harness/RPC layers and dependency rules |
| [03-actions-managers.md](03-actions-managers.md) | Action lifecycle, registry, every manager and action |
| [04-navigation.md](04-navigation.md) | Goal navigation, breadcrumbs, flight A*, dig planning, movement actions |
| [05-services.md](05-services.md) | Inventory, machine, world and navigation read/planning services |
| [06-harness.md](06-harness.md) | LLM loop, Code Mode runtime, task/session, heartbeat, RPC dispatcher |
| [07-tools-rpc.md](07-tools-rpc.md) | Tool catalog, translators, HTTP API, SDK behavior |
| [08-runtime.md](08-runtime.md) | Mod lifecycle, tick pipeline, modes, spawn/link/despawn, config, logging |
| [09-resources.md](09-resources.md) | System prompt, SDK modules, skill format, bundled skills |
| [10-build-deploy.md](10-build-deploy.md) | Gradle/RFG, validation, tests, deploy, server operations |
| [11-extending.md](11-extending.md) | Rules and recipes for adding actions, services, tools and skills |
| [12-source-inventory.md](12-source-inventory.md) | Class-by-class source map |
| [removed-tasks.md](removed-tasks.md) | Tasks intentionally removed from the runtime |

## Reading order

1. **New contributor**: 01 → 02 → 03 → 06 → 07.
2. **Navigation work**: 02 → 03 → 04 → 05.
3. **LLM/prompt work**: 06 → 07 → 09.
4. **Deployment**: 10 → 08.
5. **Adding a feature**: 11, then the relevant subsystem chapter.

## Source of truth

The documentation describes the current code. When code and docs differ, the
code under `src/main/java` and `src/main/resources` wins; update the relevant
chapter in the same change.
