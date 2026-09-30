package io.mctunnel.forge;

import io.mctunnel.core.TunnelCore;
import io.mctunnel.core.chat.ChatServer;
import io.mctunnel.core.chat.MeshManager;
import io.mctunnel.core.storage.ChatStorage;
import io.mctunnel.core.tunnel.NgrokAdapter;
import io.mctunnel.core.tunnel.SyncThingAdapter;
import io.mctunnel.core.tunnel.TailscaleAdapter;
import io.mctunnel.core.tunnel.TunnelClient;
import io.mctunnel.core.tunnel.TunnelTool;
import io.mctunnel.core.tunnel.TunnelType;
import io.mctunnel.core.web.WebServer;
import io.mctunnel.forge.chat.IngameChatHandler;

import com.mojang.logging.LogUtils;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Forge 模组主类.
 * <p>
 * 架构: 模组层只做 HTTP 客户端,所有工具操作通过 TunnelClient 调用后端服务.
 * 桌面端: 模组内嵌启动 WebServer(后端服务),自给自足.
 * Android: 后端服务跑在 Termux,通过配置的地址访问.
 */
@Mod(MCTunnelMod.MODID)
public class MCTunnelMod {

    public static final String MODID = "mctunnel";

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 后端服务(WebServer),桌面端由模组内嵌启动 */
    private static WebServer webServer;
    /** 聊天服务(内嵌后端时启动) */
    private static ChatServer chatServer;
    private static ChatStorage chatStorage;
    private static MeshManager meshManager;
    /** HTTP 客户端,模组通过它操作工具 */
    private static TunnelClient tunnelClient;

    public MCTunnelMod() {
        var modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(this::commonSetup);
        MinecraftForge.EVENT_BUS.register(this);

        LOGGER.info("[MC-Tunnel] Mod constructed. Core: {}", TunnelCore.greet());
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        boolean embedded = shouldUseEmbeddedBackend();
        String backendUrl = System.getProperty("mctunnel.backend", "http://localhost:8787");
        String chatUrl = System.getProperty("mctunnel.chat", "ws://localhost:8788");

        if (embedded) {
            // 桌面端: 内嵌启动后端服务(Web + Chat)
            startBackendIfNeeded();
        } else {
            // Android 端: 后端需手动在 Termux 起 serve,模组只做客户端
            LOGGER.info("[MC-Tunnel] Android environment detected, using external backend at {}", backendUrl);
        }

        tunnelClient = new TunnelClient(backendUrl);

        // 客户端侧: 启动游戏内聊天同步
        if (FMLEnvironment.dist == Dist.CLIENT) {
            event.enqueueWork(() -> IngameChatHandler.init(chatUrl));
        }

        LOGGER.info("[MC-Tunnel] Common setup. Backend: {} (embedded={})", backendUrl, embedded);
    }

    /**
     * 判断是否内嵌启动后端.
     * 桌面端(Windows/Linux/macOS): true
     * Android: false (后端需在 Termux 手动启动)
     * 可通过 -Dmctunnel.embedded=true/false 强制覆盖
     */
    private boolean shouldUseEmbeddedBackend() {
        String override = System.getProperty("mctunnel.embedded");
        if (override != null) {
            return Boolean.parseBoolean(override);
        }
        return !isAndroid();
    }

    /** 检测是否运行在 Android 上 */
    private boolean isAndroid() {
        // 方式1: java.vendor
        String vendor = System.getProperty("java.vendor", "").toLowerCase();
        if (vendor.contains("android")) return true;
        // 方式2: 环境变量
        if (System.getenv("ANDROID_ROOT") != null) return true;
        if (System.getenv("ANDROID_DATA") != null) return true;
        // 方式3: 尝试加载 Android 框架类
        try {
            Class.forName("android.os.Build");
            return true;
        } catch (ClassNotFoundException ignored) {
        }
        return false;
    }

    /**
     * 如果后端服务未运行,启动内嵌的 WebServer.
     * Android 端: 用户需手动在 Termux 起 serve,此时此方法会跳过.
     */
    private void startBackendIfNeeded() {
        try {
            // 先检测是否已有服务在跑
            TunnelClient probe = new TunnelClient("http://localhost:8787");
            if (probe.isReachable()) {
                LOGGER.info("[MC-Tunnel] Backend already running, skip embedded start.");
                return;
            }
        } catch (Exception ignored) {
            // 不可达,继续启动
        }

        try {
            Map<TunnelType, TunnelTool> tools = new HashMap<>();
            tools.put(TunnelType.NGROK, new NgrokAdapter());
            tools.put(TunnelType.TAILSCALE, new TailscaleAdapter());
            tools.put(TunnelType.SYNCTHING, new SyncThingAdapter());

            webServer = new WebServer(tools);
            webServer.start();
            LOGGER.info("[MC-Tunnel] Embedded backend started at http://localhost:{}",
                    webServer.getPort());

            // 启动内嵌聊天服务(带存储 + 组网)
            String nodeId = System.getProperty("mctunnel.nodeId",
                    "node-" + UUID.randomUUID().toString().substring(0, 8));
            chatStorage = new ChatStorage();
            meshManager = new MeshManager(nodeId);
            chatServer = new ChatServer(nodeId, chatStorage, meshManager);
            chatServer.start();
            LOGGER.info("[MC-Tunnel] Embedded chat server started (node={})", nodeId);
        } catch (IOException e) {
            LOGGER.error("[MC-Tunnel] Failed to start embedded backend: {}", e.getMessage());
        }
    }

    @SubscribeEvent
    public void onServerStarting(final ServerStartingEvent event) {
        LOGGER.info("[MC-Tunnel] Server starting. {}", TunnelCore.greet());
    }

    /** 获取 HTTP 客户端(供 GUI/命令调用) */
    public static TunnelClient getTunnelClient() {
        return tunnelClient;
    }

    /** 获取后端 WebServer(可能为 null,如果用户自己起了外部服务) */
    public static WebServer getWebServer() {
        return webServer;
    }
}
