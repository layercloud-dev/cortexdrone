package ir.layercloud.cortexdrone.service.machine;

import com.google.gson.JsonObject;
import lombok.experimental.UtilityClass;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Reflective bridge to HBM's live in-memory recipes (GenericRecipes /
 * AnvilRecipes) used as a fallback when config JSON files are absent.
 * Package-private helper so MachineRecipeService stays under the line limit.
 */
@UtilityClass
final class MachineRecipeReflection {

    static List<MachineRecipeService.NormalizedRecipe> readLive() {
        List<MachineRecipeService.NormalizedRecipe> out = new ArrayList<>();
        try {
            Class<?> anvil = Class.forName("com.hbm.inventory.recipes.anvil.AnvilRecipes");
            java.lang.reflect.Method getCons = anvil.getMethod("getConstruction");
            List<?> cons = (List<?>) getCons.invoke(null);
            if (cons != null) {
                int ctorIndex = 0;
                for (Object recipe : cons) {
                    MachineRecipeService.NormalizedRecipe r = normalizeAnvil(recipe, ctorIndex);
                    if (r != null && !r.inputs.isEmpty() && !r.outputs.isEmpty()) out.add(r);
                    ctorIndex++;
                }
            }
            // Smithing recipes live ONLY in memory (registerSmithing); the JSON file
            // holds 1-input plate smithing but never the 2-input hot/mold pairs.
            // Discovered by class name (AnvilSmithingHotRecipe etc.), not hardcoded lists.
            try {
                java.lang.reflect.Method getSm = anvil.getMethod("getSmithing");
                List<?> smith = (List<?>) getSm.invoke(null);
                if (smith != null) {
                    for (Object recipe : smith) {
                        MachineRecipeService.NormalizedRecipe r = normalizeSmithing(recipe);
                        if (r != null && !r.inputs.isEmpty() && !r.outputs.isEmpty()) out.add(r);
                    }
                }
            } catch (Throwable ignored) {
            }
            // Dynamic discovery: iterate SerializableRecipe.recipeHandlers so
            // EVERY registered GenericRecipes subclass (not a hardcoded subset)
            // contributes its live recipeOrderedList. Handler class name -> machine
            // kind via the same alias map as JSON files, no static recipe list.
            try {
                Class<?> serCls = Class.forName("com.hbm.inventory.recipes.loader.SerializableRecipe");
                java.lang.reflect.Field handlersF = serCls.getField("recipeHandlers");
                List<?> handlers = (List<?>) handlersF.get(null);
                if (handlers != null) {
                    for (Object handler : handlers) {
                        if (handler == null) continue;
                        try {
                            java.lang.reflect.Field ordF = handler.getClass().getField("recipeOrderedList");
                            ordF.setAccessible(true);
                            Object ordVal = ordF.get(handler);
                            if (!(ordVal instanceof List)) continue;
                            List<?> ordList = (List<?>) ordVal;
                            if (ordList.isEmpty()) continue;
                            String handlerName = handler.getClass().getSimpleName();
                            String machine = MachineTileInspector.normalizeMachineAlias(handlerName);
                            if (machine.isEmpty()) machine = handlerName;
                            for (Object genRecipe : ordList) {
                                MachineRecipeService.NormalizedRecipe r = normalizeGeneric(genRecipe, machine);
                                if (r != null) out.add(r);
                            }
                            // Guard: if the first recipe normalized to nothing, the
                            // subclass uses a custom shape (e.g. Mixer/Pedestal/Cyclotron
                            // which do not extend GenericRecipe); JSON files cover those.
                        } catch (Throwable ignored) {
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            // Backstop for the three primary GenericRecipes handlers in case
            // recipeHandlers was empty before recipe registration completed.
            if (out.isEmpty()) {
                addGeneric(out, "com.hbm.inventory.recipes.AssemblyMachineRecipes", "assembly_machine");
                addGeneric(out, "com.hbm.inventory.recipes.ChemicalPlantRecipes", "chemical_plant");
                addGeneric(out, "com.hbm.inventory.recipes.BlastFurnaceRecipesNT", "blast_furnace");
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static void addGeneric(List<MachineRecipeService.NormalizedRecipe> out, String className, String machine) {
        try {
            Class<?> cls = Class.forName(className);
            java.lang.reflect.Field inst = cls.getField("INSTANCE");
            Object instObj = inst.get(null);
            java.lang.reflect.Field ord = instObj.getClass().getField("recipeOrderedList");
            List<?> ordList = (List<?>) ord.get(instObj);
            if (ordList != null) {
                for (Object genRecipe : ordList) {
                    MachineRecipeService.NormalizedRecipe r = normalizeGeneric(genRecipe, machine);
                    if (r != null) out.add(r);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static MachineRecipeService.NormalizedRecipe normalizeAnvil(Object recipe, int recipeIndex) {
        try {
            MachineRecipeService.NormalizedRecipe r = new MachineRecipeService.NormalizedRecipe();
            r.machine = "anvil";
            r.tierLower = recipe.getClass().getField("tierLower").getInt(recipe);
            r.tierUpper = recipe.getClass().getField("tierUpper").getInt(recipe);
            Object ov = recipe.getClass().getField("overlay").get(recipe);
            r.overlay = ov != null ? ov.toString() : "";
            List<?> inputs = (List<?>) recipe.getClass().getField("input").get(recipe);
            List<?> outputs = (List<?>) recipe.getClass().getField("output").get(recipe);
            if (inputs != null) {
                for (Object a : inputs) {
                    JsonObject io = normalizeAStack(a);
                    if (io != null) r.inputs.add(io);
                }
            }
            if (outputs != null) {
                for (Object a : outputs) {
                    JsonObject io = normalizeAnvilOutput(a);
                    if (io != null) r.outputs.add(io);
                }
            }
            if (!r.outputs.isEmpty()) {
                r.displayName = r.outputs.get(0).has("displayName") ? r.outputs.get(0).get("displayName").getAsString() : "anvil output";
                r.name = "anvil." + r.displayName.toLowerCase().replace(' ', '_');
            }
            // Construction recipes are executed by the AnvilCraftPacket path:
            // InventoryUtil.doesPlayerHaveAStacks / giveChanceStacksToPlayer on the
            // fake player's main inventory and tag the construction index.
            r.overlay = (r.overlay == null || r.overlay.isEmpty()) ? "CONSTRUCTION" : r.overlay;
            r.constructionIndex = recipeIndex;
            return r;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static MachineRecipeService.NormalizedRecipe normalizeSmithing(Object recipe) {
        try {
            String cls = recipe.getClass().getName();
            boolean hot = cls.contains("Hot");
            MachineRecipeService.NormalizedRecipe r = new MachineRecipeService.NormalizedRecipe();
            r.machine = "anvil";
            java.lang.reflect.Field tierF = recipe.getClass().getField("tier");
            r.tierLower = tierF.getInt(recipe);
            r.tierUpper = -1;
            if (hot) {
                r.overlay = "SMITHING_HOT";
                r.displayName = "hot smithing";
            } else if (cls.contains("Mold")) {
                r.overlay = "SMITHING_MOLD";
                r.displayName = "mold smithing";
            } else if (cls.contains("Cyanide")) {
                r.overlay = "SMITHING_CYANIDE";
                r.displayName = "cyanide smithing";
            } else if (cls.contains("Rename")) {
                r.overlay = "SMITHING_RENAME";
                r.displayName = "rename smithing";
            } else {
                r.overlay = "SMITHING";
                r.displayName = "smithing";
            }
            java.lang.reflect.Field outF = recipe.getClass().getDeclaredField("output");
            outF.setAccessible(true);
            ItemStack outSt = (ItemStack) outF.get(recipe);
            java.lang.reflect.Field leftF = recipe.getClass().getDeclaredField("left");
            java.lang.reflect.Field rightF = recipe.getClass().getDeclaredField("right");
            leftF.setAccessible(true);
            rightF.setAccessible(true);
            Object left = leftF.get(recipe);
            Object right = rightF.get(recipe);
            JsonObject ioL = normalizeAStack(left);
            JsonObject ioR = normalizeAStack(right);
            if (ioL != null) r.inputs.add(ioL);
            if (ioR != null) r.inputs.add(ioR);
            if (outSt == null || outSt.getItem() == null) return null;
            JsonObject oo = new JsonObject();
            oo.addProperty("type", "item");
            String reg = Item.itemRegistry.getNameForObject(outSt.getItem());
            oo.addProperty("registryName", reg != null ? reg : "");
            oo.addProperty("meta", outSt.getItemDamage());
            oo.addProperty("displayName", outSt.getDisplayName());
            oo.addProperty("amount", Math.max(1, outSt.stackSize));
            oo.addProperty("chance", 1.0);
            r.outputs.add(oo);
            r.displayName = outSt.getDisplayName();
            r.name = "anvil.smithing." + r.displayName.toLowerCase().replace(' ', '_');
            return r;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static MachineRecipeService.NormalizedRecipe normalizeGeneric(Object genRecipe, String machine) {
        try {
            MachineRecipeService.NormalizedRecipe r = new MachineRecipeService.NormalizedRecipe();
            r.machine = machine;
            java.lang.reflect.Field nameF = genRecipe.getClass().getDeclaredField("name");
            nameF.setAccessible(true);
            r.name = (String) nameF.get(genRecipe);
            String nw = (String) genRecipe.getClass().getField("nameWrapper").get(genRecipe);
            r.displayName = (nw != null && !nw.isEmpty()) ? nw : r.name;
            r.duration = genRecipe.getClass().getField("duration").getInt(genRecipe);
            r.power = genRecipe.getClass().getField("power").getLong(genRecipe);
            Object[] inArr = (Object[]) genRecipe.getClass().getField("inputItem").get(genRecipe);
            if (inArr != null) {
                for (Object a : inArr) {
                    JsonObject io = normalizeAStack(a);
                    if (io != null) r.inputs.add(io);
                }
            }
            Object[] outArr = (Object[]) genRecipe.getClass().getField("outputItem").get(genRecipe);
            if (outArr != null) {
                for (Object ioObj : outArr) {
                    JsonObject io = normalizeIOutput(ioObj);
                    if (io != null) r.outputs.add(io);
                }
            }
            Object[] inFl = (Object[]) genRecipe.getClass().getField("inputFluid").get(genRecipe);
            if (inFl != null) {
                for (Object fs : inFl) {
                    JsonObject io = normalizeFluid(fs);
                    if (io != null) r.inputFluids.add(io);
                }
            }
            Object[] outFl = (Object[]) genRecipe.getClass().getField("outputFluid").get(genRecipe);
            if (outFl != null) {
                for (Object fs : outFl) {
                    JsonObject io = normalizeFluid(fs);
                    if (io != null) r.outputFluids.add(io);
                }
            }
            return r;
        } catch (Throwable ignored) {
            return null;
        }
    }

    static JsonObject normalizeAStack(Object a) {
        try {
            if (a == null) return null;
            String cls = a.getClass().getName();
            int amt = a.getClass().getField("stacksize").getInt(a);
            JsonObject o = new JsonObject();
            if (cls.contains("OreDictStack")) {
                o.addProperty("type", "dict");
                String name = (String) a.getClass().getField("name").get(a);
                o.addProperty("dict", name);
                o.addProperty("displayName", name);
            } else if (cls.contains("ComparableStack") || cls.contains("NBTStack")) {
                o.addProperty("type", "item");
                Item it = (Item) a.getClass().getField("item").get(a);
                int meta = a.getClass().getField("meta").getInt(a);
                String reg = Item.itemRegistry.getNameForObject(it);
                o.addProperty("registryName", reg != null ? reg : it.getClass().getName());
                o.addProperty("meta", meta);
                try {
                    ItemStack disp = new ItemStack(it, 1, meta);
                    o.addProperty("displayName", disp.getDisplayName());
                } catch (Throwable ignored) {
                    o.addProperty("displayName", reg != null ? reg : "item");
                }
            } else {
                return null;
            }
            o.addProperty("amount", Math.max(1, amt));
            return o;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static JsonObject normalizeAnvilOutput(Object a) {
        try {
            ItemStack st = (ItemStack) a.getClass().getField("stack").get(a);
            float chance = a.getClass().getField("chance").getFloat(a);
            if (st == null || st.getItem() == null) return null;
            JsonObject o = new JsonObject();
            o.addProperty("type", "item");
            String reg = Item.itemRegistry.getNameForObject(st.getItem());
            o.addProperty("registryName", reg != null ? reg : "");
            o.addProperty("meta", st.getItemDamage());
            o.addProperty("displayName", st.getDisplayName());
            o.addProperty("amount", Math.max(1, st.stackSize));
            o.addProperty("chance", chance);
            return o;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static JsonObject normalizeIOutput(Object o) {
        try {
            JsonObject best = null;
            float bestChance = -1.0f;
            try {
                Object all = o.getClass().getMethod("getAllPossibilities").invoke(o);
                Object[] poss = (Object[]) all;
                if (poss != null) {
                    for (Object p : poss) {
                        if (!(p instanceof ItemStack)) continue;
                        ItemStack cand = (ItemStack) p;
                        if (cand.getItem() == null) continue;
                        float ch = 1.0f;
                        try {
                            java.lang.reflect.Field cf = o.getClass().getField("chance");
                            ch = cf.getFloat(o);
                        } catch (Throwable ignored) {
                        }
                        if (best == null || ch > bestChance) {
                            JsonObject io = new JsonObject();
                            io.addProperty("type", "item");
                            String reg = Item.itemRegistry.getNameForObject(cand.getItem());
                            io.addProperty("registryName", reg != null ? reg : "");
                            io.addProperty("meta", cand.getItemDamage());
                            io.addProperty("displayName", cand.getDisplayName());
                            io.addProperty("amount", Math.max(1, cand.stackSize));
                            io.addProperty("chance", ch);
                            best = io;
                            bestChance = ch;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            if (best != null) return best;
            Object single = o.getClass().getMethod("getSingle").invoke(o);
            if (!(single instanceof ItemStack)) return null;
            ItemStack st = (ItemStack) single;
            if (st.getItem() == null) return null;
            JsonObject io = new JsonObject();
            io.addProperty("type", "item");
            String reg = Item.itemRegistry.getNameForObject(st.getItem());
            io.addProperty("registryName", reg != null ? reg : "");
            io.addProperty("meta", st.getItemDamage());
            io.addProperty("displayName", st.getDisplayName());
            io.addProperty("amount", Math.max(1, st.stackSize));
            io.addProperty("chance", 1.0);
            return io;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static JsonObject normalizeFluid(Object fs) {
        try {
            Object type = fs.getClass().getField("type").get(fs);
            int fill = fs.getClass().getField("fill").getInt(fs);
            JsonObject o = new JsonObject();
            String typeName;
            try {
                typeName = (String) type.getClass().getMethod("getName").invoke(type);
            } catch (Throwable ignored) {
                typeName = type.toString();
            }
            o.addProperty("fluid", typeName);
            o.addProperty("amount", fill);
            return o;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
