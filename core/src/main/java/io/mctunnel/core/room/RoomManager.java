package io.mctunnel.core.room;

import io.mctunnel.core.chat.ChatMessage;
import io.mctunnel.core.relay.client.RelayClient;
import io.mctunnel.core.relay.server.RelayServer;
import io.mctunnel.core.storage.ChatStorage;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 房间管理器(客户端侧).
 * <p>
 * 双中继模型:
 * <ul>
 *   <li>{@code bootstrapRelay} — 引导中继(云服务器初始网络主机),创建/加入房间时建立,承担引导与回退;</li>
 *   <li>{@code primaryRelay} — 活跃中继,初始等于引导中继;房主启用房间服务端(内网穿透/虚拟组网)后,
 *       房主与成员切换到房主端点,聊天/成员状态/事件改经已建立的网络,不再走云中继.</li>
 * </ul>
 * 房主在切换后对引导中继双发,以覆盖仍走云中继的成员;主中继断线自动回退引导中继.
 * 网络的可见性控制:仅已加入房间的成员可查询该房间的网络列表.
 */
public class RoomManager {

    private final String selfNodeId;
    private final String displayName;
    private final ChatStorage storage;
    /** 引导中继(云服务器):创建/加入房间时建立,承担引导与回退 */
    private RelayClient bootstrapRelay;
    /** 活跃中继:初始等于引导中继;切换到房间服务端后为房主端点连接 */
    private volatile RelayClient primaryRelay;
    private String bootstrapHost;
    private int bootstrapPort;
    private volatile Room currentRoom;
    private volatile boolean isHost;
    /** 当前使用的房间服务端端点(host:port);null 表示引导模式 */
    private volatile String activeEndpoint;
    /** 房主本地房间中继(网络建立后,作为房间服务端) */
    private volatile RelayServer localServer;
    /** 房主本地中继端口(默认 8721,环境变量 MCTUNNEL_ROOM_SERVER_PORT 覆盖) */
    private final int roomServerPort;
    private final List<RoomMember> members = new CopyOnWriteArrayList<>();
    private final List<ChatHandler> chatHandlers = new CopyOnWriteArrayList<>();
    /** 成员网络状态缓存(nodeId -> status JSON 对象),含本机 */
    private final Map<String, String> memberStatus = new ConcurrentHashMap<>();
    private final List<MemberStatusHandler> statusHandlers = new CopyOnWriteArrayList<>();
    /** 引导中继重连中标志(防并发重连) */
    private final java.util.concurrent.atomic.AtomicBoolean bootstrapReconnecting =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public interface ChatHandler { void onChat(ChatMessage msg); }
    public interface MemberStatusHandler { void onMemberStatus(String nodeId, String statusJson); }

    public RoomManager(String selfNodeId, String displayName, ChatStorage storage) {
        this.selfNodeId = selfNodeId;
        this.displayName = displayName;
        this.storage = storage;
        this.roomServerPort = Integer.parseInt(System.getenv()
                .getOrDefault("MCTUNNEL_ROOM_SERVER_PORT", "8721"));
    }

    public String getSelfNodeId() { return selfNodeId; }
    public Room getCurrentRoom() { return currentRoom; }
    public List<RoomMember> getMembers() { return List.copyOf(members); }
    /** 当前活跃端点(host:port),null 表示仍走引导中继 */
    public String getActiveEndpoint() { return activeEndpoint; }
    /** 是否已启用房间服务端(房主且本地中继在跑) */
    public boolean isRoomServerRunning() { return localServer != null; }
    public int getRoomServerPort() { return roomServerPort; }

    public void onChat(ChatHandler h) { chatHandlers.add(h); }
    public void onMemberStatus(MemberStatusHandler h) { statusHandlers.add(h); }

    // ── 房间操作 ──────────────────────────────────────────

