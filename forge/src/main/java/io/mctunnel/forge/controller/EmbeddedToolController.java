package io.mctunnel.forge.controller;

import io.mctunnel.core.network.NatTraversalNetwork;
import io.mctunnel.core.network.Network;
import io.mctunnel.core.network.VirtualNetwork;
import io.mctunnel.core.room.NetworkStatus;
import io.mctunnel.core.room.NetworkType;
import io.mctunnel.core.room.Room;
import io.mctunnel.core.room.RoomManager;
import io.mctunnel.core.room.RoomMember;
import io.mctunnel.core.room.RoomNetwork;
import io.mctunnel.core.tunnel.DaemonResult;
import io.mctunnel.core.tunnel.InstallOptions;
import io.mctunnel.core.tunnel.SudoPasswordRequiredException;
import io.mctunnel.core.tunnel.TunnelInfo;
import io.mctunnel.core.tunnel.TunnelTool;
import io.mctunnel.core.tunnel.TunnelType;
import io.mctunnel.core.tunnel.UpdateCheck;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 内嵌模式控制器:直接调用 core 层工具适配器.
 * <p>
 * 不经过 HTTP,因此 WebUI(8787 端口)是否开启不影响游戏内操作.
 */
public class EmbeddedToolController implements ToolController {

    private final Map<TunnelType, TunnelTool> tools;
    private final RoomManager roomManager;

    public EmbeddedToolController(Map<TunnelType, TunnelTool> tools) {
        this(tools, null);
    }

    public EmbeddedToolController(Map<TunnelType, TunnelTool> tools, RoomManager roomManager) {
        this.tools = tools;
        this.roomManager = roomManager;
    }

    @Override
    public List<TunnelInfo> status() {
        List<TunnelInfo> list = new ArrayList<>();
        // 固定按枚举顺序:GUI 按钮行按 TunnelType.values() 排列,状态行顺序必须一致
        for (TunnelType type : TunnelType.values()) {
            TunnelTool tool = tools.get(type);
            if (tool != null) {
                list.add(tool.getInfo());
            }
        }
        return list;
    }

    @Override
    public UpdateCheck checkUpdate(TunnelType type) throws IOException {
        return tools.get(type).checkUpdate();
    }

    @Override
    public TunnelInfo install(TunnelType type, InstallOptions options) throws IOException {
        TunnelTool tool = tools.get(type);
        tool.install(options);
        return tool.getInfo();
    }

    @Override
    public TunnelInfo update(TunnelType type) throws IOException {
        TunnelTool tool = tools.get(type);
        tool.update(InstallOptions.DEFAULT);
        return tool.getInfo();
    }

    @Override
    public TunnelInfo configure(TunnelType type, Map<String, String> config) {
        TunnelTool tool = tools.get(type);
        tool.configure(config);
        return tool.getInfo();
    }

    @Override
    public TunnelInfo start(TunnelType type, String... args) throws IOException {
        return tools.get(type).start(args);
    }

    @Override
    public TunnelInfo stop(TunnelType type) {
        TunnelTool tool = tools.get(type);
        tool.stop();
        return tool.getInfo();
    }

    @Override
    public TunnelInfo locate(TunnelType type, String path) throws IOException {
        TunnelTool tool = tools.get(type);
        tool.setBinaryPath(Paths.get(path));
        return tool.getInfo();
    }

    @Override
    public DaemonResult startDaemon(TunnelType type) throws IOException {
        return startDaemon(type, null);
    }

    @Override
    public DaemonResult startDaemon(TunnelType type, String sudoPassword) throws IOException {
        TunnelTool tool = tools.get(type);
        char[] pw = sudoPassword == null ? null : sudoPassword.toCharArray();
        try {
            tool.startDaemon(pw);
            return DaemonResult.ok(tool.getInfo());
        } catch (SudoPasswordRequiredException e) {
            return DaemonResult.needPassword();
        }
    }

    @Override
    public DaemonResult stopDaemon(TunnelType type) throws IOException {
        return stopDaemon(type, null);
    }

