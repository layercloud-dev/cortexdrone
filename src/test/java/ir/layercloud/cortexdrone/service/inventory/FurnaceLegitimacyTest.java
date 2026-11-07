package ir.layercloud.cortexdrone.service.inventory;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Furnaces must run at vanilla speed.
 *
 * <p>The reported symptom — "the furnace works faster" — came from feeding it
 * from a distance, but a future edit could also tick it or write cook progress
 * directly. This guards the invariant at the source level so neither can return
 * silently.</p>
 */
public class FurnaceLegitimacyTest {
    private static final String ROOT = "src/main/java";

    @Test
    public void noCodeAdvancesFurnaceCookProgress() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (File file : javaFiles(new File(ROOT))) {
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            if (text.contains("furnaceCookTime =") || text.contains("furnaceCookTime=")) {
                offenders.add(file.getPath() + " writes furnaceCookTime");
            }
            if (text.contains("currentItemBurnTime =") || text.contains("currentItemBurnTime=")) {
                offenders.add(file.getPath() + " writes currentItemBurnTime");
            }
        }
        assertTrue("A furnace must never be advanced by the mod: " + offenders, offenders.isEmpty());
    }

    @Test
    public void noCodeManuallyTicksFurnaces() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (File file : javaFiles(new File(ROOT))) {
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            if (!text.contains("Furnace")) continue;
            if (text.contains("updateEntity()") && text.contains("Furnace")) {
                offenders.add(file.getPath());
            }
        }
        assertTrue("The world ticks furnaces; the mod must not: " + offenders, offenders.isEmpty());
    }

    @Test
    public void smeltingOnlyMovesItemsThroughReachableContainers() throws Exception {
        String service = read("src/main/java/ir/layercloud/cortexdrone/service/inventory/InventoryService.java");
        assertTrue("withdraw must filter to reachable containers",
                service.contains("ContainerAccess.reachable"));
        assertFalse("withdraw must not expand the search to the player's chests",
                service.contains("ownerTiles"));
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }

    private static List<File> javaFiles(File dir) {
        List<File> files = new ArrayList<>();
        File[] children = dir.listFiles();
        if (children == null) return files;
        for (File child : children) {
            if (child.isDirectory()) files.addAll(javaFiles(child));
            else if (child.getName().endsWith(".java")) files.add(child);
        }
        return files;
    }
}