    /**
     * 创建房间(房主).连接引导中继(云服务器),注册房间,返回加入链接.
     */
    public String createRoom(String relayHost, int relayPort, String name) throws IOException {
        bootstrapHost = relayHost;
        bootstrapPort = relayPort;
        bootstrapRelay = new RelayClient(selfNodeId, displayName);
        wire(bootstrapRelay);
        bootstrapRelay.connect(relayHost, relayPort);
        bootstrapRelay.createRoom(name);
        String roomId = waitForRoomId(bootstrapRelay, 3000);
        if (roomId == null) throw new IOException("创建房间超时:未收到中继响应");
        currentRoom = new Room(roomId, name, selfNodeId, System.currentTimeMillis());
        isHost = true;
        primaryRelay = bootstrapRelay;
        storage.saveRoom(currentRoom);
        storage.addRoomMember(roomId, new RoomMember(selfNodeId, displayName, System.currentTimeMillis()));
        members.removeIf(x -> x.nodeId().equals(selfNodeId));
        members.add(new RoomMember(selfNodeId, displayName, System.currentTimeMillis()));
        return LinkCodec.encode(relayHost, relayPort, roomId);
    }

    /**
     * 通过链接加入房间(成员).连接引导中继并加入.
     */
    public void joinRoom(String link) throws IOException {
        JoinLink jl = LinkCodec.decode(link);
        bootstrapHost = jl.relayHost();
        bootstrapPort = jl.relayPort();
        bootstrapRelay = new RelayClient(selfNodeId, displayName);
        wire(bootstrapRelay);
        bootstrapRelay.connect(jl.relayHost(), jl.relayPort());
        bootstrapRelay.joinRoomById(jl.roomId());
        Room room = storage.loadRoom(jl.roomId());
        if (room == null) {
            room = new Room(jl.roomId(), "Room", "", System.currentTimeMillis());
        }
        currentRoom = room;
        isHost = room.hostNodeId() != null && room.hostNodeId().equals(selfNodeId);
        primaryRelay = bootstrapRelay;
        storage.addRoomMember(jl.roomId(), new RoomMember(selfNodeId, displayName, System.currentTimeMillis()));
        members.removeIf(x -> x.nodeId().equals(selfNodeId));
        members.add(new RoomMember(selfNodeId, displayName, System.currentTimeMillis()));
        for (RoomMember m : storage.loadRoomMembers(jl.roomId())) {
            if (!m.nodeId().equals(selfNodeId)) members.add(m);
        }
    }

    /** 离开/关闭房间.若当前节点是房主,关闭房间并通知所有成员 */
    public void leaveRoom() throws IOException {
        stopLocalServer();
        if (primaryRelay != null && primaryRelay != bootstrapRelay) {
            try { primaryRelay.close(); } catch (Exception ignored) {}
        }
        if (bootstrapRelay != null) {
            try {
                if (isHost) bootstrapRelay.closeRoom();
                else bootstrapRelay.leaveRoom();
            } catch (IOException ignored) {}
            try { bootstrapRelay.close(); } catch (Exception ignored) {}
            bootstrapRelay = null;
        }
        primaryRelay = null;
        if (currentRoom != null) {
            storage.removeRoomMember(currentRoom.id(), selfNodeId);
        }
        currentRoom = null;
        members.clear();
        memberStatus.clear();
        isHost = false;
        activeEndpoint = null;
    }

    // ── 房间服务端(房主) ──────────────────────────────────

