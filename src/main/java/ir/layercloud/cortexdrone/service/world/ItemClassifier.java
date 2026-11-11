package ir.layercloud.cortexdrone.service.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import lombok.experimental.UtilityClass;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import java.util.List;

/**
 * High-level block, item, container, and entity classifier facade backed by
 * dynamic Forge runtime introspection (RegistryIntrospector) and word-boundary
 * lexical scoring (LexicalItemMatcher).
 */
@UtilityClass
public final class ItemClassifier {

    public static List<String> getOreDictNames(ItemStack stack) {
        return RegistryIntrospector.getOreDictNames(stack);
    }

    public static List<String> getOreDictNames(Block block, int meta) {
        return RegistryIntrospector.getOreDictNames(block, meta);
    }

    public static boolean isLiquidBlock(Block b) {
        return RegistryIntrospector.isFluidBlock(b);
    }

    /**
     * Lava is a fluid, but it must never be a flight corridor.
     */
    public static boolean isDangerousLiquid(Block b) {
        if (b == null) return false;
        Material m = b.getMaterial();
        return m == Material.lava
                || b == net.minecraft.init.Blocks.lava
                || b == net.minecraft.init.Blocks.flowing_lava;
    }

    public static boolean isReplaceableBlock(World world, int x, int y, int z) {
        if (!WorldBounds.inWorld(y)) return false;
        if (world.isAirBlock(x, y, z)) return true;
        Block b = world.getBlock(x, y, z);
        if (b == null || isLiquidBlock(b)) return true;
        Material m = b.getMaterial();
        return m != null && m.isReplaceable();
    }

    public static boolean isPassableVoxel(World world, int bx, int by, int bz) {
        if (!WorldBounds.inWorld(by)) return false;
        // Navigation must not silently generate chunks while the player-online
        // policy is closed. ChunkAccess lets the installed chunk manager load
        // on demand when the policy is open; otherwise the voxel is unavailable.
        // Client worlds keep their normal loaded-chunk reads.
        if (!world.isRemote && !ChunkAccess.ensureBlockLoaded(world, bx, bz)) return false;
        if (world.isAirBlock(bx, by, bz)) return true;
        Block b = world.getBlock(bx, by, bz);
        if (b == null) return true;
        // Both water and lava are flight-passable: a drone must be able to
        // escape a lava-bound cave. Lava is priced very high in A* and is
        // rejected as a standoff by the callers that care about hovering.
        if (isLiquidBlock(b)) return true;
        if (b.getBlocksMovement(world, bx, by, bz)) return true;
        return b.getCollisionBoundingBoxFromPool(world, bx, by, bz) == null;
    }

    private static boolean isProtectedTileEntity(World world, int x, int y, int z) {
        TileEntity te = world.getTileEntity(x, y, z);
        if (te == null) return false;
        if (RegistryIntrospector.isFurnaceTile(world, te)) return true;
        String cls = te.getClass().getName().toLowerCase();
        if (cls.contains("opencomputers") || cls.contains("li.cil.oc")) return true;
        // Any inventory tile is property, empty or not. The old code returned
        // false for an empty container, so automatic tunnelling happily broke
        // through an empty crate or chest.
        if (te instanceof IInventory) return true;
        // A tile entity is not automatically property. Many decorative/modded
        // stone and ore blocks carry a tile entity for rendering or drops; the
        // old unconditional `return true` made them undiggable, which is what
        // produced "no tunnel direction found" against HBM stone resources.
        // Keep protecting things that are clearly containers or machines.
        return cls.contains("chest") || cls.contains("crate") || cls.contains("barrel")
                || cls.contains("cabinet") || cls.contains("container")
                || cls.contains("machine") || cls.contains("tank")
                || cls.contains("drawer") || cls.contains("hopper")
                || cls.contains("safe") || cls.contains("spawner");
    }

    public static boolean isMineableBlock(World world, int x, int y, int z) {
        if (!WorldBounds.inWorld(y)) return false;
        if (world.isAirBlock(x, y, z)) return false;
        Block b = world.getBlock(x, y, z);
        if (b == null || b == Blocks.air || b == Blocks.bedrock) return false;
        if (isLiquidBlock(b)) return false;
        if (b.getBlockHardness(world, x, y, z) < 0.0f) return false;
        // A chest whose tile entity is not loaded yet is still a chest.
        if (b instanceof net.minecraft.block.BlockChest
                || b instanceof net.minecraft.block.BlockEnderChest) return false;
        return !isProtectedTileEntity(world, x, y, z);
    }

    public static boolean isProtectedFixture(World world, int x, int y, int z) {
        if (!WorldBounds.inWorld(y)) return true;
        Block b = world.getBlock(x, y, z);
        if (b == Blocks.bedrock) return true;
        if (isProtectedTileEntity(world, x, y, z)) return true;
        return b != null && !world.isAirBlock(x, y, z) && b.getBlockHardness(world, x, y, z) < 0.0f;
    }

