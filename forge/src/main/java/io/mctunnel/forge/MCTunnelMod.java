package io.mctunnel.forge;

import io.mctunnel.core.TunnelCore;
import io.mctunnel.core.DataDir;
import io.mctunnel.core.chat.ChatServer;
import io.mctunnel.core.chat.MeshManager;
import io.mctunnel.core.room.RoomManager;
import io.mctunnel.core.storage.ChatStorage;
import io.mctunnel.core.tunnel.NgrokAdapter;
import io.mctunnel.core.tunnel.SyncThingAdapter;
import io.mctunnel.core.tunnel.TailscaleAdapter;
import io.mctunnel.core.tunnel.TunnelClient;
import io.mctunnel.core.tunnel.TunnelTool;
import io.mctunnel.core.tunnel.TunnelType;
import io.mctunnel.core.web.WebServer;
import io.mctunnel.forge.chat.IngameChatHandler;
import io.mctunnel.forge.controller.EmbeddedToolController;
import io.mctunnel.forge.controller.RemoteToolController;
import io.mctunnel.forge.controller.ToolController;

import com.mojang.logging.LogUtils;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Forge 模组主类.
 * <p>
 * 架构: 模组即完整应用.
 * 桌面端: 工具由模组内嵌直连,WebUI(HTTP 服务)只是可选门面,可在游戏内开关.
 * Android: 工具由 Termux 中的后端管理(同一 jar 以 serve 模式启动),模组走 HTTP.
 */
@Mod(MCTunnelMod.MODID)
public class MCTunnelMod {

    public static final String MODID = "mctunnel";

    private static final Logger LOGGER = LogUtils.getLogger();

    /** WebUI/HTTP API 服务,桌面端可选启停 */
    private static WebServer webServer;
    /** 聊天服务(内嵌模式启动) */
    private static ChatServer chatServer;
    private static ChatStorage chatStorage;
    private static MeshManager meshManager;
    /** 房间管理器(内嵌模式) */
    private static RoomManager roomManager;
    /** 内嵌模式的工具实例 */
    private static Map<TunnelType, TunnelTool> tools;
    /** 工具控制器(内嵌直连 或 远程 HTTP) */
    private static ToolController toolController;
    /** 是否内嵌模式 */
    private static boolean embedded;

