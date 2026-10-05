package io.mctunnel.core.storage;

import io.mctunnel.core.chat.ChatMessage;
import io.mctunnel.core.room.NetworkStatus;
import io.mctunnel.core.room.NetworkType;
import io.mctunnel.core.room.Room;
import io.mctunnel.core.room.RoomMember;
import io.mctunnel.core.room.RoomNetwork;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 聊天、房间、网络与配置持久化存储.
 * <p>
 * 优先使用 SQLite(需 sqlite-jdbc 驱动在 classpath 上);
 * 若驱动不可用,自动回退到内存存储.
 * <p>
 * 表结构:
 * <ul>
 *   <li>chat_messages(id, node_id, sender, content, timestamp, room_id)</li>
 *   <li>rooms(id, name, host_node_id, created_at)</li>
 *   <li>room_members(room_id, node_id, display_name, joined_at)</li>
 *   <li>networks(id, room_id, type, status, endpoint, host_node_id, created_at)</li>
 *   <li>network_members(network_id, node_id, joined_at)</li>
 *   <li>tunnel_configs(tool, key, value)</li>
 *   <li>nodes(node_id, address, last_seen)</li>
 * </ul>
 */
public final class ChatStorage implements io.mctunnel.core.tunnel.ToolConfigStore {

    private final boolean sqliteAvailable;
    private Connection conn;

    // ── 内存回退存储 ──────────────────────────────────────
    private final List<ChatMessage> memoryMessages = new CopyOnWriteArrayList<>();
    private final Map<String, Map<String, String>> memoryConfigs = new ConcurrentHashMap<>();
    private final Map<String, NodeInfo> memoryNodes = new ConcurrentHashMap<>();
    private final Map<String, Room> memoryRooms = new ConcurrentHashMap<>();
    private final Map<String, List<RoomMember>> memoryRoomMembers = new ConcurrentHashMap<>();
    private final Map<String, List<RoomNetwork>> memoryNetworks = new ConcurrentHashMap<>();
    private final Map<String, List<String>> memoryNetworkMembers = new ConcurrentHashMap<>();

    public ChatStorage() {
        this.sqliteAvailable = initSqlite();
    }

    private boolean initSqlite() {
        try {
            Class.forName("org.sqlite.JDBC");
            Path dbPath = io.mctunnel.core.DataDir.dbFile();
            Files.createDirectories(dbPath.getParent());
            conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            createTables();
            migrateRoomIdColumn();
            createRoomIndexes();
            System.out.println("[MC-Tunnel] SQLite storage initialized at " + dbPath);
            return true;
        } catch (Throwable t) {
            System.out.println("[MC-Tunnel] SQLite unavailable (" + t.getMessage()
                    + "), using in-memory storage.");
            return false;
        }
    }

