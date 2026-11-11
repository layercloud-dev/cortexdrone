package ir.layercloud.cortexdrone.harness.agent;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.config.ModConfig;
import ir.layercloud.cortexdrone.harness.js.JsEngine;
import ir.layercloud.cortexdrone.harness.lesson.Lesson;
import ir.layercloud.cortexdrone.harness.lesson.LessonStore;
import ir.layercloud.cortexdrone.harness.memory.MemoryEntry;
import ir.layercloud.cortexdrone.harness.memory.MemoryStore;
import ir.layercloud.cortexdrone.harness.session.DroneSession;
import ir.layercloud.cortexdrone.harness.session.SessionJournal;
import ir.layercloud.cortexdrone.harness.session.SessionOutcome;
import ir.layercloud.cortexdrone.harness.skill.Skill;
import ir.layercloud.cortexdrone.harness.skill.SkillCodeGuard;
import ir.layercloud.cortexdrone.harness.skill.SkillLibrary;
import ir.layercloud.cortexdrone.harness.tool.ToolContext;
import ir.layercloud.cortexdrone.harness.tool.ToolInvoker;
import ir.layercloud.cortexdrone.harness.tool.ToolRegistry;
import ir.layercloud.cortexdrone.harness.tool.ToolSpec;
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
    private static final int MIN_TASK_DEADLINE_SECONDS = 30;
    private static final long MILLIS_PER_SECOND = 1000L;
    private static final String SYSTEM_PROMPT = AgentResources.load("/prompts/system.md");

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
        long deadline = System.currentTimeMillis() + Math.max(MIN_TASK_DEADLINE_SECONDS, this.config.getTaskDeadlineSeconds())
                * MILLIS_PER_SECOND;
        int emptyResponses = 0;
        // 0 = the configured max_tokens; raised after a truncated thinking turn.
        int outputTokens = 0;
        boolean riderWrapUp = false;
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
                    if (!riderWrapUp
                            && AgentInbox.beginRiderWrapUp(controller, conversation, this.logger)) {
                        riderWrapUp = true;
                        deadline = System.currentTimeMillis() + AgentInbox.RIDER_WRAP_UP_MS;
                        continue;
                    }
                    String message = partialMessage("time limit", session);
                    finishRun(run, SessionOutcome.INCOMPLETE, "deadline", message);
                    speak(player, controller, message);
                    return;
                }
                AgentInbox.drain(session, conversation, this.logger, controller, loopId, turn);
                conversation.compactIfNeeded();
                setStatus(controller, "Thinking T" + turn);

                long turnStarted = System.currentTimeMillis();
                LlmResponse response;
                try {
                    response = this.llm.chat(controller, conversation.messages(),
                            this.registry.toOpenAiTools(), false, outputTokens);
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
                        response.content(), response.hasToolCalls() ? "<tool calls>" : "",
                        response.finishReason());

                conversation.assistant(response);

                if (!response.hasToolCalls()) {
                    AgentTurnRecovery.Decision recovery = AgentTurnRecovery.decide(
                            response, emptyResponses + 1,
                            outputTokens > 0 ? outputTokens : this.config.getMaxTokens(), session);
                    if (recovery.retry) {
                        emptyResponses++;
                        outputTokens = recovery.nextBudget;
                        this.logger.log("LLM", controller,
                                (recovery.empty ? "Empty model reply" : "Truncated model reply")
                                        + " on turn " + turn
                                        + "; retrying with a larger output budget",
                                "finish=" + recovery.finish + " retry=" + emptyResponses
                                        + "/" + AgentTurnRecovery.MAX_RETRIES
                                        + " max_tokens=" + outputTokens);
                        conversation.user(recovery.nudge);
                        continue;
                    }
                    if (recovery.finalMessage != null) {
                        finishRun(run, SessionOutcome.INCOMPLETE, "empty_model_response",
                                recovery.finalMessage);
                        speak(player, controller, recovery.finalMessage);
                        return;
                    }
                    if (recovery.deliverTruncated) {
                        this.logger.log("LLM", controller,
                                "Final answer still truncated after retries; "
                                        + "delivering the partial reply",
                                "finish=" + recovery.finish + " max_tokens=" + outputTokens);
                    }
                    if (session.hasQueuedMessages()) {
                        // A message arrived while this final answer was being
                        // written; answer it in the same generation instead of
                        // ending and losing it.
                        continue;
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
            AgentInbox.requeueLate(player, controller, session, this.manager);
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
     * this only guarantees that a working workflow is not thrown away. Scripts
     * that embed instance-specific literals are skipped: a hardcoded recording
     * is not a reusable skill.
     */
    private void maybeAutoSaveSkill(DroneController controller, String prompt,
                                    String code, boolean refined) {
        if (refined || code == null || code.trim().length() < 200) return;
        if (!performsWorldAction(code)) return;
        List<SkillCodeGuard.Violation> violations = SkillCodeGuard.inspect(code);
        if (!violations.isEmpty()) {
            this.logger.log("SKILL", controller,
                    "Auto-save skipped: script contains instance-specific literals",
                    SkillCodeGuard.describe(violations));
            return;
        }
        String name = ChatLines.skillName(prompt);
        Skill skill = this.skills.save(name, ChatLines.limit(prompt, 220), "auto",
                java.util.Collections.singletonList(ChatLines.limit(prompt, 120)), code, "auto");
        if (skill == null) return;
        this.logger.log("SKILL", controller, "Auto-saved skill '" + skill.getName() + "' v"
                        + skill.getVersion(),
                "model did not call skill_refine; script kept verbatim");
    }

    /**
     * True when the script calls a tool that changes the world or an inventory.
     *
     * <p>Derived from the tool registry's declared capabilities, so a newly
     * registered mutating tool is recognised without editing a name list here.
     * The JS method name is the camelCase form of the tool name.</p>
     */
    private boolean performsWorldAction(String code) {
        if (code == null) return false;
        for (ToolSpec spec : this.registry.specs()) {
            if (!spec.mutatesAnything()) continue;
            String jsName = JsEngine.camelCase(spec.getName());
            if (!jsName.isEmpty() && code.contains(jsName)) return true;
        }
        return false;
    }

    private static boolean isOk(JsonObject result) {
        return result != null && (!result.has("ok") || result.get("ok").getAsBoolean());
    }

    private static String partialMessage(String reason, DroneSession session) {
        return AgentTurnRecovery.partial(reason, session);
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

    /**
     * Minecraft chat is short; split long replies at sentence/word boundaries.
     */
    static java.util.List<String> splitChat(String message) {
        return ChatLines.split(message);
    }
}