    public MCTunnelMod() {
        // 数据目录指向游戏文件夹下的 mctunnel/
        DataDir.set(FMLPaths.GAMEDIR.get().resolve("mctunnel"));
        // 生成独立启动脚本(不覆盖已有文件)
        writeLaunchScripts();

        MCTunnelConfig.register();

        var modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(this::commonSetup);
        MinecraftForge.EVENT_BUS.register(this);

        LOGGER.info("[MC-Tunnel] Mod constructed. Core: {}", TunnelCore.greet());
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        String backendUrl = System.getProperty("mctunnel.backend", "http://localhost:8787");
        String chatUrl = System.getProperty("mctunnel.chat", "ws://localhost:8788");

        embedded = shouldUseEmbeddedBackend();
        if (embedded) {
            // 若已有后端在运行(如用户已 java -jar serve),直接复用,避免端口冲突
            try {
                if (new TunnelClient(backendUrl).isReachable()) {
                    embedded = false;
                    LOGGER.info("[MC-Tunnel] Existing backend detected at {}, reusing it.", backendUrl);
                }
            } catch (Exception ignored) {
                // 不可达,按内嵌模式启动
            }
        }

        if (embedded) {
            // 桌面端: 内嵌启动工具 + 聊天服务;WebUI 按配置决定是否开启
            chatStorage = new ChatStorage();
            tools = createTools();
            startChatServerIfNeeded();
            // 房间管理器(初始网络主机)
            String nodeId = System.getenv().getOrDefault("MCTUNNEL_NODE_ID",
                    "node-" + java.util.UUID.randomUUID().toString().substring(0, 8));
            roomManager = new RoomManager(nodeId,
                    System.getProperty("user.name"), chatStorage);
            roomManager.loadLastRoom();
            if (MCTunnelConfig.isWebUiEnabled()) {
                startWebUi();
            } else {
                LOGGER.info("[MC-Tunnel] WebUI disabled by config, skipping.");
            }
            toolController = new EmbeddedToolController(tools, roomManager);
        } else {
            // Android 端: 后端需手动在 Termux 起 serve,模组只做客户端
            LOGGER.info("[MC-Tunnel] Android environment detected, using external backend at {}", backendUrl);
            toolController = new RemoteToolController(new TunnelClient(backendUrl));
        }

        // 客户端侧: 启动游戏内聊天同步
        if (FMLEnvironment.dist == Dist.CLIENT) {
            event.enqueueWork(() -> IngameChatHandler.init(chatUrl));
        }

        LOGGER.info("[MC-Tunnel] Common setup. embedded={}, webUi={}",
                embedded, isWebUiRunning());
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

    private static Map<TunnelType, TunnelTool> createTools() {
        // EnumMap:遍历顺序固定为 ngrok→tailscale→syncthing,与 GUI 按钮行顺序一致
        Map<TunnelType, TunnelTool> map = new java.util.EnumMap<>(TunnelType.class);
        NgrokAdapter ngrok = new NgrokAdapter();
        TailscaleAdapter tailscale = new TailscaleAdapter();
        SyncThingAdapter syncthing = new SyncThingAdapter();
        // 绑定持久化配置存储(记录 ngrok 安装目录/二进制路径等)
        if (chatStorage != null) {
            ngrok.setConfigStore(chatStorage);
            tailscale.setConfigStore(chatStorage);
            syncthing.setConfigStore(chatStorage);
        }
        map.put(TunnelType.NGROK, ngrok);
        map.put(TunnelType.TAILSCALE, tailscale);
        map.put(TunnelType.SYNCTHING, syncthing);
        return map;
    }

    /** 启动 WebUI/HTTP API 服务(若已有实例或端口被占则跳过) */
    public static synchronized boolean startWebUi() {
        if (webServer != null) {
            return true;
        }
        if (tools == null) {
            LOGGER.warn("[MC-Tunnel] Not in embedded mode, cannot start WebUI.");
            return false;
        }
        try {
            webServer = new WebServer(tools);
            webServer.start();
            LOGGER.info("[MC-Tunnel] WebUI started at http://localhost:{}", webServer.getPort());
            return true;
        } catch (IOException e) {
            webServer = null;
            LOGGER.error("[MC-Tunnel] Failed to start WebUI: {}", e.getMessage());
            return false;
        }
    }

    /** 停止 WebUI/HTTP API 服务 */
    public static synchronized void stopWebUi() {
        if (webServer != null) {
            webServer.stop();
            webServer = null;
            LOGGER.info("[MC-Tunnel] WebUI stopped.");
        }
    }

    public static boolean isWebUiRunning() {
        return webServer != null;
    }

    public static boolean isEmbedded() {
        return embedded;
    }

    /**
     * 游戏内切换 WebUI 开关:立即生效并写入配置.
     *
     * @return 切换后 WebUI 是否在运行
     */
    public static boolean toggleWebUi() {
        boolean target = !isWebUiRunning();
        if (target) {
            startWebUi();
        } else {
            stopWebUi();
        }
        MCTunnelConfig.setWebUiEnabled(isWebUiRunning());
        return isWebUiRunning();
    }

    /** 启动内嵌聊天服务(带存储 + 组网);存储已创建时复用 */
    private void startChatServerIfNeeded() {
        try {
            if (chatStorage == null) {
                chatStorage = new ChatStorage();
            }
            String nodeId = System.getProperty("mctunnel.nodeId",
                    "node-" + UUID.randomUUID().toString().substring(0, 8));
            meshManager = new MeshManager(nodeId);
            chatServer = new ChatServer(nodeId, chatStorage, meshManager);
            chatServer.start();
            LOGGER.info("[MC-Tunnel] Embedded chat server started (node={})", nodeId);
        } catch (IOException e) {
            LOGGER.error("[MC-Tunnel] Failed to start embedded chat server: {}", e.getMessage());
        }
    }

    @SubscribeEvent
    public void onServerStarting(final ServerStartingEvent event) {
        LOGGER.info("[MC-Tunnel] Server starting. {}", TunnelCore.greet());
    }

    /**
     * 在数据目录生成独立启动脚本(start.sh / start.bat).
     * 脚本引用 ../mods/ 下的 jar,用户可双击或终端运行以启动 WebUI.
     * 已存在的文件不覆盖.
     */
    private static void writeLaunchScripts() {
        Path dir = DataDir.get();
        try {
            Files.createDirectories(dir);

            Path sh = dir.resolve("start.sh");
            if (!Files.exists(sh)) {
                Files.writeString(sh, """
                        #!/bin/sh
                        # MC-Tunnel 独立启动脚本
                        # 用法: ./start.sh serve  或直接 ./start.sh (默认 serve)
                        DIR="$(cd "$(dirname "$0")" && pwd)"
                        JAR="$DIR/../mods/mctunnel.jar"
                        # 若带参数则透传,否则默认 serve
                        if [ $# -gt 0 ]; then
                            java -jar "$JAR" "$@"
                        else
                            java -jar "$JAR" serve
                        fi
                        """);
                sh.toFile().setExecutable(true);
            }

            Path bat = dir.resolve("start.bat");
            if (!Files.exists(bat)) {
                Files.writeString(bat, """
                        @echo off
                        :: MC-Tunnel 独立启动脚本
                        :: 用法: start.bat serve  或直接 start.bat (默认 serve)
                        set "JAR=%~dp0..\\mods\\mctunnel.jar"
                        if "%~1"=="" (
                            java -jar "%JAR%" serve
                        ) else (
                            java -jar "%JAR%" %*
                        )
                        """);
            }
        } catch (IOException e) {
            LOGGER.warn("[MC-Tunnel] Failed to write launch scripts: {}", e.getMessage());
        }
    }

    /** 获取工具控制器(供 GUI/命令调用) */
    public static ToolController getToolController() {
        return toolController;
    }

    /** 获取 WebUI 端口(未运行返回 -1) */
    public static int getWebUiPort() {
        return webServer != null ? webServer.getPort() : -1;
    }
}
