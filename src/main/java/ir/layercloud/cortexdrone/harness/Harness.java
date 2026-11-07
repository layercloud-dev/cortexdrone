package ir.layercloud.cortexdrone.harness;

import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.config.ModConfig;
import ir.layercloud.cortexdrone.harness.agent.AgentService;
import ir.layercloud.cortexdrone.harness.agent.LlmClient;
import ir.layercloud.cortexdrone.harness.js.JsEngine;
import ir.layercloud.cortexdrone.harness.lesson.LessonStore;
import ir.layercloud.cortexdrone.harness.memory.MemoryStore;
import ir.layercloud.cortexdrone.harness.skill.SkillLibrary;
import ir.layercloud.cortexdrone.harness.skill.SkillRefiner;
import ir.layercloud.cortexdrone.harness.tool.ScanJobs;
import ir.layercloud.cortexdrone.harness.tool.ToolCatalog;
import ir.layercloud.cortexdrone.harness.tool.ToolInvoker;
import ir.layercloud.cortexdrone.harness.tool.ToolProvider;
import ir.layercloud.cortexdrone.harness.tool.ToolRegistry;
import ir.layercloud.cortexdrone.harness.tool.ToolSpec;
import ir.layercloud.cortexdrone.harness.tools.BlockTools;
import ir.layercloud.cortexdrone.harness.tools.CodeTools;
import ir.layercloud.cortexdrone.harness.tools.InteractionTools;
import ir.layercloud.cortexdrone.harness.tools.InventoryActionTools;
import ir.layercloud.cortexdrone.harness.tools.InventoryQueryTools;
import ir.layercloud.cortexdrone.harness.tools.LessonTools;
import ir.layercloud.cortexdrone.harness.tools.MemoryTools;
import ir.layercloud.cortexdrone.harness.tools.NavigationTools;
import ir.layercloud.cortexdrone.harness.tools.ScanTools;
import ir.layercloud.cortexdrone.harness.tools.SessionTools;
import ir.layercloud.cortexdrone.harness.tools.SkillTools;
import ir.layercloud.cortexdrone.harness.tools.VisualsTools;
import ir.layercloud.cortexdrone.harness.tools.WebTools;
import ir.layercloud.cortexdrone.harness.tools.WorldTools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Composition root of the harness.
 *
 * <p>Wires the registry, invoker, in-process JavaScript engine, LLM client,
 * skill refiner and task agent. Every consumer reads the same registry, so the
 * model tool list, the JavaScript `drone` surface and the prompt catalog cannot
 * drift apart.</p>
 */
public final class Harness {
    private final AIDroneManager manager;
    private final ModConfig config;
    private final SkillLibrary skills;
    private final MemoryStore memory;
    private final LessonStore lessons;
    private final ToolRegistry registry;
    private final ScanJobs scans;
    private final ToolInvoker invoker;
    private final JsEngine js;
    private final LlmClient llm;
    private final SkillRefiner refiner;
    private final AgentService agent;
    private final List<ToolProvider> providers = new ArrayList<>();

    private Harness(AIDroneManager manager, ModConfig config, SkillLibrary skills) {
        this.manager = manager;
        this.config = config;
        this.skills = skills;
        this.memory = new MemoryStore(manager.getConfigRoot());
        this.lessons = new LessonStore(manager.getConfigRoot());
        this.registry = new ToolRegistry();
        this.registry.declareAll(ToolCatalog.standard());
        this.scans = new ScanJobs(manager);
        this.invoker = new ToolInvoker(manager, registry, scans);
        this.js = new JsEngine(invoker, registry, config, skills);
        this.llm = new LlmClient(config, manager.getLogger());
        this.refiner = new SkillRefiner(llm, skills, manager.getLogger());
        this.agent = new AgentService(manager, manager.getLogger(), manager.getWorldScanner(),
                llm, invoker, registry, skills, memory, lessons, config);

        this.providers.addAll(Arrays.asList(
                new SessionTools(manager),
                new NavigationTools(manager),
                new BlockTools(manager),
                new InteractionTools(manager),
                new InventoryActionTools(manager),
                new InventoryQueryTools(manager.getInventoryService()),
                new MemoryTools(manager, memory),
                new LessonTools(manager, lessons),
                new VisualsTools(manager),
                new WorldTools(manager, manager.getWorldScanner()),
                new SkillTools(manager, skills, refiner),
                new WebTools(manager),
                new CodeTools(manager),
                new ScanTools(scans)
        ));
    }

    public static Harness create(AIDroneManager manager, ModConfig config, SkillLibrary skills) {
        Harness harness = new Harness(manager, config, skills);
        for (ToolProvider provider : harness.providers) provider.register(harness.registry);
        harness.registry.requireAllBound();
        return harness;
    }

    public AIDroneManager manager() {
        return this.manager;
    }

    public ModConfig config() {
        return this.config;
    }

    public SkillLibrary skills() {
        return this.skills;
    }

    public MemoryStore memory() {
        return this.memory;
    }

    public LessonStore lessons() {
        return this.lessons;
    }

    public ToolRegistry registry() {
        return this.registry;
    }

    public ToolInvoker invoker() {
        return this.invoker;
    }

    public JsEngine js() {
        return this.js;
    }

    public LlmClient llm() {
        return this.llm;
    }

    public SkillRefiner refiner() {
        return this.refiner;
    }

    public AgentService agent() {
        return this.agent;
    }

    public ir.layercloud.cortexdrone.harness.session.SessionManager sessions() {
        return this.manager.getSessionManager();
    }

    public void onServerTickEnd() {
        this.invoker.onServerTickEnd();
    }

    /** Compact tool catalog injected into the context message. */
    public String registryCatalog() {
        StringBuilder out = new StringBuilder();
        for (ToolSpec spec : this.registry.modelTools()) {
            Set<String> required = new HashSet<>();
            if (spec.getParameters().has("required")) {
                for (com.google.gson.JsonElement element : spec.getParameters().getAsJsonArray("required")) {
                    required.add(element.getAsString());
                }
            }
            out.append("- `").append(spec.getName()).append('(');
            List<String> order = spec.argumentOrder();
            for (int i = 0; i < order.size(); i++) {
                if (i > 0) out.append(", ");
                String name = order.get(i);
                out.append(name);
                if (required.contains(name)) out.append('*');
            }
            out.append(")` — ").append(firstSentence(spec.getDescription())).append('\n');
        }
        out.append("(* = required)\n");
        return out.toString();
    }

    private static String firstSentence(String text) {
        if (text == null) return "";
        int dot = text.indexOf(". ");
        String sentence = dot > 20 ? text.substring(0, dot + 1) : text;
        return sentence.length() > 240 ? sentence.substring(0, 237) + "..." : sentence;
    }
}
