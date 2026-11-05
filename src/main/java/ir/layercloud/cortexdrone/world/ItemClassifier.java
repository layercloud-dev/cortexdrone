package ir.layercloud.cortexdrone.world;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.experimental.UtilityClass;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

/**
 * High-level block, item, container, and entity classifier facade backed by
 * dynamic Forge runtime introspection (RegistryIntrospector) and word-boundary
 * lexical scoring (LexicalItemMatcher).
 */
@UtilityClass
public final class ItemClassifier {
    private static final Map<Integer, String> BLOCK_DISP_CACHE = new ConcurrentHashMap<Integer, String>();
    private static final Map<Integer, String> BLOCK_ID_CACHE = new ConcurrentHashMap<Integer, String>();

    public static List<String> getOreDictNames(ItemStack stack) {
        return RegistryIntrospector.getOreDictNames(stack);
    }

    public static List<String> getOreDictNames(Block block, int meta) {
        return RegistryIntrospector.getOreDictNames(block, meta);
    }

    public static boolean hasOreDictPrefix(ItemStack stack, String... prefixes) {
        return RegistryIntrospector.hasOreDictPrefix(stack, prefixes);
    }

    public static boolean hasOreDictPrefix(Block block, int meta, String... prefixes) {
        return RegistryIntrospector.hasOreDictPrefix(block, meta, prefixes);
    }

    public static boolean isLiquidBlock(Block b) {
        return RegistryIntrospector.isFluidBlock(b);
    }

    public static boolean isReplaceableBlock(World world, int x, int y, int z) {
        if (y <= 1 || y >= 255) return false;
        if (world.func_147437_c(x, y, z)) return true;
        Block b = world.func_147439_a(x, y, z);
        if (b == null || isLiquidBlock(b)) return true;
        Material m = b.func_149688_o();
        return m != null && m.func_76222_j();
    }

    public static boolean isPassableVoxel(World world, int bx, int by, int bz) {
        if (by <= 1 || by >= 255) return false;
        if (world.func_147437_c(bx, by, bz)) return true;
        Block b = world.func_147439_a(bx, by, bz);
        if (b == null || isLiquidBlock(b)) return true;
        if (b.func_149655_b(world, bx, by, bz)) return true;
        return b.func_149668_a(world, bx, by, bz) == null;
    }

