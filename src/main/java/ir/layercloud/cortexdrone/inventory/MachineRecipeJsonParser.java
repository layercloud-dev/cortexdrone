package ir.layercloud.cortexdrone.inventory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import lombok.experimental.UtilityClass;

/**
 * JSON normalization for HBM machine recipes. Package-private helper so
 * MachineRecipeService stays under the line limit. Handles flat GenericRecipes,
 * Anvil, ArcFurnace, Mixer nested, CustomMachines, and fluid-only shapes.
 */
@UtilityClass
final class MachineRecipeJsonParser {

    static MachineRecipeService.NormalizedRecipe normalize(JsonObject o, String machine, String fileName) {
        try {
            MachineRecipeService.NormalizedRecipe r = new MachineRecipeService.NormalizedRecipe();
            r.machine = machine;
            r.file = fileName;
            r.name = o.has("name") ? o.get("name").getAsString() : fileName.replace(".json", "") + "." + r.inputs.size();
            r.displayName = o.has("nameWrapper") ? o.get("nameWrapper").getAsString() : r.name;
            if (o.has("duration")) r.duration = o.get("duration").getAsInt();
            if (o.has("power")) r.power = o.get("power").getAsLong();
            if (o.has("consumption")) r.power = Math.max(r.power, o.get("consumption").getAsLong());
            if (o.has("consumptionPerTick")) r.power = Math.max(r.power, o.get("consumptionPerTick").getAsLong());
            if (o.has("tierLower")) r.tierLower = o.get("tierLower").getAsInt();
            if (o.has("tierUpper")) r.tierUpper = o.get("tierUpper").getAsInt();
            if (o.has("overlay")) r.overlay = o.get("overlay").getAsString();
            if (o.has("outputType")) {
                JsonObject fj = parseFluidEntryJson(o.get("outputType").getAsString(), 0);
                if (fj != null) {
                    fj.remove("amount");
                    r.outputFluids.add(fj);
                }
            }
            if (o.has("recipes") && o.get("recipes").isJsonArray()) {
                JsonArray nestedArr = o.getAsJsonArray("recipes");
                List<JsonObject> nested = new ArrayList<JsonObject>();
                for (JsonElement e : nestedArr) {
                    if (e.isJsonObject()) nested.add(e.getAsJsonObject());
                }
                if (!nested.isEmpty()) {
                    for (JsonObject n : nested) {
                        MachineRecipeService.NormalizedRecipe nr = normalize(n, machine, fileName);
                        if (nr == null) continue;
                        String fluidName = o.has("outputType") ? o.get("outputType").getAsString() : "";
                        int outAmt = n.has("outputAmount") ? n.get("outputAmount").getAsInt() : 0;
                        if (!fluidName.isEmpty() && outAmt > 0) {
                            JsonObject fj = parseFluidEntryJson(fluidName, outAmt);
                            if (fj != null) {
                                fj.addProperty("isMixingOutput", true);
                                nr.outputFluids.add(fj);
                            }
                        }
                        if (nr.displayName == null || nr.displayName.isEmpty()) nr.displayName = nr.name;
                        return nr;
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
            String[] inputKeys = new String[]{"input", "input1", "input2", "ingredients", "ingredient", "toppings", "pcb", "solder", "wafer", "solidInput", "acid", "hydrogen", "stamp"};
            for (String key : inputKeys) {
                if (!o.has(key)) continue;
                JsonElement el = o.get(key);
                if (el == null || el.isJsonNull()) continue;
                if (el.isJsonArray()) {
                    JsonArray arr = el.getAsJsonArray();
                    if (arr.size() > 0 && arr.get(0).isJsonArray()) {
                        for (JsonElement e : arr) {
                            JsonObject io = parseInputEntry(e);
                            if (io != null) r.inputs.add(io);
                        }
                    } else if (arr.size() > 0 && arr.get(0).isJsonPrimitive() && !arr.get(0).getAsString().equals("item") && !arr.get(0).getAsString().equals("dict") && !arr.get(0).getAsString().equals("nbt") && !arr.get(0).getAsString().equals("dictframe")) {
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
                } else if (el.isJsonArray()) {
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
            String[] outputKeys = new String[]{"output", "output1", "output2", "output3", "output4", "output0", "outputItem", "outputItems", "outputs", "solid", "solidOutput", "byproduct", "byproducts", "outputFluid", "outputFluids", "fluidOutput"};
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
                        JsonObject fj = parseFluidEntryJson(el.getAsString(), 0);
                        if (fj != null) r.outputFluids.add(fj);
                    }
                } else if (el.isJsonArray()) {
                    JsonArray arr = el.getAsJsonArray();
                    boolean fluidLike = arr.size() >= 2 && arr.get(0).isJsonPrimitive() && arr.get(1).isJsonPrimitive() && arr.get(1).getAsJsonPrimitive().isNumber()
                        && !arr.get(0).getAsString().contains(":") && !arr.get(0).getAsString().startsWith("hbm:");
                    if (fluidLike && arr.size() <= 3 && key.equals("output") && isFluidNameHeuristic(arr.get(0).getAsString())) {
                        JsonObject fj = parseFluidEntryJson(arr.get(0).getAsString(), arr.get(1).getAsInt());
                        if (fj != null) r.outputFluids.add(fj);
                    } else if (arr.size() > 0 && arr.get(0).isJsonArray()) {
                        for (JsonElement e : arr) {
                            JsonObject io = parseOutputEntry(e);
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
                    JsonObject fj = parseFluidEntryJson(el.getAsString(), 0);
                    if (fj != null) r.outputFluids.add(fj);
                }
            }
            if (o.has("fluid") && o.get("fluid").isJsonArray()) {
                JsonObject io = parseFluidEntry(o.get("fluid"));
                if (io != null) r.inputFluids.add(io);
            }
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
                                    continue;
                                } else if (io != null) {
                                    boolean dup = false;
                                    for (JsonObject ex : r.inputs) {
                                        if (ex.has("dict") && io.has("dict") && ex.get("dict").getAsString().equals(io.get("dict").getAsString())) { dup = true; break; }
                                    }
                                    if (!dup) r.inputs.add(io);
                                }
                            }
                        }
                    }
                }
            }
            if (r.inputs.isEmpty() && r.outputs.isEmpty() && r.inputFluids.isEmpty() && r.outputFluids.isEmpty()) return null;
            if (r.displayName == null || r.displayName.isEmpty()) r.displayName = r.name;
            return r;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isFluidNameHeuristic(String s) {
        if (s == null || s.isEmpty()) return false;
        String u = s.toUpperCase();
        return u.equals(s) && !s.contains(":") && !s.contains(".") && s.length() > 2 && !s.contains(" ");
    }

    static JsonObject parseInputEntry(JsonElement el) {
        try {
            if (!el.isJsonArray()) return null;
            JsonArray a = el.getAsJsonArray();
            if (a.size() == 0) return null;
            if (a.get(0).isJsonArray()) {
                return parseInputEntry(a.get(0));
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
                JsonObject io = parseOutputEntry(a.get(0));
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

    private static JsonObject parseItemStackForOutput(JsonArray a, float chance) {
        try {
            String reg = a.get(0).getAsString();
            int amt = (a.size() > 1 && a.get(1).isJsonPrimitive() && a.get(1).getAsJsonPrimitive().isNumber())
                ? a.get(1).getAsInt() : 1;
            int meta = 0;
            if (a.size() > 2 && a.get(2).isJsonPrimitive()) {
                try {
                    meta = a.get(2).getAsInt();
                } catch (Throwable ignored) {
                    try { meta = (int) a.get(2).getAsFloat(); } catch (Throwable ignored2) { }
                }
            }
            float adjChance = chance;
            try {
                if (a.size() > 1) {
                    float maybeChance = a.get(a.size() - 1).getAsFloat();
                    if (maybeChance > 0 && maybeChance <= 1.0f) adjChance = maybeChance;
                }
            } catch (Throwable ignored) { }
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

    static JsonObject parseFluidEntryJson(String name, int amount) {
        if (name == null) return null;
        String n = name.trim();
        if (n.isEmpty() || n.equalsIgnoreCase("NONE")) return null;
        JsonObject o = new JsonObject();
        o.addProperty("fluid", n);
        o.addProperty("amount", Math.max(0, amount));
        return o;
    }

    static String displayNameFor(String reg, int meta) {
        try {
            Item item = (Item) Item.field_150901_e.func_82594_a(reg);
            if (item != null) {
                ItemStack st = new ItemStack(item, 1, meta);
                return st.func_82833_r();
            }
            Object blockObj = net.minecraft.block.Block.field_149771_c.func_82594_a(reg);
            if (blockObj instanceof net.minecraft.block.Block) {
                net.minecraft.block.Block b = (net.minecraft.block.Block) blockObj;
                Item item2 = Item.func_150898_a(b);
                if (item2 != null) {
                    ItemStack st = new ItemStack(item2, 1, meta);
                    return st.func_82833_r();
                }
            }
        } catch (Throwable ignored) {
        }
        return reg;
    }
}
