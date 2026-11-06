# 09 — Resources, Prompt and Skills

## 1. Resource layout

```text
src/main/resources/
  mcmod.info
  system-prompt.md
  sdk/
    core-rpc.mjs
    drone-actions.mjs
  skills/
    index.json
    build_wall.json
    mine_and_deliver.json
    mine_blocks.json
    place_double_chest.json
    rainbow_show.json
    return_to_player.json
    store_items.json
    transport_entity.json
```

Resources are packaged into the jar. `CodeModeRuntime.loadResourceText` loads
them from the classpath; `SkillLibrary` loads the bundled skill index at startup.

## 2. `system-prompt.md`

The system prompt is the model's contract. It defines:

- the drone's role and version;
- required JSON response schema:
  `{thought, stop, code, done, reply}`;
- the 15 master tools and their exact semantics;
- inspection ops;
- composition rules (no premade tools, no hidden queues);
- entity transport / mining / move-then-cut examples;
- single-task/single-session behavior;
- workflow rules (await physical primitives, compact code, report blockers).

The prompt is loaded once into `LlmAgentService.SYSTEM_PROMPT_TEMPLATE`.
At task start, the service appends:

- `Initial World State: <json>`;
- `Relevant Reusable Skills: <json>` when BM25 finds matches.

## 3. SDK `core-rpc.mjs`

Infrastructure injected before the model's code:

| Symbol | Purpose |
| --- | --- |
| `logs` | captured `console.log` output |
| `log` / `console.log` | append to logs |
| `sleep(ms)` | capped at 30 s |
| `rpc(method,args,opts)` | POST `/rpc`, 3 attempts, JSON cap unless `raw` |
| `_state()` | `/rpc state` |
| `_waitUntil(predicate,maxWaitMs)` | poll state with backoff; throws `SUPERSEDED` on aborted |
| `_waitIdle(maxWaitMs)` | wait for `busy === false` and no break target |
| `_isPendingScan` / `_normalizeScanResult` | large-scan handling |
| `_awaitScanJob(jobId,radius)` | poll `poll_scan` with backoff until done/timeout |
| `_finalizeScript` | no-op hook before output |
| `hsvToRgb(h,s,v)` | HSV→packed RGB |
| `_isObj(v)` | object check excluding arrays |
| `_killTimer` | 175 s internal watchdog; prints `timedOutAfterMs` |

RPC retries use 250 ms × attempt backoff. The default JSON cap is 8000 chars;
`opts.raw` bypasses it. `_waitUntil` backoff starts at 250 ms, grows to 1500 ms.

## 4. SDK `drone-actions.mjs`

The only public global is `drone`:

```js
drone.state()
drone.inspect(op,args)
drone.goto(x,y,z,opts) / drone.goto({x,y,z,standoffOf,maxWaitMs})
drone.stop()
drone.breakBlock(x,y,z,opts)
drone.placeBlock(x,y,z,block,meta,opts)
drone.interactBlock(x,y,z,side,opts)
drone.useItem(itemOrSlot,x?,y?,z?,side?)
drone.pickEntity(id,opts)
drone.unpickEntity(opts)
drone.transfer(opOrOpts,args)
drone.craft(filter,count)
drone.visuals(kind,args)
drone.skills(op,args)
drone.cancel()
```

Movement awaits `state.navigationActive === false`. Block/interaction helpers
retry with `goto(standoff)` when `needsMovement` is returned, and the break
helper tunnels to the target if no standoff exists. Transfer waits for idle when
the result reports a flight in progress.

There are no `world`, `player`, `kb`, `harness`, `say`, `setStatusText`, `mine`,
`tunnel`, `digUp`, or `digDown` globals.

## 5. Bundled skills

`skills/index.json` is an array of filenames. Current bundled skills:

| Skill | Description | Tags |
| --- | --- | --- |
| `build_wall` | Inspect a line and place blocks one at a time | build wall place cobblestone shelter home |
| `mine_and_deliver` | Find matching blocks, break, deliver to player | mine ore break deliver player iron stone |
| `mine_blocks` | Find blocks, move to standoff, break a bounded set | mining mine ore block tunnel dig |
| `place_double_chest` | Craft chests then place two adjacent | craft place double chest planks wood |
| `rainbow_show` | Rainbow LED loop via `drone.visuals` | rainbow led color light fun music show |
| `return_to_player` | Goal-driven navigation back to owner | return come back player escape cave underground tunnel navigate |
| `store_items` | Fly to chest and deposit inventory | chest deposit store items inventory storage |
| `transport_entity` | Pick entity, fly, unpick | entity transport pick unpick player carry move |

Skills are examples/compositions, not hidden Java tasks. They call the same
master tools the model has.

## 6. Skill format

JSON object:

```json
{
  "name": "mine_blocks",
  "description": "Inspect matching blocks, move to a safe standoff, and break a bounded set of them",
  "tags": "mining mine ore block tunnel dig",
  "code": "async function ..."
}
```

- `name` is normalized to lowercase/underscore on save.
- `code` is JavaScript body using `drone`, `sleep`, `log`, `hsvToRgb`.
- `description` and `tags` feed BM25 retrieval.
- `uses` and `updatedAt` are stored for saved skills; bundled defaults are
  normalized on load.

World storage: `world/layercloud-drone-skills-v2.json` (array of SkillEntry).
Saved skills override bundled skills with the same normalized name.

## 7. Loading and matching

`SkillLibrary`:

1. loads `/skills/index.json`;
2. loads each file into `SkillEntry`;
3. normalizes name, defaults description/code/tags/uses/updatedAt;
4. loads world file afterwards (override);
5. on task start, `LlmAgentService` searches top 3 skills using the user prompt;
6. matching skills are appended to the system message.

Search:

- tokenize query with `LexicalItemMatcher`;
- document text = name twice + tags twice + description;
- Okapi BM25 `k1=1.5`, `b=0.75`;
- fuzzy term match (Damerau–Levenshtein);
- sort by score then updatedAt;
- bounded by requested limit.

## 8. Self-corrected skills

When a turn succeeds after self-correction (`done && hadSelfCorrection`),
`SkillLibrary.recordSelfCorrectedSkill` may save:

- name `learned_<slug of task>` (max 36 chars);
- description = thought or task;
- code = working JS;
- tags = task text.

It only records code between 15 and 1400 chars.

## 9. Prompt/context budgets

- `PromptCompactor` threshold: 60,000 chars.
- system prompt capped at 16,000 chars during compaction.
- digest: last 10 messages, 700 chars each.
- LLM `max_tokens`: 1400.
- LLM turn cap: 100; wall clock: 8 minutes.
- SDK JSON default cap: 8,000 chars; raw scans bypass then normalize/cap.
- Agent response size target: under 1200 tokens of JSON (prompt guidance).
- JS body guidance: under 80 lines.

## 10. Resource editing checklist

When changing the prompt/SDK/skills:

1. Keep the master tool list in sync with `MasterToolCatalog` and
   `MasterRpcHandler`.
2. Keep SDK methods in sync with RPC translators.
3. Never reintroduce removed composite tools.
4. Keep `index.json` listing every bundled skill file.
5. Validate JSON (`python -m json.tool`) and JS (`node --check`).
6. Run `./gradlew clean build` (validate runs `node --check`).