    /**
     * 房主启用本机为房间服务端(网络建立后调用).
     * 启动本地中继(预置当前房间)→ 经引导中继广播端点 → 本机也切到本地中继.
     *
     * @param endpointHostPort 对外可达端点(如 100.x.x.x:8721 / ngrok tcp 地址)
     * @return 端点
     */
    public String activateAsRoomServer(String endpointHostPort) throws IOException {
        if (currentRoom == null) throw new IOException("未加入房间");
        if (!isHost) throw new IOException("仅房主可启动房间服务端");
        if (localServer == null) {
            RelayServer srv = new RelayServer(roomServerPort, endpointHostPort);
            srv.preRegisterRoom(currentRoom.id(), currentRoom.name(), selfNodeId);
            srv.start();
            localServer = srv;
        }
        // 经引导中继广播端点(成员自动切换)
        if (bootstrapRelay != null && bootstrapRelay.isConnected()) {
            bootstrapRelay.sendEndpoint(List.of(endpointHostPort));
        }
        // 本机切到本地中继(127.0.0.1 更稳),对外端点仍记 endpointHostPort 用于防重
        if (!endpointHostPort.equals(activeEndpoint)) {
            switchPrimary("127.0.0.1:" + roomServerPort, endpointHostPort);
        }
        return endpointHostPort;
    }

    /** 房主停止本机房间服务端(停本地中继,主中继回退引导中继) */
    public void deactivateRoomServer() {
        stopLocalServer();
        if (primaryRelay != null && primaryRelay != bootstrapRelay) {
            try { primaryRelay.close(); } catch (Exception ignored) {}
        }
        primaryRelay = bootstrapRelay;
        activeEndpoint = null;
        System.out.println("[Room] 房间服务端已停止,回退引导中继");
    }

    /** 手动切换到指定房间服务端端点(成员调试/手动场景) */
    public void connectToEndpoint(String endpointHostPort) throws IOException {
        if (currentRoom == null) throw new IOException("未加入房间");
        switchPrimary(endpointHostPort, endpointHostPort);
    }

    // ── 聊天/状态 ─────────────────────────────────────────

    /** 发送聊天消息到当前房间(房主在切换后对引导中继双发) */
    public void sendChat(String sender, String content) throws IOException {
        if (primaryRelay == null || currentRoom == null) {
            throw new IOException("未加入房间");
        }
        ChatMessage msg = ChatMessage.create(selfNodeId, sender, content, currentRoom.id());
        storage.saveMessage(msg);
        primaryRelay.sendChat(sender, content);
        if (isHost && primaryRelay != bootstrapRelay && bootstrapRelay != null
                && bootstrapRelay.isConnected()) {
            bootstrapRelay.sendChat(sender, content);
        }
        for (ChatHandler h : chatHandlers) h.onChat(msg);
    }

    /** 上报本机网络状态到当前房间(房主在切换后对引导中继双发) */
    public void sendMyStatus(String statusJson) throws IOException {
        if (primaryRelay == null || currentRoom == null) {
            throw new IOException("未加入房间");
        }
        primaryRelay.sendMemberStatus(statusJson);
        if (isHost && primaryRelay != bootstrapRelay && bootstrapRelay != null
                && bootstrapRelay.isConnected()) {
            bootstrapRelay.sendMemberStatus(statusJson);
        }
        memberStatus.put(selfNodeId, statusJson);
    }

    /** 全部成员的网络状态快照(nodeId -> status JSON),含本机;未上报的成员不在此 map */
    public Map<String, String> getMemberStatus() {
        return Map.copyOf(memberStatus);
    }

    /** 指定成员的网络状态 JSON;未上报返回 null */
    public String getMemberStatus(String nodeId) {
        return memberStatus.get(nodeId);
    }

    /** 读取当前房间的聊天历史 */
    public List<ChatMessage> loadChatHistory(int limit) {
        if (currentRoom == null) return List.of();
        return storage.loadRecentMessages(currentRoom.id(), limit);
    }

    /**
     * 从存储恢复上次加入的房间(不重连中继).
     * 用于 CLI 跨进程调用时恢复房间上下文(如 network create/list/start).
     */
    public boolean loadLastRoom() {
        List<String> roomIds = storage.loadRoomIdsByNode(selfNodeId);
        if (roomIds.isEmpty()) return false;
        String rid = roomIds.get(0);
        Room room = storage.loadRoom(rid);
        if (room == null) return false;
        currentRoom = room;
        isHost = room.hostNodeId() != null && room.hostNodeId().equals(selfNodeId);
        members.clear();
        members.addAll(storage.loadRoomMembers(rid));
        return true;
    }

