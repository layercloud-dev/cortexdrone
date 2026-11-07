# 01 — Overview

## What it is

CortexDrone is a Forge 1.7.10 mod that controls one OpenComputers drone per
player. The drone is not driven by a scripted task system: an LLM decides what
to inspect and which atomic operation to perform next. The model can either call
one declared tool directly or call `execute` to run a JavaScript body that
composes many tool calls.

## Runtime pipeline

```text
player chat / @drone / /drone
  -> TaskCoordinator          intent: question, note, modify, cancel, new task
  -> AgentService             system prompt + live context + user request
  -> LlmClient                OpenAI-compatible chat with native tools
  -> tool_calls               each call goes to ToolInvoker
  -> ToolInvoker              validate schema, dispatch MAIN or ASYNC
       -> providers -> managers -> actions -> Minecraft / OpenComputers
       -> JsEngine (Rhino) -> drone.* bindings -> ToolInvoker
  -> tool results             JSON envelope + catchable JS errors
  -> final text               spoken to the player in short chat lines
```

Key properties:

- **One task at a time.** There is no instruction queue. A new or modified task
  cancels the current generation and its retained actions.
- **Native tool calling.** The model produces `tool_calls`, not a JSON envelope
  inside prose.
- **In-process code mode.** `execute` runs in the server JVM on Rhino. Tool calls
  are synchronous; `await` is accepted but unnecessary.
- **Registry-driven surface.** The OpenAI tool list, the JavaScript `drone`
  object and the prompt catalog come from one `ToolRegistry`.
- **Config lives outside the world.** `config/cortexdrone/` holds the gateway
  settings, skills and saved drone bindings.

## Requirements

- Minecraft 1.7.10, Forge, OpenComputers (`1.12.70-GTNH` at build time).
- A server JVM able to load Java 8 bytecode (the deployed server runs Java 25).
- An OpenAI-compatible chat-completions endpoint that supports `tools` /
  `tool_calls`. Legacy `functions` gateways are attempted as a one-time fallback.
- No Node.js installation is required.

## What the model can do

- Inspect blocks, rooms, veins, entities, drops, inventories, chests, furnaces,
  machines and recipes.
- Fly, break, place, interact, use items, attack, pick/unpick entities.
- Move items, give/take, withdraw/deposit, suck drops, craft.
- Look up local registries, machine recipes, wiki pages and the public web.
- Search, load and refine reusable skills.
- Remember durable player facts and named locations (bases, farms, mines) across
  sessions in `config/cortexdrone/memory.json`.
- Remember procedural lessons (symptom -> cause -> rule) in
  `config/cortexdrone/lessons/<name>/LESSON.md`, injected before planning similar work.
- Log every task to `config/cortexdrone/sessions/<outcome>/` (success, failure,
  cancelled, interrupted, incomplete, error) with a searchable index.
- Run the bundled skill suite: mining/hauling, storage sorting, hunting,
  guarding, patrolling, carrying players, crop/sugarcane/melon farming, tree
  felling and phased structure building.
- Work recon -> plan -> one script -> verify, then turn successful workflows
  into skills that can call each other.
- Run JavaScript for loops, plans, bulk scans and conditional workflows.

## What it deliberately cannot do

- Queue several instructions and run them later.
- Use hidden composite tasks (`mine`, `hunt`, `farm`, `buildStructure`, ...).
- Ask the model for a JSON response envelope.
- Call Node.js or a second process for code execution.
