package ir.layercloud.cortexdrone.service.machine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.experimental.UtilityClass;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * JSON schema parsing for HBM machine recipes. Moved verbatim from
 * MachineRecipeService.normalizeJsonRecipe and its entry helpers so the
 * core stays under the line limit. Handles Generic / Anvil /
 * ArcFurnace / Compact / Compressor / Mixer / Coker / Electrolyser /
 * Fractions shapes, including nested Mixer recipes, pedestal-shaped
 * inputs, and fluid-only entries.
 */
@UtilityClass
final class MachineRecipeParser {

    static MachineRecipeService.NormalizedRecipe normalizeJsonRecipe(JsonObject o, String machine, String fileName) {
        try {
            MachineRecipeService.NormalizedRecipe r = new MachineRecipeService.NormalizedRecipe();
            r.machine = machine;
            r.file = fileName;
            r.name = o.has("name") ? o.get("name").getAsString() : fileName.replace(".json", "") + "." + r.inputs.size();
            r.displayName = o.has("nameWrapper") ? o.get("nameWrapper").getAsString() : r.name;
            if (o.has("duration")) r.duration = o.get("duration").getAsInt();
            if (o.has("frequency") && o.get("frequency").isJsonPrimitive() && o.get("frequency").getAsJsonPrimitive().isNumber())
                r.duration = Math.max(r.duration, o.get("frequency").getAsInt());
            if (o.has("power")) r.power = o.get("power").getAsLong();
            if (o.has("consumption")) r.power = Math.max(r.power, o.get("consumption").getAsLong());
            if (o.has("momentum") && o.get("momentum").isJsonPrimitive() && o.get("momentum").getAsJsonPrimitive().isNumber())
                r.power = Math.max(r.power, o.get("momentum").getAsLong());
            if (o.has("steam") && o.get("steam").isJsonPrimitive() && o.get("steam").getAsJsonPrimitive().isNumber())
                r.power = Math.max(r.power, o.get("steam").getAsLong());
            if (o.has("flux") && o.get("flux").isJsonPrimitive() && o.get("flux").getAsJsonPrimitive().isNumber())
                r.power = Math.max(r.power, o.get("flux").getAsLong());
            if (o.has("antimatter") && o.get("antimatter").isJsonPrimitive() && o.get("antimatter").getAsJsonPrimitive().isNumber())
                r.power = Math.max(r.power, o.get("antimatter").getAsLong());
            if (o.has("tierLower")) r.tierLower = o.get("tierLower").getAsInt();
            if (o.has("tierUpper")) r.tierUpper = o.get("tierUpper").getAsInt();
            if (o.has("overlay")) r.overlay = o.get("overlay").getAsString();
            if (o.has("consumptionPerTick")) r.power = Math.max(r.power, o.get("consumptionPerTick").getAsLong());
            if (o.has("outputType")) {
                r.outputFluids.add(parseFluidEntryJson(o.get("outputType").getAsString(), 0));
                if (r.outputFluids.get(0) != null) {
                    JsonObject fj = r.outputFluids.get(0);
                    fj.remove("amount");
                } else {
                    r.outputFluids.clear();
                }
            }
            List<JsonObject> nested = null;
            if (o.has("recipes") && o.get("recipes").isJsonArray()) {
                JsonArray nestedArr = o.getAsJsonArray("recipes");
                nested = new ArrayList<>();
                for (JsonElement e : nestedArr) {
                    if (e.isJsonObject()) nested.add(e.getAsJsonObject());
                }
                if (!nested.isEmpty()) {
                    JsonObject base = new JsonObject();
                    base.addProperty("outputType", o.has("outputType") ? o.get("outputType").getAsString() : "");
                    JsonArray inner = new JsonArray();
                    for (JsonObject n : nested) inner.add(n);
                    base.add("recipes", inner);
                    java.util.List<MachineRecipeService.NormalizedRecipe> expanded = new ArrayList<>();
                    for (JsonObject n : nested) {
                        JsonObject wrapper = new JsonObject();
                        wrapper.add("recipe", n);
                        MachineRecipeService.NormalizedRecipe nr = normalizeSingleRecipe(n, machine, fileName, wrapper);
                        if (nr != null) {
                            String fluidName = o.has("outputType") ? o.get("outputType").getAsString() : "";
                            int outAmt = n.has("outputAmount") ? n.get("outputAmount").getAsInt() : 0;
                            if (!fluidName.isEmpty() && outAmt > 0) {
                                JsonObject fj = parseFluidEntryJson(fluidName, outAmt);
                                if (fj != null) {
                                    fj.addProperty("isMixingOutput", true);
                                    nr.outputFluids.add(fj);
                                }
                            }
                            if (n.has("duration")) nr.duration = n.get("duration").getAsInt();
                            expanded.add(nr);
                        }
                    }
                    for (MachineRecipeService.NormalizedRecipe e : expanded) {
                        if (e.inputs.isEmpty() && e.outputs.isEmpty() && e.inputFluids.isEmpty() && e.outputFluids.isEmpty())
                            continue;
                        if (e.displayName == null || e.displayName.isEmpty()) e.displayName = e.name;
                        return e;
                    }
                }
            }
            if (o.has("inputItem") && o.get("inputItem").isJsonArray()) {
                for (JsonElement e : o.getAsJsonArray("inputItem")) {
                    JsonObject io = parseInputEntry(e);
                    if (io != null) r.inputs.add(io);
                }
            }
            if (o.has("inputs") && o.get("inputs").isJsonArray()) {
                for (JsonElement e : o.getAsJsonArray("inputs")) {
                    JsonObject io = parseInputEntry(e);
                    if (io != null) r.inputs.add(io);
                }
            }
            String[] inputKeys = new String[]{"input", "input1", "input2", "ingredients", "ingredient", "toppings", "pcb", "solder", "wafer", "solidInput", "acid", "hydrogen", "flux", "stamp", "particle", "gas", "gasIn", "itemIn"};
            for (String key : inputKeys) {
                if (!o.has(key)) continue;
                JsonElement el = o.get(key);
                if (el == null || el.isJsonNull()) continue;
                if (el.isJsonArray()) {
                    JsonArray arr = el.getAsJsonArray();
                    if (arr.size() > 0 && arr.get(0).isJsonArray()) {
                        for (JsonElement e : arr) {
                            // Crucible-style inner arrays are [FluidName, amount] (molten
                            // transfer, e.g. ["Iron", 16]) rather than item dicts. Route
                            // them to inputFluids so crucible recipes carry molten
                            // inputs instead of being dropped silently.
                            JsonObject fj = tryParseFluidShapedInput(e);
                            if (fj != null) {
                                r.inputFluids.add(fj);
                                continue;
                            }
                            JsonObject io = parseInputEntry(e);
                            if (io != null) r.inputs.add(io);
                        }
                    } else if (arr.size() > 0 && arr.get(0).isJsonPrimitive() && !arr.get(0).getAsString().equals("item") && !arr.get(0).getAsString().equals("dict") && !arr.get(0).getAsString().equals("nbt") && !arr.get(0).getAsString().equals("dictframe")) {
                        // fluid-typed single entry like ["BITUMEN", 16000] or ["REDMUD", 450] or pedestal 9-slot grid
                        if (arr.size() >= 2 && arr.get(1).isJsonPrimitive() && arr.get(1).getAsJsonPrimitive().isNumber()) {
                            JsonObject fj = parseFluidEntryJson(arr.get(0).getAsString(), arr.get(1).getAsInt());
                            if (fj != null) r.inputFluids.add(fj);
                        } else {
                            JsonObject io = parseInputEntry(el);
                            if (io != null) r.inputs.add(io);
                        }
                    } else {
                        JsonObject io = parseInputEntry(el);
                        if (io != null) r.inputs.add(io);
                        else {
                            for (JsonElement e : arr) {
                                JsonObject io2 = parseInputEntry(e);
                                if (io2 != null) r.inputs.add(io2);
                            }
                        }
                    }
                } else if (el.isJsonPrimitive()) {
                    String fluidName = el.getAsString();
                    if (!fluidName.isEmpty() && !fluidName.equalsIgnoreCase("NONE")) {
                        JsonObject fj = parseFluidEntryJson(fluidName, 0);
                        if (fj != null) r.inputFluids.add(fj);
                    }
                }
            }
            if (o.has("input") && o.get("input").isJsonPrimitive() && !o.get("input").isJsonNull()) {
                String fluidName = o.get("input").getAsString();
                if (!fluidName.isEmpty()) {
                    JsonObject fj = parseFluidEntryJson(fluidName, 0);
                    if (fj != null) r.inputFluids.add(fj);
                }
            }
            String[] itemInKeys = new String[]{"inputItems", "inputItem", "inputFluids", "inputFluid"};
            for (String key : itemInKeys) {
                if (!o.has(key)) continue;
                JsonElement el = o.get(key);
                if (el == null || el.isJsonNull()) continue;
                if (key.contains("Fluid")) {
                    if (el.isJsonArray()) {
                        JsonArray arr = el.getAsJsonArray();
                        if (arr.size() > 0 && arr.get(0).isJsonArray()) {
                            for (JsonElement e : arr) {
                                JsonObject io = parseFluidEntry(e);
                                if (io != null) r.inputFluids.add(io);
                            }
                        } else if (arr.size() > 0) {
                            JsonObject io = parseFluidEntry(el);
                            if (io != null) r.inputFluids.add(io);
                        }
                    }
                } else {
                    if (el.isJsonArray()) {
                        JsonArray arr = el.getAsJsonArray();
                        if (arr.size() > 0 && arr.get(0).isJsonArray()) {
                            for (JsonElement e : arr) {
                                JsonObject io = parseInputEntry(e);
                                if (io != null) r.inputs.add(io);
                            }
                        } else if (arr.size() > 0) {
                            JsonObject io = parseInputEntry(el);
                            if (io != null) r.inputs.add(io);
                        }
                    }
                }
            }
            String[] outputKeys = new String[]{"output", "output1", "output2", "output3", "output4", "output0", "outputItem", "outputItems", "outputs", "solid", "solidOutput", "byproduct", "byproducts", "outputFluid", "outputFluids", "fluidOutput", "byproducts"};
            for (String key : outputKeys) {
                if (!o.has(key)) continue;
                JsonElement el = o.get(key);
                if (el == null || el.isJsonNull()) continue;
                if (key.contains("Fluid") || key.equals("fluidOutput")) {
                    if (el.isJsonArray()) {
                        JsonArray arr = el.getAsJsonArray();
                        if (arr.size() > 0 && arr.get(0).isJsonArray()) {
                            for (JsonElement e : arr) {
                                JsonObject io = parseFluidEntry(e);
                                if (io != null) r.outputFluids.add(io);
                            }
                        } else if (arr.size() > 0) {
                            JsonObject io = parseFluidEntry(el);
                            if (io != null) r.outputFluids.add(io);
                        }
                    } else if (el.isJsonPrimitive()) {
                        String fluidName = el.getAsString();
                        JsonObject fj = parseFluidEntryJson(fluidName, 0);
                        if (fj != null) r.outputFluids.add(fj);
                    }
                } else if (el.isJsonArray()) {
                    JsonArray arr = el.getAsJsonArray();
                    boolean fluidLike = arr.size() >= 2 && arr.get(0).isJsonPrimitive() && arr.get(1).isJsonPrimitive() && arr.get(1).getAsJsonPrimitive().isNumber()
                            && !arr.get(0).getAsString().contains(":") && !arr.get(0).getAsString().startsWith("hbm:") && !arr.get(0).getAsString().startsWith("minecraft:");
                    // Fluid outputs occur under output/output1..output4 (electrolyser,
                    // fractions, crucible molten, hydrotreating): the bare name plus
                    // mB amount is a FluidStack, not an ItemStack. Item outputs always
                    // carry a modid colon, so the heuristic is safe on all output keys.
                    if (fluidLike && arr.size() <= 3 && isFluidNameHeuristic(arr.get(0).getAsString())) {
                        JsonObject fj = parseFluidEntryJson(arr.get(0).getAsString(), arr.get(1).getAsInt());
                        if (fj != null) r.outputFluids.add(fj);
                    } else if (arr.size() > 0 && arr.get(0).isJsonArray()) {
                        for (JsonElement e : arr) {
                            // Crucible molten outputs pair each inner array with a
                            // fluid amount (["Steel", 16]); detect before item parse.
                            JsonObject fj = tryParseFluidShapedInput(e);
                            if (fj != null) {
                                r.outputFluids.add(fj);
                                continue;
                            }
                            JsonObject io = parseOutputEntry(e);
                            if (io != null) r.outputs.add(io);
                        }
                    } else if (arr.size() > 0 && arr.get(0).isJsonPrimitive() && arr.get(0).getAsString().equals("single")) {
                        JsonObject io = parseOutputEntry(el);
                        if (io != null) r.outputs.add(io);
                    } else if (arr.size() > 0 && arr.get(0).isJsonArray()) {
                        JsonObject io = parseOutputEntry(el);
                        if (io != null) r.outputs.add(io);
                    } else if (arr.size() > 0 && arr.get(0).isJsonPrimitive() && !arr.get(0).getAsString().contains(":")) {
                        // Bare-material output [name, quanta] is molten-fluid
                        // transfer (e.g. Rotary ["Steel",72], Crucible molten).
                        // Item outputs always carry a modid colon, so route bare
                        // tokens to outputFluids instead of dropping them.
                        if (isFluidNameHeuristic(arr.get(0).getAsString()) && arr.size() >= 2 && arr.get(1).isJsonPrimitive() && arr.get(1).getAsJsonPrimitive().isNumber()) {
                            JsonObject fj = parseFluidEntryJson(arr.get(0).getAsString(), arr.get(1).getAsInt());
                            if (fj != null) r.outputFluids.add(fj);
                        } else {
                            JsonObject io = parseOutputEntry(el);
                            if (io != null) r.outputs.add(io);
                        }
                    } else {
                        JsonObject io = parseOutputEntry(el);
                        if (io != null) r.outputs.add(io);
                        else {
                            for (JsonElement e : arr) {
                                JsonObject io2 = parseOutputEntry(e);
                                if (io2 != null) r.outputs.add(io2);
                            }
                        }
                    }
                } else if (el.isJsonPrimitive()) {
                    String fluidName = el.getAsString();
                    JsonObject fj = parseFluidEntryJson(fluidName, 0);
                    if (fj != null) r.outputFluids.add(fj);
                }
            }
            if (o.has("fluid") && o.get("fluid").isJsonArray()) {
                JsonElement fe = o.get("fluid");
                JsonArray fa = fe.getAsJsonArray();
                if (fa.size() > 0 && fa.get(0).isJsonArray()) {
                    for (JsonElement e : fa) {
                        JsonObject io = parseFluidEntry(e);
                        if (io != null) r.inputFluids.add(io);
                    }
                } else {
                    JsonObject io = parseFluidEntry(fe);
                    if (io != null) r.inputFluids.add(io);
                }
            }
            if (o.has("fluidOutput") && o.get("fluidOutput").isJsonArray()) {
                JsonElement fe = o.get("fluidOutput");
                JsonArray fa = fe.getAsJsonArray();
                if (fa.size() > 0 && fa.get(0).isJsonArray()) {
                    for (JsonElement e : fa) {
                        JsonObject io = parseFluidEntry(e);
                        if (io != null) r.outputFluids.add(io);
                    }
                } else {
                    JsonObject io = parseFluidEntry(fe);
                    if (io != null) r.outputFluids.add(io);
                }
            }
            if (o.has("fluidIn") && o.get("fluidIn").isJsonArray()) {
                JsonElement fe = o.get("fluidIn");
                JsonArray fa = fe.getAsJsonArray();
                if (fa.size() > 0 && fa.get(0).isJsonArray()) {
                    for (JsonElement e : fa) {
                        JsonObject io = parseFluidEntry(e);
                        if (io != null) r.inputFluids.add(io);
                    }
                } else {
                    JsonObject io = parseFluidEntry(fe);
                    if (io != null) r.inputFluids.add(io);
                }
            }
            if (o.has("outputFluid") && o.get("outputFluid").isJsonArray()) {
                for (JsonElement e : o.getAsJsonArray("outputFluid")) {
                    JsonObject io = parseFluidEntry(e);
                    if (io != null) r.outputFluids.add(io);
                }
            }
            if (o.has("inputFluid") && o.get("inputFluid").isJsonArray()) {
                for (JsonElement e : o.getAsJsonArray("inputFluid")) {
                    JsonObject io = parseFluidEntry(e);
                    if (io != null) r.inputFluids.add(io);
                }
            }
            if (o.has("pedestal") || o.has("extra")) {
                // Pedestal extra is biome selector, not input; ignore.
            }
            // Pedestal 9-slot shaped input: [null, [dict...], null...] — parseInputEntry with null elements
            if (o.has("input") && o.get("input").isJsonArray()) {
                JsonArray arr = o.getAsJsonArray("input");
                for (int idx = 0; idx < arr.size(); idx++) {
                    JsonElement e = arr.get(idx);
                    if (e == null || e.isJsonNull()) continue;
                    if (e.isJsonArray() && e.getAsJsonArray().size() > 0) {
                        JsonArray cell = e.getAsJsonArray();
                        if (cell.size() > 0 && cell.get(0).isJsonPrimitive()) {
                            String kind = cell.get(0).getAsString();
                            if (kind.equals("dict") || kind.equals("item") || kind.equals("dictframe")) {
                                JsonObject io = parseInputEntry(e);
                                if (io != null && io.has("dict") && io.get("dict").getAsString().equals(kind)) {
                                } else if (io != null) {
                                    boolean dup = false;
                                    for (JsonObject ex : r.inputs) {
                                        if (ex.has("dict") && io.has("dict") && ex.get("dict").getAsString().equals(io.get("dict").getAsString())
                                                && ex.has("amount") && io.has("amount") && ex.get("amount").getAsInt() == io.get("amount").getAsInt()) {
                                            dup = true;
                                            break;
                                        }
                                    }
                                    if (!dup) r.inputs.add(io);
                                }
                            }
                        }
                    }
                }
            }
            if (r.inputs.isEmpty() && r.outputs.isEmpty() && r.inputFluids.isEmpty() && r.outputFluids.isEmpty())
                return null;
            if (r.displayName == null || r.displayName.isEmpty()) {
                r.displayName = r.name;
            }
            return r;
        } catch (Throwable ignored) {
            return null;
        }
    }

