package io.mctunnel.core.relay.client;

import io.mctunnel.core.chat.ChatMessage;
import io.mctunnel.core.room.JoinLink;
import io.mctunnel.core.room.LinkCodec;
import io.mctunnel.core.room.RoomMember;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 中继客户端:连接云中继服务,加入房间,收发聊天,监听事件.
 * <p>
 * 用法:
 * <pre>
 * RelayClient client = new RelayClient(selfNodeId, displayName);
 * client.onChat(msg -> ...);
 * client.onMemberJoined(m -> ...);
 * client.createRoom("My Room");          // 房主
 * // 或 client.joinRoom(link);            // 成员
 * </pre>
 */
public class RelayClient {

    private final String nodeId;
    private final String displayName;
    private Socket socket;
    private InputStream in;
    private OutputStream out;
    private volatile boolean connected = false;
    private volatile String roomId;
    private final List<ChatHandler> chatHandlers = new CopyOnWriteArrayList<>();
    private final List<MemberEventHandler> memberHandlers = new CopyOnWriteArrayList<>();
    private final List<RoomClosedHandler> roomClosedHandlers = new CopyOnWriteArrayList<>();
    private final List<ConnectionListener> connListeners = new CopyOnWriteArrayList<>();
    private final List<MemberStatusHandler> statusHandlers = new CopyOnWriteArrayList<>();
    private Thread readThread;

    public RelayClient(String nodeId, String displayName) {
        this.nodeId = nodeId;
        this.displayName = displayName;
    }

    public String getNodeId() { return nodeId; }
    public String getRoomId() { return roomId; }
    public boolean isConnected() { return connected; }

    // ── 事件回调 ──────────────────────────────────────────

    public interface ChatHandler { void onChat(ChatMessage msg); }
    public interface MemberEventHandler { void onMemberJoined(RoomMember m); void onMemberLeft(String nodeId); }
    public interface RoomClosedHandler { void onRoomClosed(String roomId); }
    public interface ConnectionListener { void onDisconnected(); }
    /** 成员网络状态事件:onMemberStatus(nodeId, statusJson) */
    public interface MemberStatusHandler { void onMemberStatus(String nodeId, String statusJson); }

    public void onChat(ChatHandler h) { chatHandlers.add(h); }
    public void onMemberEvent(MemberEventHandler h) { memberHandlers.add(h); }
    public void onRoomClosed(RoomClosedHandler h) { roomClosedHandlers.add(h); }
    public void onDisconnect(ConnectionListener l) { connListeners.add(l); }
    public void onMemberStatus(MemberStatusHandler h) { statusHandlers.add(h); }

    // ── 连接 ──────────────────────────────────────────────

