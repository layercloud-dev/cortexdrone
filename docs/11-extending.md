# 11 — Extending CortexDrone

## 1. Choose the right layer

| You are adding... | Layer | Location |
| --- | --- | --- |
| one physical operation | action + context | `action/<cat>/impl`, `context/impl` |
| a category/service manager | manager | `manager/<cat>/impl` |
| a read-only query/planner | service | `service/<domain>` |
| a new JSON tool | transport + SDK | `harness/rpc/translate`, `resources/sdk` |
| a reusable workflow | skill | `resources/skills` |
| a chat/command entry | command/harness | `command/impl`, `harness` |

Do not put operation logic in a manager, JSON in an action, or a goal loop in a
service.

## 2. Adding an action category

1. Add DTOs under `action/<category>/` (`XRequest`, `XResult`), Lombok
   `@Value @Builder`.
2. Add `context/impl/XContext.java` extending `ActionContextBase`; keep only
   execution state and result.
3. Add action(s) under `action/<category>/impl/`, implementing
   `Action<XContext>`.
4. Add `manager/<category>/impl/XManager.java` extending
   `ActionManager<XContext, XRequest, XResult>`.
5. Implement `createContext`, `createAction`, `result`.
6. Register the manager in `AIDroneManager` and `ActionRegistry`.
7. Add `harness/rpc/translate/XRpcTranslator.java`.
8. Route operation names in `ActionRpcTranslator`.
9. If it is a generic master primitive, add it to `MasterToolCatalog`, the
   `MasterRpcHandler`, the SDK, the system prompt, and the tool-surface test.
10. Add tests for lifecycle/routing if the behavior is non-trivial.
11. Update docs.

### Manager template

```java
public final class XManager extends ActionManager<XContext, XRequest, XResult> {
    private final AIDroneManager manager;

    public XManager(AIDroneManager manager) {
        super("x", "x_operation");
        this.manager = manager;
    }

    @Override
    protected XContext createContext(DroneController c, EntityPlayerMP p, String op) {
        return new XContext(c, p, op);
    }

    @Override
    protected Action<XContext> createAction(XContext ctx, String op, XRequest req) {
        if ("x_operation".equals(op)) return new XAction(req);
        throw new IllegalArgumentException("Unsupported x action: " + op);
    }

    @Override
    protected XResult result(XContext ctx) {
        return ctx.result();
    }
}
```

### Action template

```java
@RequiredArgsConstructor
public final class XAction implements Action<XContext> {
    private final XRequest request;

    @Override
    public void execute(XContext context) {
        // one operation
        context.result(XResult.success());
        // context.retain() only if it must tick
    }
}
```

## 3. Adding a service

1. Put it under `service/<domain>/` or `service/<domain>/impl/`.
2. Keep it read-only/planning unless it is a low-level storage primitive invoked
   by an action/manager.
3. Inject it into `AIDroneManager` or the manager that needs it.
4. Do not let it own goals, work state, path state, or start actions.
5. Add caches only with a short TTL and document invalidation.
6. Prefer returning typed DTOs/JSON only at the RPC boundary; services may return
   domain objects.

## 4. Adding a master tool

A master tool is justified only if it is a **generic primitive**, not a
composite task.

1. Add the operation name to `MasterToolCatalog.METHODS`.
2. Update `MasterToolSurfaceTest` expected list.
3. Route it in `MasterRpcHandler.handle`.
4. Add/extend a translator; never parse JSON in the manager.
5. Add the SDK method in `resources/sdk/drone-actions.mjs`.
6. Add the tool and semantics to `resources/system-prompt.md`.
7. Update `docs/07-tools-rpc.md`.
8. Consider `AgentLoopHeuristics.codeMentionsPhysicalAction` and
   `hasConcreteProgress` so the loop recognizes the new action.
9. Add validation: no removed composite tool names.

Do **not** add:
- `mine`, `hunt`, `farm`, `chop`, `buildStructure`, `sortChests`;
- queued/batched tools (`queue_mine`, `waitUntilIdle`, task lists);
- aliases for existing tools.

## 5. Adding a navigation strategy

Navigation is intentionally centralized. To change it:

1. Add a read-only planner/service if new data is needed.
2. Add an action executor under `action/navigation/impl` if a new physical
   movement type is needed.
3. Add a `NavigationMode` value.
4. Wire it in `NavigationManager.actionFor` and `decide`/`escalate`.
5. Keep `goto`/`stop` as the only public navigation tools.
6. Do not add a trail action: breadcrumbs are a `BreadcrumbService` query.

## 6. Adding a skill

1. Create `src/main/resources/skills/<name>.json`.
2. Add it to `skills/index.json`.
3. Use only master tools (`drone.*`) and utility globals.
4. Keep code compact and await every physical call.
5. Avoid returning huge payloads; return compact summaries.
6. Validate JSON and run `./gradlew validate`.

Skill format:

```json
{
  "name": "lowercase_name",
  "description": "what it does",
  "tags": "space separated search tags",
  "code": "const x = await drone.inspect(...); ... return ...;"
}
```

## 7. Adding a chat/command behavior

- Command syntax/help belongs in `DroneCommand`.
- Intent classification belongs in `TaskIntentClassifier`.
- One-session behavior belongs in `TaskCoordinator`.
- Agent loop/LLM behavior belongs in `LlmAgentService` and its helpers.
- Do not add a second command name for an existing behavior.

## 8. Threading and safety rules

- World/entity/inventory mutations must run on the server thread.
- Use `AIDroneManager.callOnMainThread` or `CortexDroneMod.queueMain`.
- Do not block the server thread on HTTP/Node/LLM.
- Large scans must go through `RpcDispatcher` time slicing.
- Avoid unbounded queues/caches; cap and expire.
- Do not load/generate chunks during scans/pathfinding; use `ChunkAccess`.

## 9. Logging and diagnostics

- Use `DroneLogger.log(category, ctrl, message, details)`.
- Choose a category; throttle repetitive lines.
- Never log API keys; use `LoopTracer.maskKey`.
- Add `NAV`/`PATH`/`STUCK`/`ESCAPE` lines for navigation changes.
- Add `MODE` lines when changing modes.
- Keep details useful for `/debug`.

## 10. Validation checklist

Before committing:

```bash
./gradlew clean build     # compile + tests + validate + reobf + deploy
node --check src/main/resources/sdk/*.mjs
python3 -m json.tool src/main/resources/skills/index.json
```

Then:

- no `.java` >696 lines;
- package = directory;
- no JSON imports in action/context/manager/service;
- no action calls another action;
- no manager contains flight/waypoint code;
- master tool list synchronized in catalog, handler, SDK, prompt, test, docs;
- removed tools are not reintroduced.