    // ── 网络可见性 ────────────────────────────────────────

    /** 查询当前房间的网络列表(仅已加入房间可查) */
    public List<RoomNetwork> getNetworks() {
        if (currentRoom == null) return List.of();
        return storage.loadNetworksByRoom(currentRoom.id());
    }

    /** 保存网络到当前房间(由 Network 层调用) */
    public void registerNetwork(RoomNetwork network) {
        if (currentRoom == null) return;
        storage.saveNetwork(network);
    }

    // ── 事件接线 ──────────────────────────────────────────

    private void wire(RelayClient c) {
        c.onChat(this::onRelayChat);
        c.onMemberEvent(new RelayClient.MemberEventHandler() {
            @Override public void onMemberJoined(RoomMember m) {
                members.removeIf(x -> x.nodeId().equals(m.nodeId()));
                members.add(m);
                if (currentRoom != null) storage.addRoomMember(currentRoom.id(), m);
            }
            @Override public void onMemberLeft(String nodeId) {
                // 成员离开只看主通道:切换后云中继会广播被切走成员的 leave,
                // 该成员仍在房主中继内,忽略以免误删
                if (c != primaryRelay) return;
                members.removeIf(x -> x.nodeId().equals(nodeId));
                memberStatus.remove(nodeId);
                if (currentRoom != null) storage.removeRoomMember(currentRoom.id(), nodeId);
            }
        });
        c.onMemberStatus(this::onRelayMemberStatus);
        c.onEndpoint(this::onRoomEndpoint);
        c.onRoomClosed(rid -> {
            // 房主关闭了房间,清理本地状态
            stopLocalServer();
            if (primaryRelay != null && primaryRelay != bootstrapRelay) {
                try { primaryRelay.close(); } catch (Exception ignored) {}
            }
            primaryRelay = null;
            bootstrapRelay = null;
            currentRoom = null;
            members.clear();
            memberStatus.clear();
            isHost = false;
            activeEndpoint = null;
        });
        c.onDisconnect(() -> onRelayDisconnected(c));
    }

    private void onRelayChat(ChatMessage msg) {
        storage.saveMessage(msg);
        for (ChatHandler h : chatHandlers) h.onChat(msg);
    }

    private void onRelayMemberStatus(String nodeId, String statusJson) {
        memberStatus.put(nodeId, statusJson);
        for (MemberStatusHandler h : statusHandlers) h.onMemberStatus(nodeId, statusJson);
    }

    /** 成员侧:收到房间服务端端点广播,自动切换到已建立网络 */
    private void onRoomEndpoint(String nodeId, List<String> endpoints) {
        if (currentRoom == null || isHost) return;
        if (nodeId.equals(selfNodeId)) return;
        for (String ep : endpoints) {
            if (ep == null || ep.isBlank()) continue;
            String addr = ep.contains("://") ? ep.substring(ep.indexOf("://") + 3) : ep;
            if (addr.equals(activeEndpoint)) return;
            Thread t = new Thread(() -> {
                try {
                    switchPrimary(addr, addr);
                    System.out.println("[Room] 已切换到房间服务端: " + addr);
                } catch (Exception e) {
                    System.out.println("[Room] 切换到 " + addr + " 失败: " + e.getMessage());
                }
            }, "room-switch");
            t.setDaemon(true);
            t.start();
            return;
        }
    }

