package ir.layercloud.cortexdrone.harness;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;

/**
 * Resilient parser and salvager for LLM Code Mode responses.
 * Strips `<think>...</think>` blocks, extracts balanced JSON objects, salvages
 * truncated or unescaped JavaScript code blocks, and sanitizes thoughts.
 */
@UtilityClass
public final class LlmResponseParser {
    private static final Pattern THINK_BLOCK_PATTERN = Pattern.compile("(?s)<think>(.*?)</think>");
    private static final Pattern THOUGHT_FIELD_PATTERN = Pattern.compile("\"thought\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern REPLY_FIELD_PATTERN = Pattern.compile("\"reply\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern DONE_FIELD_PATTERN = Pattern.compile("\"done\"\\s*:\\s*(true|false)");
    private static final Pattern STOP_FIELD_PATTERN = Pattern.compile("\"stop\"\\s*:\\s*(true|false)");
    private static final Pattern CODE_STRICT_PATTERN = Pattern.compile("\"code\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern NEXT_FIELD_PATTERN = Pattern.compile("\"\\s*,\\s*\"(?:done|reply|thought|stop)\"\\s*:");
    // Machine-config-only fast path: prose that names an HBM machine as the
    // blocker (Assembly Machine, Chemical Plant, Blast Furnace...) but never
    // emits a ```json block or "code" field must be salvaged as done:true in
    // ONE turn instead of burning the 3 HTTP retries + self-correction turns.
    private static final Pattern BLOCKER_MACHINE_PATTERN = Pattern.compile(
        "(?i)(assembly machine|assemblymachine|chemical plant|blast furnace|arc furnace|precision assembly|"
        + "pyrolysis|crystallizer|electrolyser|rotary furnace|\\bmbt anvil\\b|hbm anvil|ntm anvil|"
        + "centrifuge|compressor|mixer|coker|rock ?mill|soldering station|shredder|press|"
        + "machine ?config|machine-config)");
    // Extended blocker detection: the BlackSnow double-chest essay was pure prose with no
    // machine name; the meteor hunt essay never named a machine either. Catch general
    // "blocked / missing ingredient" prose too so it finishes done:true instead of
    // burning self-correction turns on essay-mode output.
    private static final Pattern BLOCKER_ACTION_PATTERN = Pattern.compile(
        "(?i)(\\bneed(s|ed)?\\b|\\brequires?\\b|\\brequirement\\b|\\bmissing\\b|\\bnot found\\b|"
        + "\\bnot available\\b|\\bnot in (chests|inventory)\\b|\\bnothing (found|nearby)\\b|"
        + "\\bno (meteor|meteorite|block|item|recipe)[^.\\n]{0,60}(found|nearby|available)\\b|"
        + "\\bbust\\b|\\bcan't\\b|\\bcannot\\b|\\bblocked\\b|"
        + "\\bgui.only\\b|\\bgui only\\b|\\bhammer\\b|\\bcraft(?:ed|ing)?\\b[^.]{0,40}\\banvil\\b|"
        + "\\bmachine.config\\b|\\bmachine config\\b)");
    private static final Pattern GENERIC_BLOCKER_PATTERN = Pattern.compile(
        "(?i)(\\bcannot\\b|\\bcan't\\b|\\bblocked\\b|\\bno (meteor|meteorite|block|item|chest|recipe)[^.\\n]{0,50}(found|nearby|available|in (chests|inventory))|"
        + "\\bnot (found|available|in chests)(\\b[^.\\n]{0,60})?\\band (nothing|nowhere))");

    public static String sanitizeThought(String rawThought, String fallbackReasoning) {
        String src = (rawThought != null && !rawThought.trim().isEmpty())
            ? rawThought.trim()
            : (fallbackReasoning != null ? fallbackReasoning.trim() : "");
        if (src.isEmpty()) return "";
        src = src.replaceAll("(?s)<think>.*?</think>", " ")
            .replaceAll("</?think>", " ")
            .replaceAll("```[a-zA-Z]*", " ")
            .replaceAll("\\s+", " ")
            .trim();
        if (src.length() > 220) {
            int dot = src.indexOf(". ");
            if (dot >= 25 && dot <= 200) {
                src = src.substring(0, dot + 1);
            } else {
                src = src.substring(0, 217) + "...";
            }
        }
        return src;
    }

