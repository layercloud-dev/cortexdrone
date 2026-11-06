# Removed legacy paths

These were deliberately removed during the v2 harness rewrite. Do not reintroduce
them as hidden paths.

## Removed harness / transport

| Legacy | Replaced by |
| --- | --- |
| `CodeModeRuntime` writing `/tmp/... .mjs` and spawning Node.js | `harness.js.JsEngine` (in-JVM Rhino) |
| `sdk/core-rpc.mjs`, `sdk/drone-actions.mjs` | registry-generated `drone` object |
| HTTP `/rpc` transport between JS and the server | direct `ToolInvoker` calls |
| `RpcDispatcher`, `RpcHandler`, `harness/rpc/**` | `ToolInvoker`, `ToolProvider`, `harness/tool/**` |
| `ToolRegistry` name/description-only catalog | schema-carrying `ToolSpec` + binding registry |
| `system-prompt.md` with a debug JSON dump | `prompts/system.md` + `ContextRenderer` Markdown |
| `LlmResponseParser` (JSON envelope salvage) | native OpenAI `tool_calls` |
| `AgentLoopHeuristics` (string-based re-query/loiter nudges) | real tool error envelopes |
| `AgentHeartbeat` resurrection threads | turn/time budget + explicit model recovery |
| `PromptCompactor` | `Conversation` compaction with tool-call pairing |
| `DroneContextBuilder` JSON dump | `ContextRenderer` readable context |
| `AIConfig` in `world/layercloud-ai.json` | `ModConfig` in `config/cortexdrone/config.json` |
| skills in `world/layercloud-drone-skills-v2.json` | skills in `config/cortexdrone/skills.json` |

## Removed task/behavior layer (earlier refactors)

| Legacy | Replaced by |
| --- | --- |
| `Mode` enum / FOLLOW/STAY/GOTO/MINE/DEPOSIT/DROP switch | idle = no navigation + no retained action |
| Mining engine / dig planner / break progress | model composes `scan_blocks` + `break_block` |
| LED effect engine | `set_led` loops / skills |
| Premade composite tools (`mine`, `hunt`, `farm`, `buildStructure`, `sortChests`, `carry`, ...) | `execute` compositions |
| Instruction queue | one task/session, cancellation on new/modify |
| `codemode/` package | `harness/` |
| `model/`, top-level `inventory/`, `world/`, `navigation/` packages | `service/*` |

## Still intentionally unsupported

- Multiple simultaneous tasks per drone.
- Background work that outlives the final answer.
- A second JavaScript engine/process.
- Model-authored raw RPC method names (use the registry).
