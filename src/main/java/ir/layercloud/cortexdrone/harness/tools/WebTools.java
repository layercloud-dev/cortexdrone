package ir.layercloud.cortexdrone.harness.tools;

import ir.layercloud.cortexdrone.harness.tool.ToolArgs;
import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.config.ModConfig;
import ir.layercloud.cortexdrone.harness.tool.ToolAffinity;
import ir.layercloud.cortexdrone.harness.tool.ToolJson;
import ir.layercloud.cortexdrone.harness.tool.ToolProvider;
import ir.layercloud.cortexdrone.harness.tool.ToolRegistry;
import ir.layercloud.cortexdrone.harness.tool.ToolSchema;
import ir.layercloud.cortexdrone.harness.tool.ToolSpec;
import ir.layercloud.cortexdrone.service.world.WebLookupService;

import java.util.Arrays;
import java.util.List;

/**
 * Internet knowledge tools. They run outside the server thread and are
 * best-effort: the model must verify game facts against the live registries
 * (doc_lookup, get_recipes) before acting on them.
 */
public final class WebTools implements ToolProvider {
    private static final List<ToolSpec> SPECS = Arrays.asList(
            ToolSpec.builder("web_search")
                    .description("Search the public web (DuckDuckGo HTML, MediaWiki fallback) for a "
                            + "Minecraft/mod/item/machine/recipe question. Returns title, url and snippet for "
                            + "each hit. Use it to find the right wiki page or mod name, then fetch the page "
                            + "with web_fetch/wiki_lookup. Do not use it for facts the game can answer "
                            + "(use doc_lookup, get_recipes, scan_blocks).")
                    .parameters(ToolSchema.object()
                            .requiredString("query", "Search query, e.g. 'GTNH industrial blast furnace casing'.")
                            .optionalInt("max_results", "Hits to return, 1..10.", 1, 10, 5))
                    .affinity(ToolAffinity.ASYNC)
                    .timeoutMs(20_000L)
                    .build(),
            ToolSpec.builder("web_fetch")
                    .description("Fetch one http(s) page and return readable text (scripts/styles/tags "
                            + "stripped, capped). Useful for wiki pages, mod documentation and changelogs. "
                            + "Private/local addresses are blocked.")
                    .parameters(ToolSchema.object()
                            .requiredString("url", "Absolute http:// or https:// URL.")
                            .optionalInt("max_chars", "Maximum characters of text to return.", 500, 20_000, 6_000))
                    .affinity(ToolAffinity.ASYNC)
                    .timeoutMs(20_000L)
                    .build(),
            ToolSpec.builder("wiki_lookup")
                    .description("Look up a Minecraft or GTNH/HBM page on the official wiki APIs and return "
                            + "the page extract. Prefer this over web_search when you know the page title. "
                            + "wiki=auto tries minecraft.wiki; use wiki=ntm for HBM Nuclear Tech.")
                    .parameters(ToolSchema.object()
                            .requiredString("query", "Page title or search phrase.")
                            .optionalEnum("wiki", "Wiki to query.", "auto", "auto", "minecraft", "ntm")
                            .optionalInt("max_chars", "Maximum characters per page extract.", 500, 12_000, 4_000))
                    .affinity(ToolAffinity.ASYNC)
                    .timeoutMs(20_000L)
                    .build()
    );

    private final ModConfig config;
    private final WebLookupService web;

    public WebTools(AIDroneManager manager) {
        this.config = manager.getConfig();
        this.web = new WebLookupService();
    }

    public static List<ToolSpec> specs() {
        return SPECS;
    }

    @Override
    public void register(ToolRegistry registry) {
        registry.bind("web_search", getClass().getSimpleName(), (context, arguments) -> {
            if (!this.config.isWebSearchEnabled()) return disabled();
            return this.web.search(arguments.has("query") ? arguments.get("query").getAsString() : "",
                    arguments.has("max_results") ? arguments.get("max_results").getAsInt() : 5);
        });
        registry.bind("web_fetch", getClass().getSimpleName(), (context, arguments) -> {
            if (!this.config.isWebSearchEnabled()) return disabled();
            return this.web.fetch(arguments.has("url") ? arguments.get("url").getAsString() : "",
                    arguments.has("max_chars") ? arguments.get("max_chars").getAsInt() : 6_000);
        });
        registry.bind("wiki_lookup", getClass().getSimpleName(), (context, arguments) -> {
            if (!this.config.isWebSearchEnabled()) return disabled();
            return this.web.wiki(arguments.has("query") ? arguments.get("query").getAsString() : "",
                    arguments.has("wiki") ? arguments.get("wiki").getAsString() : "auto",
                    arguments.has("max_chars") ? arguments.get("max_chars").getAsInt() : 4_000);
        });
    }

    private static JsonObject disabled() {
        return ToolJson.error("web_disabled", "Internet tools are disabled in config.json.",
                "Set webSearchEnabled=true to use them; otherwise rely on doc_lookup and recipes.", false);
    }
}
