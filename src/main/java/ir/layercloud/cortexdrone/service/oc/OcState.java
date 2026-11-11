package ir.layercloud.cortexdrone.service.oc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Read-only snapshot of an OpenComputers drone's machine, component network and
 * callback surface.
 *
 * <p>This is data only: it never starts, stops or mutates the machine. Actions
 * own every write.</p>
 */
@SuppressWarnings("unused")
public final class OcState {
    private final boolean available;
    private final String address;
    private final String machineState;
    private final boolean running;
    private final boolean paused;
    private final int components;
    private final int maxComponents;
    private final double energy;
    private final double energyCapacity;
    private final int tier;
    private final String statusText;
    private final int lightColor;
    private final List<String> componentNames;
    private final List<OcMethod> methods;
    private final String error;

    private OcState(boolean available, String address, String machineState, boolean running,
                    boolean paused, int components, int maxComponents, double energy,
                    double energyCapacity, int tier, String statusText, int lightColor,
                    List<String> componentNames, List<OcMethod> methods, String error) {
        this.available = available;
        this.address = address;
        this.machineState = machineState;
        this.running = running;
        this.paused = paused;
        this.components = components;
        this.maxComponents = maxComponents;
        this.energy = energy;
        this.energyCapacity = energyCapacity;
        this.tier = tier;
        this.statusText = statusText;
        this.lightColor = lightColor;
        this.componentNames = componentNames != null ? componentNames : Collections.<String>emptyList();
        this.methods = methods != null ? methods : Collections.<OcMethod>emptyList();
        this.error = error;
    }

    public static OcState unavailable(String reason) {
        return new OcState(false, null, "unavailable", false, false, 0, 0,
                0.0, 0.0, 0, "", 0, null, null, reason);
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean available() {
        return this.available;
    }

    public String address() {
        return this.address;
    }

    public String machineState() {
        return this.machineState;
    }

    public boolean running() {
        return this.running;
    }

    public boolean paused() {
        return this.paused;
    }

    public int components() {
        return this.components;
    }

    public int maxComponents() {
        return this.maxComponents;
    }

    public double energy() {
        return this.energy;
    }

    public double energyCapacity() {
        return this.energyCapacity;
    }

    public int tier() {
        return this.tier;
    }

    public String statusText() {
        return this.statusText;
    }

    public int lightColor() {
        return this.lightColor;
    }

    public List<String> componentNames() {
        return this.componentNames;
    }

    public List<OcMethod> methods() {
        return this.methods;
    }

    public String error() {
        return this.error;
    }

    /**
     * Mutable builder so the service stays a single readable method.
     */
    public static final class Builder {
        private boolean available = true;
        private String address;
        private String machineState = "unknown";
        private boolean running;
        private boolean paused;
        private int components;
        private int maxComponents;
        private double energy;
        private double energyCapacity;
        private int tier;
        private String statusText = "";
        private int lightColor;
        private List<String> componentNames = new ArrayList<>();
        private List<OcMethod> methods = new ArrayList<>();
        private String error;

        public Builder address(String value) {
            this.address = value;
            return this;
        }

        public Builder machineState(String value) {
            this.machineState = value;
            return this;
        }

        public Builder running(boolean value) {
            this.running = value;
            return this;
        }

        public Builder paused(boolean value) {
            this.paused = value;
            return this;
        }

        public Builder components(int value) {
            this.components = value;
            return this;
        }

        public Builder maxComponents(int value) {
            this.maxComponents = value;
            return this;
        }

        public Builder energy(double value, double capacity) {
            this.energy = value;
            this.energyCapacity = capacity;
            return this;
        }

        public Builder tier(int value) {
            this.tier = value;
            return this;
        }

        public Builder statusText(String value) {
            this.statusText = value != null ? value : "";
            return this;
        }

        public Builder lightColor(int value) {
            this.lightColor = value;
            return this;
        }

        public Builder componentNames(List<String> value) {
            this.componentNames = value != null ? value : new ArrayList<String>();
            return this;
        }

        public Builder methods(List<OcMethod> value) {
            this.methods = value != null ? value : new ArrayList<OcMethod>();
            return this;
        }

        public Builder error(String value) {
            this.error = value;
            return this;
        }

        public OcState build() {
            return new OcState(this.available, this.address, this.machineState, this.running,
                    this.paused, this.components, this.maxComponents, this.energy,
                    this.energyCapacity, this.tier, this.statusText, this.lightColor,
                    this.componentNames, this.methods, this.error);
        }
    }

    /**
     * Component map keys, sorted for stable output.
     */
    public static List<String> sortedKeys(Map<String, String> components) {
        List<String> keys = new ArrayList<>(components.keySet());
        Collections.sort(keys);
        return keys;
    }
}
