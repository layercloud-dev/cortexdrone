# Session findings — 2026-11-13

Source: `config/cortexdrone/sessions/**` (30 JSON + Markdown transcripts,
`index.jsonl`). Reviewed: every session's `outcome`, `reason`, tool-error rows
and final reply.

## Outcome distribution at the time of review

| Outcome | Runs | Notes |
|---|---|---|
| success | 12 | mining, navigation, inventory, general |
| failure | 2 | one of them mis-filed (below) |
| interrupted | 7 | server stop, config reload, drone despawn |
| cancelled | 1 | player cancelled a 113-tool mining run |
| error | 6 | all `llm_error`, all gateway |

## Defects proven by these transcripts

1. **Mis-filed success.** `20261113-072743-d5-l8-mining-1-f630` sits under
   `sessions/failure/` although the drone mined 60 ore. Its reply contains
   "plus a few failed attempts", which the old substring scan read as a
   failure. Fixed: an explicit `OUTCOME:` line wins, a past-setback sentence is
   ignored, and the system prompt now requires the line
   (`SessionOutcomeClassificationTest`).

2. **Gateway failures.** Six `llm_error` sessions: five were an HTTP 429
   `free-models-per-day` quota, one an HTTP 404. Both told the player to "fix
   the gateway/key" (wrong setting) and the quota was retried seven times.
   Fixed: daily quota is not retried; advice names credits/model for 429,
   `apiUrl`/model for 404 and the key for 401/403.

3. **Reflection failures inside `execute`.** The same session tried to read an
   HBM crate through `Java.type(...).class`, `new IFC()` and
   `new java.io.File(url.getPath())`. All three failed in the harness, not in
   the script:
   - `JavaAdapter requires at least one argument` / `ObjectWrapper is not a
     function` → a class literal is now constructible;
   - `InvalidPathException: Illegal char <:>` → `toFile()`/`toPath()`;
   - `InstantiationException` (abstract `ItemInventory`) and ambiguous
     `String.replace` → targeted `JsHints`.

4. **Repeated 3-second navigation loop.** `dug=0/32 recov=4 detours=12` from
   the same block for ~20 minutes. Fixed with an 8-recovery same-spot cap.

## Tool-error tally (all sessions)

| Tool | Errors | Dominant cause |
|---|---|---|
| `execute` | 25 | 4 script budget, 3 `allow_dig:false` nav, reflection cases above |
| `goto` | 18 | navigation refusals (protected zone, budget, no progress) |
| `skill_run` | 5 | 4 script budget on learned skills |
| `get_chest` | 2 | container not at the given coordinate |
| `get_top_y`, `wiki_lookup` | 1 each | transient / bad lookup |

The two `js_budget`-heavy learned skills (`crate_mining_cycle`,
`mine_refill_room_surface`) exceed the default 120 s script budget by design;
they are player data, so they were left untouched and recorded here.