    /** 连接到中继服务 */
    public void connect(String host, int port) throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 8000);
        in = socket.getInputStream();
        out = socket.getOutputStream();
        wsHandshake(host, port);
        connected = true;
        readThread = new Thread(this::readLoop, "relay-client-read");
        readThread.setDaemon(true);
        readThread.start();
    }

    /** 解析链接并连接 */
    public void connectByLink(String link) throws IOException {
        JoinLink jl = LinkCodec.decode(link);
        connect(jl.relayHost(), jl.relayPort());
    }

    private void wsHandshake(String host, int port) throws IOException {
        byte[] keyBytes = new byte[16];
        new Random().nextBytes(keyBytes);
        String wsKey = Base64.getEncoder().encodeToString(keyBytes);
        String req = "GET / HTTP/1.1\r\nHost: " + host + ":" + port + "\r\n"
                + "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + wsKey + "\r\nSec-WebSocket-Version: 13\r\n\r\n";
        out.write(req.getBytes(StandardCharsets.UTF_8));
        out.flush();
        // 读 101 响应
        String line;
        boolean upgraded = false;
        while ((line = readLine(in)) != null) {
            if (line.isEmpty()) break;
            if (line.startsWith("HTTP/") && line.contains("101")) upgraded = true;
        }
        if (!upgraded) throw new IOException("WebSocket handshake failed");
    }

    // ── 房间操作 ──────────────────────────────────────────

    /** 创建房间(房主),返回加入链接 */
    public String createRoom(String name) throws IOException {
        sendJson("{\"type\":\"create_room\",\"name\":\"" + esc(name)
                + "\",\"displayName\":\"" + esc(displayName)
                + "\",\"nodeId\":\"" + esc(nodeId) + "\"}");
        return null; // 链接通过 room_created 事件异步返回,由调用方监听
    }

    /** 通过链接加入房间 */
    public void joinRoom(String link) throws IOException {
        JoinLink jl = LinkCodec.decode(link);
        if (!connected) connect(jl.relayHost(), jl.relayPort());
        sendJson("{\"type\":\"join_room\",\"roomId\":\"" + jl.roomId()
                + "\",\"displayName\":\"" + esc(displayName) + "\"}");
        this.roomId = jl.roomId();
    }

    /** 加入指定房间(需已连接) */
    public void joinRoomById(String roomId) throws IOException {
        sendJson("{\"type\":\"join_room\",\"roomId\":\"" + roomId
                + "\",\"displayName\":\"" + esc(displayName)
                + "\",\"nodeId\":\"" + esc(nodeId) + "\"}");
        this.roomId = roomId;
    }

    public void leaveRoom() throws IOException {
        sendJson("{\"type\":\"leave_room\"}");
        this.roomId = null;
    }

    /** 房主关闭房间(通知所有成员) */
    public void closeRoom() throws IOException {
        sendJson("{\"type\":\"close_room\"}");
        this.roomId = null;
    }

    /** 发送聊天消息到当前房间 */
    public void sendChat(String sender, String content) throws IOException {
        sendJson("{\"type\":\"chat\",\"sender\":\"" + esc(sender)
                + "\",\"content\":\"" + esc(content) + "\"}");
    }

    /** 上报本机网络状态到当前房间(statusJson 为 JSON 对象字符串) */
    public void sendMemberStatus(String statusJson) throws IOException {
        sendJson("{\"type\":\"member_status\",\"nodeId\":\"" + esc(nodeId)
                + "\",\"status\":" + statusJson + "}");
    }

    // ── 读循环 ────────────────────────────────────────────

    private void readLoop() {
        try {
            while (connected) {
                String text = readText();
                if (text == null) break;
                handleMessage(text);
            }
        } catch (IOException e) {
            // 断开
        } finally {
            connected = false;
            for (ConnectionListener l : connListeners) l.onDisconnected();
        }
    }

    private void handleMessage(String text) {
        String type = field(text, "type");
        if (type == null) return;
        switch (type) {
            case "chat" -> {
                String id = field(text, "id");
                String sender = field(text, "sender");
                String content = field(text, "content");
                String rid = field(text, "roomId");
                long ts = fieldLong(text, "timestamp");
                if (id != null && content != null) {
                    ChatMessage msg = new ChatMessage(id, nodeId, sender, content, ts,
                            rid == null ? "" : rid);
                    for (ChatHandler h : chatHandlers) h.onChat(msg);
                }
            }
            case "member_joined" -> {
                String nid = field(text, "nodeId");
                String dn = field(text, "displayName");
                if (nid != null) {
                    for (MemberEventHandler h : memberHandlers)
                        h.onMemberJoined(new RoomMember(nid, dn == null ? "" : dn, System.currentTimeMillis()));
                }
            }
            case "member_left" -> {
                String nid = field(text, "nodeId");
                if (nid != null) {
                    for (MemberEventHandler h : memberHandlers) h.onMemberLeft(nid);
                }
            }
            case "room_closed" -> {
                String rid = field(text, "roomId");
                this.roomId = null;
                for (RoomClosedHandler h : roomClosedHandlers) h.onRoomClosed(rid);
            }
            case "room_created" -> {
                this.roomId = field(text, "roomId");
            }
            case "room_joined" -> {
                this.roomId = field(text, "roomId");
                // 回填房间现有成员(room_joined.members 数组)
                String arr = extractJsonArray(text, "members");
                if (arr != null) {
                    for (String m : splitJsonArray(arr)) {
                        String nid = field(m, "nodeId");
                        String dn = field(m, "displayName");
                        if (nid != null) {
                            for (MemberEventHandler h : memberHandlers)
                                h.onMemberJoined(new RoomMember(nid,
                                        dn == null ? "" : dn, System.currentTimeMillis()));
                        }
                    }
                }
            }
            case "member_status" -> {
                String nid = field(text, "nodeId");
                String status = extractJsonObject(text, "status");
                if (nid != null && status != null) {
                    for (MemberStatusHandler h : statusHandlers) h.onMemberStatus(nid, status);
                }
            }
            // room_created / room_joined / error 等暂不特别处理
        }
    }

    // ── 工具方法 ──────────────────────────────────────────

    private void sendJson(String json) throws IOException {
        if (!connected) throw new IOException("not connected");
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        byte[] mask = new byte[4];
        new Random().nextBytes(mask);
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(0x81); // FIN + text
        int len = payload.length;
        if (len < 126) {
            frame.write(0x80 | len); // mask bit set
        } else if (len < 65536) {
            frame.write(0x80 | 126);
            frame.write((len >> 8) & 0xFF);
            frame.write(len & 0xFF);
        } else {
            frame.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) frame.write((int) ((len >> (8 * i)) & 0xFF));
        }
        frame.write(mask);
        for (int i = 0; i < len; i++) frame.write(payload[i] ^ mask[i % 4]);
        synchronized (out) {
            out.write(frame.toByteArray());
            out.flush();
        }
    }

    private String readText() throws IOException {
        int b0 = in.read();
        if (b0 == -1) return null;
        int opcode = b0 & 0x0F;
        if (opcode == 0x8) return null;
        int b1 = in.read();
        if (b1 == -1) return null;
        int len = b1 & 0x7F;
        if (len == 126) len = (in.read() << 8) | in.read();
        else if (len == 127) {
            len = 0;
            for (int i = 0; i < 8; i++) len = (len << 8) | in.read();
        }
        byte[] mask = null;
        if ((b1 & 0x80) != 0) {
            mask = new byte[4];
            in.readNBytes(mask, 0, 4);
        }
        byte[] payload = in.readNBytes(len);
        if (mask != null) {
            for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
        }
        return opcode == 0x1 ? new String(payload, StandardCharsets.UTF_8) : null;
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\r') {
                int next = in.read();
                if (next == '\n') break;
                buf.write(b);
                if (next != -1) buf.write(next);
            } else if (b == '\n') break;
            else buf.write(b);
        }
        return buf.size() == 0 && b == -1 ? null : buf.toString(StandardCharsets.UTF_8);
    }

    private static String field(String json, String key) {
        String k = "\"" + key + "\"";
        int idx = json.indexOf(k);
        if (idx < 0) return null;
        int colon = json.indexOf(":", idx + k.length());
        if (colon < 0) return null;
        int q1 = json.indexOf("\"", colon + 1);
        if (q1 < 0) return null;
        int q2 = json.indexOf("\"", q1 + 1);
        if (q2 < 0) return null;
        return json.substring(q1 + 1, q2).replace("\\\"", "\"")
                .replace("\\\\", "\\").replace("\\n", "\n").replace("\\r", "\r");
    }

    /** 提取 "key": 后的 JSON 对象(平衡括号,含嵌套) */
    private static String extractJsonObject(String json, String key) {
        String k = "\"" + key + "\"";
        int idx = json.indexOf(k);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + k.length());
        if (colon < 0) return null;
        int start = colon + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        if (start >= json.length() || json.charAt(start) != '{') return null;
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

    /** 提取 "key":[..] 数组子串(平衡括号) */
    private static String extractJsonArray(String json, String key) {
        String k = "\"" + key + "\"";
        int idx = json.indexOf(k);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + k.length());
        if (colon < 0) return null;
        int start = colon + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        if (start >= json.length() || json.charAt(start) != '[') return null;
        int depth = 0;
        boolean inStr = false;
        for (int i = start; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (inStr) {
                if (ch == '\\') i++;
                else if (ch == '"') inStr = false;
            } else if (ch == '"') inStr = true;
            else if (ch == '[') depth++;
            else if (ch == ']') {
                depth--;
                if (depth == 0) return json.substring(start, i + 1);
            }
        }
        return null;
    }

    /** 数组按元素拆分(支持对象嵌套) */
    private static java.util.List<String> splitJsonArray(String arr) {
        java.util.List<String> out = new java.util.ArrayList<>();
        int i = 0;
        while (i < arr.length()) {
            char c = arr.charAt(i);
            if (c == '{') {
                int depth = 0;
                boolean inStr = false;
                int j = i;
                for (; j < arr.length(); j++) {
                    char ch = arr.charAt(j);
                    if (inStr) {
                        if (ch == '\\') j++;
                        else if (ch == '"') inStr = false;
                    } else if (ch == '"') inStr = true;
                    else if (ch == '{') depth++;
                    else if (ch == '}') {
                        depth--;
                        if (depth == 0) break;
                    }
                }
                out.add(arr.substring(i, j + 1));
                i = j + 1;
            } else {
                i++;
            }
        }
        return out;
    }

    private static long fieldLong(String json, String key) {
        String k = "\"" + key + "\"";
        int idx = json.indexOf(k);
        if (idx < 0) return 0;
        int colon = json.indexOf(":", idx + k.length());
        if (colon < 0) return 0;
        int end = colon + 1;
        while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
        try { return Long.parseLong(json.substring(colon + 1, end).trim()); }
        catch (NumberFormatException e) { return 0; }
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    public void close() {
        connected = false;
        if (socket != null) {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }
}
