# Evidence index

This directory holds reviewed evidence: sanitized excerpts of real server
artifacts and the conclusions drawn from them. Generated logs stay untracked
(`*.log` in `.gitignore`); only the reviewed summaries below are committed.

| Date | Source artifact | What was reviewed | Outcome |
|---|---|---|---|
| 2026-11-13 | `config/cortexdrone/logs/cortexdrone.log` (7.8 MB, 49,468 lines) | every `WARN`/`ERROR`/`STUCK`/exception row (1,370) grouped and classified | 599 false "Slow server tick" warnings → now a `SPIKE` note; 2 `InvalidPathException`; 4 same-spot loops; 0 `ERROR`/`SEVERE`. Full table in [`log-audit-2026-11-13.md`](log-audit-2026-11-13.md) |
| 2026-11-13 | `config/cortexdrone/sessions/**` (30 JSON/Markdown transcripts) | each run's outcome, tool errors, final reply | one successful mining run was mis-filed as `failure` ("a few failed attempts"); outcome now takes an explicit `OUTCOME:` line. See [`session-findings-2026-11-13.md`](session-findings-2026-11-13.md) |
| 2026-11-13 | `config/cortexdrone/lessons/**` (39 `LESSON.md`) | every learned rule | informed OC slot handling, junk matching, standoff navigation and crater/void fill behavior |
| 2026-11-13 | `config/cortexdrone/skills/**` (15 learned skills) | every `SKILL.md` + `code.js` | two degenerate auto-skills (`depsawn`, `come_here`) confirmed the need for the outcome/reply guard |
| 2026-11-13 | `logs/fml-client-1..3.log`, `fml-client-latest.log`, `*.log.gz` | CortexDrone load/unload + any mod exception | clean load (0.57 s); one real defect: an off-thread world read from `main(fn)` (inverted guard) — fixed. The only server crash is HodgePodge `TileEntitySkull` bounds validation, not this mod |

Raw logs are intentionally **not** committed: they contain player names, chat
and coordinates. Reproduce a review by pointing the commands at the live
instance directory.

## Rotated logs and crash reports (same date)

`logs/fml-client-1.log`, `fml-client-2.log`, `fml-client-3.log`,
`fml-client-latest.log` and the `*.log.gz` archives were grepped for
`cortexdrone|CortexDrone`. Only one row was more than a normal load/tick
message:

```
fml-client-3.log:17740
[CortexDrone-Agent-1277281352500/WARN] [ServerThreadLongHashMap/]:
Off-thread read from CortexDrone-Agent-1277281352500 - serving from snapshot
  at ...JsEngine$5.call(JsEngine.java:214)
```

That is this mod reading a live chunk from an agent thread. Cause: the
`main(fn)` guard was inverted, so the callback ran on the caller instead of
hopping to the server thread. Fixed; `MainThreadHopTest` pins the branch order.

Crash reports: the only server crash is
`TileEntitySkull ... coordinates outside chunk bounds` raised by HodgePodge's
chunk validation, with CortexDrone present only as a loaded mod. Not this mod.
