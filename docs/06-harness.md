# 06 — Harness and Code Mode

The `harness` package is the agent/orchestration layer. It owns the LLM loop,
JavaScript execution, task/session state, observability, skill memory, and the
RPC transport boundary (`harness/rpc`).

## 1. Entry points

A task can start from:

- chat: `@drone <message>` or `@<droneName> <message>`;
- command: `/drone <natural-language instruction>` (unknown subcommands fall
  through to natural language);
- HTTP `/action` with `action:"instruct"`/`"command"`;
- heartbeat resurrection (internal continuation loop).

All paths reach `AIDroneManager.handleNaturalInstruction`, which dispatches to the
server thread and then `TaskCoordinator.handle`.

## 2. TaskCoordinator — one session, no queue

`TaskCoordinator`:

1. trims the prompt;
2. determines whether a task is active (`ctrl.task.isActive()` or any live loop);
3. classifies intent deterministically;
4. acts by intent.

| Intent | When active | When idle |
| --- | --- | --- |
| `QUESTION` | Reply from live state; task continues | Starts a new task |
| `BY_THE_WAY` | Attach note to `TaskSession`; task continues | Adds note, replies "Noted." |
| `MODIFY` | Cancel old loops/actions, start replacement with previous-task note | Starts new task |
| `CANCEL` | Cancel loops, clear action state, cancel task, stop navigation | Replies "No task is running" |
| `NEW_TASK` | Replaces the active task | Starts new task |

Starting a task:

1. increments `ctrl.activeLoopId`;
2. `ctrl.task.begin(loopId, prompt)`;
3. builds world context via `LlmAgentService.buildDroneContextForLlm`;
4. registers the loop ID;
5. submits `runAgentLoop` to `CortexDroneMod.HTTP_POOL`.

There is no instruction queue and no background work that the model does not
await.

## 3. TaskIntentClassifier

Deterministic, no LLM. Normalizes text (trim/lowercase, removes leading `@`,
collapses whitespace) and checks:

- cancel prefixes (`stop`, `cancel`, `abort`, `halt`, `quit`, `cease`,
  `stand down`, `never mind`, `forget it`, `belay that`, `shut it down`);
- polite cancel (`can you stop ...`);
- question prefixes (`what/how/where/when/why/who/which/status/report/...`);
- `?` questions (unless a polite command);
- side-note prefixes (`by the way`, `btw`, `also`, `fyi`, ...);
- idle → new task;
- modify prefixes (`actually`, `instead`, `change`, `modify`, ...) or action
  words (`mine`, `dig`, `place`, `craft`, `go`, `bring`, ...).

Unknown side comments while a task is active are treated as questions (keep the
task, acknowledge).

## 4. TaskSession

`TaskSession` lives in `drone/` because it is state owned by the controller,
not part of the LLM loop. One `TaskSession` per `DroneController`:

- active flag, loop ID, prompt, summary, timestamps;
- notes list (max 8); notes append to the summary as `| note: ...`;
- `complete`/`cancel`;
- `getAgeMs`.

It is synchronized because HTTP/agent threads read it while the server thread
updates it.

## 5. LlmAgentService

### Inputs

- `AIDroneManager`
- `DroneLogger`
- `WorldScanner`
- `CodeModeRuntime`
- `RpcDispatcher`

### Prompt construction

`SYSTEM_PROMPT_TEMPLATE` is loaded from `/system-prompt.md`.
`LlmAgentService.buildDroneContextForLlm` delegates to `DroneContextBuilder`,
which adds:

- player position/facing/yaw/pitch/held item/crosshair/support block;
- drone position/navigation/active actions/can-see-sky;
- breadcrumb count;
- drone and player inventory summaries;
- distance to player, task state, script count;
- last path partial/nodes, last thought;
- nearby resource summary (24 m).

Matched skills (top 3 by BM25) are appended to the system message when available.

### Loop

Constants: `MAX_AGENT_TURNS = 100`, `AGENT_DEADLINE_MS = 8 min`.

