package ir.layercloud.cortexdrone.service.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small, dependency-free web lookup service used by the internet tools.
 *
 * <p>Search uses DuckDuckGo's HTML endpoint (no API key) and falls back to the
 * MediaWiki search API. Page fetch strips scripts/styles/tags and caps the
 * output. Requests run on the agent thread, never on the server tick thread.</p>
 */
public final class WebLookupService {
    private static final String USER_AGENT = "CortexDrone/2.0 (Minecraft 1.7.10 AI drone)";
    private static final int MAX_BYTES = 2_000_000;
    private static final long CACHE_TTL_MS = 10L * 60L * 1000L;
    private static final Pattern DDG_RESULT = Pattern.compile(
            "(?is)<a[^>]+class=\"result__a\"[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>.*?"
                    + "(?:<a[^>]+class=\"result__snippet\"[^>]*>(.*?)</a>)?");
    private static final Pattern TAG = Pattern.compile("(?is)<[^>]+>");
    private static final Pattern SCRIPT_STYLE = Pattern.compile("(?is)<(script|style|noscript)[^>]*>.*?</\\1>");
    private static final Map<String, CacheEntry> CACHE = new LinkedHashMap<String, CacheEntry>(32, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
            return size() > 48;
        }
    };

    public JsonObject search(String query, int maxResults) {
        String clean = query != null ? query.trim() : "";
        if (clean.isEmpty()) return error("empty_query", "Search query is empty.");
        int limit = Math.max(1, Math.min(10, maxResults));
        String key = "s:" + clean;
        JsonObject cached = cached(key);
        if (cached != null) return cached;
        try {
            JsonObject ddg = searchDuckDuckGo(clean, limit);
            if (ddg.has("results") && ddg.getAsJsonArray("results").size() > 0) {
                cache(key, ddg);
                return ddg;
            }
        } catch (Throwable ignored) {
        }
        try {
            JsonObject wiki = searchMediaWiki("minecraft.wiki", clean, limit);
            if (wiki.has("results") && wiki.getAsJsonArray("results").size() > 0) {
                wiki.addProperty("fallback", "minecraft.wiki");
                cache(key, wiki);
                return wiki;
            }
        } catch (Throwable ignored) {
        }
        JsonObject empty = new JsonObject();
        empty.addProperty("ok", true);
        empty.addProperty("query", clean);
        empty.add("results", new JsonArray());
        empty.addProperty("count", 0);
        empty.addProperty("hint", "No results. Try a more specific item/mod name, or use wiki_lookup.");
        return empty;
    }

    public JsonObject fetch(String url, int maxChars) {
        String clean = url != null ? url.trim() : "";
        if (clean.isEmpty()) return error("empty_url", "URL is empty.");
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
            return error("invalid_url", "Only http:// and https:// URLs are supported.");
        }
        if (isPrivateHost(clean)) {
            return error("blocked_url", "Refusing to fetch a local/private address.");
        }
        String key = "f:" + clean;
        JsonObject cached = cached(key);
        if (cached != null && cached.has("text")) {
            JsonObject copy = copy(cached);
            String text = copy.get("text").getAsString();
            int cap = Math.max(500, Math.min(20_000, maxChars));
            if (text.length() > cap) {
                copy.addProperty("text", text.substring(0, cap) + "\n...[truncated]");
                copy.addProperty("truncated", true);
            }
            return copy;
        }
        try {
            String body = get(clean, "text/html,application/json,text/plain");
            String title = extractTitle(body);
            String text = htmlToText(body);
            if (text.length() > 20_000) text = text.substring(0, 20_000) + "\n...[truncated]";
            JsonObject result = new JsonObject();
            result.addProperty("ok", true);
            result.addProperty("url", clean);
            result.addProperty("title", title);
            result.addProperty("text", text);
            result.addProperty("chars", text.length());
            cache(key, result);
            return result;
        } catch (Throwable t) {
            return error("fetch_failed", "Could not fetch " + clean + ": " + t);
        }
    }

    public JsonObject wiki(String query, String wiki, int maxChars) {
        String clean = query != null ? query.trim() : "";
        if (clean.isEmpty()) return error("empty_query", "Wiki query is empty.");
        String host = "ntm".equalsIgnoreCase(wiki) ? "ntm.fandom.com" : "minecraft.wiki";
        String key = "w:" + host + ":" + clean;
        JsonObject cached = cached(key);
        if (cached != null) return cached;
        try {
            JsonObject search = searchMediaWiki(host, clean, 3);
            JsonArray results = search.has("results") ? search.getAsJsonArray("results") : new JsonArray();
            if (results.size() == 0) {
                JsonObject empty = new JsonObject();
                empty.addProperty("ok", true);
                empty.addProperty("query", clean);
                empty.addProperty("wiki", host);
                empty.add("pages", new JsonArray());
                empty.addProperty("hint", "No page found. Try web_search for the exact page title.");
                return empty;
            }
            JsonArray pages = new JsonArray();
            int budget = Math.max(500, Math.min(12_000, maxChars));
            for (JsonElement element : results) {
                JsonObject item = element.getAsJsonObject();
                String title = item.has("title") ? item.get("title").getAsString() : clean;
                try {
                    String extract = wikiExtract(host, title);
                    JsonObject page = new JsonObject();
                    page.addProperty("title", title);
                    page.addProperty("url", "https://" + host + "/wiki/"
                            + URLEncoder.encode(title.replace(' ', '_'), "UTF-8"));
                    page.addProperty("extract", extract.length() > budget
                            ? extract.substring(0, budget) + "..." : extract);
                    pages.add(page);
                } catch (Throwable ignored) {
                }
                if (pages.size() >= 2) break;
            }
            JsonObject result = new JsonObject();
            result.addProperty("ok", true);
            result.addProperty("query", clean);
            result.addProperty("wiki", host);
            result.add("pages", pages);
            result.addProperty("count", pages.size());
            cache(key, result);
            return result;
        } catch (Throwable t) {
            return error("wiki_failed", "Wiki lookup failed: " + t);
        }
    }

    private JsonObject searchDuckDuckGo(String query, int limit) throws Exception {
        String url = "https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query, "UTF-8");
        String html = get(url, "text/html");
        JsonArray results = new JsonArray();
        Matcher matcher = DDG_RESULT.matcher(html);
        while (matcher.find() && results.size() < limit) {
            String href = decodeDuckLink(unescape(matcher.group(1)));
            String title = cleanText(matcher.group(2));
            String snippet = matcher.group(3) != null ? cleanText(matcher.group(3)) : "";
            if (title.isEmpty() || href.isEmpty() || href.startsWith("https://duckduckgo.com/y.js")) continue;
            JsonObject item = new JsonObject();
            item.addProperty("title", title);
            item.addProperty("url", href);
            item.addProperty("snippet", snippet);
            results.add(item);
        }
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("query", query);
        result.addProperty("engine", "duckduckgo");
        result.addProperty("count", results.size());
        result.add("results", results);
        return result;
    }

    private JsonObject searchMediaWiki(String host, String query, int limit) throws Exception {
        String url = "https://" + host + "/api.php?action=query&list=search&srsearch="
                + URLEncoder.encode(query, "UTF-8") + "&utf8=1&format=json&srlimit=" + limit;
        JsonObject json = new JsonParser().parse(get(url, "application/json")).getAsJsonObject();
        JsonArray results = new JsonArray();
        if (json.has("query") && json.getAsJsonObject("query").has("search")) {
            for (JsonElement element : json.getAsJsonObject("query").getAsJsonArray("search")) {
                JsonObject hit = element.getAsJsonObject();
                JsonObject item = new JsonObject();
                item.addProperty("title", hit.has("title") ? hit.get("title").getAsString() : "");
                item.addProperty("snippet", cleanText(hit.has("snippet") ? hit.get("snippet").getAsString() : ""));
                item.addProperty("url", "https://" + host + "/wiki/"
                        + URLEncoder.encode(item.get("title").getAsString().replace(' ', '_'), "UTF-8"));
                results.add(item);
            }
        }
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("query", query);
        result.addProperty("engine", host);
        result.addProperty("count", results.size());
        result.add("results", results);
        return result;
    }

    private String wikiExtract(String host, String title) throws Exception {
        String url = "https://" + host + "/api.php?action=query&prop=extracts&explaintext=1&redirects=1"
                + "&format=json&titles=" + URLEncoder.encode(title, "UTF-8");
        JsonObject json = new JsonParser().parse(get(url, "application/json")).getAsJsonObject();
        if (!json.has("query") || !json.getAsJsonObject("query").has("pages")) return "";
        JsonObject pages = json.getAsJsonObject("query").getAsJsonObject("pages");
        for (java.util.Map.Entry<String, JsonElement> entry : pages.entrySet()) {
            JsonObject page = entry.getValue().getAsJsonObject();
            if (page.has("extract")) return page.get("extract").getAsString();
        }
        return "";
    }

    private static String get(String url, String accept) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(6_000);
        connection.setReadTimeout(10_000);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Accept", accept);
        connection.setInstanceFollowRedirects(true);
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("HTTP " + code);
        }
        try (InputStream stream = connection.getInputStream()) {
            byte[] buffer = new byte[8192];
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            int read;
            int total = 0;
            while ((read = stream.read(buffer)) >= 0) {
                total += read;
                if (total > MAX_BYTES) break;
                out.write(buffer, 0, read);
            }
            String encoding = connection.getContentEncoding();
            if ("gzip".equalsIgnoreCase(encoding)) {
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }

    static String htmlToText(String html) {
        if (html == null) return "";
        String withoutScripts = SCRIPT_STYLE.matcher(html).replaceAll(" ");
        String withBreaks = withoutScripts
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</(p|div|li|h[1-6]|tr)>", "\n");
        String text = TAG.matcher(withBreaks).replaceAll(" ");
        text = unescape(text);
        return text.replaceAll("[\\t\\x0B\\f\\r]+", " ")
                .replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .replaceAll(" {2,}", " ")
                .trim();
    }

    private static String extractTitle(String html) {
        Matcher matcher = Pattern.compile("(?is)<title[^>]*>(.*?)</title>").matcher(html);
        return matcher.find() ? unescape(TAG.matcher(matcher.group(1)).replaceAll("")).trim() : "";
    }

    private static String cleanText(String html) {
        if (html == null) return "";
        return unescape(TAG.matcher(html).replaceAll(" ")).replaceAll("\\s+", " ").trim();
    }

    private static String decodeDuckLink(String href) {
        try {
            int index = href.indexOf("uddg=");
            if (index >= 0) {
                String encoded = href.substring(index + 5);
                int amp = encoded.indexOf('&');
                if (amp >= 0) encoded = encoded.substring(0, amp);
                return URLDecoder.decode(encoded, "UTF-8");
            }
            return href;
        } catch (Exception e) {
            return href;
        }
    }

    private static String unescape(String text) {
        if (text == null) return "";
        return text.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
                .replace("&mdash;", "-").replace("&ndash;", "-");
    }

    private static boolean isPrivateHost(String url) {
        try {
            String host = new URL(url).getHost().toLowerCase();
            return host.equals("localhost") || host.equals("::1") || host.startsWith("127.")
                    || host.startsWith("10.") || host.startsWith("192.168.")
                    || host.startsWith("169.254.") || host.startsWith("0.")
                    || host.matches("172\\.(1[6-9]|2[0-9]|3[0-1])\\..*");
        } catch (Exception e) {
            return true;
        }
    }

    private static JsonObject error(String code, String message) {
        JsonObject error = new JsonObject();
        error.addProperty("ok", false);
        error.addProperty("code", code);
        error.addProperty("error", message);
        error.addProperty("retryable", true);
        return error;
    }

    private static JsonObject copy(JsonObject value) {
        return new JsonParser().parse(value.toString()).getAsJsonObject();
    }

    private static JsonObject cached(String key) {
        synchronized (CACHE) {
            CacheEntry entry = CACHE.get(key);
            if (entry == null) return null;
            if (System.currentTimeMillis() - entry.createdAt > CACHE_TTL_MS) {
                CACHE.remove(key);
                return null;
            }
            return copy(entry.value);
        }
    }

    private static void cache(String key, JsonObject value) {
        synchronized (CACHE) {
            CACHE.put(key, new CacheEntry(copy(value), System.currentTimeMillis()));
        }
    }

    private static final class CacheEntry {
        final JsonObject value;
        final long createdAt;

        CacheEntry(JsonObject value, long createdAt) {
            this.value = value;
            this.createdAt = createdAt;
        }
    }
}
