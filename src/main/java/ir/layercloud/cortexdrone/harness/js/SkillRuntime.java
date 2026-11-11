package ir.layercloud.cortexdrone.harness.js;

import ir.layercloud.cortexdrone.harness.skill.Skill;
import ir.layercloud.cortexdrone.harness.skill.SkillLibrary;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;

/**
 * Executes one skill body inside an existing Rhino scope.
 *
 * <p>A skill is a function body that receives {@code params} and returns a
 * value. Skills call each other with {@code skills.call(name, params)}, so a
 * composite workflow (mine then deliver, harvest then store) is assembled from
 * small skills instead of duplicating their code.</p>
 *
 * <p>Each call compiles the skill as a function in the global scope: it can use
 * every tool and helper, but it cannot see the caller's local variables. A
 * thread-local depth guard stops runaway recursion between skills.</p>
 */
public final class SkillRuntime {
    private static final int MAX_DEPTH = 8;
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private SkillRuntime() {
    }

    public static Object call(Context cx, Scriptable scope, SkillLibrary library,
                              String name, Object params) {
        if (library == null) {
            throw Context.reportRuntimeError("Skill library is unavailable in this runtime.");
        }
        Skill skill = library.get(name);
        if (skill == null || skill.getCode() == null || skill.getCode().trim().isEmpty()) {
            throw Context.reportRuntimeError("Unknown or empty skill '" + name
                    + "'. Search with skills.search(query) or the `skill` tool.");
        }
        int depth = DEPTH.get();
        if (depth >= MAX_DEPTH) {
            throw Context.reportRuntimeError("Skill recursion limit reached at '" + name
                    + "' (depth " + depth + ", max " + MAX_DEPTH + ").");
        }
        DEPTH.set(depth + 1);
        try {
            String wrapper = "(function(__params) {\n"
                    + "var params = (__params === null || __params === undefined) ? {} : __params;\n"
                    + JsSource.prepare(skill.getCode())
                    + "\n})";
            Object compiled = cx.evaluateString(scope, wrapper, "skill:" + skill.getName(), 1, null);
            if (!(compiled instanceof Function)) {
                throw Context.reportRuntimeError("Skill '" + name + "' did not compile to a function.");
            }
            Object result = ((Function) compiled).call(cx, scope, scope, new Object[]{params});
            library.recordUse(skill.getName());
            return result;
        } finally {
            DEPTH.set(depth);
        }
    }
}