    public static JsonObject tryParseJsonObject(String candidate) {
        if (candidate == null || candidate.isEmpty()) return null;        try {
            JsonElement el = new JsonParser().parse(candidate);
            if (el != null && el.isJsonObject()) {
                return el.getAsJsonObject();
            }
        } catch (Exception ignored) {
        }
        try {
            JsonReader reader = new JsonReader(new StringReader(candidate));
            reader.setLenient(true);
            JsonElement el = new JsonParser().parse(reader);
            if (el != null && el.isJsonObject()) {
                return el.getAsJsonObject();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public static String unescapeJsonLiteral(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    default: sb.append(n); break;
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public static JsonObject salvageMalformedResponse(String s, String reasoning) {
        String extractedThought = "";
        Matcher mThought = THOUGHT_FIELD_PATTERN.matcher(s);
        if (mThought.find()) {
            extractedThought = unescapeJsonLiteral(mThought.group(1));
        }

        String extractedReply = "";
        Matcher mReply = REPLY_FIELD_PATTERN.matcher(s);
        if (mReply.find()) {
            extractedReply = unescapeJsonLiteral(mReply.group(1));
        }

        Boolean extractedDone = null;
        Matcher mDone = DONE_FIELD_PATTERN.matcher(s);
        if (mDone.find()) {
            extractedDone = Boolean.parseBoolean(mDone.group(1));
        }

        String extractedCode = "";
        Matcher mCodeStrict = CODE_STRICT_PATTERN.matcher(s);
        if (mCodeStrict.find()) {
            extractedCode = unescapeJsonLiteral(mCodeStrict.group(1)).trim();
        } else {
            int codeKey = s.indexOf("\"code\"");
            if (codeKey >= 0) {
                int colon = s.indexOf(':', codeKey + 6);
                int firstQuote = colon >= 0 ? s.indexOf('"', colon + 1) : -1;
                if (firstQuote >= 0) {
                    Matcher endMatcher = NEXT_FIELD_PATTERN.matcher(s);
                    int endQuote = -1;
                    if (endMatcher.find(firstQuote + 1)) {
                        endQuote = endMatcher.start();
                    } else {
                        int lastBrace = s.lastIndexOf('}');
                        if (lastBrace > firstQuote) {
                            int qBeforeBrace = s.lastIndexOf('"', lastBrace);
                            if (qBeforeBrace > firstQuote) endQuote = qBeforeBrace;
                        }
                    }
                    if (endQuote > firstQuote) {
                        extractedCode = unescapeJsonLiteral(s.substring(firstQuote + 1, endQuote)).trim();
                    }
                }
            }
        }

        if (!extractedCode.isEmpty() || extractedDone != null) {
            JsonObject salvaged = new JsonObject();
            salvaged.addProperty("thought", sanitizeThought(extractedThought, reasoning));
            Matcher mStop = STOP_FIELD_PATTERN.matcher(s);
            if (mStop.find()) {
                salvaged.addProperty("stop", Boolean.parseBoolean(mStop.group(1)));
            }
            salvaged.addProperty("code", extractedCode);
            salvaged.addProperty("done", extractedDone != null ? extractedDone : false);
            salvaged.addProperty("reply", extractedReply);
            if (reasoning != null && !reasoning.isEmpty()) {
                salvaged.addProperty("_reasoning", reasoning);
            }
            return salvaged;
        }
        return null;
    }

    /**
     * Machine-config-only blocker fast path.
     * If the prose (content or reasoning_content) names an HBM machine AND
     * frames it as a requirement/missing item, return a done:true object
     * whose reply carries the exact blocker instead of looping kb.lookup /
     * getRecipes for many turns. Returns null otherwise.
     * Extended: also catches generic missing/blocked prose (essay-mode output
     * like the double-chest essay or the meteor hunt essay that never names a
     * machine) when it is long, has no fenced code, and names a concrete
     * blocker — so it finishes in one turn rather than burning retries.
     */
    public static JsonObject proseBlocker(String s, String reasoning) {
        if (s != null && s.trim().startsWith("{")) {
            return null;
        }
        String hay = ((s != null ? s : "") + "\n" + (reasoning != null ? reasoning : "")).trim();
        if (hay.isEmpty() || hay.length() < 40) {
            return null;
        }
        Matcher mMachine = BLOCKER_MACHINE_PATTERN.matcher(hay);
        Matcher mAction = BLOCKER_ACTION_PATTERN.matcher(hay);
        boolean hasMachine = mMachine.find();
        boolean hasAction = mAction.find();
        if (!hasMachine || !hasAction) {
            return null;
        }
        // Generic (machine-less) blocker: only fires on long essay-like prose
        // with no fenced code — a short "need blast furnace" note is handled by
        // the machine path above. This keeps pure planning essays out of the loop.
        String machine = hasMachine ? mMachine.group(0).trim() : "";
        String safeMachine = machine.isEmpty() ? "HBM machine" : machine;
        String thought = "Machine-config blocker detected (" + safeMachine + "). "
            + "Finished with an explicit blocker instead of re-querying.";
        JsonObject o = new JsonObject();
        o.addProperty("thought", sanitizeThought(thought, reasoning));
        o.addProperty("code", "");
        o.addProperty("done", true);
        String reply = "Blocked on " + safeMachine
            + ": this recipe is machine-config-only (no vanilla crafting-table recipe). "
            + "Next step: set up/bring the " + safeMachine + " (and its power/fuel) or "
            + "provide the missing ingredient, then re-request.";
        String oneLine = hay.replace('\n', ' ').replaceAll("\\s+", " ").trim();
        if (oneLine.length() > 200) {
            oneLine = oneLine.substring(0, 197) + "…";
        }
        o.addProperty("reply", reply + " Detail: " + oneLine);
        if (reasoning != null && !reasoning.isEmpty()) {
            o.addProperty("_reasoning", reasoning);
        }
        return o;
    }

    /**
     * Generic prose-blocker fallback: long prose (essay) with a blocked/missing
     * framing but no machine name. Returns a done:true partial reply. Only
     * fires when the prose is long (essay-like) and contains no fenced code —
     * the machine path above handles short machine notes.
     */
    public static JsonObject genericProseBlocker(String s, String reasoning) {
        if (s != null && s.trim().startsWith("{")) {
            return null;
        }
        String hay = ((s != null ? s : "") + "\n" + (reasoning != null ? reasoning : "")).trim();
        if (hay.isEmpty() || hay.length() < 180) {
            return null;
        }
        if (hay.contains("```")) {
            return null; // fenced code was already handled above; not an essay blocker
        }
        Matcher m = GENERIC_BLOCKER_PATTERN.matcher(hay);
        if (!m.find()) {
            return null;
        }
        String thought = "Prose blocker detected (essay-mode, no executable code). "
            + "Finished with a partial reply instead of re-querying.";
        JsonObject o = new JsonObject();
        o.addProperty("thought", sanitizeThought(thought, reasoning));
        o.addProperty("code", "");
        o.addProperty("done", true);
        String oneLine = hay.replace('\n', ' ').replaceAll("\\s+", " ").trim();
        if (oneLine.length() > 200) {
            oneLine = oneLine.substring(0, 197) + "…";
        }
        o.addProperty("reply", "Blocked: " + oneLine
            + " Next step: provide the exact target/coords/filter (or the missing item/machine), "
            + "then re-request.");
        if (reasoning != null && !reasoning.isEmpty()) {
            o.addProperty("_reasoning", reasoning);
        }
        return o;
    }

    public static JsonObject parse(String raw, String reasoningContent) {
        if (raw == null) return null;
        String s = raw.trim();
        StringBuilder thinkCollected = new StringBuilder();
        if (reasoningContent != null && !reasoningContent.trim().isEmpty()) {
            thinkCollected.append(reasoningContent.trim()).append(' ');
        }

        Matcher thinkMatcher = THINK_BLOCK_PATTERN.matcher(s);
        while (thinkMatcher.find()) {
            thinkCollected.append(thinkMatcher.group(1).trim()).append(' ');
        }
        s = s.replaceAll("(?s)<think>.*?</think>", "").trim();
        int closeThink = s.lastIndexOf("</think>");
        if (closeThink >= 0) {
            thinkCollected.append(s.substring(0, closeThink).trim()).append(' ');
            s = s.substring(closeThink + 8).trim();
        }
        String combinedReasoning = thinkCollected.toString().trim();

        // Fence salvage: the old code only looked for a ```json opener and only parsed the
        // *first* fence when it was a valid object. When the model emits prose + a
        // fenced ```json block (or ```js code fences), the fenced body was silently
        // skipped and the balanced-brace scan fell through to malformed. Extract every
        // fence (any language tag, incl. bare ``` and ```json) and try each before
        // giving up to the brace/rescue paths below.
        Pattern anyFence = Pattern.compile("(?s)```[a-zA-Z0-9_+-]*\\s*\\r?\\n?(.*?)```");
        Matcher fenceMatcher = anyFence.matcher(s);
        while (fenceMatcher.find()) {
            String body = fenceMatcher.group(1).trim();
            if (body.isEmpty()) continue;
            JsonObject fenced = tryParseJsonObject(body);
            if (fenced != null && (fenced.has("code") || fenced.has("thought") || fenced.has("done")
                || fenced.has("reply") || fenced.has("tool_calls") || fenced.has("actions"))) {
                if (!combinedReasoning.isEmpty()) fenced.addProperty("_reasoning", combinedReasoning);
                return fenced;
            }
            // Fenced JS code body (```js ... await drone.breakBlock(...) ... ```) with no JSON
            // wrapper: package it as executable code instead of losing the turn.
            if (body.contains("await ") || body.contains("return ") || body.contains("function") || body.contains("const ")) {
                JsonObject o = new JsonObject();
                o.addProperty("thought", sanitizeThought(s, combinedReasoning));
                o.addProperty("code", body);
                o.addProperty("done", true);
                o.addProperty("reply", "");
                if (!combinedReasoning.isEmpty()) o.addProperty("_reasoning", combinedReasoning);
                return o;
            }
        }

        int jsonFence = s.indexOf("```json");
        if (jsonFence >= 0) {
            int endFence = s.indexOf("```", jsonFence + 7);
            if (endFence > jsonFence) {
                JsonObject o = tryParseJsonObject(s.substring(jsonFence + 7, endFence).trim());
                if (o != null) {
                    if (!combinedReasoning.isEmpty()) o.addProperty("_reasoning", combinedReasoning);
                    return o;
                }
            }
        }

        if (s.startsWith("{") && s.endsWith("}")) {
            JsonObject o = tryParseJsonObject(s);
            if (o != null) {
                if (!combinedReasoning.isEmpty()) o.addProperty("_reasoning", combinedReasoning);
                return o;
            }
        }

        List<String> balancedBlocks = new ArrayList<String>();
        int depth = 0;
        int startIdx = -1;
        boolean inQuotes = false;
        boolean escaped = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\' && inQuotes) {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inQuotes = !inQuotes;
                continue;
            }
            if (!inQuotes) {
                if (c == '{') {
                    if (depth == 0) startIdx = i;
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0 && startIdx >= 0) {
                        balancedBlocks.add(s.substring(startIdx, i + 1));
                        startIdx = -1;
                    } else if (depth < 0) {
                        depth = 0;
                        startIdx = -1;
                    }
                }
            }
        }
        for (int i = balancedBlocks.size() - 1; i >= 0; i--) {
            JsonObject o = tryParseJsonObject(balancedBlocks.get(i));
            if (o != null && (o.has("code") || o.has("thought") || o.has("done") || o.has("reply") || o.has("tool_calls") || o.has("actions"))) {
                if (!combinedReasoning.isEmpty()) o.addProperty("_reasoning", combinedReasoning);
                return o;
            }
        }

        JsonObject salvaged = salvageMalformedResponse(s, combinedReasoning);
        if (salvaged != null) {
            return salvaged;
        }
        if (!combinedReasoning.isEmpty()) {
            JsonObject salvagedFromReasoning = salvageMalformedResponse(combinedReasoning, combinedReasoning);
            if (salvagedFromReasoning != null && salvagedFromReasoning.has("code") && !salvagedFromReasoning.get("code").getAsString().isEmpty()) {
                return salvagedFromReasoning;
            }
        }

        // Check all code fences (any language tag: ```js, ```javascript, ```js\n, or a bare ``` fence)
        // in either s or combinedReasoning for a salvageable executable body containing await/return.
        String[] fenceSources = new String[]{s, combinedReasoning};
        Pattern genericFence = Pattern.compile("(?s)```[a-zA-Z0-9_+-]*\\s*\\r?\\n(.*?)```");
        for (String src : fenceSources) {
            if (src == null || src.isEmpty()) continue;
            Matcher fm = genericFence.matcher(src);
            while (fm.find()) {
                String body = fm.group(1).trim();
                if (body.isEmpty()) continue;
                if (body.contains("await ") || body.contains("return ") || body.contains("function") || body.contains("const ")) {
                    JsonObject o = new JsonObject();
                    o.addProperty("thought", sanitizeThought(s != null ? s : "", combinedReasoning));
                    o.addProperty("code", body);
                    o.addProperty("done", true);
                    o.addProperty("reply", "");
                    if (!combinedReasoning.isEmpty()) {
                        o.addProperty("_reasoning", combinedReasoning);
                    }
                    return o;
                }
            }
        }

        // Machine-config-only fast path: prose that names an HBM machine as
        // the blocker but carries no executable code finishes here in one
        // turn instead of burning retries + self-correction turns.
        JsonObject blocker = proseBlocker(s, combinedReasoning);
        if (blocker != null) {
            return blocker;
        }
        // Generic prose-blocker fallback for essay-mode output (long prose, no
        // fenced code, blocked/missing framing — e.g. the double-chest essay
        // that never emitted JSON). Finishes done:true in one turn.
        JsonObject genericBlocker = genericProseBlocker(s, combinedReasoning);
        if (genericBlocker != null) {
            return genericBlocker;
        }

        JsonObject malformed = new JsonObject();
        malformed.addProperty("thought", sanitizeThought(s, combinedReasoning));
        malformed.addProperty("code", "");
        malformed.addProperty("done", false);
        malformed.addProperty("reply", "");
        malformed.addProperty("malformed", true);
        if (!combinedReasoning.isEmpty()) {
            malformed.addProperty("_reasoning", combinedReasoning);
        }
        return malformed;
    }
}
