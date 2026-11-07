package ir.layercloud.cortexdrone.harness.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.CortexDroneMod;
import ir.layercloud.cortexdrone.harness.lesson.Lesson;
import ir.layercloud.cortexdrone.harness.lesson.LessonStore;
import ir.layercloud.cortexdrone.harness.tool.ToolAffinity;
import ir.layercloud.cortexdrone.harness.tool.ToolJson;
import ir.layercloud.cortexdrone.harness.tool.ToolProvider;
import ir.layercloud.cortexdrone.harness.tool.ToolRegistry;
import ir.layercloud.cortexdrone.harness.tool.ToolSchema;
import ir.layercloud.cortexdrone.harness.tool.ToolSpec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Persistent lessons: short procedural rules learned from real attempts.
 *
 * <p>Not to be confused with {@code memory} (player facts and locations) or
 * {@code skill} (executable workflows). A lesson is prose such as
 * "breaking the block under the drone drops it" or "the north mine entrance is
 * behind the waterfall". Lessons are injected into the task context and are
 * searchable before planning a similar job.</p>
 */
public final class LessonTools implements ToolProvider {
    private static final List<ToolSpec> SPECS = Arrays.asList(
            ToolSpec.builder("lesson")
                    .description("Persistent lessons: durable procedural knowledge learned from real "
                            + "attempts. Use action=add after a failure or a non-obvious success, in the form "
                            + "symptom -> cause -> rule (e.g. 'drone fell while mining down -> break_block "
                            + "under the drone removes its support -> always keep a floor block below before "
                            + "digging downward'). Use action=search or list before starting a similar job, and "
                            + "action=forget when a lesson is wrong or obsolete. Do NOT store player facts or "
                            + "places (use memory) or workflow code (use skill_refine).")
                    .parameters(ToolSchema.object()
                            .optionalEnum("action", "add, search, list, forget, or use (mark as applied).",
                                    "search", "add", "search", "list", "forget", "use")
                            .optionalString("scope", "Area of work: navigation, mining, building, inventory, "
                                    + "combat, farm, survival, general.")
                            .optionalString("title", "Short stable rule name, e.g. 'keep floor when digging down'.")
                            .optionalString("body", "Symptom -> cause -> rule, in one or two sentences.")
                            .optionalEnum("outcome", "Whether the lesson came from a success, a failure or a "
                                    + "plain observation.", "note", "success", "failure", "note")
                            .optionalString("tags", "Extra retrieval keywords, comma or space separated.")
                            .optionalString("query", "Search text for action=search.")
                            .optionalString("id", "Lesson id or title for action=forget/use.")
                            .optionalBoolean("pinned", "Always show this lesson in context and never evict it.",
                                    false)
                            .optionalInt("limit", "Maximum results for search/list, 1..50.", 1, 50, 6))
                    .affinity(ToolAffinity.ASYNC)
                    .timeoutMs(10_000L)
                    .build()
    );

    private final LessonStore lessons;

    public LessonTools(AIDroneManager manager, LessonStore lessons) {
        this.lessons = lessons;
    }

    public static List<ToolSpec> specs() {
        return SPECS;
    }

    @Override
    public void register(ToolRegistry registry) {
        registry.bind("lesson", getClass().getSimpleName(),
                (context, arguments) -> handle(arguments));
    }

    private JsonObject handle(JsonObject args) {
        String action = args.has("action") ? args.get("action").getAsString().toLowerCase() : "search";
        String scope = args.has("scope") ? args.get("scope").getAsString() : null;
        int limit = args.has("limit") ? args.get("limit").getAsInt() : 6;

        if (action.equals("add")) {
            String title = args.has("title") ? args.get("title").getAsString() : "";
            String body = args.has("body") ? args.get("body").getAsString() : "";
            if (title.trim().isEmpty() && body.trim().isEmpty()) {
                return ToolJson.error("invalid_arguments", "lesson add requires 'title' or 'body'.",
                        "Write the symptom -> cause -> rule in 'body'.", true);
            }
            String outcome = args.has("outcome") ? args.get("outcome").getAsString() : "note";
            boolean pinned = args.has("pinned") && args.get("pinned").getAsBoolean();
            List<String> tags = splitTags(args.has("tags") ? args.get("tags").getAsString() : "");
            Lesson lesson = this.lessons.add(scope, title, body, tags, outcome, pinned, "agent");
            if (lesson == null) {
                return ToolJson.error("lesson_failed", "Lesson was empty.",
                        "Provide a title and a short rule.", true);
            }
            JsonObject result = ToolJson.ok();
            result.addProperty("saved", true);
            result.add("lesson", toJson(lesson));
            return result;
        }

        if (action.equals("forget")) {
            String key = args.has("id") ? args.get("id").getAsString()
                    : (args.has("title") ? args.get("title").getAsString() : "");
            boolean removed = this.lessons.forget(key);
            if (!removed) {
                return ToolJson.error("lesson_not_found", "No lesson matched '" + key + "'.",
                        "Use action=list to see stored lesson ids and titles.", true);
            }
            JsonObject result = ToolJson.ok();
            result.addProperty("forgotten", true);
            result.addProperty("key", key);
            return result;
        }

        if (action.equals("use")) {
            String key = args.has("id") ? args.get("id").getAsString()
                    : (args.has("title") ? args.get("title").getAsString() : "");
            this.lessons.recordUse(key);
            JsonObject result = ToolJson.ok();
            result.addProperty("used", true);
            result.addProperty("key", key);
            return result;
        }

        String query = args.has("query") ? args.get("query").getAsString()
                : (args.has("body") ? args.get("body").getAsString() : "");
        List<Lesson> found = action.equals("list")
                ? this.lessons.list(scope, limit) : this.lessons.search(query, scope, limit);
        JsonObject result = ToolJson.ok();
        result.addProperty("action", action);
        result.addProperty("count", found.size());
        result.add("lessons", toJsonArray(found));
        if (found.isEmpty()) {
            result.addProperty("hint", "No matching lesson. Save one with action=add when something fails "
                    + "or needs a non-obvious workaround.");
        }
        return result;
    }

    private static JsonArray toJsonArray(List<Lesson> lessons) {
        JsonArray array = new JsonArray();
        for (Lesson lesson : lessons) array.add(toJson(lesson));
        return array;
    }

    private static JsonObject toJson(Lesson lesson) {
        JsonObject object = CortexDroneMod.GSON.toJsonTree(lesson).getAsJsonObject();
        object.addProperty("summary", lesson.summary(240));
        return object;
    }

    private static List<String> splitTags(String raw) {
        List<String> tags = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return tags;
        for (String part : raw.split("[,\\s]+")) {
            if (!part.trim().isEmpty()) tags.add(part.trim());
        }
        return tags;
    }
}
