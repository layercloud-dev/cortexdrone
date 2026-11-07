# 06 — Harness

## Components

```text
TaskCoordinator          intent + single-session policy
SessionManager           per-drone session state
SessionJournal           durable per-task transcript, grouped by outcome
AgentService             the tool-calling loop
LlmClient                OpenAI-compatible HTTP client
Conversation             bounded message log + compaction
ContextRenderer          readable live state + tool catalog
ToolInvoker              schema validation + MAIN/ASYNC dispatch + envelopes
ToolRegistry             the single tool surface
JsEngine                 in-process Rhino runtime
MemoryStore              persistent facts + named locations
LessonStore              learned procedural rules
SkillLibrary / Refiner   bundled + learned skills, recursive compiler
SkillRuntime             composes skills via skills.call(name, params)
```

## Task and session

`TaskCoordinator` classifies an incoming chat message deterministically:

- `QUESTION` — answered from live state; the task continues.
- `BY_THE_WAY` — attached as a note; the task continues.
- `MODIFY` / `NEW_TASK` — cancels the running generation and starts a new one.
- `CANCEL` — stops everything.

There is no instruction queue. `DroneSession` holds the active flag, loop
generation, notes and recent thoughts.

## Agent loop

`AgentService.runAgentLoop`:

1. Builds a `Conversation` with:
   - the static system prompt (`resources/prompts/system.md`);
   - a live context message from `ContextRenderer` (Markdown, not a debug dump);
   - the user request.
2. Sends the request through `LlmClient` with the registry's OpenAI `tools`.
3. If the assistant returns `tool_calls`:
   - parse each call's JSON arguments;
   - execute through `ToolInvoker` on the correct thread;
   - append a `tool` message with the capped result;
   - continue.
4. If the assistant returns text, speak it in short chat lines and end.
5. Empty response: retry once with a nudge, then stop with an error.
6. Turn/time budget: stop with a partial-progress message.

No JSON envelope parser, salvage heuristics, heartbeat resurrection threads or
anti-requery string matching are involved. The model sees real errors and
adapts.

## LLM client

- POST `{model, temperature, max_tokens, messages, tools, tool_choice:"auto"}`.
- Parses only `message.tool_calls`; the retired `function_call` shape is ignored.
- Replays `message.reasoning_content` verbatim on the assistant turn in the
  conversation. Thinking models require it back on the next request, and the
  gateway strips the field for upstreams that reject it.
- Retries transient failures; does not retry auth/schema failures.
- Keys are masked in logs.

## Conversation bounds

- Each string is capped; arrays are capped at 24 items by default.
- A tool result larger than `maxToolResultChars` becomes a compact preview with
  a hint to narrow the query or process it in `execute`.
- When the transcript exceeds `maxContextChars`, the middle turns are replaced by
  a digest while the system prompt, live context, original task and recent tool
  results survive. Tool-call/result pairing is preserved.

## JavaScript runtime

`JsEngine` evaluates a function body on Rhino 1.7.15 in interpreted mode.

Bindings:

```text
drone.<camelCaseTool>(args)     all public tools
drone.<snake_case_tool>(args)   alias
tools.call(name, args)          registry escape hatch
tools.list()                    compact tool list
skills.search(query, limit)     loaded skill metadata
skills.load(name)               full skill code
ctx                             {droneId, droneName, owner, player, loopId}
log / console.log               collected and returned
sleep(ms)                       capped at 10s
hsvToRgb / clamp / pick / distance3 / summarizeItems
Java.type(name), Packages, java.* (reflection when enabled)
```

Behavior:

- Tools are synchronous; `await`/`async` are removed by `JsSource`.
- `needs_movement` results are retried automatically by the JS binding.
- Large `scan_blocks` jobs are polled automatically.
- `goto` waits for arrival inside `ToolInvoker`.
- Tool failures are JavaScript errors with `message`, `tool` and `toolResult`.
- Instruction observer + wall clock enforce the budget; cancellation is checked
  between instructions and before/after each tool call.

## Memory

`MemoryStore` is separate from the conversation transcript and survives
restarts. It stores:

