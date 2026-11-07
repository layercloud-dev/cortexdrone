# CortexDrone documentation

CortexDrone connects an LLM-driven agent to OpenComputers drones in Minecraft
1.7.10. The agent uses native tool calling and an **in-process Rhino JavaScript
runtime**; there is no Node.js sidecar and no RPC transport.

Reading order:

| Chapter | Contents |
| --- | --- |
| [01-overview.md](01-overview.md) | What the mod does, runtime pipeline, requirements |
| [02-layers.md](02-layers.md) | Layer rules, package map, dependency direction |
| [03-actions-managers.md](03-actions-managers.md) | Actions, contexts, managers, registries |
| [04-navigation.md](04-navigation.md) | Goal navigation, trail/flight, digging policy |
| [05-services.md](05-services.md) | Inventory, machine, world, navigation and web services |
| [06-harness.md](06-harness.md) | Task loop, LLM client, tool calling, JS runtime, skills |
| [07-tools.md](07-tools.md) | Tool registry, schemas, JavaScript surface |
| [08-runtime.md](08-runtime.md) | Lifecycle, tick pipeline, config, logging, persistence |
| [09-resources.md](09-resources.md) | Prompts, JS bootstrap, bundled skills, config files |
| [10-build-deploy.md](10-build-deploy.md) | Gradle, tests, validation, deploy, operations |
| [11-extending.md](11-extending.md) | Adding tools, actions, services and skills |
| [12-source-inventory.md](12-source-inventory.md) | Class-by-class source inventory |

Top-level architecture: [../ARCHITECTURE.md](../ARCHITECTURE.md).
