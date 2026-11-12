package ir.layercloud.cortexdrone.service.machine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ir.layercloud.cortexdrone.config.ConfigDirectory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Optional hook: flat HBM recipe JSON files under
 * {@code config/hbmRecipes} (see {@link HbmDescriptor}).
 * Reads files only; it does not touch the world or the drone.
 */
public final class HbmRecipeFileHook implements MachineRecipeHook {
    @Override
    public boolean isAvailable() {
        File dir = new File(resolveConfigDirectory(), HbmDescriptor.RECIPE_DIRECTORY);
        return dir.isDirectory();
    }

    @Override
    public List<MachineRecipeService.NormalizedRecipe> load() {
        List<MachineRecipeService.NormalizedRecipe> out = new ArrayList<>();
        File dir = new File(resolveConfigDirectory(), HbmDescriptor.RECIPE_DIRECTORY);
        if (!dir.isDirectory()) return out;
        File[] files = dir.listFiles();
        if (files == null) return out;
        for (File file : files) {
            if (!file.getName().endsWith(".json")) continue;
            String machine = MachineTileInspector.recipeFileToMachine(file.getName());
            out.addAll(parseFile(file, machine));
        }
        return out;
    }

    private static List<MachineRecipeService.NormalizedRecipe> parseFile(File file, String machine) {
        List<MachineRecipeService.NormalizedRecipe> out = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            StringBuilder text = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) text.append(line);
            if (text.length() == 0) return out;
            JsonElement parsed = new JsonParser().parse(text.toString());
            if (!parsed.isJsonObject()) return out;
            JsonObject root = parsed.getAsJsonObject();
            JsonArray recipes = root.has("recipes") ? root.getAsJsonArray("recipes") : null;
            if (recipes == null) return out;
            for (JsonElement element : recipes) {
                if (!element.isJsonObject()) continue;
                MachineRecipeService.NormalizedRecipe recipe = MachineRecipeParser.normalizeJsonRecipe(
                        element.getAsJsonObject(), machine, file.getName());
                if (recipe != null) out.add(recipe);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static File resolveConfigDirectory() {
        return ConfigDirectory.resolve();
    }
}
