package ir.layercloud.cortexdrone.codemode;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.experimental.UtilityClass;

/**
 * Deterministic helpers for agent-loop re-query / loiter detection and message
 * pruning. Split verbatim from {@link LlmAgentService} so the core stays under
 * the modular line limit; all methods are package-private static.
 */
@UtilityClass
final class AgentLoopHeuristics {
    // Wall-clock deadline for one agent loop. The 100-turn cap alone does not stop
    // log-spam loops (meteor hunt burned 85 turns over ~45 min of kb.lookup/scan
    // re-queries with zero progress). At the deadline we exit deterministically with a
    // partial reply (done:true) instead of chasing another re-query.
    static final long AGENT_DEADLINE_MS = 8L * 60L * 1000L; // 8 minutes


    static String truncateForPrompt(String s, int maxLen) {
        if (s == null || s.length() <= maxLen) return s != null ? s : "";
        return s.substring(0, maxLen) + "...(truncated " + (s.length() - maxLen) + " chars)";
    }

    static JsonObject buildCleanAssistantMessage(String thought, String code) {
        JsonObject cleanPlan = new JsonObject();
        cleanPlan.addProperty("thought", thought != null ? thought : "");
        cleanPlan.addProperty("code", truncateForPrompt(code != null ? code : "", 1200));
        cleanPlan.addProperty("done", false);
        cleanPlan.addProperty("reply", "");
        JsonObject mAsst = new JsonObject();
        mAsst.addProperty("role", "assistant");
        mAsst.addProperty("content", cleanPlan.toString());
        return mAsst;
    }

    static void appendTurnFeedback(JsonArray messages, String thought, String code, String userFeedback) {
        messages.add(buildCleanAssistantMessage(thought, code));
        JsonObject mUser = new JsonObject();
        mUser.addProperty("role", "user");
        mUser.addProperty("content", userFeedback);
        messages.add(mUser);
    }

    static JsonArray pruneMessagesWindow(JsonArray messages) {
        if (messages == null || messages.size() <= 12) return messages;
        JsonArray pruned = new JsonArray();
        // Bounded prompt window: keep system + original user prompt + last 6 turns
        // so turn 7+ never drops the initial user instruction (BlackSnow re-queried
        // getRecipes 6x because the original prompt fell out of the window).
        pruned.add(messages.get(0));
        pruned.add(messages.get(1));
        for (int i = messages.size() - 6; i < messages.size(); i++) pruned.add(messages.get(i));
        return pruned;
    }

    static String lastUserContent(JsonArray messages) {
        if (messages == null || messages.size() == 0) return "";
        try {
            JsonObject last = messages.get(messages.size() - 1).getAsJsonObject();
            if (last == null) return "";
            return last.has("content") && !last.get("content").isJsonNull() ? last.get("content").getAsString() : "";
        } catch (Exception ignored) { return ""; }
    }

    static String fingerprintCode(String code) {
        String compact = (code == null ? "" : code).replaceAll("\\s+", " ").trim();
        return compact.length() > 200 ? compact.substring(0, 200) : compact;
    }

    static boolean shouldStopBeforeExecute(JsonObject plan, String code, boolean isMalformed) {
        if (isMalformed || plan == null) return false;
        if (plan.has("stop") && !plan.get("stop").isJsonNull() && plan.get("stop").getAsBoolean()) return true;
        if (plan.has("stopPrevious") && !plan.get("stopPrevious").isJsonNull() && plan.get("stopPrevious").getAsBoolean()) return true;
        return codeCallsStop(code);
    }

    static void executePreCodeModeStop(
        ir.layercloud.cortexdrone.model.DroneController ctrl,
        net.minecraft.entity.player.EntityPlayerMP player,
        int myLoopId,
        int turn,
        ir.layercloud.cortexdrone.config.DroneLogger logger,
        RpcDispatcher rpcDispatcher
    ) {
        int cancelledLoops = ctrl.cancelOtherLoops(myLoopId);
        JsonObject stopArgs = new JsonObject();
        stopArgs.addProperty("loopId", myLoopId);
        rpcDispatcher.executeOnMainThread(ctrl, player, "clear_queues", stopArgs);
        logger.log("CODEMODE", ctrl, "Harness executed pre-CodeMode stop before Turn " + turn,
            "loopId=" + myLoopId + " cancelledLoops=" + cancelledLoops);
        if (cancelledLoops > 0) {
            long waitUntil = System.currentTimeMillis() + 350L;
            while (ctrl.activeScriptCount.get() > 0 && System.currentTimeMillis() < waitUntil) {
                try { Thread.sleep(50L); } catch (InterruptedException ignored) { break; }
            }
        }
    }

