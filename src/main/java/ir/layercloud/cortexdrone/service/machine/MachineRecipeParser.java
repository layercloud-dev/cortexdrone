package ir.layercloud.cortexdrone.service.machine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.experimental.UtilityClass;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

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
            RecipeScalarReader.read(o, r, fileName);
            readOutputType(o, r);

            MachineRecipeService.NormalizedRecipe expanded = RecipeNestedExpander.expand(
                    o, machine, fileName, MachineRecipeParser::normalizeSingleRecipe);
            if (expanded != null) return expanded;

            RecipeInputReader.read(o, r);
            RecipeOutputReader.read(o, r);
            if (RecipeKeys.isEmptyRecipe(r)) return null;
            if (r.displayName == null || r.displayName.isEmpty()) r.displayName = r.name;
            return r;
        } catch (RuntimeException malformed) {
            // A hand-edited or truncated recipe file must not abort the whole
            // config scan, but it must be visible: the caller reports the file.
            java.util.logging.Logger.getLogger("CortexDrone").fine(
                    "Skipping malformed machine recipe in " + fileName + ": " + malformed);
            return null;
        }
    }

    /** The shared fluid {@code outputType} some mixer-style files declare. */
    private static void readOutputType(JsonObject recipe, MachineRecipeService.NormalizedRecipe out) {
        if (!recipe.has("outputType")) return;
        JsonObject fluid = parseFluidEntryJson(recipe.get("outputType").getAsString(), 0);
        if (fluid == null) return;
        // The amount comes from each nested recipe, not the outer object.
        fluid.remove("amount");
        out.outputFluids.add(fluid);
    }

    static MachineRecipeService.NormalizedRecipe normalizeSingleRecipe(JsonObject o, String machine, String fileName) {
        return normalizeJsonRecipe(o, machine, fileName);
    }

    static boolean isFluidNameHeuristic(String s) {
        // Item outputs always carry a modid colon ("hbm:item.plate_iron");
        // a bare token without colon/dot/space is a FluidType name. FluidType
        // names appear both ALL-CAPS (REDMUD, CHEMICAL) and Capitalized
        // (Uranium, Steel, KMnO4, Thorium232), so any alphanumeric token with
        // underscores counts as fluid-shaped.
        if (s == null || s.length() <= 2) return false;
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
            if (name.contains(":") || name.startsWith(HbmDescriptor.MOD_ID + ":")
                    || name.startsWith("minecraft:")) return null;
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
            if (kind.isEmpty()) {
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

    @SuppressWarnings("CatchMayIgnoreException")
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
