# 10 — Build, test, deploy

## Toolchain

| Component | Version |
| --- | --- |
| Minecraft / Forge | 1.7.10 / 10.13.4.1614 |
| Gradle | 9.2 (wrapper) |
| Gradle daemon | Java 25 (GraalVM) |
| Java compile target | 8 |
| RetroFuturaGradle | 2.0.6 |
| OpenComputers | `1.12.70-GTNH` (compile-only) |
| JavaScript engine | Rhino 1.7.15 (shaded) |

`build.sh` exports `JAVA_HOME=/usr/lib/jvm/graalvm-jdk-25` when present and runs
`./gradlew build`.

## Commands

```bash
./build.sh                 # validate + test + reobf + deploy
./gradlew compileJava      # compile against MCP-named Minecraft
./gradlew test             # unit tests
./gradlew validate         # line limit + package/directory checks
./gradlew jar              # development jar (includes shaded Rhino)
./gradlew reobfJar         # production (SRG-named) jar
./gradlew deploy           # reobf + copy to modsDir
./gradlew clean build
```

`build` depends on `validate` and `deploy`. `deploy` removes older
`CortexDrone-*.jar` files from `modsDir`.

## Dependency and shading

`build.gradle`:

```gradle
implementation 'org.mozilla:rhino:1.7.15'
configurations { shade }
dependencies { shade 'org.mozilla:rhino:1.7.15' }
jar {
    from { configurations.shade.collect { it.isDirectory() ? it : zipTree(it) } }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude 'META-INF/*.SF', 'META-INF/*.DSA', 'META-INF/*.RSA', 'META-INF/MANIFEST.MF'
}
```

Rhino 1.7.15 is deliberately pinned: it is Java 8 bytecode (class version 52),
which Minecraft 1.7.10's ASM-based mod scanner accepts. Newer Rhino/GraalJS
releases use Java 11+ class files and would break mod discovery.

## Validation

`validate` fails the build when:

- a Java file exceeds 400 lines;
- a package declaration does not match its directory;
- a declared tool is not bound (the harness also checks this at startup).

JavaScript syntax and behavior are covered by `JsSourceTest` and `JsEngineTest`
on the embedded Rhino engine; Node.js is not required.

## Tests

- `ToolRegistryTest` — catalog uniqueness, schemas, removed routers/composites,
  binding rules, internal visibility.
- `ToolValidatorTest` — required fields, coercion, enum errors.
- `JsSourceTest` — await/async normalization, modern operators, TypeScript
  erasure, literal safety, spread.
- `JsEngineTest` — tool call/return, catchable errors, envelope propagation.
- `SkillLibraryTest` — bundled loading, BM25 search, versioned persistence.
- `SessionJournalTest` — success/failure/cancelled/interrupted categorisation,
  single-write guard, index search and counters.
- `BundledSkillSuiteTest` — every bundled `SKILL.md` + `code.js` pair loads,
  the body compiles in Rhino, `requires` targets exist, compound skills call
  their primitives, the Markdown round-trips, and search retrieves the expected
  capability.
- `SkillLibraryTest` — directory storage, SKILL.md persistence, ignored retired
  `skills.json`, and restoring a bundled skill when an override is deleted.
- `LessonStoreTest` — LESSON.md directories, Markdown round-trip, ignored retired
  `lessons.json`, and directory cleanup on forget.
- `LessonStoreTest` — lesson add/search/dedupe/context and persistence.
- `MemoryStoreTest` / `MemoryCaptureTest` — fact/location capture, coordinates,
  dedupe/update, persistence and pinned context selection.
- `DroneSessionTest`, `ActionRegistryTest` — session, inbox and action
  lifecycle invariants.

## Deployment and operations

1. `./build.sh` (or `./gradlew build`).
2. Restart the server so Forge loads the new jar.
3. Verify with `/drone status`, `/drone memory list`, `/drone lesson list`,
   `/drone sessions stats`, and `@drone status`.

Logs: `config/cortexdrone/logs/cortexdrone.log`.
No Node.js path, script directory or RPC port is used.

## Troubleshooting

- **LLM 401/403** — check `/drone config` and `config/cortexdrone/config.json`.
- **Gateway rejects tools** — the harness requires OpenAI native tool calling;
  point it at a gateway that accepts `tools` + `tool_choice`.
- **`js_syntax`** — the model used a construct outside the ES6+subset; the error
  includes line/column and the compatibility hint.
- **`navigation_failed`** — the navigator found no safe no-dig route; dig a
  tunnel with `break_block` or choose a closer standoff and retry.
- **`scan_busy`** — one large scan at a time; poll `poll_scan` before starting
  another.
- **Chunk not loaded** — fly closer with `goto` (chunk loading follows the
  player/drone) before scanning.