    static boolean codeCallsStop(String code) {
        if (code == null || code.isEmpty()) return false;
        String c = code.toLowerCase();
        return c.contains("drone.stop(") || c.contains("drone.clearqueues(")
            || c.contains("rpc('stop'") || c.contains("rpc(\"stop\"")
            || c.contains("rpc('clear_queues'") || c.contains("rpc(\"clear_queues\"");
    }

    static boolean codeMentionsPhysicalAction(String code) {
        if (code == null || code.isEmpty()) return false;
        String c = code.toLowerCase();
        return c.contains(".mine(") || c.contains(".minemany(") || c.contains(".minerect(")
            || c.contains(".minebox(") || c.contains(".gatherdistant(") || c.contains(".breakblock(")
            || c.contains(".craft(") || c.contains(".machinecraft(") || c.contains(".driveanvil(")
            || c.contains(".cook(") || c.contains(".smelt(") || c.contains(".withdrawchest(")
            || c.contains(".depositchest(") || c.contains(".givetoplayer(") || c.contains(".placeblock(")
            || c.contains(".placemany(") || c.contains(".placebox(") || c.contains(".replaceblock(")
            || c.contains(".replaceblocks(") || c.contains(".replacebox(") || c.contains(".replaceroom(")
            || c.contains(".buildstructure(") || c.contains(".hunt(") || c.contains(".attackentity(")
            || c.contains(".farm(") || c.contains(".chop(") || c.contains(".fertilize(")
            || c.contains(".scoopliquid(") || c.contains(".placeliquid(") || c.contains(".plugliquids(")
            || c.contains(".flyto(") || c.contains(".carryplayer(") || c.contains(".takefromplayer(")
            || c.contains(".suckitems(") || c.contains(".interactblock(") || c.contains(".useitem(")
            || c.contains(".sortchests(") || c.contains(".transfercontainers(");
    }

    static boolean codeMentionsProgressAction(String code) {
        if (code == null || code.isEmpty()) return false;
        String c = code.toLowerCase();
        return c.contains(".mine(") || c.contains(".minemany(") || c.contains(".minerect(")
            || c.contains(".minebox(") || c.contains(".gatherdistant(") || c.contains(".breakblock(")
            || c.contains(".craft(") || c.contains(".machinecraft(") || c.contains(".driveanvil(")
            || c.contains(".cook(") || c.contains(".smelt(") || c.contains(".withdrawchest(")
            || c.contains(".depositchest(") || c.contains(".givetoplayer(")
            || c.contains(".placeblock(") || c.contains(".placemany(") || c.contains(".placebox(")
            || c.contains(".replaceblock(") || c.contains(".replaceblocks(") || c.contains(".replacebox(")
            || c.contains(".replaceroom(") || c.contains(".buildstructure(")
            || c.contains(".hunt(") || c.contains(".attackentity(") || c.contains(".farm(")
            || c.contains(".chop(") || c.contains(".fertilize(")
            || c.contains(".scoopliquid(") || c.contains(".placeliquid(") || c.contains(".plugliquids(")
            || c.contains(".takefromplayer(") || c.contains(".suckitems(")
            || c.contains(".interactblock(") || c.contains(".useitem(") || c.contains(".sortchests(")
            || c.contains(".transfercontainers(");
    }

