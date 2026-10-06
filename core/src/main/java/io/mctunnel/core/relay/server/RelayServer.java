package io.mctunnel.core.relay.server;

import io.mctunnel.core.chat.ChatMessage;
import io.mctunnel.core.room.LinkCodec;
import io.mctunnel.core.room.RoomMember;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 云中继服务(初始网络主机).
 * <p>
 * 运行在云服务器上,提供:
 * <ul>
 *   <li>房间创建与注册</li>
 *   <li>成员通过 roomId 加入房间</li>
 *   <li>房间内聊天中继</li>
 *   <li>成员加入/离开事件广播</li>
 * </ul>
 * <b>不承载 Minecraft 游戏流量</b>,仅处理信令与聊天.
 * <p>
 * 零依赖:基于 {@link ServerSocket} + 手写 WebSocket 握手与帧.
 * 启动入口: {@code java -cp mctunnel.jar io.mctunnel.core.relay.server.RelayServer [port]}
 */
public class RelayServer {

    /** 默认中继端口 */
    public static final int DEFAULT_PORT = 8721;

    private final int port;
    private final String publicHost;
    private ServerSocket server;
    private final RoomRegistry rooms = new RoomRegistry();

    public RelayServer(int port, String publicHost) {
        this.port = port;
        this.publicHost = publicHost;
    }

    public void start() throws IOException {
        server = new ServerSocket(port);
        Thread accept = new Thread(() -> {
            while (server != null && !server.isClosed()) {
                try {
                    Socket sock = server.accept();
                    new Thread(() -> handle(sock), "relay-conn").start();
                } catch (IOException e) {
                    if (server != null && !server.isClosed()) {
                        System.err.println("[Relay] accept error: " + e.getMessage());
                    }
                }
            }
        }, "relay-accept");
        accept.setDaemon(true);
        accept.start();
        System.out.println("[Relay] Cloud relay server started on 0.0.0.0:" + port
                + " (public host: " + publicHost + ")");
    }

    public void stop() {
        if (server != null) {
            try { server.close(); } catch (IOException ignored) {}
            server = null;
        }
    }

    public int getPort() { return port; }

    // ── 连接处理 ──────────────────────────────────────────

    private void handle(Socket sock) {
        try (sock) {
            InputStream in = sock.getInputStream();
            OutputStream out = sock.getOutputStream();

            // WebSocket 握手
            String wsKey = performHandshake(in, out);
            if (wsKey == null) return;

            WsConn conn = new WsConn(sock, in, out);
            String nodeId = UUID.randomUUID().toString();
            conn.nodeId = nodeId;

            while (conn.running) {
                String text = readText(in);
                if (text == null) break;
                dispatch(conn, text);
            }
            // 连接断开:清理房间成员
            rooms.removeMember(nodeId);
        } catch (IOException e) {
            // 客户端断开,正常
        }
    }