For each turn:

1. abort if loop cancelled/drone dead;
2. abort with partial reply at the wall-clock deadline;
3. show "Thinking..." status when no action owns the status text;
4. compact messages if too large;
5. call the configured OpenAI-compatible chat endpoint;
6. handle context-overflow compaction + retry;
7. on backend failure, retry up to 3 times;
8. parse/salvage response (`LlmResponseParser`);
9. record thought;
10. handle malformed/missing code via self-correction feedback;
11. handle optional pre-code stop/cancel;
12. execute JS through `CodeModeRuntime`;
13. feed result back; detect progress, errors, repeated code, repeated queries,
    empty searches;
14. handle `done:true`:
    - `AgentHeartbeat.rejectIncompleteDone` refuses premature completion;
    - optionally records a self-corrected skill;
    - sends `reply` and exits.

If a turn produces no code and no done, it appends a "next step" nudge. At turn
cap/deadline it reports partial progress instead of silently stopping.

### HTTP call

- OpenAI-compatible `POST` to `AIConfig.apiUrl`;
- headers: JSON, `Authorization: Bearer <key>` when set;
- body: model, temperature 0.2, `reasoning_effort:"low"`, max_tokens 1400,
  JSON response format on first attempt;
- 10 s connect / 30 s read timeout, 3 attempts;
- circuit breaker after 5 consecutive connect failures (60 s window);
- API key diagnostics are masked.

## 6. LlmResponseParser

Resilient parser for model output:

- strips ` thinking...</think>`;
- extracts balanced JSON objects;
- salvages truncated/unescaped `code` strings;
- extracts `thought`, `code`, `done`, `reply`, `stop`;
- detects prose blockers (HBM machine/recipe/missing-ingredient essays) and turns
  them into a `done:true` result with the blocker;
- `sanitizeThought` caps thought length and strips fences.

This is intentionally forgiving: a malformed model response should become one
self-correction turn, not a dead task.

## 7. PromptCompactor

- estimate chars across messages;
- threshold 60,000 chars;
- keeps system prompt (capped 16,000 chars) + original user prompt + digest of
  last 10 messages (700 chars each);
- marks context overflow for `LlmAgentService` to compact/retry;
- compaction preserves the original task and recent results so a replacement
  loop can continue.

## 8. AgentHeartbeat

Watchdog/resurrector for three failure modes:

1. empty/failed LLM responses;
2. premature `done:true` with only scans/queued work/undelivered items;
3. loop exit with active work or no progress.

Limits: max 3 continuations per drone per 10-minute window, 2.5 s delay.

`rejectIncompleteDone` checks concrete progress, active work (a navigation
session or a retained action), and delivery pending. `onLoopExit` builds a compact
`[HEARTBEAT RESUME]` prompt and re-enters `handleNaturalInstruction` if the loop
was not cancelled/superseded and budget remains.

## 9. AgentLoopHeuristics

Deterministic anti-loop helpers:

- 8-minute deadline;
- message window pruning (system + original user + last 6);
- code fingerprinting;
- pre-code stop/cancel;
- physical action detection (`goto`, `stop`, `breakBlock`, `placeBlock`,
  `interactBlock`, `useItem`, `transfer`, `craft`);
- concrete progress detection (`started/placed/used/activated/broken/
  transferred/moved/withdrawn/deposited/crafted/arrived`);
- repeated-result detection;
- machine-config/empty-search fast final;
- query fingerprinting for repeated `inspect/state` targets;
- nudge texts for anti-requery, empty-search, anti-loiter.

## 10. LoopTracer

Structured loop observability:

- `TRACE` start line for every loop;
- `TRACE` per turn with elapsed/malformed/thought/code snippets;
- `SUPERSEDED` log + spoken notice;
- masked API keys;
- error/status snippets capped.

## 11. CodeModeRuntime

