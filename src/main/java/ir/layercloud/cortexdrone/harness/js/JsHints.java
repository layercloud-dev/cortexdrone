package ir.layercloud.cortexdrone.harness.js;

/**
 * Targeted, actionable advice for a failed script.
 *
 * <p>Every non-syntax failure used to return the generic "fix the JavaScript
 * and retry" line, so the live sessions retried a {@code file:} URL path, an
 * ambiguous {@code String.replace} overload and a
 * {@code ConcurrentModificationException} with no direction. The message is
 * matched here instead of inside {@code JsEngine}, which keeps the engine under
 * the modular line ceiling.</p>
 */
final class JsHints {
    static final String GENERIC = "Fix the JavaScript and retry. Use 'return' to return a value; "
            + "tools are synchronous so await is optional.";

    private JsHints() {
    }

    /**
     * Advice for a failure message, or {@code null} for a generic script error.
     */
    static String forMessage(String message) {
        String text = message != null ? message : "";
        if (text.contains("NoClassDefFoundError") || text.contains("LinkageError")) {
            return "An optional mod is missing a class that a modded block/item references. "
                    + "This is a broken dependency, not a script bug. Do not iterate "
                    + "Block.blockRegistry or Item.itemRegistry; use doc_lookup(query, mod_id), "
                    + "find_oredict or scan_blocks to find the item instead.";
        }
        if (text.contains("ConcurrentModificationException")) {
            return "A live world list changed while the script iterated it (the server tick "
                    + "spawns and removes entities). Wrap the loop in main(function(){...}) and copy "
                    + "first: var list = Java.from(world.loadedEntityList.slice(0)); then iterate the "
                    + "copy. Never call a tool that moves or breaks inside an iterator over a live "
                    + "world collection.";
        }
        if (text.contains("is not a function") && text.contains("ObjectWrapper")) {
            return "A wrapped Java object was called like a function. For a class handle use "
                    + "new C(...) after Java.type('x.Y'); wrap Java strings with String(value) "
                    + "before comparing. toPath(value)/toFile(value) convert a file: URL from a "
                    + "code source.";
        }
        if (text.contains("JavaAdapter")) {
            return "JavaAdapter was asked to implement a Java interface from JS. Construct the "
                    + "class instead: var C = Java.type('x.Y'); var o = new C(args).";
        }
        if (text.contains("InvalidPathException") || text.contains("Illegal char <:>")) {
            return "The path still contains a URL scheme. Use toFile(url) or toPath(url) "
                    + "(bound by the runtime) instead of new java.io.File(url.toString()).";
        }
        if (text.contains("is ambiguous")) {
            return "Two Java overloads matched the JS argument types. Pass the exact type: "
                    + "String(value) for a String parameter, or an int/double literal instead of "
                    + "an object.";
        }
        return null;
    }

    /**
     * The hint for a failure: the targeted advice when one exists, otherwise the
     * generic line.
     */
    static String hint(String message) {
        String specific = forMessage(message);
        return specific != null ? specific : GENERIC;
    }
}
