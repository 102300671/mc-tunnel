package io.mctunnel.core.room;

import io.mctunnel.core.chat.ChatMessage;
import io.mctunnel.core.relay.client.RelayClient;
import io.mctunnel.core.storage.ChatStorage;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 房间管理器(客户端侧).
 * <p>
 * 整合 {@link RelayClient}(云中继)与 {@link ChatStorage}(持久化),
 * 提供房间的创建/加入/离开、成员维护、聊天收发.
 * <p>
 * 网络的可见性控制:仅已加入房间的成员可查询该房间的网络列表.
 */
public class RoomManager {

    private final String selfNodeId;
    private final String displayName;
    private final ChatStorage storage;
    private RelayClient relay;
    private volatile Room currentRoom;
    private final List<RoomMember> members = new CopyOnWriteArrayList<>();
    private final List<ChatHandler> chatHandlers = new CopyOnWriteArrayList<>();

    public interface ChatHandler { void onChat(ChatMessage msg); }

    public RoomManager(String selfNodeId, String displayName, ChatStorage storage) {
        this.selfNodeId = selfNodeId;
        this.displayName = displayName;
        this.storage = storage;
    }

    public String getSelfNodeId() { return selfNodeId; }
    public Room getCurrentRoom() { return currentRoom; }
    public List<RoomMember> getMembers() { return List.copyOf(members); }

    public void onChat(ChatHandler h) { chatHandlers.add(h); }

    // ── 房间操作 ──────────────────────────────────────────

    /**
     * 创建房间(房主).连接云中继,注册房间,返回加入链接.
     *
     * @param relayHost 中继主机
     * @param relayPort 中继端口
     * @param name      房间名称
     * @return 加入链接
     */
    public String createRoom(String relayHost, int relayPort, String name) throws IOException {
        relay = new RelayClient(selfNodeId, displayName);
        relay.onChat(this::onRelayChat);
        relay.onMemberEvent(new RelayClient.MemberEventHandler() {
            @Override public void onMemberJoined(RoomMember m) {
                members.removeIf(x -> x.nodeId().equals(m.nodeId()));
                members.add(m);
                storage.addRoomMember(currentRoom.id(), m);
            }
            @Override public void onMemberLeft(String nodeId) {
                members.removeIf(x -> x.nodeId().equals(nodeId));
                storage.removeRoomMember(currentRoom.id(), nodeId);
            }
        });
        relay.onRoomClosed(rid -> {
            // 房主关闭了房间,清理本地状态
            currentRoom = null;
            members.clear();
        });
        relay.connect(relayHost, relayPort);
        relay.createRoom(name);
        // 等待 room_created 设置 roomId(异步)
        String roomId = waitForRoomId(3000);
        if (roomId == null) throw new IOException("创建房间超时:未收到中继响应");
        currentRoom = new Room(roomId, name, selfNodeId, System.currentTimeMillis());
        storage.saveRoom(currentRoom);
        storage.addRoomMember(roomId, new RoomMember(selfNodeId, displayName, System.currentTimeMillis()));
        members.add(new RoomMember(selfNodeId, displayName, System.currentTimeMillis()));
        return LinkCodec.encode(relayHost, relayPort, roomId);
    }

    /**
     * 通过链接加入房间(成员).
     *
     * @param link 加入链接
     */
    public void joinRoom(String link) throws IOException {
        JoinLink jl = LinkCodec.decode(link);
        relay = new RelayClient(selfNodeId, displayName);
        relay.onChat(this::onRelayChat);
        relay.onMemberEvent(new RelayClient.MemberEventHandler() {
            @Override public void onMemberJoined(RoomMember m) {
                members.removeIf(x -> x.nodeId().equals(m.nodeId()));
                members.add(m);
                if (currentRoom != null) storage.addRoomMember(currentRoom.id(), m);
            }
            @Override public void onMemberLeft(String nodeId) {
                members.removeIf(x -> x.nodeId().equals(nodeId));
                if (currentRoom != null) storage.removeRoomMember(currentRoom.id(), nodeId);
            }
        });
        relay.onRoomClosed(rid -> {
            currentRoom = null;
            members.clear();
        });
        relay.connect(jl.relayHost(), jl.relayPort());
        relay.joinRoomById(jl.roomId());
        // 从存储加载房间信息(房主创建时已持久化)
        Room room = storage.loadRoom(jl.roomId());
        if (room == null) {
            room = new Room(jl.roomId(), "Room", "", System.currentTimeMillis());
        }
        currentRoom = room;
        storage.addRoomMember(jl.roomId(), new RoomMember(selfNodeId, displayName, System.currentTimeMillis()));
        members.add(new RoomMember(selfNodeId, displayName, System.currentTimeMillis()));
        // 从存储加载已有成员
        members.addAll(storage.loadRoomMembers(jl.roomId()));
    }

    /** 离开/关闭房间.若当前节点是房主,则关闭房间并通知所有成员 */
    public void leaveRoom() throws IOException {
        if (relay != null) {
            try {
                if (currentRoom != null && selfNodeId.equals(currentRoom.hostNodeId())) {
                    relay.closeRoom();
                } else {
                    relay.leaveRoom();
                }
            } catch (IOException ignored) {}
            relay.close();
            relay = null;
        }
        if (currentRoom != null) {
            storage.removeRoomMember(currentRoom.id(), selfNodeId);
        }
        currentRoom = null;
        members.clear();
    }

    // ── 聊天 ──────────────────────────────────────────────

    /** 发送聊天消息到当前房间 */
    public void sendChat(String sender, String content) throws IOException {
        if (relay == null || currentRoom == null) {
            throw new IOException("未加入房间");
        }
        ChatMessage msg = ChatMessage.create(selfNodeId, sender, content, currentRoom.id());
        storage.saveMessage(msg);
        relay.sendChat(sender, content);
        for (ChatHandler h : chatHandlers) h.onChat(msg);
    }

    private void onRelayChat(ChatMessage msg) {
        // 中继转发来的消息(其他成员发的),持久化并通知本地
        storage.saveMessage(msg);
        for (ChatHandler h : chatHandlers) h.onChat(msg);
    }

    /** 读取当前房间的聊天历史 */
    public List<ChatMessage> loadChatHistory(int limit) {
        if (currentRoom == null) return List.of();
        return storage.loadRecentMessages(currentRoom.id(), limit);
    }

    /**
     * 从存储恢复上次加入的房间(不重连中继).
     * 用于 CLI 跨进程调用时恢复房间上下文(如 network create/list).
     */
    public boolean loadLastRoom() {
        List<String> roomIds = storage.loadRoomIdsByNode(selfNodeId);
        if (roomIds.isEmpty()) return false;
        String rid = roomIds.get(0);
        Room room = storage.loadRoom(rid);
        if (room == null) return false;
        currentRoom = room;
        members.clear();
        members.addAll(storage.loadRoomMembers(rid));
        return true;
    }

    // ── 网络可见性 ────────────────────────────────────────

    /**
     * 查询当前房间的网络列表.
     * 只有已加入房间才能查到(未加入时 currentRoom 为 null,返回空).
     */
    public List<RoomNetwork> getNetworks() {
        if (currentRoom == null) return List.of();
        return storage.loadNetworksByRoom(currentRoom.id());
    }

    /** 保存网络到当前房间(由 Network 层调用) */
    public void registerNetwork(RoomNetwork network) {
        if (currentRoom == null) return;
        storage.saveNetwork(network);
    }

    // ── 内部 ──────────────────────────────────────────────

    private String waitForRoomId(long timeoutMs) {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (relay != null && relay.getRoomId() != null) {
                return relay.getRoomId();
            }
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
