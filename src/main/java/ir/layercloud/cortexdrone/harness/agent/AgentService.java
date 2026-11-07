package ir.layercloud.cortexdrone.harness.agent;

import ir.layercloud.cortexdrone.DroneController;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.config.ModConfig;
import ir.layercloud.cortexdrone.harness.lesson.Lesson;
import ir.layercloud.cortexdrone.harness.lesson.LessonStore;
import ir.layercloud.cortexdrone.harness.memory.MemoryEntry;
import ir.layercloud.cortexdrone.harness.memory.MemoryStore;
import ir.layercloud.cortexdrone.harness.skill.Skill;
import ir.layercloud.cortexdrone.harness.skill.SkillLibrary;
import ir.layercloud.cortexdrone.harness.session.DroneSession;
import ir.layercloud.cortexdrone.harness.session.SessionJournal;
import ir.layercloud.cortexdrone.harness.session.SessionOutcome;
import ir.layercloud.cortexdrone.harness.tool.ToolContext;
import ir.layercloud.cortexdrone.harness.tool.ToolInvoker;
import ir.layercloud.cortexdrone.harness.tool.ToolRegistry;
import ir.layercloud.cortexdrone.service.world.WorldScanner;
import net.minecraft.entity.player.EntityPlayerMP;

import java.util.List;

/**
 * The task agent: one tool-calling conversation per player request.
 *
 * <p>It sends the system prompt, a live context message and the user request;
 * the model answers with native tool calls. Each call is executed through
 * {@link ToolInvoker} and its result is appended as a {@code tool} message.
 * The loop ends when the model replies without a tool call, the task is
 * cancelled, or the turn/time budget is exhausted.</p>
 *
 * <p>There is no JSON envelope parsing, no salvage heuristics and no hidden
 * instruction queue: the model either calls a tool or talks to the player.</p>
 */
public final class AgentService {
    private static final String SYSTEM_PROMPT = AgentResources.load("/prompts/system.md");
    private static final int MAX_EMPTY_RESPONSES = 2;

    private final AIDroneManager manager;
    private final DroneLogger logger;
    private final WorldScanner worldScanner;
    private final LlmClient llm;
    private final ToolInvoker invoker;
    private final ToolRegistry registry;
    private final SkillLibrary skills;
    private final MemoryStore memory;
    private final LessonStore lessons;
    private final SessionJournal journal;
    private final ModConfig config;

    public AgentService(AIDroneManager manager, DroneLogger logger, WorldScanner worldScanner,
                        LlmClient llm, ToolInvoker invoker, ToolRegistry registry,
                        SkillLibrary skills, MemoryStore memory, LessonStore lessons,
                        SessionJournal journal, ModConfig config) {
        this.manager = manager;
        this.logger = logger;
        this.worldScanner = worldScanner;
        this.llm = llm;
        this.invoker = invoker;
        this.registry = registry;
        this.skills = skills;
        this.memory = memory;
        this.lessons = lessons;
        this.journal = journal;
        this.config = config;
    }

    public String buildContext(EntityPlayerMP player, DroneController controller,
                               String prompt, String intent) {
        List<Skill> suggested = this.skills.search(prompt, 3);
        List<MemoryEntry> memories = this.memory != null
                ? this.memory.forContext(prompt, 10) : java.util.Collections.emptyList();
        List<Lesson> relevantLessons = this.lessons != null
                ? this.lessons.forContext(prompt, 6) : java.util.Collections.emptyList();
        return ContextRenderer.render(this.manager, this.worldScanner, player, controller,
                prompt, intent, suggested, memories, relevantLessons);
    }

