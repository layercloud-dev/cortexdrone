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
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.ServerChatEvent;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

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
    public static final String VERSION = "2.3.0";
    private static final Logger LOG = Logger.getLogger("CortexDrone");

    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /**
     * Agent loops (LLM + JS engine + log flushing). A hung loop must not block
     * the server thread; every task is bounded and cancellable.
     */
    public static final ExecutorService AGENT_POOL = Executors.newCachedThreadPool(runnable -> {
        Thread t = new Thread(runnable, "CortexDrone-Agent-" + System.nanoTime());
        t.setDaemon(true);
        return t;
    });

    private final List<Runnable> mainThreadTasks = Collections.synchronizedList(new ArrayList<>());
    private AIDroneManager aiDroneManager = null;
    private Method authCheckMethod = null;
    private boolean authCheckResolved = false;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(this);
        FMLCommonHandler.instance().bus().register(this);
        this.aiDroneManager = new AIDroneManager(this,
                new File(event.getModConfigurationDirectory(), "cortexdrone"));
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        File worldDir = event.getServer().getFile("world");
        if (!worldDir.exists() && !worldDir.mkdirs()) {
            LOG.warning("Could not create world directory " + worldDir.getAbsolutePath());
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
                    LOG.log(Level.WARNING, "Main-thread task failed", e);
                }
            }
        }

        if (this.aiDroneManager != null) {
            try {
                this.aiDroneManager.onServerTickEnd();
            } catch (Throwable t) {
                LOG.log(Level.SEVERE, "Server tick pipeline failed", t);
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
                    return (Boolean) res;
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
            sender.addChatMessage(new ChatComponentText(msg));
        }
    }

    public static void broadcast(String msg) {
        MinecraftServer srv = MinecraftServer.getServer();
        if (srv != null && srv.getConfigurationManager() != null) {
            srv.getConfigurationManager().sendChatMsg(new ChatComponentText(msg));
        }
    }

    public static EntityPlayerMP findPlayer(String name) {
        MinecraftServer srv = MinecraftServer.getServer();
        if (srv == null || srv.getConfigurationManager() == null || name == null) return null;
        return srv.getConfigurationManager().func_152612_a(name);
    }
}