    public static boolean isOreBlock(Block b, int meta) {
        return RegistryIntrospector.isOreBlock(b, meta);
    }

    public static boolean isLogBlock(Block b) {
        return RegistryIntrospector.isLogBlock(b);
    }

    public static boolean isLeafBlock(Block b) {
        return RegistryIntrospector.isLeafBlock(b);
    }

    public static boolean isCropOrReedBlock(Block b) {
        return RegistryIntrospector.isCropOrPlantBlock(b);
    }

    public static boolean isLikelyUndergroundQuery(String filter) {
        return RegistryIntrospector.isLikelyUndergroundQuery(filter);
    }

    public static String humanizeUnlocalizedName(String raw, String reg) {
        String s = (raw != null && !raw.isEmpty()) ? raw : (reg != null ? reg : "Block");
        int colon = s.indexOf(':');
        if (colon >= 0 && colon < s.length() - 1) s = s.substring(colon + 1);
        if (s.startsWith("tile.")) s = s.substring(5);
        if (s.startsWith("item.")) s = s.substring(5);
        if (s.startsWith("block.")) s = s.substring(6);
        if (s.endsWith(".name")) s = s.substring(0, s.length() - 5);
        int dot = s.lastIndexOf('.');
        if (dot >= 0 && dot < s.length() - 1) s = s.substring(dot + 1);
        List<String> words = LexicalItemMatcher.tokenize(s);
        if (words.isEmpty()) return "Block";
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0)));
            if (w.length() > 1) sb.append(w.substring(1));
        }
        return sb.toString();
    }

    /**
     * @see RegistryNames#blockDisplayName(Block, int)
     */
    public static String getBlockDisplayName(Block b, int meta) {
        return RegistryNames.blockDisplayName(b, meta);
    }

    /**
     * @see RegistryNames#blockIdentifier(Block, int)
     */
    public static String getBlockIdentifier(Block b, int meta) {
        return RegistryNames.blockIdentifier(b, meta);
    }

    public static String getBlockIdentifier(Block b) {
        return RegistryNames.blockIdentifier(b, 0);
    }

    /**
     * @see RegistryNames#itemIdentifier(ItemStack)
     */
    public static String getItemIdentifier(ItemStack st) {
        return RegistryNames.itemIdentifier(st);
    }

    public static String resolveHeldFilter(EntityPlayerMP player, String rawFilter) {
        if (rawFilter == null) return "any";
        String f = rawFilter.trim();
        String lower = f.toLowerCase();
        if (lower.equals("held") || lower.equals("selected") || lower.equals("selected block")
                || lower.equals("held block") || lower.equals("held item") || lower.equals("hand") || lower.equals("equipped")) {
            if (player != null) {
                ItemStack held = player.getCurrentEquippedItem();
                if (held != null && held.stackSize > 0 && held.getItem() != null) {
                    return held.getDisplayName() + ":" + held.getItemDamage();
                }
            }
            return "any";
        }
        return f;
    }

    private static String stripNegationPrefix(String clause) {
        if (clause.startsWith("!")) return clause.substring(1).trim();
        if (clause.startsWith("not ")) return clause.substring(4).trim();
        if (clause.startsWith("non ")) return clause.substring(4).trim();
        if (clause.startsWith("non-")) return clause.substring(4).trim();
        if (clause.startsWith("except ")) return clause.substring(7).trim();
        if (clause.startsWith("without ")) return clause.substring(8).trim();
        return null;
    }

    public static int scoreBlockOrItemMatch(String displayName, String fullId, int meta, String rawFilter) {
        if (rawFilter == null) return 0;
        String raw = rawFilter.trim().toLowerCase();
        if (raw.isEmpty()) return 0;

        if (raw.contains("|") || raw.contains(",") || stripNegationPrefix(raw) != null) {
            String[] clauses = raw.split("[|,]+");
            boolean hasPositive = false;
            int bestPositiveScore = 0;
            String disp = (displayName != null ? displayName : "").trim().toLowerCase();
            String id = (fullId != null ? fullId : "").toLowerCase();
            for (String rawClause : clauses) {
                String clause = rawClause.trim();
                if (clause.isEmpty()) continue;
                String neg = stripNegationPrefix(clause);
                if (neg != null) {
                    if (neg.endsWith(" blocks")) neg = neg.substring(0, neg.length() - 7).trim();
                    if (!neg.isEmpty()) {
                        if (scoreSingleClause(displayName, fullId, meta, neg) > 0
                                || (!neg.contains(":") && (disp.contains(neg) || id.contains(neg)))) {
                            return 0;
                        }
                    }
                } else {
                    hasPositive = true;
                    if (clause.equals("all") || clause.equals("any") || clause.equals("*") || clause.equals("solid") || clause.equals("block")) {
                        bestPositiveScore = Math.max(bestPositiveScore, 50);
                    } else {
                        int s = scoreSingleClause(displayName, fullId, meta, clause);
                        if (s > bestPositiveScore) bestPositiveScore = s;
                    }
                }
            }
            return hasPositive ? bestPositiveScore : 50;
        }
        return scoreSingleClause(displayName, fullId, meta, raw);
    }

    /**
     * One filter clause: lexical display-name/registry match first (cheap and
     * exact), then the OreDictionary fallback so a player word like "tin"
     * still reaches a mod block named "Cassiterite". An explicit tag filter
     * ({@code #oreTin}, {@code oredict:...}) always decides on its own.
     */
    private static int scoreSingleClause(String displayName, String fullId, int meta, String clause) {
        if (OreDictResolver.hasExplicitPrefix(clause)) {
            return OreDictResolver.scoreIdentifierTagMatch(fullId, clause);
        }
        int lexical = LexicalItemMatcher.scoreSingleMatch(displayName, fullId, meta, clause);
        if (lexical > 0) return lexical;
        return OreDictResolver.scoreIdentifierTagMatch(fullId, clause);
    }

    public static boolean matchesBlockOrItemName(String displayName, String fullId, int meta, String rawFilter) {
        return scoreBlockOrItemMatch(displayName, fullId, meta, rawFilter) > 0;
    }

    public static boolean matchesMineFilter(Block b, int meta, String filter) {
        if (b == null || filter == null) return false;
        String raw = filter.trim().toLowerCase();
        if (raw.isEmpty()) return false;

        if (raw.contains("|") || raw.contains(",") || stripNegationPrefix(raw) != null) {
            String[] clauses = raw.split("[|,]+");
            boolean hasPositive = false;
            boolean anyPositiveMatched = false;
            String disp = getBlockDisplayName(b, meta).toLowerCase();
            String id = getBlockIdentifier(b, meta);
            for (String rawClause : clauses) {
                String clause = rawClause.trim();
                if (clause.isEmpty()) continue;
                String neg = stripNegationPrefix(clause);
                if (neg != null) {
                    if (neg.endsWith(" blocks")) neg = neg.substring(0, neg.length() - 7).trim();
                    if (!neg.isEmpty()) {
                        if (MineFilter.matches(b, meta, neg)
                                || (!neg.contains(":") && (disp.contains(neg) || id.contains(neg)))) {
                            return false;
                        }
                    }
                } else {
                    hasPositive = true;
                    if (clause.equals("all") || clause.equals("any") || clause.equals("*") || clause.equals("solid")) {
                        anyPositiveMatched = true;
                    } else if (MineFilter.matches(b, meta, clause)) {
                        anyPositiveMatched = true;
                    }
                }
            }
            return !hasPositive || anyPositiveMatched;
        }
        return MineFilter.matches(b, meta, raw);
    }

    public static boolean isFurnaceTile(World world, TileEntity te) {
        return RegistryIntrospector.isFurnaceTile(world, te);
    }

    public static boolean isStorageChestTile(World world, TileEntity te) {
        return RegistryIntrospector.isStorageContainerTile(world, te);
    }

    public static boolean isHostileOrMutantEntity(EntityLivingBase el) {
        return EntityClassifier.isHostile(el);
    }

    /**
     * @see EntityClassifier#matches(EntityLivingBase, String)
     */
    public static boolean matchesEntityFilter(EntityLivingBase el, String filter) {
        return EntityClassifier.matches(el, filter);
    }

    /**
     * Compact OreDictionary tag array for a block, or null when unregistered.
     */
    public static JsonArray blockOreDictJson(Block b, int meta) {
        List<String> tags = getOreDictNames(b, meta);
        if (tags.isEmpty()) return null;
        JsonArray out = new JsonArray();
        for (String tag : tags) out.add(new JsonPrimitive(tag));
        return out;
    }

    public static JsonObject itemStackToJson(int slot, ItemStack st) {
        JsonObject io = new JsonObject();
        if (slot >= 0) io.addProperty("slot", slot);
        if (st == null || st.stackSize <= 0 || st.getItem() == null) return io;
        String disp = st.getDisplayName();
        String reg = Item.itemRegistry.getNameForObject(st.getItem());
        int id = Item.getIdFromItem(st.getItem());
        int meta = st.getItemDamage();
        io.addProperty("name", disp != null ? disp : "");
        io.addProperty("displayName", disp != null ? disp : "");
        io.addProperty("registryName", reg != null ? reg : "");
        io.addProperty("item", reg != null ? reg : "");
        io.addProperty("id", id);
        io.addProperty("meta", meta);
        io.addProperty("damage", meta);
        io.addProperty("count", st.stackSize);
        // Compact OreDictionary tags so scripts can match "tin ingot" against
        // oreTin/ingotTin without a per-call Java round trip.
        List<String> tags = getOreDictNames(st);
        if (!tags.isEmpty()) {
            JsonArray oreTags = new JsonArray();
            for (int i = 0; i < tags.size() && i < 6; i++) {
                oreTags.add(new JsonPrimitive(tags.get(i)));
            }
            io.add("oreDict", oreTags);
        }
        return io;
    }
}