- `fact` / `preference` / `task` / `person` entries with a text body;
- `location` entries with a dimension and `x/y/z` coordinates;
- optional tags and a `pinned` flag.

Explicit chat messages such as "remember ...", "keep in mind ...",
"don't forget ...", "this is my base" or "call this the iron farm" are captured
deterministically by `TaskCoordinator` + `MemoryCapture`, even while another
task is running. They become a session note and are confirmed in chat.

The model also gets a `memory` tool:

```text
memory action=remember kind=location name=base use_current_position=true
memory action=remember text="prefers torches over glowstone"
memory action=search query="iron farm"
memory action=forget id=mem_...
```

`AgentService` injects relevant memories plus pinned locations into the context.
Storage is `config/cortexdrone/memory.json`; entries are deduplicated by
`kind + name` and updated in place.

## Operating loop

`system.md` makes the model work in four phases: read-only **recon**,
a short **plan**, one **`execute`** script for the whole action, then
**verify + learn**. Single tool calls are reserved for one lookup or one
primitive the player asked for. After a successful multi-step workflow the
model calls `skill_refine`; if it forgets, `AgentService` auto-saves the last
successful script (only when it performed a real world action) so the work is
never lost.

## Lessons

`LessonStore` is the third memory layer, stored in
`config/cortexdrone/lessons/`, one `LESSON.md` directory per rule (same
frontmatter + Markdown format as `SKILL.md`):

- `memory` — player facts and named places;
- `lesson` — prose rule learned from an attempt;
- `skill` — executable workflow code.

```text
lesson action=add scope=mining title="keep floor when digging down" \
       outcome=failure body="fell -> broke support -> keep a floor block"
lesson action=search query="digging down"
lesson action=forget id=les_...
```

Relevant and pinned lessons are injected into the task context next to memory.

## Session journal

Every `@drone` task creates one `SessionJournal.SessionRun`. The run records
the prompt, live context metadata, every thought, tool call/result and the
final reply. When the loop ends, `SessionJournal` writes:

```text
config/cortexdrone/sessions/
  index.jsonl          one compact line per run (outcome, topic, counts, file)
  success/<id>.json    full transcript + <id>.md readable copy
  failure/
  cancelled/
  interrupted/
  incomplete/
  error/
```

Categories:

- `success` — final answer did not report a problem;
- `failure` — final answer reports that the task was blocked/impossible;
- `cancelled` — player or model cancelled the task;
- `interrupted` — replaced by a new task, drone despawned, config reload or
  server shutdown;
- `incomplete` — turn/time budget or an empty model response ended the loop;
- `error` — LLM/backend/internal exception.

The `topic` field (`mining`, `building`, `navigation`, `inventory`, `combat`,
`survival`, `general`) is derived from the prompt and the tools used. A run is
written exactly once even if the loop and a shutdown hook both finish it.
Inspect with `/drone sessions list`, `/drone sessions stats` or
`/drone sessions show <id>`.

## Skills

`SkillLibrary` stores bundled + learned skills in
`config/cortexdrone/skills/` (one `SKILL.md` + `code.js` directory per skill).
`skill` searches/lists/loads, `skill_run`
executes one with a parameter object, and `skill_refine` sends the task, working
code and notes to the model with a compiler prompt, normalizes
name/description/triggers/tags/requires/code, and saves a new version. If the
compiler call fails, the working code is saved verbatim under a derived name.

Bundled skills keep `SKILL.md` (frontmatter + usage docs) and `code.js` in
`resources/skills/<name>/`. Bodies stay small because
they compose: `mine_and_deliver` calls `mine_blocks` and `deliver_items`,
`guard`/`patrol` call `guard_scan`, `farm_cycle` calls the harvesting skills, and
`build_structure` calls `survey_build_site` then `place_build_plan`. The
`requires` list records those dependencies; loading a skill reports missing
ones and `skill_run` refuses to run with an incomplete dependency set.

`SkillRuntime.call` compiles each body as `(function(params) { ... })` in the
same Rhino scope as `execute`, under a depth guard (8). Skills therefore share
the tool surface and helpers but not the caller's local variables.
