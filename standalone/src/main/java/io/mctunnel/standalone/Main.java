package io.mctunnel.standalone;

import io.mctunnel.core.TunnelCore;
import io.mctunnel.core.chat.ChatServer;
import io.mctunnel.core.chat.MeshManager;
import io.mctunnel.core.storage.ChatStorage;
import io.mctunnel.core.tunnel.NgrokAdapter;
import io.mctunnel.core.tunnel.SyncThingAdapter;
import io.mctunnel.core.tunnel.TailscaleAdapter;
import io.mctunnel.core.tunnel.TunnelInfo;
import io.mctunnel.core.tunnel.TunnelTool;
import io.mctunnel.core.tunnel.TunnelType;
import io.mctunnel.core.web.WebServer;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 独立 jar 入口.
 * <p>
 * 用法:
 * <pre>
 * java -jar mctunnel-standalone.jar install ngrok
 * java -jar mctunnel-standalone.jar start ngrok http 25565
 * java -jar mctunnel-standalone.jar status
 * </pre>
 */
public final class Main {

    private static final Map<TunnelType, TunnelTool> TOOLS = new HashMap<>();

    static {
        TOOLS.put(TunnelType.NGROK, new NgrokAdapter());
        TOOLS.put(TunnelType.TAILSCALE, new TailscaleAdapter());
        TOOLS.put(TunnelType.SYNCTHING, new SyncThingAdapter());
    }

    private Main() {
    }

    public static void main(String[] args) {
        System.out.println(TunnelCore.greet());

        if (args.length == 0) {
            printHelp();
            return;
        }

        String command = args[0].toLowerCase();
        String[] rest = Arrays.copyOfRange(args, 1, args.length);

        try {
            switch (command) {
                case "install" -> cmdInstall(rest);
                case "start" -> cmdStart(rest);
                case "stop" -> cmdStop(rest);
                case "status" -> cmdStatus();
                case "serve" -> cmdServe();
                case "help" -> printHelp();
                default -> {
                    System.err.println("Unknown command: " + command);
                    printHelp();
                    System.exit(1);
                }
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(2);
        }
    }

    private static void cmdInstall(String[] args) throws IOException {
        TunnelTool tool = resolveTool(args);
        System.out.println("Installing " + tool.getType().getDisplayName() + " ...");
        tool.install();
        System.out.println("Installed: " + tool.isInstalled());
    }

    private static void cmdStart(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("Usage: start <tool> [args...]");
            System.err.println("Example: start ngrok http 25565");
            System.err.println("Example: start tailscale");
            System.exit(1);
        }
        TunnelTool tool = resolveTool(new String[]{args[0]});
        String[] toolArgs = args.length > 1
                ? Arrays.copyOfRange(args, 1, args.length)
                : new String[0];

        if (!tool.isInstalled()) {
            System.out.println("Not installed, installing...");
            tool.install();
        }

        TunnelInfo info = tool.start(toolArgs);
        System.out.println("Started: " + info);
    }

    private static void cmdStop(String[] args) {
        TunnelTool tool = resolveTool(args);
        tool.stop();
        System.out.println("Stopped: " + tool.getInfo());
    }

    private static void cmdStatus() {
        for (TunnelTool tool : TOOLS.values()) {
            System.out.println(tool.getInfo());
        }
    }

    private static void cmdServe() throws IOException, InterruptedException {
        String nodeId = System.getenv().getOrDefault("MCTUNNEL_NODE_ID",
                "node-" + UUID.randomUUID().toString().substring(0, 8));

        // 存储(SQLite 或内存回退)
        ChatStorage storage = new ChatStorage();
        // 组网管理器
        MeshManager mesh = new MeshManager(nodeId);

        WebServer web = new WebServer(TOOLS);
        web.start();
        ChatServer chat = new ChatServer(nodeId, storage, mesh);
        chat.start();

        // 从存储加载已知对等节点并连接
        for (ChatStorage.NodeInfo node : storage.loadNodes()) {
            if (node.address() != null && !node.address().isBlank()) {
                mesh.addPeer(node.address());
                System.out.println("[MC-Tunnel] Connecting to peer: " + node.nodeId()
                        + " @ " + node.address());
            }
        }

        // 支持通过环境变量 MCTUNNEL_PEERS=ws://a:8788,ws://b:8788 添加对等节点
        String peersEnv = System.getenv("MCTUNNEL_PEERS");
        if (peersEnv != null && !peersEnv.isBlank()) {
            for (String peer : peersEnv.split(",")) {
                String p = peer.trim();
                if (!p.isEmpty()) {
                    mesh.addPeer(p);
                    storage.upsertNode("peer-" + p, p);
                }
            }
        }

        System.out.println("Node ID: " + nodeId);
        System.out.println("Storage: " + (storage.isPersistent() ? "SQLite" : "in-memory"));
        System.out.println("WebUI + Chat running. Press Ctrl+C to stop.");

        // 注册关闭钩子,清理资源
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            mesh.shutdown();
            storage.close();
            web.stop();
            chat.stop();
        }));

        // 主线程阻塞,等待中断
        Thread.currentThread().join();
    }

    private static TunnelTool resolveTool(String[] args) {
        if (args.length == 0) {
            System.err.println("Usage: <tool> (ngrok|tailscale|syncthing)");
            System.exit(1);
        }
        TunnelType type;
        try {
            type = TunnelType.valueOf(args[0].toUpperCase());
        } catch (IllegalArgumentException e) {
            System.err.println("Unknown tool: " + args[0]);
            System.exit(1);
            return null;
        }
        TunnelTool tool = TOOLS.get(type);
        if (tool == null) {
            System.err.println("Tool not yet implemented: " + type);
            System.exit(1);
        }
        return tool;
    }

    private static void printHelp() {
        System.out.println("""
                MC-Tunnel CLI
                Commands:
                  install <tool>          Install a tool binary
                  start <tool> [args...]  Start a tool (e.g. start ngrok http 25565)
                  stop <tool>             Stop a running tool
                  status                  Show status of all tools
                  serve                   Start the WebUI dashboard
                  help                    Show this help
                Tools: ngrok, tailscale, syncthing""");
    }
}
