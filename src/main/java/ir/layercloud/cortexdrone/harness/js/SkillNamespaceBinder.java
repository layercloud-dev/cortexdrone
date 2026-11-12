package ir.layercloud.cortexdrone.harness.js;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import ir.layercloud.cortexdrone.harness.skill.Skill;
import ir.layercloud.cortexdrone.harness.skill.SkillLibrary;
import ir.layercloud.cortexdrone.harness.tool.ToolContext;
import ir.layercloud.cortexdrone.harness.tool.ToolInvoker;
import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

/**
 * Binds the {@code skills} object into a script scope.
 *
 * <p>Extracted from {@link JsEngine}: the engine owns the Rhino context and
 * budgets, while this binder owns the namespace shape. Each stored skill becomes
 * a callable function, plus six generic helpers ({@code search}, {@code load},
 * {@code call}/{@code run}, {@code fn}, {@code names}).</p>
 */
@SuppressWarnings("unused")
final class SkillNamespaceBinder {
    private final ToolInvoker invoker;
    private final SkillLibrary defaultLibrary;

    SkillNamespaceBinder(ToolInvoker invoker, SkillLibrary defaultLibrary) {
        this.invoker = invoker;
        this.defaultLibrary = defaultLibrary;
    }

    /**
     * Installs {@code skills} into {@code scope}.
     */
    void bind(Context cx, Scriptable scope, final ToolContext context) {
        final SkillLibrary library = context != null && context.skills() != null
                ? context.skills() : this.defaultLibrary;
        SkillNamespace skills = new SkillNamespace();
        skills.setParentScope(scope);
        skills.setPrototype(ScriptableObject.getObjectPrototype(scope));
        if (library != null) {
            for (Skill skill : library.all()) {
                if (skill == null || skill.getName() == null) continue;
                skills.bindSkill(skill.getName(), JsEngine.camelCase(skill.getName()),
                        new SkillNamespace.SkillFunction(library, skill.getName()));
            }
        }
        ScriptableObject.putProperty(skills, "search", skillToolCall("search", context));
        ScriptableObject.putProperty(skills, "load", skillToolCall("load", context));
        ScriptableObject.putProperty(skills, "call", new BaseFunction() {
            @Override
            public Object call(Context inner, Scriptable innerScope, Scriptable thisObj, Object[] args) {
                String name = args.length > 0 ? Context.toString(args[0]) : "";
                return SkillRuntime.call(inner, innerScope, library, name,
                        args.length > 1 ? args[1] : null);
            }
        });
        ScriptableObject.putProperty(skills, "run", ScriptableObject.getProperty(skills, "call"));
        // skills.fn('carry_player') returns the callable without running it, so
        // a script can pass a workflow around as a value.
        ScriptableObject.putProperty(skills, "fn", new BaseFunction() {
            @Override
            public Object call(Context inner, Scriptable innerScope, Scriptable thisObj, Object[] args) {
                String name = args.length > 0 ? Context.toString(args[0]) : "";
                return SkillNamespace.lookup(library, name);
            }
        });
        ScriptableObject.putProperty(skills, "names", new BaseFunction() {
            @Override
            public Object call(Context inner, Scriptable innerScope, Scriptable thisObj, Object[] args) {
                JsonArray names = new JsonArray();
                if (library != null) {
                    for (Skill skill : library.all()) {
                        if (skill != null && skill.getName() != null) {
                            names.add(new JsonPrimitive(skill.getName()));
                        }
                    }
                }
                return JsConvert.toJs(names, inner, innerScope);
            }
        });
        ScriptableObject.putProperty(scope, "skills", skills);
    }

    /**
     * The {@code skill} tool with a fixed action, exposed as a JS function.
     */
    private BaseFunction skillToolCall(final String action, final ToolContext context) {
        return new BaseFunction() {
            @Override
            public Object call(Context inner, Scriptable innerScope, Scriptable thisObj, Object[] args) {
                JsonObject arguments = new JsonObject();
                arguments.addProperty("action", action);
                if ("search".equals(action)) {
                    arguments.addProperty("query", args.length > 0 ? Context.toString(args[0]) : "");
                    if (args.length > 1 && args[1] instanceof Number) {
                        arguments.addProperty("limit", ((Number) args[1]).intValue());
                    }
                } else {
                    arguments.addProperty("name", args.length > 0 ? Context.toString(args[0]) : "");
                }
                JsonObject result = DroneToolFunction.invokeChecked(inner, innerScope,
                        invoker, context, "skill", arguments);
                return JsConvert.toJs(result, inner, innerScope);
            }
        };
    }
}