    @Override
    public DaemonResult stopDaemon(TunnelType type, String sudoPassword) throws IOException {
        TunnelTool tool = tools.get(type);
        char[] pw = sudoPassword == null ? null : sudoPassword.toCharArray();
        try {
            tool.stopDaemon(pw);
            return DaemonResult.ok(tool.getInfo());
        } catch (SudoPasswordRequiredException e) {
            return DaemonResult.needPassword();
        }
    }

    @Override
    public String meshSaveToken(String apiToken) throws IOException {
        tailscale().saveApiToken(apiToken);
        return "API 令牌已验证并保存";
    }

    @Override
    public String meshShare(String emails) throws IOException {
        return tailscale().shareSelfTo(java.util.List.of(emails));
    }

    @Override
    public String meshInvite(String emails) throws IOException {
        return tailscale().inviteToTailnet(java.util.List.of(emails));
    }

    @Override
    public String meshClearToken() throws IOException {
        tailscale().clearApiToken();
        return "令牌已清除";
    }

    // ── 房间 & 网络 ──

    @Override
    public String roomCreate(String name) throws IOException {
        if (roomManager == null) throw new IOException("房间管理器未初始化");
        String relayHost = System.getenv().getOrDefault("MCTUNNEL_RELAY_HOST", "59.110.163.88");
        int relayPort = Integer.parseInt(System.getenv().getOrDefault("MCTUNNEL_RELAY_PORT", "8721"));
        String link = roomManager.createRoom(relayHost, relayPort, name);
        reportStatus();
        return link;
    }

    @Override
    public String roomJoin(String link) throws IOException {
        if (roomManager == null) throw new IOException("房间管理器未初始化");
        roomManager.joinRoom(link);
        reportStatus();
        Room r = roomManager.getCurrentRoom();
        if (r == null) return "{}";
        return "{\"roomId\":\"" + esc(r.id())
                + "\",\"name\":\"" + esc(r.name())
                + "\",\"members\":" + roomManager.getMembers().size() + "}";
    }

    @Override
    public void roomLeave() throws IOException {
        if (roomManager == null) throw new IOException("房间管理器未初始化");
        roomManager.leaveRoom();
    }

    @Override
    public String roomCurrent() throws IOException {
        if (roomManager == null) throw new IOException("房间管理器未初始化");
        Room r = roomManager.getCurrentRoom();
        if (r == null) return "{\"room\":null}";
        StringBuilder sb = new StringBuilder();
        sb.append("{\"room\":{\"id\":\"").append(esc(r.id()))
          .append("\",\"name\":\"").append(esc(r.name()))
          .append("\",\"hostNodeId\":\"").append(esc(r.hostNodeId()))
          .append("\"},\"members\":[");
        boolean first = true;
        for (RoomMember m : roomManager.getMembers()) {
            if (!first) sb.append(",");
            String st = roomManager.getMemberStatus(m.nodeId());
            sb.append("{\"nodeId\":\"").append(esc(m.nodeId()))
              .append("\",\"displayName\":\"").append(esc(m.displayName()))
              .append("\",\"status\":").append(st == null ? "null" : st)
              .append(",\"statusSummary\":\"")
              .append(esc(io.mctunnel.core.room.DeviceStatus.summary(st))).append("\"}");
            first = false;
        }
        sb.append("]}");
        return sb.toString();
    }

    @Override
    public void reportStatus() throws IOException {
        if (roomManager == null || roomManager.getCurrentRoom() == null) return;
        roomManager.sendMyStatus(io.mctunnel.core.room.DeviceStatus.collect(tools));
    }

    @Override
    public String networkList() throws IOException {
        if (roomManager == null) throw new IOException("房间管理器未初始化");
        if (roomManager.getCurrentRoom() == null) return "[]";
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (RoomNetwork n : roomManager.getNetworks()) {
            if (!first) sb.append(",");
            sb.append("{\"id\":\"").append(esc(n.id()))
              .append("\",\"type\":\"").append(esc(n.type().name()))
              .append("\",\"status\":\"").append(esc(n.status().name()))
              .append("\",\"endpoint\":")
              .append(n.endpoint() == null ? "null" : "\"" + esc(n.endpoint()) + "\"")
              .append("}");
            first = false;
        }
        sb.append("]");
        return sb.toString();
    }