    private static boolean isProtectedTileEntity(World world, int x, int y, int z) {
        TileEntity te = world.func_147438_o(x, y, z);
        if (te == null) return false;
        if (RegistryIntrospector.isFurnaceTile(world, te)) return true;
        String cls = te.getClass().getName().toLowerCase();
        if (cls.contains("opencomputers") || cls.contains("li.cil.oc")) return true;
        if (te instanceof IInventory) {
            IInventory inv = (IInventory) te;
            for (int i = 0; i < inv.func_70302_i_(); i++) {
                ItemStack st = inv.func_70301_a(i);
                if (st != null && st.field_77994_a > 0) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    public static boolean isMineableBlock(World world, int x, int y, int z) {
        if (y <= 1 || y >= 255) return false;
        if (world.func_147437_c(x, y, z)) return false;
        Block b = world.func_147439_a(x, y, z);
        if (b == null || b == Blocks.field_150350_a || b == Blocks.field_150357_h) return false;
        if (isLiquidBlock(b)) return false;
        if (b.func_149712_f(world, x, y, z) < 0.0f) return false;
        return !isProtectedTileEntity(world, x, y, z);
    }

    public static boolean isProtectedFixture(World world, int x, int y, int z) {
        if (y <= 1 || y >= 255) return true;
        Block b = world.func_147439_a(x, y, z);
        if (b == Blocks.field_150357_h) return true;
        if (isProtectedTileEntity(world, x, y, z)) return true;
        return b != null && !world.func_147437_c(x, y, z) && b.func_149712_f(world, x, y, z) < 0.0f;
    }

    public static boolean isOreBlock(Block b, int meta) {
        return RegistryIntrospector.isOreBlock(b, meta);
    }

    public static boolean isOreBlock(Block b) {
        return RegistryIntrospector.isOreBlock(b, 0);
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

    public static String getBlockDisplayName(Block b, int meta) {
        if (b == null || b == Blocks.field_150350_a) return "Air";
        int key = RegistryIntrospector.blockCacheKey(b, meta);
        String cached = BLOCK_DISP_CACHE.get(key);
        if (cached != null) return cached;

        String result = null;
        try {
            Item item = Item.func_150898_a(b);
            if (item != null) {
                int dmg = RegistryIntrospector.normalizeBlockMeta(b, meta);
                ItemStack st = new ItemStack(item, 1, dmg);
                String dn = st.func_82833_r();
                if (dn != null && !dn.isEmpty() && !dn.endsWith(".name") && !dn.startsWith("tile.") && !dn.startsWith("item.")) {
                    result = dn;
                }
            }
        } catch (Throwable ignored) {
        }
        if (result == null) {
            String loc = b.func_149732_F();
            if (loc != null && !loc.isEmpty() && !loc.endsWith(".name") && !loc.startsWith("tile.") && !loc.startsWith("item.")) {
                result = loc;
            } else {
                String reg = Block.field_149771_c.func_148750_c(b);
                result = humanizeUnlocalizedName(loc, reg);
            }
        }
        BLOCK_DISP_CACHE.put(key, result);
        return result;
    }

    public static String getBlockIdentifier(Block b, int meta) {
        if (b == null) return "";
        int key = RegistryIntrospector.blockCacheKey(b, meta);
        String cached = BLOCK_ID_CACHE.get(key);
        if (cached != null) return cached;

        String reg = Block.field_149771_c.func_148750_c(b);
        String unloc = b.func_149739_a();
        String loc = b.func_149732_F();
        String disp = getBlockDisplayName(b, meta);
        StringBuilder oreTags = new StringBuilder();
        for (String tag : getOreDictNames(b, meta)) {
            oreTags.append(' ').append(tag);
        }
        String result = ((reg != null ? reg : "") + " " + (reg != null ? reg + ":" + meta : "") + " "
            + (unloc != null ? unloc : "") + " " + (loc != null ? loc : "") + " "
            + disp + oreTags.toString()).toLowerCase();
        BLOCK_ID_CACHE.put(key, result);
        return result;
    }

    public static String getBlockIdentifier(Block b) {
        return getBlockIdentifier(b, 0);
    }

    public static String getItemIdentifier(ItemStack st) {
        if (st == null || st.func_77973_b() == null) return "";
        Item item = st.func_77973_b();
        int meta = st.func_77960_j();
        String reg = Item.field_150901_e.func_148750_c(item);
        String unloc = st.func_77977_a();
        String disp = st.func_82833_r();
        StringBuilder oreTags = new StringBuilder();
        for (String tag : getOreDictNames(st)) {
            oreTags.append(' ').append(tag);
        }
        return ((reg != null ? reg : "") + " " + (reg != null ? reg + ":" + meta : "") + " "
            + (unloc != null ? unloc : "") + " " + (disp != null ? disp : "") + oreTags.toString()).toLowerCase();
    }

    public static String resolveHeldFilter(EntityPlayerMP player, String rawFilter) {
        if (rawFilter == null) return "any";
        String f = rawFilter.trim();
        String lower = f.toLowerCase();
        if (lower.equals("held") || lower.equals("selected") || lower.equals("selected block")
            || lower.equals("held block") || lower.equals("held item") || lower.equals("hand") || lower.equals("equipped")) {
            if (player != null) {
                ItemStack held = player.func_71045_bC();
                if (held != null && held.field_77994_a > 0 && held.func_77973_b() != null) {
                    return held.func_82833_r() + ":" + held.func_77960_j();
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
                        if (LexicalItemMatcher.scoreSingleMatch(displayName, fullId, meta, neg) > 0
                            || (!neg.contains(":") && (disp.contains(neg) || id.contains(neg)))) {
                            return 0;
                        }
                    }
                } else {
                    hasPositive = true;
                    if (clause.equals("all") || clause.equals("any") || clause.equals("*") || clause.equals("solid") || clause.equals("block")) {
                        bestPositiveScore = Math.max(bestPositiveScore, 50);
                    } else {
                        int s = LexicalItemMatcher.scoreSingleMatch(displayName, fullId, meta, clause);
                        if (s > bestPositiveScore) bestPositiveScore = s;
                    }
                }
            }
            return hasPositive ? bestPositiveScore : 50;
        }
        return LexicalItemMatcher.scoreSingleMatch(displayName, fullId, meta, raw);
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
                        if (matchesSingleMineFilter(b, meta, neg)
                            || (!neg.contains(":") && (disp.contains(neg) || id.contains(neg)))) {
                            return false;
                        }
                    }
                } else {
                    hasPositive = true;
                    if (clause.equals("all") || clause.equals("any") || clause.equals("*") || clause.equals("solid")) {
                        anyPositiveMatched = true;
                    } else if (matchesSingleMineFilter(b, meta, clause)) {
                        anyPositiveMatched = true;
                    }
                }
            }
            return !hasPositive || anyPositiveMatched;
        }
        return matchesSingleMineFilter(b, meta, raw);
    }

    private static boolean matchesSingleMineFilter(Block b, int meta, String filter) {
        if (b == null || filter == null) return false;
        String f = filter.trim().toLowerCase().replace('_', ' ');
        int normMeta = RegistryIntrospector.normalizeBlockMeta(b, meta);
        String disp = getBlockDisplayName(b, normMeta);
        String id = getBlockIdentifier(b, normMeta);

        if (f.equals("all non air") || f.equals("*")) {
            return b != Blocks.field_150350_a;
        }
        if (f.equals("ore") || f.equals("ores") || f.equals("vein") || f.equals("all")) {
            return isOreBlock(b, normMeta);
        }
        if (f.equals("tree") || f.equals("trees") || f.equals("wood") || f.equals("log") || f.equals("logs")) {
            return isLogBlock(b);
        }
        if (f.equals("crop") || f.equals("crops") || f.equals("plant") || f.equals("plants")) {
            return isCropOrReedBlock(b);
        }
        if (id.contains("ore" + f.replace(" ", ""))) {
            return true;
        }
        if (!f.contains("block") && !f.contains("ingot") && isOreBlock(b, normMeta) && id.contains(f)) {
            return true;
        }
        return LexicalItemMatcher.scoreSingleMatch(disp, id, normMeta, f) > 0;
    }

    public static int getItemCategoryOrder(ItemStack st) {
        return RegistryIntrospector.getItemCategoryOrder(st);
    }

    public static boolean isFurnaceTile(World world, TileEntity te) {
        return RegistryIntrospector.isFurnaceTile(world, te);
    }

    public static boolean isStorageChestTile(World world, TileEntity te) {
        return RegistryIntrospector.isStorageContainerTile(world, te);
    }

    public static boolean isHostileOrMutantEntity(EntityLivingBase el) {
        return RegistryIntrospector.isHostileEntity(el);
    }

    public static boolean matchesEntityFilter(EntityLivingBase el, String filter) {
        if (el == null || el.field_70128_L || el.func_110143_aJ() <= 0.0f) return false;
        if (el instanceof EntityPlayer) return false;
        String f = (filter == null || filter.trim().isEmpty()) ? "animal" : filter.trim().toLowerCase();
        String combined = RegistryIntrospector.getEntityIdentifier(el);
        List<String> entityTokens = LexicalItemMatcher.tokenize(combined);

        String[] clauses = f.split("[,|/]+|\\s+or\\s+");
        for (String rawTok : clauses) {
            String tok = rawTok.trim().replace('_', ' ');
            if (tok.isEmpty()) continue;
            if (tok.equals("all") || tok.equals("mob") || tok.equals("mobs") || tok.equals("any") || tok.equals("these") || tok.equals("nearby")) {
                return true;
            }
            if (tok.equals("animal") || tok.equals("animals") || tok.equals("passive") || tok.equals("food")) {
                if (RegistryIntrospector.isPassiveEntity(el)) return true;
            }
            if (tok.equals("hostile") || tok.equals("hostiles") || tok.equals("monster") || tok.equals("monsters")
                || LexicalItemMatcher.fuzzyTokenMatches("mutant", tok) || tok.equals("enemy") || tok.equals("enemies")) {
                if (RegistryIntrospector.isHostileEntity(el)) return true;
            }
            String sing = LexicalItemMatcher.singularize(tok);
            if (combined.contains(tok) || combined.contains(sing) || LexicalItemMatcher.tokenListContains(entityTokens, sing)) {
                return true;
            }
        }
        return false;
    }

    public static JsonObject itemStackToJson(int slot, ItemStack st) {
        JsonObject io = new JsonObject();
        if (slot >= 0) io.addProperty("slot", slot);
        if (st == null || st.field_77994_a <= 0 || st.func_77973_b() == null) return io;
        String disp = st.func_82833_r();
        String reg = Item.field_150901_e.func_148750_c(st.func_77973_b());
        int id = Item.func_150891_b(st.func_77973_b());
        int meta = st.func_77960_j();
        io.addProperty("name", disp != null ? disp : "");
        io.addProperty("displayName", disp != null ? disp : "");
        io.addProperty("registryName", reg != null ? reg : "");
        io.addProperty("item", reg != null ? reg : "");
        io.addProperty("id", id);
        io.addProperty("meta", meta);
        io.addProperty("damage", meta);
        io.addProperty("count", st.field_77994_a);
        return io;
    }
}
