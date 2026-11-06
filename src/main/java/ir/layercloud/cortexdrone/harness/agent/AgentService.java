package ir.layercloud.cortexdrone.harness.agent;

import ir.layercloud.cortexdrone.DroneController;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.config.ModConfig;
import ir.layercloud.cortexdrone.harness.memory.MemoryEntry;
import ir.layercloud.cortexdrone.harness.memory.MemoryStore;
import ir.layercloud.cortexdrone.harness.skill.Skill;
import ir.layercloud.cortexdrone.harness.skill.SkillLibrary;
import ir.layercloud.cortexdrone.harness.session.DroneSession;
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
    private final ModConfig config;

    public AgentService(AIDroneManager manager, DroneLogger logger, WorldScanner worldScanner,
                        LlmClient llm, ToolInvoker invoker, ToolRegistry registry,
                        SkillLibrary skills, MemoryStore memory, ModConfig config) {
        this.manager = manager;
        this.logger = logger;
        this.worldScanner = worldScanner;
        this.llm = llm;
        this.invoker = invoker;
        this.registry = registry;
        this.skills = skills;
        this.memory = memory;
        this.config = config;
    }

    public String buildContext(EntityPlayerMP player, DroneController controller,
                               String prompt, String intent) {
        List<Skill> suggested = this.skills.search(prompt, 3);
        List<MemoryEntry> memories = this.memory != null
                ? this.memory.forContext(prompt, 10) : java.util.Collections.emptyList();
        return ContextRenderer.render(this.manager, this.worldScanner, player, controller,
                prompt, intent, suggested, memories);
    }

    public void runAgentLoop(final EntityPlayerMP player, final DroneController controller,
                             final String userPrompt, final String contextMessage,
                             final int loopId) {
        DroneSession session = this.manager.getSessionManager().session(controller);
        Conversation conversation = new Conversation(SYSTEM_PROMPT, contextMessage, userPrompt,
                this.config.getMaxContextChars(), this.config.getMaxToolResultChars());
        long deadline = System.currentTimeMillis() + Math.max(30, this.config.getTaskDeadlineSeconds()) * 1000L;
        int emptyResponses = 0;

        this.logger.log("AGENT", controller, "Task loop " + loopId + " started",
                "prompt=\"" + LoopTracer.snippet(userPrompt, 200) + "\" tools="
                        + this.registry.modelTools().size() + " model=" + this.config.getModel());
        LoopTracer.traceStart(this.logger, controller, loopId, userPrompt);

        try {
            for (int turn = 1; turn <= Math.max(1, this.config.getMaxTurns()); turn++) {
                if (superseded(controller, session, loopId)) {
                    LoopTracer.logSuperseded(this.logger, controller, loopId, turn, userPrompt,
                            session.activeLoopId());
                    return;
                }
                if (System.currentTimeMillis() > deadline) {
                    speak(player, controller, partialMessage("time limit", session));
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
                    speak(player, controller, "AI backend error: " + LoopTracer.snippet(t.toString(), 220)
                            + ". Fix the gateway/key and re-send the task.");
                    return;
                }

                String thought = !response.reasoning().isEmpty()
                        ? response.reasoning() : response.content();
                if (!thought.trim().isEmpty()) {
                    session.recordThought(turn, LoopTracer.snippet(thought, 400));
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
                        speak(player, controller, partialMessage("empty model response", session));
                        return;
                    }
                    speak(player, controller, response.content());
                    return;
                }
                emptyResponses = 0;

                for (ToolCall call : response.toolCalls()) {
                    if (superseded(controller, session, loopId)) {
                        LoopTracer.logSuperseded(this.logger, controller, loopId, turn, userPrompt,
                                session.activeLoopId());
                        return;
                    }
                    JsonObject arguments = call.argumentsAsJson();
                    if (arguments == null) {
                        conversation.toolError(call,
                                "Arguments for '" + call.name() + "' are not valid JSON: "
                                        + LoopTracer.snippet(call.arguments(), 400));
                        continue;
                    }
                    this.logger.log("TOOL", controller, "Turn " + turn + " -> " + call.name(),
                            LoopTracer.snippet(call.arguments(), 500));
                    ToolContext context = new ToolContext(this.manager.getHarness(),
                            controller, player, loopId);
                    JsonObject result = this.invoker.invoke(context, call.name(), arguments);
                    conversation.toolResult(call, result);
                    this.logger.log("TOOL", controller, "Turn " + turn + " <- " + call.name()
                                    + (result.has("ok") && result.get("ok").getAsBoolean() ? " ok" : " error"),
                            LoopTracer.snippet(result.toString(), 700));
                }
            }
            speak(player, controller, partialMessage("turn limit", session));
        } catch (Throwable t) {
            this.logger.log("AGENT", controller, "Agent loop failed",
                    t.toString() + " key=" + LoopTracer.maskKey(this.config.getApiKey()));
            speak(player, controller, "Stopped due to an internal error ("
                    + LoopTracer.snippet(t.toString(), 200) + "). Re-send the task to retry.");
        } finally {
            session.unregisterLoop(loopId);
        }
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