    static boolean hasConcreteProgress(JsonObject execRes, String code) {
        if (execRes == null || !execRes.has("result") || !execRes.get("result").isJsonObject()) return false;
        JsonObject res = execRes.getAsJsonObject("result");
        String[] counters = new String[]{"queued", "minedCount", "builtCount", "huntedCount", "smeltedCount", "scoopedCount", "crafted", "flyingToPlayer", "flyingToChest"};
        for (String k : counters) {
            if (res.has(k) && !res.get(k).isJsonNull()) {
                JsonElement v = res.get(k);
                if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber() && v.getAsInt() > 0) return true;
                if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean() && v.getAsBoolean()) return true;
            }
        }
        if (res.has("transferred") && res.get("transferred").isJsonPrimitive()
            && res.get("transferred").getAsInt() > 0) return true;
        if (res.has("withdrawn") && res.get("withdrawn").isJsonPrimitive()
            && res.get("withdrawn").getAsInt() > 0) return true;
        if (res.has("deposited") && res.get("deposited").isJsonPrimitive()
            && res.get("deposited").getAsInt() > 0) return true;
        // Movement alone (flyTo/moveTo/goto) is exploration, not progress.
        // The meteor hunt burned dozens of turns on flyTo+empty scan with
        // counted-as-progress movement, so flyTo no longer resets streaks.
        return codeMentionsProgressAction(code);
    }

    static boolean isRepeatedToolResult(String lastUser, String feedback) {
        if (lastUser == null || feedback == null) return false;
        int n = Math.min(feedback.length(), lastUser.length());
        if (n < 200) return false;
        int same = 0;
        for (int i = 0; i < n; i++) if (feedback.charAt(i) == lastUser.charAt(i)) same++;
        return (double) same / (double) n > 0.85;
    }

    static String nudgeForRepeat(String originalPrompt) {
        return "REPETITION WARNING: you just re-queried the same recipe/chest data with no progress. Do NOT call kb.lookup/getRecipes/findItemInChests for the same target again. Act on what you already know: if 'found:true' in chests, withdraw/craft with those items; if an ingredient is missing, report the machine-config blocker as your final reply (done=true). Original task: " + truncateForPrompt(originalPrompt, 300);
    }

    static String fingerprintAndAction(String code) {
        return fingerprintCode(code) + "|" + codeMentionsPhysicalAction(code);
    }

    static boolean isMachineConfigOnlyResult(JsonObject result) {
        if (result == null || !result.has("result") || !result.get("result").isJsonObject()) return false;
        JsonObject r = result.getAsJsonObject("result");
        String s = r.toString().toLowerCase();
        boolean hasMachine = s.contains("machine_config") || s.contains("machine config")
            || s.contains("assembly machine") || s.contains("chemical plant")
            || s.contains("blast furnace") || s.contains("assemblymachine");
        boolean noCrafting = !r.has("craftingRecipes") && !s.contains("craftingrecipes");
        // Machine-only signal: machine config found but no crafting-table source
        // in this tool result. Exact recipe shapes vary; this is heuristic.
        return hasMachine && (noCrafting || r.has("blocker") || s.contains("gui_only") || s.contains("gui only"));
    }

    static boolean shouldForceFinal(JsonObject plan, JsonArray messages) {
        if (plan == null) return false;
        if (plan.has("malformed") && plan.get("malformed").getAsBoolean()) return false;
        JsonElement doneEl = plan.has("done") ? plan.get("done") : null;
        boolean isDone = doneEl != null && doneEl.isJsonPrimitive() && doneEl.getAsBoolean();
        if (isDone) return false;
        boolean hasCode = plan.has("code") && !plan.get("code").isJsonNull()
            && !plan.get("code").getAsString().trim().isEmpty();
        if (hasCode) return false;
        String thought = plan.has("thought") && !plan.get("thought").isJsonNull()
            ? plan.get("thought").getAsString() : "";
        String reply = plan.has("reply") && !plan.get("reply").isJsonNull()
            ? plan.get("reply").getAsString() : "";
        String s = (thought + " " + reply).toLowerCase();
        boolean machineOnly = s.contains("machine_config") || s.contains("machine config")
            || s.contains("gui_only") || s.contains("gui only");
        boolean emptySearch = s.contains("\"found\":false") || s.contains("\"count\":0")
            || s.contains("\"matchcount\":0");
        if (machineOnly || emptySearch) return true;
        String lastUser = lastUserContent(messages);
        if (lastUser != null && lastUser.contains("STRICT FORMAT")) return false;
        return thought.length() > 200;
    }

    /**
     * Deterministic helpers for re-query / loiter detection. The meteor hunt loop
     * re-issued kb.lookup('meteor') ~22x and findDistant('meteor',*) ~67x with zero
     * progress because neither the identical-code check nor the repetition heuristic
     * fired on single-line differences. Extract the literal query argument so a
     * near-identical re-query (same tool, same target) is caught early.
     */
    static String queryFingerprint(String code) {
        if (code == null || code.isEmpty()) return "";
        String c = code.replaceAll("\\s+", " ");
        String[] tools = new String[]{"kb.lookup", "world.getrecipes",
            "world.finditeminchests", "world.finddistant",
            "world.scanblocks", "world.finditem"};
        for (String t : tools) {
            int idx = c.toLowerCase().indexOf(t);
            if (idx < 0) continue;
            int open = c.indexOf('(', idx + t.length());
            if (open < 0) continue;
            int close = c.indexOf(')', open);
            if (close < 0) continue;
            String args = c.substring(open + 1, close).toLowerCase();
            // Strip quotes/punctuation so 'meteor', "meteor", `meteor` fingerprint the same.
            String arg = args.replaceAll("[\"'`,;()|]", " ").replaceAll("\\s+", " ").trim();
            if (!arg.isEmpty()) return t + ":" + arg;
        }
        return "";
    }

    static boolean isMachineBlockerResult(JsonObject result) {
        if (result == null || !result.has("result") || !result.get("result").isJsonObject()) return false;
        JsonObject r = result.getAsJsonObject("result");
        if (r.has("blocker") && !r.get("blocker").isJsonNull()) return true;
        String s = r.toString().toLowerCase();
        return s.contains("\"gui_only\"") || s.contains("\"machine_config_only\"");
    }

    static boolean isEmptySearchResult(JsonObject execRes) {
        if (execRes == null || !execRes.has("result") || !execRes.get("result").isJsonObject()) return false;
        JsonObject r = execRes.getAsJsonObject("result");
        boolean foundFalse = r.has("found") && r.get("found").isJsonPrimitive() && !r.get("found").getAsBoolean();
        boolean count0 = r.has("count") && r.get("count").isJsonPrimitive() && r.get("count").getAsInt() == 0;
        boolean match0 = r.has("matchCount") && r.get("matchCount").isJsonPrimitive() && r.get("matchCount").getAsInt() == 0;
        return foundFalse || (count0 && match0) || (count0 && !r.has("blocks"));
    }

    static String progressReplyFor(String base, boolean hadProgress, String lastThought) {
        String blocker = (lastThought != null && !lastThought.isEmpty())
            ? " Last blocker: " + lastThought.substring(0, Math.min(160, lastThought.length())) + "."
            : "";
        return base + (hadProgress ? " Partial progress was made earlier this session." : "") + blocker
            + " Re-send the instruction to continue from here.";
    }

    static boolean promptExpectsActionQueue(String prompt) {
        if (prompt == null) return false;
        String p = prompt.toLowerCase();
        return p.contains("mine") || p.contains("dig") || p.contains("break") || p.contains("clear")
            || p.contains("replace") || p.contains("build") || p.contains("place")
            || p.contains("farm") || p.contains("harvest") || p.contains("replant")
            || p.contains("plug") || p.contains("scoop") || p.contains("cook") || p.contains("smelt");
    }

    static boolean hasZeroQueuedWarning(JsonObject execRes) {
        if (execRes == null || !execRes.has("result") || !execRes.get("result").isJsonObject()) {
            return false;
        }
        JsonObject res = execRes.getAsJsonObject("result");
        if (!res.has("queued") || res.get("queued").getAsInt() != 0) {
            return false;
        }
        int progressed = (res.has("minedCount") ? res.get("minedCount").getAsInt() : 0)
            + (res.has("builtCount") ? res.get("builtCount").getAsInt() : 0)
            + (res.has("huntedCount") ? res.get("huntedCount").getAsInt() : 0)
            + (res.has("smeltedCount") ? res.get("smeltedCount").getAsInt() : 0)
            + (res.has("scoopedCount") ? res.get("scoopedCount").getAsInt() : 0)
            + (res.has("crafted") ? res.get("crafted").getAsInt() : 0);
        return progressed == 0;
    }
}