    public void runAgentLoop(final EntityPlayerMP player, final DroneController controller,
                             final String userPrompt, final String contextMessage,
                             final int loopId, final String intent) {
        DroneSession session = this.manager.getSessionManager().session(controller);
        Conversation conversation = new Conversation(SYSTEM_PROMPT, contextMessage, userPrompt,
                this.config.getMaxContextChars(), this.config.getMaxToolResultChars());
        SessionJournal.SessionRun run = beginSession(controller, player, userPrompt, intent, loopId);
        long deadline = System.currentTimeMillis() + Math.max(30, this.config.getTaskDeadlineSeconds()) * 1000L;
        int emptyResponses = 0;
        String lastWorkflowScript = null;
        boolean refinedSkill = false;

        this.logger.log("AGENT", controller, "Task loop " + loopId + " started",
                "prompt=\"" + LoopTracer.snippet(userPrompt, 200) + "\" tools="
                        + this.registry.modelTools().size() + " model=" + this.config.getModel());
        LoopTracer.traceStart(this.logger, controller, loopId, userPrompt);

        try {
            for (int turn = 1; turn <= Math.max(1, this.config.getMaxTurns()); turn++) {
                if (superseded(controller, session, loopId)) {
                    LoopTracer.logSuperseded(this.logger, controller, loopId, turn, userPrompt,
                            session.activeLoopId());
                    finishInterrupted(run, session, loopId);
                    return;
                }
                if (System.currentTimeMillis() > deadline) {
                    String message = partialMessage("time limit", session);
                    finishRun(run, SessionOutcome.INCOMPLETE, "deadline", message);
                    speak(player, controller, message);
                    return;
                }
                conversation.compactIfNeeded();
                setStatus(controller, "Thinking T" + turn);

                long turnStarted = System.currentTimeMillis();
                LlmResponse response;
                try {
                    response = this.llm.chat(controller, conversation.messages(),
                            this.registry.toOpenAiTools(), false);
                } catch (Throwable t) {
                    this.logger.log("LLM", controller, "LLM request failed on turn " + turn,
                            t.toString() + " key=" + LoopTracer.maskKey(this.config.getApiKey()));
                    String message = "AI backend error: " + LoopTracer.snippet(t.toString(), 220)
                            + ". Fix the gateway/key and re-send the task.";
                    finishRun(run, SessionOutcome.ERROR, "llm_error", message);
                    speak(player, controller, message);
                    return;
                }

                String thought = !response.reasoning().isEmpty()
                        ? response.reasoning() : response.content();
                if (!thought.trim().isEmpty()) {
                    session.recordThought(turn, LoopTracer.snippet(thought, 400));
                    if (run != null) run.thought(turn, thought);
                    this.logger.log("THINK", controller, "Turn " + turn + ": "
                            + LoopTracer.snippet(thought, 600), "");
                }
                LoopTracer.traceTurn(this.logger, controller, loopId, turn,
                        System.currentTimeMillis() - turnStarted, false,
                        response.content(), response.hasToolCalls() ? "<tool calls>" : "");

                conversation.assistant(response);

                if (!response.hasToolCalls()) {
                    if (response.content().trim().isEmpty()) {
                        emptyResponses++;
                        if (emptyResponses <= MAX_EMPTY_RESPONSES) {
                            conversation.user("Your last response was empty. Call a tool to make progress, "
                                    + "or give a short final answer with what is done and what is blocked.");
                            continue;
                        }
                        String message = partialMessage("empty model response", session);
                        finishRun(run, SessionOutcome.INCOMPLETE, "empty_model_response", message);
                        speak(player, controller, message);
                        return;
                    }
                    maybeAutoSaveSkill(controller, userPrompt, lastWorkflowScript, refinedSkill);
                    SessionOutcome outcome = SessionOutcome.fromReply(response.content());
                    if (outcome == SessionOutcome.SUCCESS && run != null && run.allToolsFailed()) {
                        outcome = SessionOutcome.FAILURE;
                    }
                    finishRun(run, outcome, "final_answer", response.content());
                    speak(player, controller, response.content());
                    return;
                }
                emptyResponses = 0;

                for (ToolCall call : response.toolCalls()) {
                    if (superseded(controller, session, loopId)) {
                        LoopTracer.logSuperseded(this.logger, controller, loopId, turn, userPrompt,
                                session.activeLoopId());
                        finishInterrupted(run, session, loopId);
                        return;
                    }
                    JsonObject arguments = call.argumentsAsJson();
                    if (arguments == null) {
                        String message = "Arguments for '" + call.name() + "' are not valid JSON: "
                                + LoopTracer.snippet(call.arguments(), 400);
                        conversation.toolError(call, message);
                        if (run != null) run.toolError(turn, call.name(), call.arguments(), message);
                        continue;
                    }
                    this.logger.log("TOOL", controller, "Turn " + turn + " -> " + call.name(),
                            LoopTracer.snippet(call.arguments(), 500));
                    ToolContext context = new ToolContext(this.manager.getHarness(),
                            controller, player, loopId);
                    JsonObject result = this.invoker.invoke(context, call.name(), arguments);
                    conversation.toolResult(call, result);
                    boolean ok = isOk(result);
                    if (run != null) {
                        run.tool(turn, call.name(), arguments.toString(), ok, result.toString());
                        if ("cancel".equals(call.name())) run.cancelRequested("model_cancel");
                    }
                    if ("skill_refine".equals(call.name())) refinedSkill = true;
                    if ("execute".equals(call.name()) && ok && arguments.has("code")) {
                        String code = arguments.get("code").getAsString();
                        if (code != null && code.trim().length() >= 200) lastWorkflowScript = code;
                    }
                    this.logger.log("TOOL", controller, "Turn " + turn + " <- " + call.name()
                                    + (ok ? " ok" : " error"),
                            LoopTracer.snippet(result.toString(), 700));
                }
            }
            String message = partialMessage("turn limit", session);
            finishRun(run, SessionOutcome.INCOMPLETE, "turn_limit", message);
            speak(player, controller, message);
        } catch (Throwable t) {
            this.logger.log("AGENT", controller, "Agent loop failed",
                    t.toString() + " key=" + LoopTracer.maskKey(this.config.getApiKey()));
            String message = "Stopped due to an internal error ("
                    + LoopTracer.snippet(t.toString(), 200) + "). Re-send the task to retry.";
            finishRun(run, SessionOutcome.ERROR, "internal_error:" + t, message);
            speak(player, controller, message);
        } finally {
            session.unregisterLoop(loopId);
        }
    }

