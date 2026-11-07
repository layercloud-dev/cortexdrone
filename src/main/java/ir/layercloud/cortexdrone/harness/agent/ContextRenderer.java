package ir.layercloud.cortexdrone.harness.agent;

import ir.layercloud.cortexdrone.DroneController;

import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.harness.lesson.Lesson;
import ir.layercloud.cortexdrone.harness.memory.MemoryEntry;
import ir.layercloud.cortexdrone.harness.session.DroneSession;
import ir.layercloud.cortexdrone.harness.skill.Skill;
import ir.layercloud.cortexdrone.manager.navigation.NavigationState;
import ir.layercloud.cortexdrone.service.inventory.InventoryService;
import ir.layercloud.cortexdrone.service.world.WorldScanner;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

import java.util.List;
import java.util.Map;

/**
 * Renders the live world snapshot as readable Markdown for the context message.
 *
 * <p>This replaces the old "dump the whole debug JSON into the system prompt"
 * approach. The model gets a short, labelled state block plus a compact tool
 * catalog and skill suggestions; it can query for anything fresher.</p>
 */
public final class ContextRenderer {
    private ContextRenderer() {
    }

    public static String render(AIDroneManager manager, WorldScanner worldScanner,
                                EntityPlayerMP player, DroneController controller,
                                String taskPrompt, String taskIntent, List<Skill> suggestedSkills,
                                List<MemoryEntry> memories, List<Lesson> lessons) {
        StringBuilder out = new StringBuilder(4096);
        World world = controller.drone.worldObj;
        double refX = player != null ? player.posX : controller.drone.posX;
        double refY = player != null ? player.posY : controller.drone.posY;
        double refZ = player != null ? player.posZ : controller.drone.posZ;

        out.append("# Live context\n");
        out.append("Snapshot taken when the task started; call `state` / `get_player` for fresh values.\n\n");

        out.append("## Player\n");
        out.append("- name: ").append(player != null ? player.getCommandSenderName() : controller.ownerName).append('\n');
        out.append("- position: ").append(block(refX)).append(", ").append(block(refY)).append(", ").append(block(refZ)).append('\n');
        if (player != null) {
            int facing = MathHelper.floor_double((double) (player.rotationYaw * 4.0F / 360.0F) + 0.5D) & 3;
            String[] directions = new String[]{"South (+Z)", "West (-X)", "North (-Z)", "East (+X)"};
            out.append("- facing: ").append(directions[facing])
                    .append(" (yaw ").append(round(player.rotationYaw)).append(", pitch ")
                    .append(round(player.rotationPitch)).append(")\n");
            ItemStack held = player.getCurrentEquippedItem();
            out.append("- held: ").append(held != null && held.stackSize > 0
                    ? held.getDisplayName() + " x" + held.stackSize : "empty").append('\n');
            try {
                net.minecraft.util.MovingObjectPosition ignored = null;
                com.google.gson.JsonObject look = WorldScanner.raycastPlayerLook(player.worldObj, player, 40.0D);
                if (look != null && look.has("x") && look.has("y") && look.has("z")) {
                    out.append("- crosshair block: ").append(look.get("x").getAsInt()).append(", ")
                            .append(look.get("y").getAsInt()).append(", ")
                            .append(look.get("z").getAsInt()).append('\n');
                }
            } catch (Throwable ignored) {
            }
        }
        out.append('\n');

        out.append("## Drone\n");
        out.append("- name: ").append(controller.name).append(" (#").append(controller.id).append(")\n");
        out.append("- position: ").append(round(controller.drone.posX)).append(", ")
                .append(round(controller.drone.posY)).append(", ")
                .append(round(controller.drone.posZ)).append('\n');
        out.append("- dimension: ").append(controller.drone.dimension).append('\n');
        out.append("- energy: ").append(controller.drone.globalBuffer())
                .append('/').append(controller.drone.globalBufferSize()).append('\n');
        out.append("- inventory (8 slots): ").append(InventoryService.formatInventorySummary(controller.drone.mainInventory())).append('\n');
        boolean navigation = manager.getNavigationManager().isActive(controller);
        NavigationState navigationState = manager.getNavigationManager().state(controller);
        out.append("- navigation: ").append(navigation ? "active" : "idle").append('\n');
        if (navigationState != null) {
            out.append("- stall ticks: ").append(navigationState.stuckTicks);
            if (navigationState.lastStuckReason != null && !navigationState.lastStuckReason.isEmpty()) {
                out.append(" (").append(navigationState.lastStuckReason).append(')');
            }
            out.append('\n');
        }
        out.append("- retained actions: ").append(manager.getActionRegistry().activeContextCount(controller)).append('\n');
        out.append("- breadcrumbs: ").append(manager.getBreadcrumbService().count(controller)).append('\n');
        out.append('\n');

        out.append("## Task\n");
        out.append("- request: ").append(oneLine(taskPrompt, 500)).append('\n');
        out.append("- intent: ").append(taskIntent != null ? taskIntent : "NEW_TASK").append('\n');
        DroneSession session = manager.getSessionManager().session(controller);
        if (session != null && !session.getNotes().isEmpty()) {
            out.append("- notes:\n");
            for (String note : session.getNotes()) out.append("  - ").append(oneLine(note, 240)).append('\n');
        }
        out.append('\n');

        out.append("## Memory\n");
        if (memories == null || memories.isEmpty()) {
            out.append("- none stored yet; use `memory` action=remember for facts and named locations.\n");
        } else {
            out.append(manager.getHarness().memory().formatForPrompt(memories));
            out.append("Use `memory` action=search/remember to refine these entries.\n");
        }
        out.append('\n');

        out.append("## Lessons (learned)\n");
        if (lessons == null || lessons.isEmpty()) {
            out.append("- none yet; after a failure or a non-obvious success record one with `lesson` action=add.\n");
        } else {
            out.append(manager.getHarness().lessons().formatForPrompt(lessons));
            out.append("Apply these rules unless the live data contradicts them; mark one with `lesson` action=use when it changes a decision.\n");
        }
        out.append('\n');

        out.append("## Nearby blocks (24m)\n");
        try {
            Map<String, int[]> resources = worldScanner.scanNearbyResources(world, refX, refY, refZ, 24);
            if (resources.isEmpty()) {
                out.append("- none recorded\n");
            } else {
                for (Map.Entry<String, int[]> entry : resources.entrySet()) {
                    int[] value = entry.getValue();
                    out.append("- ").append(entry.getKey()).append(" x").append(value[0])
                            .append(" (nearest ").append(value[1]).append(", ")
                            .append(value[2]).append(", ").append(value[3]).append(")\n");
                }
            }
        } catch (Throwable ignored) {
            out.append("- scan unavailable\n");
        }
        out.append('\n');

        if (suggestedSkills != null && !suggestedSkills.isEmpty()) {
            out.append("## Suggested skills\n");
            out.append("Load one with `skill({action:\"load\", name:\"...\"})` before writing the workflow.\n");
            for (Skill skill : suggestedSkills) {
                out.append("- `").append(skill.getName()).append("` — ").append(oneLine(skill.getDescription(), 220)).append('\n');
            }
            out.append('\n');
        }

        out.append("## Tool registry\n");
        out.append("Every name below is callable as `drone.camelCaseName(args)` inside `execute`.\n");
        out.append(manager.getHarness().registryCatalog());
        out.append('\n');
        return out.toString();
    }

    private static String block(double value) {
        return String.valueOf(MathHelper.floor_double(value));
    }

    private static String round(double value) {
        return String.valueOf(Math.round(value * 10.0D) / 10.0D);
    }

    private static String oneLine(String text, int max) {
        if (text == null) return "";
        String clean = text.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
        return clean.length() <= max ? clean : clean.substring(0, Math.max(0, max - 3)) + "...";
    }
}