    /**
     * 替换活跃中继为指向指定端点的连接.
     *
     * @param endpointHostPort 实际连接的地址(host:port)
     * @param publicEndpoint   对外端点(用于防重复切换记录)
     */
    private synchronized void switchPrimary(String endpointHostPort, String publicEndpoint)
            throws IOException {
        if (currentRoom == null) return;
        String[] hp = endpointHostPort.split(":");
        if (hp.length != 2) throw new IOException("端点格式应为 host:port: " + endpointHostPort);
        RelayClient c = new RelayClient(selfNodeId, displayName);
        wire(c);
        c.connect(hp[0], Integer.parseInt(hp[1]));
        c.joinRoomById(currentRoom.id());
        RelayClient old = primaryRelay;
        primaryRelay = c;
        activeEndpoint = publicEndpoint;
        // 在新主中继上重报本机状态
        String st = memberStatus.get(selfNodeId);
        if (st != null) {
            try { c.sendMemberStatus(st); } catch (IOException ignored) {}
        }
        if (old != null && old != bootstrapRelay) {
            try { old.close(); } catch (Exception ignored) {}
        }
        // 成员切换成功后离开云中继房间(避免房主双发时重复接收);
        // 连接保留,回退时重新 join。房主保留挂名以覆盖仍走云中继的成员。
        if (!isHost && old == bootstrapRelay && bootstrapRelay != null
                && bootstrapRelay.isConnected()) {
            try { bootstrapRelay.leaveRoom(); } catch (IOException ignored) {}
        }
    }

    /** 主中继断线处理:若切到了房间服务端,自动回退引导中继(云服务器) */
    private void onRelayDisconnected(RelayClient c) {
        if (c == primaryRelay && primaryRelay != bootstrapRelay) {
            primaryRelay = bootstrapRelay;
            activeEndpoint = null;
            System.out.println("[Room] 房间服务端连接断开,回退引导中继");
            if (bootstrapRelay != null && bootstrapRelay.isConnected() && currentRoom != null) {
                try {
                    bootstrapRelay.joinRoomById(currentRoom.id());
                    String st = memberStatus.get(selfNodeId);
                    if (st != null) bootstrapRelay.sendMemberStatus(st);
                } catch (IOException e) {
                    System.out.println("[Room] 回退引导中继失败: " + e.getMessage());
                }
            }
        }
        // 引导中继断线:自动重连(回退生命线),带防重入与循环重试
        if (c == bootstrapRelay) {
            reconnectBootstrap();
        }
    }

    /** 引导中继断线重连:循环尝试直至成功(每 5s),成功后恢复房间挂名 */
    private void reconnectBootstrap() {
        if (!bootstrapReconnecting.compareAndSet(false, true)) return;
        Thread t = new Thread(() -> {
            try {
                int attempt = 0;
                while (!Thread.currentThread().isInterrupted()) {
                    attempt++;
                    try {
                        RelayClient nb = new RelayClient(selfNodeId, displayName);
                        wire(nb);
                        nb.connect(bootstrapHost, bootstrapPort);
                        if (currentRoom != null) nb.joinRoomById(currentRoom.id());
                        RelayClient old;
                        synchronized (this) {
                            old = bootstrapRelay;
                            bootstrapRelay = nb;
                            if (primaryRelay == old || primaryRelay == null) {
                                primaryRelay = nb;
                            }
                        }
                        if (old != null) {
                            try { old.close(); } catch (Exception ignored) {}
                        }
                        System.out.println("[Room] 引导中继已重连(" + attempt + "): "
                                + bootstrapHost + ":" + bootstrapPort);
                        break;
                    } catch (Exception e) {
                        System.out.println("[Room] 引导中继重连失败(" + attempt + "): "
                                + e.getMessage());
                        try { Thread.sleep(5000); }
                        catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            } finally {
                bootstrapReconnecting.set(false);
            }
        }, "bootstrap-reconnect");
        t.setDaemon(true);
        t.start();
    }

    private void stopLocalServer() {
        if (localServer != null) {
            localServer.stop();
            localServer = null;
        }
    }

    // ── 内部 ──────────────────────────────────────────────

    private String waitForRoomId(RelayClient c, long timeoutMs) {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (c.getRoomId() != null) return c.getRoomId();
            try { Thread.sleep(50); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    /** 生成本节点 ID */
    public static String generateNodeId() {
        return UUID.randomUUID().toString();
    }
}
