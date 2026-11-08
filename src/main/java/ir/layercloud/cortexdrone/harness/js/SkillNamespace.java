package ir.layercloud.cortexdrone.harness.js;

import ir.layercloud.cortexdrone.harness.skill.Skill;
import ir.layercloud.cortexdrone.harness.skill.SkillLibrary;
import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import java.util.HashSet;
import java.util.Set;

/**
 * The {@code skills} object inside a script, with every stored skill bound as a
 * callable function.
 *
 * <p>Scripts can run a workflow the same way they call a tool:</p>
 *
 * <pre>
 * var mined = skills.mineBlocks({ filter: 'iron_ore', count: 12 });
 * var ride  = skills.carryPlayer({ destination: { x: 10, y: 70, z: -4 } });
 * </pre>
 *
 * <p>Both {@code skills.mine_blocks} and {@code skills.mineBlocks} work, and the
 * lookup ignores case and separators so a slightly misspelled name still
 * resolves. The generic helpers ({@code search}, {@code load}, {@code call},
 * {@code run}, {@code fn}, {@code names}) always win; a stored skill with one of
 * those names is reachable through {@code skills.call('name', params)} or
 * {@code skills.fn('name')}.</p>
 */
final class SkillNamespace extends ScriptableObject {
    private static final Set<String> RESERVED = new HashSet<String>();

    static {
        RESERVED.add("search");
        RESERVED.add("load");
        RESERVED.add("call");
        RESERVED.add("run");
        RESERVED.add("fn");
        RESERVED.add("names");
        RESERVED.add("list");
        RESERVED.add("get");
        RESERVED.add("has");
    }

    private final java.util.Map<String, Object> folded = new java.util.HashMap<String, Object>();

    @Override
    public String getClassName() {
        return "SkillNamespace";
    }

    static boolean isReserved(String name) {
        return name != null && RESERVED.contains(fold(name));
    }

    /** Binds one skill under its normalized and camelCase names. */
    void bindSkill(String snakeName, String camelName, Object function) {
        if (isReserved(snakeName)) return;
        ScriptableObject.putProperty(this, snakeName, function);
        if (!camelName.equals(snakeName)) {
            ScriptableObject.putProperty(this, camelName, function);
        }
        this.folded.put(fold(snakeName), function);
        this.folded.put(fold(camelName), function);
    }

    @Override
    public Object get(String name, Scriptable start) {
        Object direct = super.get(name, start);
        if (direct != Scriptable.NOT_FOUND) return direct;
        if (isReserved(name)) return Scriptable.NOT_FOUND;
        Object foldedMatch = this.folded.get(fold(name));
        return foldedMatch != null ? foldedMatch : Scriptable.NOT_FOUND;
    }

    @Override
    public boolean has(String name, Scriptable start) {
        return super.has(name, start) || this.folded.containsKey(fold(name));
    }

    private static String fold(String name) {
        if (name == null) return "";
        StringBuilder out = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '_' || c == '-' || Character.isWhitespace(c)) continue;
            out.append(Character.toLowerCase(c));
        }
        return out.toString();
    }

    /**
     * One skill bound as a JS function. It delegates to {@link SkillRuntime}, so
     * the called body sees the same tools, helpers and nested skills as the
     * caller while keeping its own {@code params}.
     */
    static final class SkillFunction extends BaseFunction {
        private final transient SkillLibrary library;
        private final transient String skillName;

        SkillFunction(SkillLibrary library, String skillName) {
            this.library = library;
            this.skillName = skillName;
        }

        @Override
        public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
            return SkillRuntime.call(cx, scope, this.library, this.skillName,
                    args.length > 0 ? args[0] : null);
        }
    }

    /** A function that runs one named skill; used by {@code skills.fn(name)}. */
    static Function lookup(SkillLibrary library, String name) {
        if (library == null) {
            throw Context.reportRuntimeError("Skill library is unavailable in this runtime.");
        }
        Skill skill = library.get(name);
        if (skill == null || skill.getCode() == null || skill.getCode().trim().isEmpty()) {
            throw Context.reportRuntimeError("Unknown or empty skill '" + name
                    + "'. List skills with skills.names() or the `skill` tool.");
        }
        return new SkillFunction(library, skill.getName());
    }
}