    private void createTables() throws java.sql.SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS chat_messages (
                        id TEXT PRIMARY KEY,
                        node_id TEXT NOT NULL,
                        sender TEXT NOT NULL,
                        content TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        room_id TEXT NOT NULL DEFAULT ''
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS rooms (
                        id TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        host_node_id TEXT NOT NULL,
                        created_at INTEGER NOT NULL
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS room_members (
                        room_id TEXT NOT NULL,
                        node_id TEXT NOT NULL,
                        display_name TEXT NOT NULL,
                        joined_at INTEGER NOT NULL,
                        PRIMARY KEY (room_id, node_id)
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS networks (
                        id TEXT PRIMARY KEY,
                        room_id TEXT NOT NULL,
                        type TEXT NOT NULL,
                        status TEXT NOT NULL,
                        endpoint TEXT,
                        host_node_id TEXT NOT NULL,
                        created_at INTEGER NOT NULL
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS network_members (
                        network_id TEXT NOT NULL,
                        node_id TEXT NOT NULL,
                        joined_at INTEGER NOT NULL,
                        PRIMARY KEY (network_id, node_id)
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS tunnel_configs (
                        tool TEXT NOT NULL,
                        key TEXT NOT NULL,
                        value TEXT,
                        PRIMARY KEY (tool, key)
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS nodes (
                        node_id TEXT PRIMARY KEY,
                        address TEXT,
                        last_seen INTEGER NOT NULL
                    )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_chat_ts ON chat_messages(timestamp)");
        }
    }

    /** 创建依赖 room_id 列的索引(必须在迁移之后调用) */
    private void createRoomIndexes() throws java.sql.SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE INDEX IF NOT EXISTS idx_chat_room ON chat_messages(room_id)");
        }
    }

    /** 旧库升级:chat_messages 表缺 room_id 列时补上 */
    private void migrateRoomIdColumn() {
        try (Statement st = conn.createStatement()) {
            // PRAGMA table_info 返回列信息,检查是否已有 room_id
            boolean hasRoomId = false;
            try (ResultSet rs = st.executeQuery("PRAGMA table_info(chat_messages)")) {
                while (rs.next()) {
                    if ("room_id".equals(rs.getString("name"))) {
                        hasRoomId = true;
                        break;
                    }
                }
            }
            if (!hasRoomId) {
                st.execute("ALTER TABLE chat_messages ADD COLUMN room_id TEXT NOT NULL DEFAULT ''");
                System.out.println("[MC-Tunnel] Migrated chat_messages: added room_id column");
            }
        } catch (Exception e) {
            System.err.println("[MC-Tunnel] migrateRoomIdColumn failed: " + e.getMessage());
        }
    }

    /** 是否使用 SQLite(否则为内存回退) */
    public boolean isPersistent() {
        return sqliteAvailable;
    }

    // ── 聊天消息 ──────────────────────────────────────────

    /** 保存一条消息 */
    public void saveMessage(ChatMessage msg) {
        if (!sqliteAvailable) {
            memoryMessages.add(msg);
            if (memoryMessages.size() > 5000) memoryMessages.remove(0);
            return;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO chat_messages(id,node_id,sender,content,timestamp,room_id) VALUES(?,?,?,?,?,?)")) {
            ps.setString(1, msg.id());
            ps.setString(2, msg.nodeId());
            ps.setString(3, msg.sender());
            ps.setString(4, msg.content());
            ps.setLong(5, msg.timestamp());
            ps.setString(6, msg.roomId() == null ? "" : msg.roomId());
            ps.executeUpdate();
        } catch (Exception e) {
            System.err.println("[MC-Tunnel] saveMessage failed: " + e.getMessage());
        }
    }

    /** 读取最近 limit 条消息(按时间升序),按房间过滤 */
    public List<ChatMessage> loadRecentMessages(String roomId, int limit) {
        String rid = roomId == null ? "" : roomId;
        if (!sqliteAvailable) {
            List<ChatMessage> filtered = new ArrayList<>();
            for (ChatMessage m : memoryMessages) {
                String mRoom = m.roomId() == null ? "" : m.roomId();
                if (rid.equals(mRoom)) filtered.add(m);
            }
            int size = filtered.size();
            return new ArrayList<>(filtered.subList(Math.max(0, size - limit), size));
        }
        List<ChatMessage> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id,node_id,sender,content,timestamp,room_id FROM chat_messages "
                        + "WHERE room_id = ? ORDER BY timestamp DESC LIMIT ?")) {
            ps.setString(1, rid);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<ChatMessage> reversed = new ArrayList<>();
                while (rs.next()) {
                    reversed.add(new ChatMessage(
                            rs.getString("id"),
                            rs.getString("node_id"),
                            rs.getString("sender"),
                            rs.getString("content"),
                            rs.getLong("timestamp"),
                            rs.getString("room_id")));
                }
                for (int i = reversed.size() - 1; i >= 0; i--) {
                    list.add(reversed.get(i));
                }
            }
        } catch (Exception e) {
            System.err.println("[MC-Tunnel] loadRecentMessages failed: " + e.getMessage());
        }
        return list;
    }

    /** 兼容旧调用:读取全局房间(room_id='')的最近消息 */
    public List<ChatMessage> loadRecentMessages(int limit) {
        return loadRecentMessages("", limit);
    }

    /** 清理超过 maxAgeMs 的旧消息 */
    public int purgeOldMessages(long maxAgeMs) {
        if (!sqliteAvailable) {
            long cutoff = System.currentTimeMillis() - maxAgeMs;
            memoryMessages.removeIf(m -> m.timestamp() < cutoff);
            return 0;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM chat_messages WHERE timestamp < ?")) {
            ps.setLong(1, System.currentTimeMillis() - maxAgeMs);
            return ps.executeUpdate();
        } catch (Exception e) {
            System.err.println("[MC-Tunnel] purgeOldMessages failed: " + e.getMessage());
            return 0;
        }
    }

    // ── 房间 ──────────────────────────────────────────────

    /** 保存房间(upsert) */
    public void saveRoom(Room room) {
        if (!sqliteAvailable) {
            memoryRooms.put(room.id(), room);
            memoryRoomMembers.computeIfAbsent(room.id(), k -> new CopyOnWriteArrayList<>());
            memoryNetworks.computeIfAbsent(room.id(), k -> new CopyOnWriteArrayList<>());
            return;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO rooms(id,name,host_node_id,created_at) VALUES(?,?,?,?)")) {
            ps.setString(1, room.id());
            ps.setString(2, room.name());
            ps.setString(3, room.hostNodeId());
            ps.setLong(4, room.createdAt());
            ps.executeUpdate();
        } catch (Exception e) {
            System.err.println("[MC-Tunnel] saveRoom failed: " + e.getMessage());
        }
    }

    /** 读取房间 */
    public Room loadRoom(String roomId) {
        if (!sqliteAvailable) {
            return memoryRooms.get(roomId);
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id,name,host_node_id,created_at FROM rooms WHERE id = ?")) {
            ps.setString(1, roomId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new Room(
                            rs.getString("id"),
                            rs.getString("name"),
                            rs.getString("host_node_id"),
                            rs.getLong("created_at"));
                }
            }
        } catch (Exception e) {
            System.err.println("[MC-Tunnel] loadRoom failed: " + e.getMessage());
        }
        return null;
    }

    /** 删除房间及其成员、网络 */
    public void deleteRoom(String roomId) {
        if (!sqliteAvailable) {
            memoryRooms.remove(roomId);
            memoryRoomMembers.remove(roomId);
            memoryNetworks.remove(roomId);
            return;
        }
        try {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM rooms WHERE id = ?")) {
                ps.setString(1, roomId); ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM room_members WHERE room_id = ?")) {
                ps.setString(1, roomId); ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM networks WHERE room_id = ?")) {
                ps.setString(1, roomId); ps.executeUpdate();
            }
            conn.commit();
        } catch (Exception e) {
            try { conn.rollback(); } catch (Exception ignored) {}
            System.err.println("[MC-Tunnel] deleteRoom failed: " + e.getMessage());
        } finally {
            try { conn.setAutoCommit(true); } catch (Exception ignored) {}
        }
    }

    /** 添加房间成员 */
    public void addRoomMember(String roomId, RoomMember member) {
        if (!sqliteAvailable) {
            memoryRoomMembers.computeIfAbsent(roomId, k -> new CopyOnWriteArrayList<>())
                    .removeIf(m -> m.nodeId().equals(member.nodeId()));
            memoryRoomMembers.get(roomId).add(member);
            return;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO room_members(room_id,node_id,display_name,joined_at) VALUES(?,?,?,?)")) {
            ps.setString(1, roomId);
            ps.setString(2, member.nodeId());
            ps.setString(3, member.displayName());
            ps.setLong(4, member.joinedAt());
            ps.executeUpdate();
        } catch (Exception e) {
            System.err.println("[MC-Tunnel] addRoomMember failed: " + e.getMessage());
        }
    }

    /** 移除房间成员 */
    public void removeRoomMember(String roomId, String nodeId) {
        if (!sqliteAvailable) {
            List<RoomMember> list = memoryRoomMembers.get(roomId);
            if (list != null) list.removeIf(m -> m.nodeId().equals(nodeId));
            return;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM room_members WHERE room_id = ? AND node_id = ?")) {
            ps.setString(1, roomId);
            ps.setString(2, nodeId);
            ps.executeUpdate();
        } catch (Exception e) {
            System.err.println("[MC-Tunnel] removeRoomMember failed: " + e.getMessage());
        }
    }

    /** 读取房间所有成员 */
    public List<RoomMember> loadRoomMembers(String roomId) {
        if (!sqliteAvailable) {
            List<RoomMember> list = memoryRoomMembers.get(roomId);
            return list == null ? List.of() : new ArrayList<>(list);
        }
        List<RoomMember> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT node_id,display_name,joined_at FROM room_members WHERE room_id = ? ORDER BY joined_at")) {
            ps.setString(1, roomId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new RoomMember(
                            rs.getString("node_id"),
                            rs.getString("display_name"),
                            rs.getLong("joined_at")));
                }
            }
        } catch (Exception e) {
            System.err.println("[MC-Tunnel] loadRoomMembers failed: " + e.getMessage());
        }
        return list;
    }

    /** 查询某节点加入的所有房间 ID(按最近加入排序) */
    public List<String> loadRoomIdsByNode(String nodeId) {
        if (!sqliteAvailable) {
            List<String> ids = new ArrayList<>();
            for (var e : memoryRoomMembers.entrySet()) {
                for (var m : e.getValue()) {
                    if (m.nodeId().equals(nodeId)) {
                        ids.add(e.getKey());
                        break;
                    }
                }
            }
            return ids;
        }
        List<String> ids = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT room_id FROM room_members WHERE node_id = ? ORDER BY joined_at DESC")) {
            ps.setString(1, nodeId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getString("room_id"));
                }
            }
        } catch (Exception e) {
            System.err.println("[MC-Tunnel] loadRoomIdsByNode failed: " + e.getMessage());
        }
        return ids;
    }

    // ── 网络 ──────────────────────────────────────────────

    /** 保存网络(upsert) */
    public void saveNetwork(RoomNetwork network) {
        if (!sqliteAvailable) {
            List<RoomNetwork> list = memoryNetworks.computeIfAbsent(network.roomId(),
                    k -> new CopyOnWriteArrayList<>());
            list.removeIf(n -> n.id().equals(network.id()));
            list.add(network);
            memoryNetworkMembers.computeIfAbsent(network.id(), k -> new CopyOnWriteArrayList<>());
            return;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO networks(id,room_id,type,status,endpoint,host_node_id,created_at) "
                        + "VALUES(?,?,?,?,?,?,?)")) {
            ps.setString(1, network.id());
            ps.setString(2, network.roomId());
            ps.setString(3, network.type().name());
            ps.setString(4, network.status().name());
            ps.setString(5, network.endpoint());
            ps.setString(6, network.hostNodeId());
            ps.setLong(7, network.createdAt());
            ps.executeUpdate();
        } catch (Exception e) {
            System.err.println("[MC-Tunnel] saveNetwork failed: " + e.getMessage());
        }
    }

    /** 读取房间内所有网络 */
    public List<RoomNetwork> loadNetworksByRoom(String roomId) {
        if (!sqliteAvailable) {
            List<RoomNetwork> list = memoryNetworks.get(roomId);
            return list == null ? List.of() : new ArrayList<>(list);
        }
        List<RoomNetwork> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id,room_id,type,status,endpoint,host_node_id,created_at FROM networks "
                        + "WHERE room_id = ? ORDER BY created_at")) {
            ps.setString(1, roomId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new RoomNetwork(
                            rs.getString("id"),
                            rs.getString("room_id"),
                            NetworkType.valueOf(rs.getString("type")),
                            NetworkStatus.valueOf(rs.getString("status")),
                            rs.getString("endpoint"),
                            rs.getString("host_node_id"),
                            rs.getLong("created_at")));
                }
            }
        } catch (Exception e) {
            System.err.println("[MC-Tunnel] loadNetworksByRoom failed: " + e.getMessage());
        }
        return list;
    }

    /** 删除网络 */
    public void deleteNetwork(String networkId) {
        if (!sqliteAvailable) {
            memoryNetworkMembers.remove(networkId);
            for (List<RoomNetwork> list : memoryNetworks.values()) {
                list.removeIf(n -> n.id().equals(networkId));
            }
            return;
        }
        try {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM networks WHERE id = ?")) {
                ps.setString(1, networkId); ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM network_members WHERE network_id = ?")) {
                ps.setString(1, networkId); ps.executeUpdate();
            }
            conn.commit();
        } catch (Exception e) {
            try { conn.rollback(); } catch (Exception ignored) {}
            System.err.println("[MC-Tunnel] deleteNetwork failed: " + e.getMessage());
        } finally {
            try { conn.setAutoCommit(true); } catch (Exception ignored) {}
        }
    }

    /** 添加网络成员 */
    public void addNetworkMember(String networkId, String nodeId) {
        if (!sqliteAvailable) {
            List<String> list = memoryNetworkMembers.computeIfAbsent(networkId,
                    k -> new CopyOnWriteArrayList<>());
            if (!list.contains(nodeId)) list.add(nodeId);
            return;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO network_members(network_id,node_id,joined_at) VALUES(?,?,?)")) {
            ps.setString(1, networkId);
            ps.setString(2, nodeId);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (Exception e) {
            System.err.println("[MC-Tunnel] addNetworkMember failed: " + e.getMessage());
        }
    }

    /** 读取网络成员 nodeId 列表 */
    public List<String> loadNetworkMembers(String networkId) {
        if (!sqliteAvailable) {
            List<String> list = memoryNetworkMembers.get(networkId);
            return list == null ? List.of() : new ArrayList<>(list);
        }
        List<String> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT node_id FROM network_members WHERE network_id = ? ORDER BY joined_at")) {
            ps.setString(1, networkId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(rs.getString("node_id"));
                }
            }
        } catch (Exception e) {
            System.err.println("[MC-Tunnel] loadNetworkMembers failed: " + e.getMessage());
        }
        return list;
    }

    // ── 隧道配置 ──────────────────────────────────────────

    /** 保存工具配置键值对 */
    public void saveConfig(String tool, String key, String value) {
        if (!sqliteAvailable) {
            memoryConfigs.computeIfAbsent(tool, k -> new ConcurrentHashMap<>()).put(key, value);
            return;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO tunnel_configs(tool,key,value) VALUES(?,?,?)")) {
            ps.setString(1, tool);
            ps.setString(2, key);
            ps.setString(3, value);
            ps.executeUpdate();
        } catch (Exception e) {
            System.err.println("[MC-Tunnel] saveConfig failed: " + e.getMessage());
        }
    }

    /** 读取工具的所有配置 */
    public Map<String, String> loadConfig(String tool) {
        if (!sqliteAvailable) {
            return new LinkedHashMap<>(
                    memoryConfigs.getOrDefault(tool, Map.of()));
        }
        Map<String, String> map = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT key,value FROM tunnel_configs WHERE tool = ?")) {
            ps.setString(1, tool);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    map.put(rs.getString("key"), rs.getString("value"));
                }
            }
        } catch (Exception e) {
            System.err.println("[MC-Tunnel] loadConfig failed: " + e.getMessage());
        }
        return map;
    }

    // ── ToolConfigStore 接口(工具元数据存取) ─────────────

    @Override
    public String get(String tool, String key) {
        return loadConfig(tool).get(key);
    }

    @Override
    public void put(String tool, String key, String value) {
        saveConfig(tool, key, value);
    }

    // ── 节点列表 ──────────────────────────────────────────

    /** 记录一个节点(upsert) */
    public void upsertNode(String nodeId, String address) {
        if (!sqliteAvailable) {
            memoryNodes.put(nodeId, new NodeInfo(nodeId, address, System.currentTimeMillis()));
            return;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO nodes(node_id,address,last_seen) VALUES(?,?,?)")) {
            ps.setString(1, nodeId);
            ps.setString(2, address);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (Exception e) {
            System.err.println("[MC-Tunnel] upsertNode failed: " + e.getMessage());
        }
    }

    /** 读取所有已知节点 */
    public List<NodeInfo> loadNodes() {
        if (!sqliteAvailable) {
            return new ArrayList<>(memoryNodes.values());
        }
        List<NodeInfo> list = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT node_id,address,last_seen FROM nodes ORDER BY last_seen DESC")) {
            while (rs.next()) {
                list.add(new NodeInfo(
                        rs.getString("node_id"),
                        rs.getString("address"),
                        rs.getLong("last_seen")));
            }
        } catch (Exception e) {
            System.err.println("[MC-Tunnel] loadNodes failed: " + e.getMessage());
        }
        return list;
    }

    /** 移除一个节点 */
    public void removeNode(String nodeId) {
        if (!sqliteAvailable) {
            memoryNodes.remove(nodeId);
            return;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM nodes WHERE node_id = ?")) {
            ps.setString(1, nodeId);
            ps.executeUpdate();
        } catch (Exception e) {
            System.err.println("[MC-Tunnel] removeNode failed: " + e.getMessage());
        }
    }

    public void close() {
        if (conn != null) {
            try {
                conn.close();
            } catch (Exception ignored) {
            }
            conn = null;
        }
    }

    /** 节点信息 */
    public record NodeInfo(String nodeId, String address, long lastSeen) {
    }

    // ── 便捷工厂 ──────────────────────────────────────────

    /** 生成新的消息 ID */
    public static String newMessageId() {
        return UUID.randomUUID().toString();
    }
}