    static MachineRecipeService.NormalizedRecipe normalizeSingleRecipe(JsonObject o, String machine, String fileName, JsonObject wrapper) {
        return normalizeJsonRecipe(o, machine, fileName);
    }

    static boolean isFluidNameHeuristic(String s) {
        // Item outputs always carry a modid colon ("hbm:item.plate_iron");
        // a bare token without colon/dot/space is a FluidType name. FluidType
        // names appear both ALL-CAPS (REDMUD, CHEMICAL) and Capitalized
        // (Uranium, Steel, KMnO4, Thorium232), so any alphanumeric token with
        // underscores counts as fluid-shaped.
        if (s == null || s.isEmpty() || s.length() <= 2) return false;
        if (s.contains(":") || s.contains(".") || s.contains(" ")) return false;
        if (!Character.isLetter(s.charAt(0))) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_') return false;
        }
        return true;
    }

    /**
     * Detects a fluid-shaped element: {@code ["FLUIDNAME", amount]} with a
     * bare fluid name (no modid colon, heuristic-matched). Mirrors
     * SerializableRecipe handling where bare names are FluidType lookups and
     * "modid:item" names are AStack (ComparableStack/OreDictStack) entries.
     * Returns the fluid entry, or null when the element is item-shaped.
     */
    static JsonObject tryParseFluidShapedInput(JsonElement el) {
        try {
            if (el == null || !el.isJsonArray()) return null;
            JsonArray a = el.getAsJsonArray();
            if (a.size() < 2) return null;
            if (!a.get(0).isJsonPrimitive()) return null;
            if (!a.get(1).isJsonPrimitive() || !a.get(1).getAsJsonPrimitive().isNumber()) return null;
            String name = a.get(0).getAsString();
            if (name == null || name.isEmpty()) return null;
            if (name.contains(":") || name.startsWith("hbm:") || name.startsWith("minecraft:")) return null;
            if ("dict".equals(name) || "item".equals(name) || "nbt".equals(name) || "dictframe".equals(name))
                return null;
            if (!isFluidNameHeuristic(name)) return null;
            return parseFluidEntryJson(name, a.get(1).getAsInt());
        } catch (Throwable ignored) {
            return null;
        }
    }

    static JsonObject parseFluidEntryJson(String name, int amount) {
        if (name == null) return null;
        String n = name.trim();
        if (n.isEmpty() || n.equalsIgnoreCase("NONE")) return null;
        JsonObject o = new JsonObject();
        o.addProperty("fluid", n);
        o.addProperty("amount", Math.max(0, amount));
        return o;
    }

    static JsonObject parseInputEntry(JsonElement el) {
        try {
            if (!el.isJsonArray()) return null;
            JsonArray a = el.getAsJsonArray();
            if (a.size() == 0) return null;
            if (a.get(0).isJsonArray()) {
                JsonElement inner = a.get(0);
                JsonObject io = parseInputEntry(inner);
                return io;
            }
            String kind = a.get(0).isJsonPrimitive() ? a.get(0).getAsString() : "";
            JsonObject o = new JsonObject();
            if ("dict".equals(kind)) {
                String dict = a.get(1).getAsString();
                int amt = (a.size() > 2) ? a.get(2).getAsInt() : 1;
                o.addProperty("type", "dict");
                o.addProperty("dict", dict);
                o.addProperty("displayName", dict);
                o.addProperty("amount", Math.max(1, amt));
                return o;
            }
            if ("item".equals(kind) || "nbt".equals(kind)) {
                String reg = a.get(1).getAsString();
                int amt = (a.size() > 2) ? a.get(2).getAsInt() : 1;
                int meta = (a.size() > 3) ? a.get(3).getAsInt() : 0;
                String nbt = (a.size() > 4 && a.get(4).isJsonPrimitive()) ? a.get(4).getAsString() : "";
                o.addProperty("type", "item");
                o.addProperty("registryName", reg);
                o.addProperty("amount", Math.max(1, amt));
                o.addProperty("meta", meta);
                o.addProperty("displayName", displayNameFor(reg, meta));
                if (!nbt.isEmpty()) o.addProperty("nbt", nbt);
                return o;
            }
            if ("dictframe".equals(kind)) {
                String mat = a.get(1).getAsString();
                o.addProperty("type", "dictframe");
                o.addProperty("dict", mat);
                o.addProperty("displayName", mat);
                o.addProperty("amount", 1);
                return o;
            }
            if (kind.isEmpty() || kind.equals("")) {
                String reg = a.get(0).getAsString();
                int amt = (a.size() > 1) ? a.get(1).getAsInt() : 1;
                int meta = (a.size() > 2) ? a.get(2).getAsInt() : 0;
                o.addProperty("type", "item");
                o.addProperty("registryName", reg);
                o.addProperty("amount", Math.max(1, amt));
                o.addProperty("meta", meta);
                o.addProperty("displayName", displayNameFor(reg, meta));
                return o;
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    static JsonObject parseOutputEntry(JsonElement el) {
        try {
            if (!el.isJsonArray()) return null;
            JsonArray a = el.getAsJsonArray();
            if (a.size() == 0) return null;
            JsonObject o = new JsonObject();
            if (a.get(0).isJsonPrimitive() && "single".equals(a.get(0).getAsString())) {
                JsonArray inner = a.get(1).getAsJsonArray();
                return parseItemStackForOutput(inner, a.size() > 2 ? a.get(2).getAsFloat() : 1.0f);
            }
            if (a.get(0).isJsonPrimitive() && "multi".equals(a.get(0).getAsString())) {
                if (a.size() > 1 && a.get(1).isJsonArray()) {
                    JsonArray inner = a.get(1).getAsJsonArray();
                    return parseItemStackForOutput(inner, a.size() > 3 ? a.get(3).getAsFloat() : 1.0f);
                }
                return null;
            }
            if (a.get(0).isJsonArray()) {
                JsonElement inner = a.get(0);
                JsonObject io = parseOutputEntry(inner);
                if (io != null) return io;
                return parseItemStackForOutput(a, 1.0f);
            }
            if (a.get(0).isJsonPrimitive()) {
                return parseItemStackForOutput(a, 1.0f);
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    static JsonObject parseItemStackForOutput(JsonArray a, float chance) {
        try {
            String reg = a.get(0).getAsString();
            int amt = (a.size() > 1 && a.get(1).isJsonPrimitive() && a.get(1).getAsJsonPrimitive().isNumber())
                    ? a.get(1).getAsInt() : 1;
            if (a.size() > 1 && a.size() < 3 && a.get(1).isJsonPrimitive()) {
                try {
                    float f = a.get(1).getAsFloat();
                    if (f > 0 && f <= 1.0f && (int) f == 1 && a.size() == 2) {
                        amt = 1;
                    }
                } catch (Throwable eIgnored) {
                }
            }
            int meta = 0;
            if (a.size() > 2 && a.get(2).isJsonPrimitive()) {
                try {
                    meta = a.get(2).getAsInt();
                } catch (Throwable eIgnored2) {
                    if (a.size() > 2) {
                        try {
                            meta = (int) a.get(2).getAsFloat();
                        } catch (Throwable ignored2) {
                        }
                    }
                }
            }
            boolean isChance = false;
            if (a.size() > 1) {
                try {
                    float maybeChance = a.get(a.size() - 1).getAsFloat();
                    if (maybeChance > 0 && maybeChance <= 1.0f) {
                        isChance = true;
                    }
                } catch (Throwable eIgnored3) {
                }
            }
            float adjChance = isChance ? a.get(a.size() - 1).getAsFloat() : chance;
            if (a.size() == 2 && a.get(1).isJsonPrimitive()) {
                try {
                    float f = a.get(1).getAsFloat();
                    if (f > 0 && f <= 1.0f && a.size() == 2) {
                        adjChance = f;
                        amt = 1;
                    }
                } catch (Throwable eIgnored4) {
                }
            }
            JsonObject o = new JsonObject();
            o.addProperty("type", "item");
            o.addProperty("registryName", reg);
            o.addProperty("amount", Math.max(1, amt));
            o.addProperty("meta", meta);
            o.addProperty("chance", adjChance);
            o.addProperty("displayName", displayNameFor(reg, meta));
            if (a.size() > 3 && a.get(3).isJsonPrimitive()) {
                o.addProperty("nbt", a.get(3).getAsString());
            }
            return o;
        } catch (Throwable ignored) {
            return null;
        }
    }

    static JsonObject parseFluidEntry(JsonElement el) {
        try {
            if (!el.isJsonArray()) return null;
            JsonArray a = el.getAsJsonArray();
            if (a.size() == 0) return null;
            JsonObject o = new JsonObject();
            o.addProperty("fluid", a.get(0).getAsString());
            o.addProperty("amount", a.size() > 1 ? a.get(1).getAsInt() : 0);
            return o;
        } catch (Throwable ignored) {
            return null;
        }
    }

    static String displayNameFor(String reg, int meta) {
        try {
            Item item = (Item) Item.itemRegistry.getObject(reg);
            if (item != null) {
                ItemStack st = new ItemStack(item, 1, meta);
                return st.getDisplayName();
            }
            Object blockObj = net.minecraft.block.Block.blockRegistry.getObject(reg);
            if (blockObj instanceof net.minecraft.block.Block) {
                net.minecraft.block.Block b = (net.minecraft.block.Block) blockObj;
                Item item2 = Item.getItemFromBlock(b);
                if (item2 != null) {
                    ItemStack st = new ItemStack(item2, 1, meta);
                    return st.getDisplayName();
                }
            }
        } catch (Throwable ignored) {
        }
        return reg;
    }
}