    /** 执行 WebSocket 握手,返回客户端的 Sec-WebSocket-Key(失败返回 null) */
    private String performHandshake(InputStream in, OutputStream out) throws IOException {
        // 读取请求行 + 头
        String line = readLine(in);
        if (line == null || !line.startsWith("GET ")) return null;
        String wsKey = null;
        while ((line = readLine(in)) != null) {
            if (line.isEmpty()) break;
            if (line.toLowerCase().startsWith("sec-websocket-key:")) {
                wsKey = line.substring(line.indexOf(':') + 1).trim();
            }
        }
        if (wsKey == null) {
            out.write("HTTP/1.1 400 Bad Request\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            return null;
        }
        String accept = computeAccept(wsKey);
        String resp = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
        out.write(resp.getBytes(StandardCharsets.UTF_8));
        out.flush();
        return wsKey;
    }

    private static String computeAccept(String key) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            md.update((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(md.digest());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ── 消息分发 ──────────────────────────────────────────

    private void dispatch(WsConn conn, String text) {
        String type = field(text, "type");
        if (type == null) return;
        switch (type) {
            case "create_room" -> handleCreateRoom(conn, text);
            case "join_room" -> handleJoinRoom(conn, text);
            case "leave_room" -> handleLeaveRoom(conn);
            case "close_room" -> handleCloseRoom(conn);
            case "chat" -> handleChat(conn, text);
            case "member_status" -> handleMemberStatus(conn, text);
            case "ping" -> send(conn, "{\"type\":\"pong\"}");
            default -> { /* ignore */ }
        }
    }

    private void handleCreateRoom(WsConn conn, String text) {
        String name = field(text, "name");
        if (name == null) name = "Room";
        String displayName = field(text, "displayName");
        if (displayName == null) displayName = "host";
        String clientNodeId = field(text, "nodeId");
        if (clientNodeId != null && !clientNodeId.isBlank()) conn.nodeId = clientNodeId;
        String roomId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String link = LinkCodec.encode(publicHost, port, roomId);
        rooms.createRoom(roomId, name, conn.nodeId);
        rooms.addMember(roomId, new RoomMember(conn.nodeId, displayName, System.currentTimeMillis()));
        rooms.addConnection(roomId, conn.nodeId, conn);
        conn.roomId = roomId;
        send(conn, "{\"type\":\"room_created\",\"roomId\":\"" + roomId
                + "\",\"name\":\"" + esc(name) + "\",\"link\":\"" + esc(link) + "\"}");
        System.out.println("[Relay] Room created: " + roomId + " by " + conn.nodeId);
    }

    private void handleJoinRoom(WsConn conn, String text) {
        String roomId = field(text, "roomId");
        String displayName = field(text, "displayName");
        if (displayName == null) displayName = "member";
        if (roomId == null || !rooms.hasRoom(roomId)) {
            send(conn, "{\"type\":\"error\",\"message\":\"房间不存在\"}");
            return;
        }
        String clientNodeId = field(text, "nodeId");
        if (clientNodeId != null && !clientNodeId.isBlank()) conn.nodeId = clientNodeId;
        conn.roomId = roomId;
        rooms.addMember(roomId, new RoomMember(conn.nodeId, displayName, System.currentTimeMillis()));
        rooms.addConnection(roomId, conn.nodeId, conn);
        // 回送成员列表
        List<RoomMember> members = rooms.members(roomId);
        send(conn, "{\"type\":\"room_joined\",\"roomId\":\"" + roomId
                + "\",\"members\":" + membersJson(members) + "}");
        // 广播给房间内其他成员
        broadcast(roomId, "{\"type\":\"member_joined\",\"nodeId\":\"" + conn.nodeId
                + "\",\"displayName\":\"" + esc(displayName) + "\"}", conn.nodeId);
        // 新成员加入:推送房间内其他成员已缓存的网络状态
        for (Map.Entry<String, String> e : rooms.memberStatuses(roomId).entrySet()) {
            if (!e.getKey().equals(conn.nodeId)) {
                send(conn, "{\"type\":\"member_status\",\"nodeId\":\"" + esc(e.getKey())
                        + "\",\"status\":" + e.getValue() + "}");
            }
        }
        System.out.println("[Relay] " + conn.nodeId + " joined room " + roomId);
    }

    private void handleLeaveRoom(WsConn conn) {
        if (conn.roomId != null) {
            rooms.removeMember(conn.roomId, conn.nodeId);
            broadcast(conn.roomId, "{\"type\":\"member_left\",\"nodeId\":\"" + conn.nodeId + "\"}", conn.nodeId);
            conn.roomId = null;
        }
    }

    /** 房主关闭房间:广播 room_closed 给所有成员并清理 */
    private void handleCloseRoom(WsConn conn) {
        if (conn.roomId == null) return;
        String roomId = conn.roomId;
        broadcast(roomId, "{\"type\":\"room_closed\",\"roomId\":\"" + roomId + "\"}", null);
        rooms.removeRoom(roomId);
        conn.roomId = null;
        System.out.println("[Relay] Room closed: " + roomId + " by " + conn.nodeId);
    }

    private void handleChat(WsConn conn, String text) {
        if (conn.roomId == null) return;
        String sender = field(text, "sender");
        String content = field(text, "content");
        if (sender == null || content == null) return;
        ChatMessage msg = ChatMessage.create(conn.nodeId, sender, content, conn.roomId);
        String json = "{\"type\":\"chat\",\"id\":\"" + msg.id() + "\",\"nodeId\":\"" + msg.nodeId()
                + "\",\"sender\":\"" + esc(sender) + "\",\"content\":\"" + esc(content)
                + "\",\"timestamp\":" + msg.timestamp() + ",\"roomId\":\"" + msg.roomId() + "\"}";
        broadcast(conn.roomId, json, conn.nodeId);
    }

    private void handleMemberStatus(WsConn conn, String text) {
        if (conn.roomId == null) return;
        String nodeId = field(text, "nodeId");
        if (nodeId == null) return;
        // 仅接受房间成员自身的状态上报(防止冒名)
        if (!rooms.isMember(conn.roomId, nodeId)) return;
        String status = extractJsonValue(text, "status");
        if (status == null) return;
        rooms.cacheMemberStatus(conn.roomId, nodeId, status);
        // 广播给房间内其他成员(上报者本地已有自己的状态,跳过)
        broadcast(conn.roomId, "{\"type\":\"member_status\",\"nodeId\":\""
                + esc(nodeId) + "\",\"status\":" + status + "}", nodeId);
    }

    // ── 广播 ──────────────────────────────────────────────

    private void broadcast(String roomId, String json, String excludeNodeId) {
        for (WsConn c : rooms.connections(roomId)) {
            if (excludeNodeId != null && excludeNodeId.equals(c.nodeId)) continue;
            send(c, json);
        }
    }

    private void send(WsConn conn, String json) {
        try {
            conn.sendText(json);
        } catch (IOException e) {
            conn.running = false;
        }
    }

    // ── JSON 小工具 ───────────────────────────────────────

    /** 提取 "key": 后的 JSON 值(对象/字符串/数字),原样返回子串 */
    private static String extractJsonValue(String json, String key) {
        String k = "\"" + key + "\"";
        int idx = json.indexOf(k);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + k.length());
        if (colon < 0) return null;
        int start = colon + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        if (start >= json.length()) return null;
        char c = json.charAt(start);
        if (c == '{') {
            int depth = 0;
            boolean inStr = false;
            for (int i = start; i < json.length(); i++) {
                char ch = json.charAt(i);
                if (inStr) {
                    if (ch == '\\') i++;
                    else if (ch == '"') inStr = false;
                } else if (ch == '"') inStr = true;
                else if (ch == '{') depth++;
                else if (ch == '}') {
                    depth--;
                    if (depth == 0) return json.substring(start, i + 1);
                }
            }
            return null;
        }
        if (c == '"') {
            for (int i = start + 1; i < json.length(); i++) {
                char ch = json.charAt(i);
                if (ch == '\\') i++;
                else if (ch == '"') return json.substring(start, i + 1);
            }
            return null;
        }
        int end = start;
        while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') end++;
        return json.substring(start, end).trim();
    }

    private static String field(String json, String key) {
        String k = "\"" + key + "\"";
        int idx = json.indexOf(k);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + k.length());
        if (colon < 0) return null;
        int q1 = json.indexOf('"', colon + 1);
        if (q1 < 0) return null;
        int q2 = json.indexOf('"', q1 + 1);
        if (q2 < 0) return null;
        return json.substring(q1 + 1, q2).replace("\\\"", "\"")
                .replace("\\\\", "\\").replace("\\n", "\n").replace("\\r", "\r");
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String membersJson(List<RoomMember> members) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (RoomMember m : members) {
            if (!first) sb.append(",");
            sb.append("{\"nodeId\":\"").append(esc(m.nodeId()))
                    .append("\",\"displayName\":\"").append(esc(m.displayName())).append("\"}");
            first = false;
        }
        sb.append("]");
        return sb.toString();
    }

    // ── WebSocket 帧读写(与 ChatServer 一致) ─────────────

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\r') {
                int next = in.read();
                if (next == '\n') break;
                buf.write(b);
                if (next != -1) buf.write(next);
            } else if (b == '\n') {
                break;
            } else {
                buf.write(b);
            }
        }
        return buf.size() == 0 && b == -1 ? null : buf.toString(StandardCharsets.UTF_8);
    }

    private static String readText(InputStream in) throws IOException {
        int b0 = in.read();
        if (b0 == -1) return null;
        int opcode = b0 & 0x0F;
        if (opcode == 0x8) return null;
        int b1 = in.read();
        if (b1 == -1) return null;
        int len = b1 & 0x7F;
        if (len == 126) {
            len = (in.read() << 8) | in.read();
        } else if (len == 127) {
            len = 0;
            for (int i = 0; i < 8; i++) len = (len << 8) | in.read();
        }
        byte[] mask = new byte[4];
        in.readNBytes(mask, 0, 4);
        byte[] payload = in.readNBytes(len);
        for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
        if (opcode == 0x1) return new String(payload, StandardCharsets.UTF_8);
        return null;
    }

    private static class WsConn {
        final Socket socket;
        final InputStream in;
        final OutputStream out;
        volatile String nodeId;
        volatile String roomId;
        volatile boolean running = true;

        WsConn(Socket socket, InputStream in, OutputStream out) {
            this.socket = socket;
            this.in = in;
            this.out = out;
        }

        void sendText(String text) throws IOException {
            byte[] payload = text.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            frame.write(0x81);
            if (payload.length < 126) {
                frame.write(payload.length);
            } else if (payload.length < 65536) {
                frame.write(126);
                frame.write((payload.length >> 8) & 0xFF);
                frame.write(payload.length & 0xFF);
            } else {
                frame.write(127);
                for (int i = 7; i >= 0; i--) frame.write((int) ((payload.length >> (8 * i)) & 0xFF));
            }
            frame.write(payload);
            synchronized (out) {
                out.write(frame.toByteArray());
                out.flush();
            }
        }
    }

    // ── 房间注册表(内存) ──────────────────────────────────

    private static final class RoomRegistry {
        private final Map<String, RoomState> rooms = new ConcurrentHashMap<>();

        void createRoom(String roomId, String name, String hostNodeId) {
            rooms.put(roomId, new RoomState(roomId, name, hostNodeId));
        }

        boolean hasRoom(String roomId) {
            return rooms.containsKey(roomId);
        }

        void addMember(String roomId, RoomMember member) {
            RoomState r = rooms.get(roomId);
            if (r != null) r.addMember(member);
        }

        void addConnection(String roomId, String nodeId, WsConn conn) {
            RoomState r = rooms.get(roomId);
            if (r != null) r.conns.put(nodeId, conn);
        }

        void removeMember(String nodeId) {
            for (RoomState r : rooms.values()) r.removeMember(nodeId);
        }

        void removeMember(String roomId, String nodeId) {
            RoomState r = rooms.get(roomId);
            if (r != null) r.removeMember(nodeId);
        }

        void removeRoom(String roomId) {
            rooms.remove(roomId);
        }

        List<RoomMember> members(String roomId) {
            RoomState r = rooms.get(roomId);
            return r == null ? List.of() : r.members();
        }

        List<WsConn> connections(String roomId) {
            RoomState r = rooms.get(roomId);
            return r == null ? List.of() : r.connections();
        }

        /** 缓存成员网络状态(nodeId -> status JSON 对象) */
        void cacheMemberStatus(String roomId, String nodeId, String status) {
            RoomState r = rooms.get(roomId);
            if (r != null) r.memberStatus.put(nodeId, status);
        }

        /** nodeId 是否为房间成员 */
        boolean isMember(String roomId, String nodeId) {
            RoomState r = rooms.get(roomId);
            if (r == null) return false;
            for (RoomMember m : r.members) {
                if (m.nodeId().equals(nodeId)) return true;
            }
            return false;
        }

        /** 房间内全部成员的已缓存状态快照 */
        java.util.Map<String, String> memberStatuses(String roomId) {
            RoomState r = rooms.get(roomId);
            return r == null ? java.util.Map.of() : new java.util.HashMap<>(r.memberStatus);
        }
    }

    private static final class RoomState {
        final String roomId;
        final String name;
        final String hostNodeId;
        final List<RoomMember> members = new CopyOnWriteArrayList<>();
        /** 当前在线连接(nodeId -> conn) */
        final Map<String, WsConn> conns = new ConcurrentHashMap<>();
        /** 成员网络状态缓存(nodeId -> status JSON 对象) */
        final Map<String, String> memberStatus = new ConcurrentHashMap<>();

        RoomState(String roomId, String name, String hostNodeId) {
            this.roomId = roomId;
            this.name = name;
            this.hostNodeId = hostNodeId;
        }

        void addMember(RoomMember m) {
            members.removeIf(x -> x.nodeId().equals(m.nodeId()));
            members.add(m);
        }

        void removeMember(String nodeId) {
            members.removeIf(x -> x.nodeId().equals(nodeId));
            conns.remove(nodeId);
            memberStatus.remove(nodeId);
        }

        List<RoomMember> members() {
            return new CopyOnWriteArrayList<>(members);
        }

        List<WsConn> connections() {
            return new CopyOnWriteArrayList<>(conns.values());
        }
    }

    // ── 独立启动入口 ──────────────────────────────────────

    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        String publicHost = args.length > 1 ? args[1] : "59.110.163.88";
        try {
            new RelayServer(port, publicHost).start();
            System.out.println("[Relay] Press Ctrl+C to stop.");
            Thread.currentThread().join();
        } catch (Exception e) {
            System.err.println("[Relay] Fatal: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
