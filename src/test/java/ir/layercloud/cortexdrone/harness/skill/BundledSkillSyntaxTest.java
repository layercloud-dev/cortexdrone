package ir.layercloud.cortexdrone.harness.skill;

import ir.layercloud.cortexdrone.SourceTree;
import ir.layercloud.cortexdrone.harness.js.JsSource;
import org.junit.Test;
import org.mozilla.javascript.CompilerEnvirons;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Parser;
import org.mozilla.javascript.RhinoException;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Every bundled skill must be valid JavaScript.
 *
 * <p>Skill edits are frequent and a syntax error only shows up when the model
 * happens to run that skill, so the build parses all of them. The parser is the
 * same Rhino build the runtime uses, and the source goes through
 * {@link JsSource#prepare} exactly as it does at run time.</p>
 */
public class BundledSkillSyntaxTest {
    private static final String SKILLS = SourceTree.RESOURCES + "skills";

    @Test
    public void everyBundledSkillParses() throws Exception {
        File dir = new File(SKILLS);
        File[] entries = dir.listFiles();
        if (entries == null) fail("bundled skills directory is missing: " + SKILLS);

        List<String> failures = new ArrayList<>();
        int parsed = 0;
        int dirs = 0;
        for (File entry : entries) {
            if (entry == null || !entry.isDirectory()) continue;
            dirs++;
            File code = new File(entry, "code.js");
            if (!code.isFile()) continue;
            String source = SourceTree.read(code.getPath());
            if (source.isEmpty()) {
                failures.add(entry.getName() + ": empty code.js");
                continue;
            }
            try {
                parse(entry.getName(), source);
                parsed++;
            } catch (RhinoException syntax) {
                failures.add(entry.getName() + ": " + syntax.getMessage());
            }
        }
        assertTrue("invalid bundled skill code (parsed=" + parsed + "):\n"
                        + String.join("\n", failures),
                failures.isEmpty());
        assertTrue("no bundled skill code was parsed under " + dir.getAbsolutePath()
                        + " (dirs=" + dirs + ", entries=" + entries.length + ")",
                parsed > 0);
    }

    private static void parse(String name, String source) {
        CompilerEnvirons environs = new CompilerEnvirons();
        environs.setLanguageVersion(Context.VERSION_1_8);
        Parser parser = new Parser(environs);
        // Skill code is a function body: the runtime evaluates it inside
        // "(function() { ... })();" so a top-level `return` is legal.
        parser.parse("(function() {\n" + JsSource.prepare(source) + "\n})();", name, 1);
    }
}
