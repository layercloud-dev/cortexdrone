package ir.layercloud.cortexdrone.harness.tool;

import ir.layercloud.cortexdrone.harness.tools.*;

import java.util.ArrayList;
import java.util.List;

/**
 * The declarative catalog of every tool the harness exposes.
 *
 * <p>This class contains no handlers and no Minecraft references: it only
 * merges the {@code specs()} declared by each domain provider. It is the single
 * place that answers "what tools exist", and it is covered by unit tests.</p>
 */
public final class ToolCatalog {
    private ToolCatalog() {
    }

    public static List<ToolSpec> standard() {
        List<ToolSpec> specs = new ArrayList<>();
        specs.addAll(SessionTools.specs());
        specs.addAll(NavigationTools.specs());
        specs.addAll(BlockTools.specs());
        specs.addAll(InteractionTools.specs());
        specs.addAll(CombatTools.specs());
        specs.addAll(InventoryActionTools.specs());
        specs.addAll(InventoryQueryTools.specs());
        specs.addAll(VisualsTools.specs());
        specs.addAll(QuestionTools.specs());
        specs.addAll(WorldTools.specs());
        specs.addAll(OreDictTools.specs());
        specs.addAll(MemoryTools.specs());
        specs.addAll(LessonTools.specs());
        specs.addAll(SkillTools.specs());
        specs.addAll(WebTools.specs());
        specs.addAll(CodeTools.specs());
        specs.addAll(ScanTools.specs());
        specs.addAll(OcTools.specs());
        return specs;
    }
}