    private SessionJournal.SessionRun beginSession(DroneController controller, EntityPlayerMP player,
                                                   String prompt, String intent, int loopId) {
        if (this.journal == null) return null;
        double x = 0.0D;
        double y = 0.0D;
        double z = 0.0D;
        int dimension = 0;
        try {
            if (controller != null && controller.drone != null) {
                x = controller.drone.posX;
                y = controller.drone.posY;
                z = controller.drone.posZ;
                dimension = controller.drone.dimension;
            } else if (player != null) {
                x = player.posX;
                y = player.posY;
                z = player.posZ;
                dimension = player.dimension;
            }
        } catch (Exception ignored) {
            // Journaling must never break the task.
        }
        try {
            return this.journal.begin(loopId,
                    controller != null ? controller.name : "unknown",
                    controller != null ? controller.id : 0,
                    controller != null ? controller.ownerName : "",
                    player != null ? player.getCommandSenderName() : "",
                    intent, this.config.getModel(), dimension, x, y, z, prompt);
        } catch (Exception e) {
            return null;
        }
    }

    private static void finishRun(SessionJournal.SessionRun run, SessionOutcome outcome,
                                  String reason, String reply) {
        if (run == null) return;
        try {
            run.finish(outcome, reason, reply);
        } catch (Exception ignored) {
            // Session logging is best effort; the task result is what matters.
        }
    }

    private static void finishInterrupted(SessionJournal.SessionRun run, DroneSession session,
                                          int loopId) {
        if (run == null) return;
        String cancelReason = session != null ? session.cancelReason(loopId) : null;
        SessionOutcome outcome = SessionOutcome.INTERRUPTED;
        String reason;
        if (cancelReason != null) {
            reason = "cancelled:" + cancelReason;
            if ("player_cancel".equals(cancelReason) || "model_cancel".equals(cancelReason)) {
                outcome = SessionOutcome.CANCELLED;
            }
        } else {
            reason = "interrupted:drone_unavailable";
        }
        finishRun(run, outcome, reason, null);
    }

