package ir.layercloud.cortexdrone.action.inventory.impl;

import ir.layercloud.cortexdrone.DroneController;

import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.inventory.CraftResult;
import ir.layercloud.cortexdrone.action.inventory.InventoryRequest;
import ir.layercloud.cortexdrone.action.inventory.InventoryResult;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.context.impl.InventoryContext;
import ir.layercloud.cortexdrone.service.inventory.CraftingService;
import ir.layercloud.cortexdrone.service.inventory.InventoryService;
import li.cil.oc.common.entity.Drone;
import lombok.RequiredArgsConstructor;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraftforge.oredict.OreDictionary;

import java.util.List;

/**
 * Single-use inventory action: craft matching items from the drone inventory.
 *
 * <p>The action owns the craft job: ingredient reservation, consumption,
 * sub-crafting and output insertion. {@link CraftingService} is only queried
 * for read-only recipe data.</p>
 */
@RequiredArgsConstructor
public final class CraftAction implements Action<InventoryContext> {
    private static final int MAX_SUBCRAFT_DEPTH = 1;

    private final CraftingService craftingService;
    private final DroneLogger logger;
    private final InventoryRequest request;

    @Override
    public void execute(InventoryContext context) {
        DroneController controller = context.controller();
        if (controller == null || controller.drone == null) {
            context.result(InventoryResult.failure("No active drone"));
            return;
        }
        String filter = this.request.getFilter() != null ? this.request.getFilter() : "planks";
        int count = this.request.getCount() > 0 ? this.request.getCount() : 1;
        CraftResult result = craft(controller, context.player(), filter, count, 0);
        if (!result.isOk()) {
            context.result(InventoryResult.failure(result.getError(),
                    "crafted", 0, "item", result.getItem()));
        } else {
            context.result(InventoryResult.success(
                    "crafted", result.getCrafted(), "item", result.getItem()));
        }
    }

    private CraftResult craft(DroneController controller, EntityPlayerMP owner,
                              String targetFilter, int wantedCount, int depth) {
        if (controller == null || controller.drone == null
                || targetFilter == null || targetFilter.trim().isEmpty()) {
            return CraftResult.failure("Invalid craft arguments");
        }
        String filter = targetFilter.trim();
        int targetTotal = Math.max(1, Math.min(256, wantedCount));
        IInventory droneInv = controller.drone.mainInventory();

        List<IRecipe> matchingRecipes = this.craftingService.rankedRecipes(filter);
        if (matchingRecipes.isEmpty()) {
            return CraftResult.failure("No crafting recipe found matching '" + filter + "'");
        }

        int totalCrafted = 0;
        String craftedName = "";

        while (totalCrafted < targetTotal) {
            boolean craftedThisPass = false;

            for (int modePass = 0; modePass < 2 && !craftedThisPass; modePass++) {
                // Strict: ingredients come from the drone inventory only. Never
                // pull from nearby chests or the player; the model does that
                // explicitly (withdraw/take, break_block, craft).
                boolean allowSubcraft = (modePass == 1);
                for (IRecipe recipe : matchingRecipes) {
                    List<List<ItemStack>> reqSlots = CraftingService.extractRecipeIngredients(recipe);
                    if (reqSlots.isEmpty()) continue;

                    int[] reservedCounts = new int[droneInv.getSizeInventory()];
                    boolean allAvailable = true;

                    for (List<ItemStack> slotOpts : reqSlots) {
                        int foundSlot = findIngredientSlot(droneInv, reservedCounts, slotOpts);
                        if (foundSlot < 0 && allowSubcraft && depth < MAX_SUBCRAFT_DEPTH
                                && !slotOpts.isEmpty()) {
                            craftMissingIngredient(droneInv, controller, owner,
                                    slotOpts, reqSlots.size(), depth);
                            foundSlot = findIngredientSlot(droneInv, reservedCounts, slotOpts);
                        }
                        if (foundSlot >= 0) {
                            reservedCounts[foundSlot]++;
                        } else {
                            allAvailable = false;
                            break;
                        }
                    }

                    if (allAvailable) {
                        consumeIngredients(droneInv, reservedCounts);
                        craftedName = insertCraftOutput(droneInv, owner, recipe);
                        totalCrafted += Math.max(1, recipe.getRecipeOutput().stackSize);
                        craftedThisPass = true;
                        break;
                    }
                }
            }

            if (!craftedThisPass) break;
        }

        if (totalCrafted <= 0) {
            return CraftResult.failure("Missing ingredients for '" + filter
                    + "' in the drone inventory; take or gather them first");
        }
        this.logger.log("INV", controller, "Crafted " + totalCrafted + "x " + craftedName,
                "filter=" + filter);
        return CraftResult.success(totalCrafted, craftedName);
    }

    private void craftMissingIngredient(IInventory droneInv, DroneController controller,
                                        EntityPlayerMP owner, List<ItemStack> options,
                                        int wanted, int depth) {
        ItemStack option = options.get(0);
        if (option == null || option.getItem() == null) return;
        int meta = option.getItemDamage() == OreDictionary.WILDCARD_VALUE
                ? 0 : option.getItemDamage();
        String subName = new ItemStack(option.getItem(), 1, meta).getDisplayName();
        craft(controller, owner, subName, wanted, depth + 1);
    }

    private static int findIngredientSlot(IInventory inventory, int[] reserved,
                                          List<ItemStack> options) {
        for (int i = 0; i < inventory.getSizeInventory(); i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            if (stack != null && (stack.stackSize - reserved[i]) > 0
                    && CraftingService.stackMatchesAnyOption(stack, options)) {
                return i;
            }
        }
        return -1;
    }

    private static void consumeIngredients(IInventory inventory, int[] reservedCounts) {
        for (int i = 0; i < reservedCounts.length; i++) {
            if (reservedCounts[i] > 0) {
                inventory.decrStackSize(i, reservedCounts[i]);
            }
        }
    }

    /**
     * Inserts the recipe output, spilling anything that does not fit to the
     * owner. Returns the crafted item name.
     */
    private static String insertCraftOutput(IInventory droneInv, EntityPlayerMP owner, IRecipe recipe) {
        ItemStack outTemplate = recipe.getRecipeOutput().copy();
        int outCount = Math.max(1, outTemplate.stackSize);
        outTemplate.stackSize = outCount;
        String craftedName = outTemplate.getDisplayName();
        InventoryService.insertIntoInventory(droneInv, outTemplate);
        if (outTemplate.stackSize > 0) {
            for (int i = droneInv.getSizeInventory() - 1; i >= 0; i--) {
                ItemStack stack = droneInv.getStackInSlot(i);
                if (stack != null && !stack.isItemEqual(outTemplate)
                        && owner != null && owner.inventory != null
                        && owner.inventory.addItemStackToInventory(stack.copy())) {
                    droneInv.setInventorySlotContents(i, null);
                    InventoryService.syncPlayerInventory(owner);
                    break;
                }
            }
            InventoryService.insertIntoInventory(droneInv, outTemplate);
        }
        if (outTemplate.stackSize > 0 && owner != null && owner.inventory != null) {
            owner.inventory.addItemStackToInventory(outTemplate);
            InventoryService.syncPlayerInventory(owner);
        }
        droneInv.markDirty();
        return craftedName;
    }
}
