package ir.layercloud.cortexdrone;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import ir.layercloud.cortexdrone.harness.PlayerChunkWatchRegistry;
import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommand;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.world.ChunkWatchEvent;
import net.minecraftforge.event.world.WorldEvent;

@Mod(
    modid = CortexDroneMod.MODID,
    name = CortexDroneMod.NAME,
    version = CortexDroneMod.VERSION,
    dependencies = "required-after:OpenComputers;after:layercloudsmp",
    acceptableRemoteVersions = "*"
)
public class CortexDroneMod {
    public static final String MODID = "cortexdrone";
    public static final String NAME = "CortexDrone";
    public static final String VERSION = "1.4.0";

    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** Agent loops (LLM + JS engine). Never share with the HTTP server: a hung
     *  loop must not starve /state|/debug|/rpc (that made failures silent). */
    public static final ExecutorService HTTP_POOL = Executors.newCachedThreadPool(runnable -> {
        Thread t = new Thread(runnable, "CortexDrone-Agent-" + System.nanoTime());
        t.setDaemon(true);
        return t;
    });
    /** Drone HTTP server (port 25580) request threads. Separate pool so one
     *  hung /rpc|/action cannot wedge the LLM agent loops or vice versa. */
    public static final ExecutorService HTTP_SERVER_POOL = Executors.newCachedThreadPool(runnable -> {
        Thread t = new Thread(runnable, "CortexDrone-Http-" + System.nanoTime());
        t.setDaemon(true);
        return t;
    });

    private final List<Runnable> mainThreadTasks = Collections.synchronizedList(new ArrayList<Runnable>());
    private AIDroneManager aiDroneManager = null;
    private Method authCheckMethod = null;
    private boolean authCheckResolved = false;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(this);
        FMLCommonHandler.instance().bus().register(this);
        this.aiDroneManager = new AIDroneManager(this);
        ForgeChunkManager.setForcedChunkLoadingCallback(this, this.aiDroneManager.getChunkLoader());
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        File worldDir = event.getServer().func_71209_f("world");
        if (!worldDir.exists()) {
            worldDir.mkdirs();
        }
        if (this.aiDroneManager != null) {
            this.aiDroneManager.onServerStarting(event, worldDir);
        }
    }

    @Mod.EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        if (this.aiDroneManager != null) {
            this.aiDroneManager.onServerStopping();
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            if (this.aiDroneManager != null) {
                this.aiDroneManager.onServerTickStart();
            }
            return;
        }
        if (event.phase != TickEvent.Phase.END) return;

        synchronized (this.mainThreadTasks) {
            while (!this.mainThreadTasks.isEmpty()) {
                try {
                    this.mainThreadTasks.remove(0).run();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        if (this.aiDroneManager != null) {
            try {
                this.aiDroneManager.onServerTickEnd();
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.NORMAL)
    public void onChat(ServerChatEvent event) {
        if (event.isCanceled()) return;
        if (!isAuthenticated(event.player)) return;
        if (this.aiDroneManager != null) {
            this.aiDroneManager.onPlayerChat(event);
        }
    }

    @SubscribeEvent
    public void onChunkWatch(ChunkWatchEvent.Watch event) {
        if (event != null && event.player != null) {
            PlayerChunkWatchRegistry.watch(event.player.field_70170_p,
                event.player.func_110124_au(), event.chunk);
        }
    }

    @SubscribeEvent
    public void onChunkUnwatch(ChunkWatchEvent.UnWatch event) {
        if (event != null && event.player != null) {
            PlayerChunkWatchRegistry.unwatch(event.player.field_70170_p,
                event.player.func_110124_au(), event.chunk);
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event != null && event.player != null) {
            PlayerChunkWatchRegistry.clearPlayer(event.player.func_110124_au());
        }
    }

    @SubscribeEvent
    public void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event != null && event.player != null) {
            PlayerChunkWatchRegistry.clearPlayer(event.player.func_110124_au());
        }
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        PlayerChunkWatchRegistry.clear(event.world);
        if (this.aiDroneManager != null) {
            this.aiDroneManager.getChunkLoader().releaseWorld(event.world);
        }
    }

    public boolean isAuthenticated(EntityPlayer player) {
        if (player == null) return true;
        if (!this.authCheckResolved) {
            this.authCheckResolved = true;
            try {
                Class<?> cls = Class.forName("ir.layercloud.smp.LayerCloudSMPMod");
                this.authCheckMethod = cls.getMethod("isPlayerAuthenticated", EntityPlayer.class);
            } catch (Throwable ignored) {
            }
        }
        if (this.authCheckMethod != null) {
            try {
                Object res = this.authCheckMethod.invoke(null, player);
                if (res instanceof Boolean) {
                    return ((Boolean) res).booleanValue();
                }
            } catch (Throwable ignored) {
            }
        }
        return true;
    }

    public void queueMain(Runnable r) {
        this.mainThreadTasks.add(r);
    }

    public static String normKey(String s) {
        return s == null ? "" : s.toLowerCase();
    }

    public static void sendMsg(ICommandSender sender, String msg) {
        if (sender != null) {
            sender.func_145747_a(new ChatComponentText(msg));
        }
    }

    public static void broadcast(String msg) {
        MinecraftServer srv = MinecraftServer.func_71276_C();
        if (srv != null && srv.func_71203_ab() != null) {
            srv.func_71203_ab().func_148539_a(new ChatComponentText(msg));
        }
    }

    public static EntityPlayerMP findPlayer(String name) {
        MinecraftServer srv = MinecraftServer.func_71276_C();
        if (srv == null || srv.func_71203_ab() == null || name == null) return null;
        return srv.func_71203_ab().func_152612_a(name);
    }

    public abstract static class BaseCmd extends CommandBase {
        @Override
        public int compareTo(Object o) {
            if (o instanceof ICommand) {
                return this.func_71517_b().compareTo(((ICommand) o).func_71517_b());
            }
            return 0;
        }
    }
}
