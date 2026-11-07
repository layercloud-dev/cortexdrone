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

- Bundled: add `<name>.json` plus `<name>.js` (referenced by `codeFile`) and
  list the JSON in `resources/skills/index.json`.
- Learned: call `skill_refine({task, code, notes})` after a successful workflow.
- Manual: add an entry to `config/cortexdrone/skills.json` (or use the tool).

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

Use `Harness.lessons()` / `LessonStore` for procedural knowledge. Call
`add(...)`, `search(...)`, `forget(...)` or `recordUse(...)`. Keep lessons short
(symptom -> cause -> rule) and scoped; executable workflows belong in
`SkillLibrary`.

## Add a web source

Extend `WebLookupService` and declare an `ASYNC` tool in `WebTools`. Never touch
Minecraft state from an `ASYNC` tool. Respect `webSearchEnabled` and cap output.

## Add Java reflection access

`ModConfig.allowJavaReflection` controls whether the Rhino `ClassShutter` denies
I/O/network/process classes. Default is `true` for trusted single-server use.
Reflection is available through `Packages`, `java.*` and `Java.type(name)`;
prefer a registered tool whenever one exists.

## Guidelines

- Keep Java files under 696 lines (validated).
- Keep package names equal to directories.
- Describe *when not to use* a tool and what its errors mean.
- Never add a hidden composite task; loops belong in `execute`.
- Return small results; the conversation and JS logs are bounded.
