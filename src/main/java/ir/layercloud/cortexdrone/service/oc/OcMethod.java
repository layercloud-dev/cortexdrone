package ir.layercloud.cortexdrone.service.oc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * One invocable OpenComputers callback as reported by the host mod.
 *
 * <p>The list is discovered from the real class annotations at runtime, never
 * hardcoded, so a different OpenComputers build or an addon component exposes
 * its own methods without a mod update here.</p>
 */
public final class OcMethod {
    private final String name;
    private final String doc;
    private final boolean direct;
    private final boolean getter;
    private final boolean setter;
    private final int limit;
    private final String owner;

    public OcMethod(String name, String doc, boolean direct, boolean getter,
                    boolean setter, int limit, String owner) {
        this.name = name;
        this.doc = doc != null ? doc : "";
        this.direct = direct;
        this.getter = getter;
        this.setter = setter;
        this.limit = limit;
        this.owner = owner != null ? owner : "";
    }

    public String name() {
        return this.name;
    }

    public String doc() {
        return this.doc;
    }

    public boolean direct() {
        return this.direct;
    }

    public boolean getter() {
        return this.getter;
    }

    public boolean setter() {
        return this.setter;
    }

    public int limit() {
        return this.limit;
    }

    /**
     * Distinct class that declared the callback, for grouping and tracing.
     */
    public String owner() {
        return this.owner;
    }

    public static final Comparator<OcMethod> BY_NAME = new Comparator<OcMethod>() {
        @Override
        public int compare(OcMethod left, OcMethod right) {
            return left.name.compareTo(right.name);
        }
    };

    public static List<OcMethod> sorted(List<OcMethod> methods) {
        if (methods == null || methods.isEmpty()) return Collections.emptyList();
        List<OcMethod> copy = new ArrayList<>(methods);
        Collections.sort(copy, BY_NAME);
        return copy;
    }

    public static List<OcMethod> filter(List<OcMethod> methods, String query) {
        if (methods == null) return Collections.emptyList();
        if (query == null || query.trim().isEmpty()) return sorted(methods);
        String needle = query.trim().toLowerCase();
        List<OcMethod> matches = new ArrayList<>();
        for (OcMethod method : methods) {
            if (method.name.toLowerCase().contains(needle)
                    || method.doc.toLowerCase().contains(needle)) {
                matches.add(method);
            }
        }
        return sorted(matches);
    }
}
