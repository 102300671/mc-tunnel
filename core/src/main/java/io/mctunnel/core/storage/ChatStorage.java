package io.mctunnel.core.storage;

import io.mctunnel.core.chat.ChatMessage;

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
 * 聊天与配置持久化存储.
 * <p>
 * 优先使用 SQLite(需 sqlite-jdbc 驱动在 classpath 上);
 * 若驱动不可用(如未打 fat jar 的模组内嵌场景),自动回退到内存存储,
 * 保证功能不崩溃,只是不持久化.
 * <p>
 * 数据存储位置: {DataDir}/mctunnel.db
 * <p>
 * 表结构:
 * <ul>
 *   <li>chat_messages(id, node_id, sender, content, timestamp)</li>
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
                        timestamp INTEGER NOT NULL
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

    /** 是否使用 SQLite(否则为内存回退) */
    public boolean isPersistent() {
        return sqliteAvailable;
    }

    // ── 聊天消息 ──────────────────────────────────────────

    /** 保存一条消息 */
    public void saveMessage(ChatMessage msg) {
        if (!sqliteAvailable) {
            memoryMessages.add(msg);
            if (memoryMessages.size() > 2000) memoryMessages.remove(0);
            return;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO chat_messages(id,node_id,sender,content,timestamp) VALUES(?,?,?,?,?)")) {
            ps.setString(1, msg.id());
            ps.setString(2, msg.nodeId());
            ps.setString(3, msg.sender());
            ps.setString(4, msg.content());
            ps.setLong(5, msg.timestamp());
            ps.executeUpdate();
        } catch (Exception e) {
            System.err.println("[MC-Tunnel] saveMessage failed: " + e.getMessage());
        }
    }

    /** 读取最近 limit 条消息(按时间升序) */
    public List<ChatMessage> loadRecentMessages(int limit) {
        if (!sqliteAvailable) {
            int size = memoryMessages.size();
            return new ArrayList<>(memoryMessages.subList(Math.max(0, size - limit), size));
        }
        List<ChatMessage> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id,node_id,sender,content,timestamp FROM chat_messages "
                        + "ORDER BY timestamp DESC LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<ChatMessage> reversed = new ArrayList<>();
                while (rs.next()) {
                    reversed.add(new ChatMessage(
                            rs.getString("id"),
                            rs.getString("node_id"),
                            rs.getString("sender"),
                            rs.getString("content"),
                            rs.getLong("timestamp")));
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