- Loads SDK bundle: `/sdk/core-rpc.mjs` + `/sdk/drone-actions.mjs`.
- Writes a temporary `/tmp/opencode/drone-codemode-<id>-<nano>.mjs`.
- Prepends constants: `DRONE_ID`, `LOOP_ID`, `IS_FINAL_TURN`, `PLAYER_NAME`,
  `HTTP_PORT`.
- Appends an async wrapper that catches errors, finalizes, and prints one JSON
  result.
- Runs `/usr/local/bin/node --max-old-space-size=256 --no-warnings <script>`.
- Default timeout 180 s; internal SDK kill timer at 175 s.
- Polls every 250 ms for cancellation/dead drone and destroys the process.
- Parses the last `{...}` JSON from stdout; returns `{ok, result, logs, error,
  superseded, timeout, rawOutput}`.
- Deletes the temp script in `finally`; decrements `activeScriptCount`.

The JavaScript body is user/model-provided; the SDK is trusted infrastructure.

## 12. RpcDispatcher

`RpcDispatcher` is the main-thread RPC gate.

### Normal RPC

1. `executeOnMainThread(ctrl, player, method, args)`.
2. If large scan and not already on server thread → `submitLargeScan`.
3. Otherwise marshal to server thread via `AIDroneManager.callOnMainThread`.
4. Timeout budget:
   - 2.5 s for cheap inspect ops (block/box/veins/room/distant/entities/drops/
     blocks);
   - 8 s otherwise.
5. `executeSync` routes to the registered `RpcHandler`.

### Large scans

Only `inspect` with op `blocks`/`scanblocks` and radius >32.

- one active job at a time (`AtomicScanLock`);
- later submissions get `{ok:false,busy:true,jobId}`;
- job scans a capped cube (default cap 32, absolute 64), optionally down to Y=1
  for underground queries;
- cursor scans one voxel per step; slices bounded by 8 ms and 30,000 voxels;
- max 400 results, distance-sorted;
- partial results report `scannedVoxels`, `truncated`, `jobId`;
- 30 s job age-out;
- `poll_scan` returns the active/finished job; it never drains inline on the
  HTTP thread;
- finished jobs stay in `jobByKey` so the SDK can poll the result.

## 13. SkillLibrary

- Storage file: `world/layercloud-drone-skills-v2.json`.
- Bundled skills: `resources/skills/index.json` (array of filenames) loaded at
  startup; saved world skills override bundled names.
- `SkillEntry`: name, description, code, tags, uses, updatedAt.
- `saveSkill` normalizes names to lowercase underscore, increments uses.
- `searchSkills` uses Okapi BM25 (k1=1.5, b=0.75) over name (weighted twice),
  tags (weighted twice), description; fuzzy term matching via
  `LexicalItemMatcher`; sorted by score then recency.
- `recordSelfCorrectedSkill` distills a working self-corrected workflow into a
  `learned_*` skill.
- `toJsonArray` used by the `skills` tool.

## 14. Threading model

| Pool/thread | Work |
| --- | --- |
| Server thread | world/entity mutation, RPC execution, tick pipeline |
| `HTTP_POOL` (`CortexDrone-Agent-*`) | LLM loops, JS runtime, log flushes |
| `HTTP_SERVER_POOL` (`CortexDrone-Http-*`) | `/state`, `/debug`, `/rpc`, `/action` handling |
| Main-thread queue | tasks queued from HTTP/agent threads |
| Node process | one per JS turn |

`AIDroneManager.callOnMainThread` is a no-op passthrough on the server thread; off
thread it queues and waits with a timeout. This is what keeps 1.7.10 world access
safe.

## 15. Failure behavior

- LLM/backend failure → retries then spoken error; heartbeat may resurrect.
- malformed response → self-correction turn(s).
- JS error → self-correction feedback with error text.
- RPC timeout → `{ok:false,error}`; no silent hang.
- superseded loop → `SUPERSEDED` result, no continuation.
- large scan busy → explicit busy/jobId.
- context overflow → compact + retry.
- turn cap/deadline → partial progress reply.