    @Override
    public String networkCreate(String type, String name) throws IOException {
        if (roomManager == null) throw new IOException("房间管理器未初始化");
        if (roomManager.getCurrentRoom() == null) {
            throw new IOException("请先加入或创建房间");
        }
        NetworkType nt = "virtual".equalsIgnoreCase(type)
                ? NetworkType.VIRTUAL
                : NetworkType.NAT_TRAVERSAL;
        String netId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        RoomNetwork net = new RoomNetwork(netId, roomManager.getCurrentRoom().id(), nt,
                NetworkStatus.CREATED, null,
                roomManager.getCurrentRoom().hostNodeId(), System.currentTimeMillis());
        roomManager.registerNetwork(net);
        return "{\"id\":\"" + esc(net.id()) + "\",\"type\":\"" + esc(nt.name()) + "\"}";
    }

    @Override
    public String networkStart(String id) throws IOException {
        if (roomManager == null) throw new IOException("房间管理器未初始化");
        if (roomManager.getCurrentRoom() == null) {
            throw new IOException("请先加入或创建房间");
        }
        RoomNetwork data = findNetwork(id);
        if (data == null) throw new IOException("网络不存在: " + id);
        Network net = buildNetwork(data.type());
        if (data.type() == NetworkType.NAT_TRAVERSAL) {
            ((NatTraversalNetwork) net).setLocalPort(roomManager.getRoomServerPort());
        }
        net.start();
        String endpoint = net.getEndpoint();
        if (endpoint == null || endpoint.isBlank()) {
            throw new IOException("未获取到对外端点(检查 ngrok/tailscale 状态)");
        }
        if (data.type() == NetworkType.VIRTUAL && !endpoint.contains(":")) {
            endpoint = endpoint + ":" + roomManager.getRoomServerPort();
        }
        roomManager.activateAsRoomServer(endpoint);
        roomManager.registerNetwork(new RoomNetwork(data.id(), data.roomId(), data.type(),
                NetworkStatus.ACTIVE, endpoint, data.hostNodeId(), data.createdAt()));
        return "{\"endpoint\":\"" + esc(endpoint) + "\",\"active\":true}";
    }

    @Override
    public String networkStop(String id) throws IOException {
        if (roomManager == null) throw new IOException("房间管理器未初始化");
        RoomNetwork data = findNetwork(id);
        if (data == null) throw new IOException("网络不存在: " + id);
        roomManager.deactivateRoomServer();
        roomManager.registerNetwork(new RoomNetwork(data.id(), data.roomId(), data.type(),
                NetworkStatus.STOPPED, null, data.hostNodeId(), data.createdAt()));
        return "{\"active\":false}";
    }

    @Override
    public String networkConnect(String id) throws IOException {
        if (roomManager == null) throw new IOException("房间管理器未初始化");
        RoomNetwork data = findNetwork(id);
        if (data == null) throw new IOException("网络不存在: " + id);
        if (data.endpoint() == null || data.endpoint().isBlank()) {
            throw new IOException("该网络尚未启动,无端点可连接");
        }
        roomManager.connectToEndpoint(data.endpoint());
        return "{\"endpoint\":\"" + esc(data.endpoint()) + "\",\"active\":true}";
    }

    private RoomNetwork findNetwork(String id) {
        for (RoomNetwork n : roomManager.getNetworks()) {
            if (n.id().equals(id)) return n;
        }
        return null;
    }

    private Network buildNetwork(NetworkType type) {
        if (type == NetworkType.NAT_TRAVERSAL) {
            return new NatTraversalNetwork(
                    (io.mctunnel.core.tunnel.NgrokAdapter) tools.get(TunnelType.NGROK));
        }
        return new VirtualNetwork(
                (io.mctunnel.core.tunnel.TailscaleAdapter) tools.get(TunnelType.TAILSCALE));
    }

    /** JSON 字符串转义 */
    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    private io.mctunnel.core.tunnel.TailscaleAdapter tailscale()
            throws java.io.IOException {
        TunnelTool t = tools.get(TunnelType.TAILSCALE);
        if (!(t instanceof io.mctunnel.core.tunnel.TailscaleAdapter ts)) {
            throw new java.io.IOException("tailscale 不可用");
        }
        return ts;
    }

    @Override
    public String describeBackend() {
        return "内嵌(游戏进程)";
    }
}
