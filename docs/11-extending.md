# 11 — Extending

## Add a tool

1. Pick the owning provider in `harness/tools/` (or create one).
2. Declare a `ToolSpec` in `specs()`:

```java
ToolSpec.builder("my_tool")
    .description("What it does, its filters, defaults and failure modes. "
            + "This text is shown to the model.")
    .parameters(ToolSchema.object()
            .requiredInt("x", "Block X.")
            .optionalString("filter", "Item/block/ore tag or 'all'.", "all"))
    .affinity(ToolAffinity.MAIN)   // or ASYNC for pure I/O
    .timeoutMs(5_000L)
    .build();
```

3. Bind a handler in `register(ToolRegistry registry)`:

```java
registry.bind("my_tool", getClass().getSimpleName(), (context, args) -> {
    JsonObject result = ToolJson.ok();
    result.addProperty("done", true);
    return result;
});
```

4. Add the provider to `ToolCatalog.standard()` and `Harness` if it is new.
5. Add the name to `ToolRegistryTest.requiredPrimitivesAndHarnessToolsExist`.
6. Implement world access in a service or manager, not in the provider.

The tool automatically appears in the OpenAI tool list, the JS `drone` object,
the prompt catalog and the JS `drone` object.

## Add an action

1. Create request/result DTOs in `action/<domain>/`.
2. Implement `Action<XContext>`.
3. Register the operation in the matching manager (`createContext` /
   `createAction` / `result`).
4. Add a tool that parses JSON into the DTO in `harness/tools/view/`.
5. Add a unit test for the manager/registry behavior.

## Add a service

Services are read-only. Put data/recon/planning in `service/` and expose it
through a tool. A service must not start actions, retain work, or import
`harness`.

## Add a skill

- Bundled: create `resources/skills/<name>/SKILL.md` (YAML frontmatter plus
  usage docs) and `resources/skills/<name>/code.js`, then add `<name>` (the
  directory) to `resources/skills/index.json`.
- Learned: call `skill_refine({task, code, notes})` after a successful workflow.
- Manual: create `config/cortexdrone/skills/<name>/SKILL.md` + `code.js`, or
  use the `skill_refine` tool. Only directory-backed skills are read.

Skill code is a function body that receives `params`: use synchronous
`drone.*` calls, read options from `params`, and `return` a small result.

Rules for keeping skills composable:

1. One skill does one job. Do not copy mining, delivery or farming code; call
   the primitive with `skills.call('mine_blocks', { ... })`.
2. List every called skill in `requires`. `skill_run` refuses to run a skill
   whose dependencies are missing, and `skill` action=load reports them.
3. Return `{ ok, ...evidence }` so a caller can decide what to do next; never
   `throw` for an expected condition such as "no ore nearby".
4. Keep the body under ~80 lines. If it grows, split the reusable part out.
5. Parameterise, don't special-case: `params.filter`, `params.count`,
   `params.radius`, `params.dry_run`.
6. Add a case to `BundledSkillSuiteTest` when the skill is part of the default
   suite (compiles, dependencies exist, compound skills call their primitives).

Building skills should always survey first (`survey_build_site`) and place a
`plan_structure` blueprint through `place_build_plan`; do not add a second
block-placement loop.

## Add a memory-backed tool

Use `Harness.memory()` / `MemoryStore` instead of inventing a new file. Call
`remember(...)`, `search(...)`, `forget(...)` or `captureFromChat(...)`.
Locations must include `dimension` and `x/y/z`; reuse `kind + name` to update.

## Add a lesson-backed tool

Use `Harness.lessons()` / `LessonStore` for procedural knowledge; each lesson
is a `config/cortexdrone/lessons/<name>/LESSON.md` directory. Call
`add(...)`, `search(...)`, `forget(...)` or `recordUse(...)`. Keep lessons short
(symptom -> cause -> rule) and scoped; executable workflows belong in
`SkillLibrary`.

## Add a web source

Extend `WebLookupService` and declare an `ASYNC` tool in `WebTools`. Never touch
Minecraft state from an `ASYNC` tool. Respect `webSearchEnabled` and cap output.

## Add Java reflection access

`ModConfig.allowJavaReflection` (default `true` for trusted single-server use)
switches the whole interop surface on or off:

- **On** — the script scope is an `ImporterTopLevel`, so `Packages`, `java.*`,
  `javax.*`, `importClass(...)` and `importPackage(...)` work, plus
  `Java.type(name)` (accepting a `.class` suffix and `[]` array suffixes),
  `Java.from`, `Java.to`, `Java.isJavaObject`, `Java.isType`,
  `Java.getClassName` and `JavaAdapter`. `JavaInterop` also adds a `.class`
  property and the `java.lang.Class` methods on every class handle and live
  instance, so `Java.type("net.minecraft.world.World").getName()` and
  reflection (`Class.forName`, `getDeclaredMethods`, `Method.invoke`) work.
- **Off** — the scope has no `Java`, `Packages`, `java.*`, `importClass` or
  `importPackage`, and a `ClassShutter` additionally hides I/O, network,
  reflection and process classes. Plain `execute` scripts keep working.

Minecraft state is not thread safe. Scripts run on the agent thread, so reading
live objects is fine but mutation must go through the `main(fn)` bridge, which
hop-posts the function to the server thread and returns its result:

```js
var mod = Java.type("ir.layercloud.cortexdrone.CortexDroneMod");
return main(function() {
  var player = mod.findPlayer(ctx.player);
  return player == null ? null : { x: player.posX, y: player.posY, z: player.posZ };
});
```

Prefer a registered tool whenever one exists; reach for reflection to inspect
what no tool exposes, not to replace mutations that already have an action.

## Guidelines

- Keep Java files under 696 lines (validated).
- Keep package names equal to directories.
- Describe *when not to use* a tool and what its errors mean.
- Never add a hidden composite task; loops belong in `execute`.
- Return small results; the conversation and JS logs are bounded.