    /**
     * Safety net for the "learn after success" rule: when a successful script
     * performed real work but the model skipped {@code skill_refine}, save the
     * exact code as a skill anyway. The model still owns the good description;
     * this only guarantees that a working workflow is not thrown away.
     */
    private void maybeAutoSaveSkill(DroneController controller, String prompt,
                                    String code, boolean refined) {
        if (refined || code == null || code.trim().length() < 200) return;
        if (!performsWorldAction(code)) return;
        String name = autoSkillName(prompt);
        Skill skill = this.skills.save(name, limit(prompt, 220), "auto",
                java.util.Collections.singletonList(limit(prompt, 120)), code, "auto");
        if (skill == null) return;
        this.logger.log("SKILL", controller, "Auto-saved skill '" + skill.getName() + "' v"
                        + skill.getVersion(),
                "model did not call skill_refine; script kept verbatim");
    }

    private static boolean performsWorldAction(String code) {
        String[] actions = new String[]{
                "breakBlock", "placeBlock", "goto(", "craft(", "depositChest",
                "giveToPlayer", "takeFromPlayer", "attackEntity", "interactBlock",
                "useItem", "moveItem", "withdrawChest", "dropItems", "pickEntity",
                "setLight", "setLed"
        };
        for (String action : actions) {
            if (code.contains(action)) return true;
        }
        return false;
    }

    private static String autoSkillName(String prompt) {
        if (prompt == null) return "task_workflow";
        String[] words = prompt.toLowerCase().replaceAll("[^a-z0-9 ]+", " ").trim().split("\\s+");
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < words.length && i < 5; i++) {
            if (words[i].isEmpty()) continue;
            if (name.length() > 0) name.append('_');
            name.append(words[i]);
        }
        String result = name.toString();
        // Non-Latin prompts normalize to nothing; keep skills distinct instead
        // of letting every such task overwrite one "task_workflow" entry.
        if (result.length() < 4) {
            result = (result.isEmpty() ? "workflow" : result) + "_"
                    + Integer.toHexString(prompt.hashCode());
        }
        return result.length() > 48 ? result.substring(0, 48) : result;
    }

    private static String limit(String text, int max) {
        if (text == null) return "";
        String clean = text.trim();
        return clean.length() <= max ? clean : clean.substring(0, Math.max(0, max - 3)) + "...";
    }

    private static boolean isOk(JsonObject result) {
        return result != null && (!result.has("ok") || result.get("ok").getAsBoolean());
    }

    private static String partialMessage(String reason, DroneSession session) {
        String last = session != null ? session.lastThought() : "";
        StringBuilder message = new StringBuilder("Stopped at the ").append(reason)
                .append(". No further progress was possible without more information.");
        if (last != null && !last.isEmpty()) {
            message.append(" Last state: ").append(LoopTracer.snippet(last, 180)).append('.');
        }
        message.append(" Re-send the task to continue from here.");
        return message.toString();
    }

    private boolean superseded(DroneController controller, DroneSession session, int loopId) {
        return controller.drone == null || controller.drone.isDead
                || session.isLoopCancelled(loopId);
    }

    private void setStatus(DroneController controller, String line) {
        this.manager.getMod().queueMain(() -> {
            if (controller.drone == null || controller.drone.isDead) return;
            if (this.manager.getNavigationManager().isActive(controller)
                    || this.manager.getActionRegistry().activeContextCount(controller) > 0) return;
            controller.drone.lightColor_$eq(0xAA55FF);
            controller.drone.statusText_$eq(AIDroneManager.trimStatus(line));
        });
    }

    private void speak(EntityPlayerMP player, DroneController controller, String message) {
        if (message == null || message.trim().isEmpty()) return;
        for (String line : splitChat(message.trim())) {
            this.manager.getMod().queueMain(() -> this.manager.droneSay(player, controller, line));
        }
    }

    /** Minecraft chat is short; split long replies at sentence/word boundaries. */
    static java.util.List<String> splitChat(String message) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        String remaining = message;
        while (remaining.length() > 240) {
            int cut = remaining.lastIndexOf(' ', 240);
            if (cut < 120) cut = remaining.lastIndexOf('\n', 240);
            if (cut < 120) cut = 240;
            lines.add(remaining.substring(0, cut).trim());
            remaining = remaining.substring(cut).trim();
        }
        if (!remaining.isEmpty()) lines.add(remaining);
        return lines;
    }
}
