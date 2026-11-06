package ir.layercloud.cortexdrone.harness.agent;

import ir.layercloud.cortexdrone.DroneController;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.harness.RpcDispatcher;
import ir.layercloud.cortexdrone.harness.session.DroneSession;
import lombok.experimental.UtilityClass;

/**
 * Deterministic helpers for agent-loop re-query / loiter detection and message
 * pruning. Split verbatim from {@link LlmAgentService} so the core stays under
 * the modular line limit; all methods are package-private static.
 */
@UtilityClass
final class AgentLoopHeuristics {
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
        } catch (Exception ignored) {
            return "";
        }
    }

    static String fingerprintCode(String code) {
        String compact = (code == null ? "" : code).replaceAll("\\s+", " ").trim();
        return compact.length() > 200 ? compact.substring(0, 200) : compact;
    }

    static boolean shouldStopBeforeExecute(JsonObject plan, String code, boolean isMalformed) {
        if (isMalformed || plan == null) return false;
        if (plan.has("stop") && !plan.get("stop").isJsonNull() && plan.get("stop").getAsBoolean()) return true;
        if (plan.has("stopPrevious") && !plan.get("stopPrevious").isJsonNull() && plan.get("stopPrevious").getAsBoolean())
            return true;
        return codeCallsStop(code);
    }

    @SuppressWarnings("BusyWait")
    static void executePreCodeModeStop(
            ir.layercloud.cortexdrone.DroneController ctrl,
            net.minecraft.entity.player.EntityPlayerMP player,
            int myLoopId,
            int turn,
            ir.layercloud.cortexdrone.config.DroneLogger logger,
            RpcDispatcher rpcDispatcher,
            DroneSession session
    ) {
        int cancelledLoops = session.cancelOtherLoops(myLoopId);
        JsonObject stopArgs = new JsonObject();
        stopArgs.addProperty("loopId", myLoopId);
        rpcDispatcher.executeOnMainThread(ctrl, player, "cancel", stopArgs);
        logger.log("CODEMODE", ctrl, "Harness executed pre-CodeMode cancel before Turn " + turn,
                "loopId=" + myLoopId + " cancelledLoops=" + cancelledLoops);
        if (cancelledLoops > 0) {
            long waitUntil = System.currentTimeMillis() + 350L;
            while (session.activeScriptCount() > 0 && System.currentTimeMillis() < waitUntil) {
                try {
                    Thread.sleep(50L);
                } catch (InterruptedException ignored) {
                    break;
                }
            }
        }
    }

    static boolean codeCallsStop(String code) {
        if (code == null || code.isEmpty()) return false;
        String c = code.toLowerCase();
        return c.contains("drone.cancel(") || c.contains("drone.stop(")
                || c.contains("rpc('cancel'") || c.contains("rpc(\"cancel\"");
    }

    static boolean codeMentionsPhysicalAction(String code) {
        if (code == null || code.isEmpty()) return false;
        String c = code.toLowerCase();
        return c.contains(".goto(") || c.contains(".stop(") || c.contains(".breakblock(") || c.contains(".placeblock(")
                || c.contains(".interactblock(") || c.contains(".useitem(")
                || c.contains(".transfer(") || c.contains(".craft(");
    }

    static boolean codeMentionsProgressAction(String code) {
        return codeMentionsPhysicalAction(code);
    }

    static boolean hasConcreteProgress(JsonObject execRes, String code) {
        if (execRes == null || !execRes.has("result") || !execRes.get("result").isJsonObject()) return false;
        JsonObject res = execRes.getAsJsonObject("result");
        String[] counters = new String[]{"started", "placed", "used", "activated", "broken",
                "transferred", "moved", "withdrawn", "deposited", "crafted"};
        for (String k : counters) {
            if (res.has(k) && !res.get(k).isJsonNull()) {
                JsonElement v = res.get(k);
                if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber() && v.getAsInt() > 0) return true;
                if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean() && v.getAsBoolean()) return true;
            }
        }
        // Movement alone (goto) is still meaningful progress for the
        // move-then-act contract, so treat a completed move as progress.
        if (res.has("arrived") && res.get("arrived").isJsonPrimitive() && res.get("arrived").getAsBoolean())
            return true;
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
        return "REPETITION WARNING: you just re-queried the same block/chest/recipe data with no progress. Do NOT call drone.inspect for the same target again. Act on what you already know: move to the target and use breakBlock/placeBlock/transfer, or report the exact blocker as your final reply (done=true). Original task: " + truncateForPrompt(originalPrompt, 300);
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
        String[] tools = new String[]{"drone.inspect", "drone.state"};
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

    static boolean promptExpectsPhysicalAction(String prompt) {
        if (prompt == null) return false;
        String p = prompt.toLowerCase();
        return p.contains("mine") || p.contains("dig") || p.contains("break") || p.contains("clear")
                || p.contains("replace") || p.contains("build") || p.contains("place")
                || p.contains("farm") || p.contains("harvest") || p.contains("replant")
                || p.contains("plug") || p.contains("scoop") || p.contains("cook") || p.contains("smelt");
    }

    static boolean attemptHadNoEffect(JsonObject execRes) {
        if (execRes == null || !execRes.has("result") || !execRes.get("result").isJsonObject()) {
            return false;
        }
        JsonObject res = execRes.getAsJsonObject("result");
        boolean attempted = res.has("started") || res.has("placed") || res.has("used")
                || res.has("activated") || res.has("transferred") || res.has("moved")
                || res.has("withdrawn") || res.has("deposited") || res.has("crafted");
        if (!attempted) return false;
        boolean acted = res.has("arrived") && res.get("arrived").isJsonPrimitive() && res.get("arrived").getAsBoolean();
        String[] flags = new String[]{"started", "placed", "used", "activated", "broken"};
        for (String k : flags) {
            if (res.has(k) && res.get(k).isJsonPrimitive() && res.get(k).getAsBoolean()) acted = true;
        }
        String[] nums = new String[]{"transferred", "moved", "withdrawn", "deposited", "crafted"};
        for (String k : nums) {
            if (res.has(k) && res.get(k).isJsonPrimitive() && res.get(k).getAsInt() > 0) acted = true;
        }
        return !acted;
    }
}
