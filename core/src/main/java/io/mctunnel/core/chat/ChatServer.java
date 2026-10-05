package io.mctunnel.core.chat;

import io.mctunnel.core.storage.ChatStorage;

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
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 聊天 WebSocket 服务器.
 * <p>
 * 零依赖实现:
 * - 基于 {@link ServerSocket} 直接处理 TCP 连接(避免 JDK HttpServer 升级后流关闭的问题)
 * - 手写 HTTP 升级握手与 WebSocket 帧解析(text/close)
 * - 消息广播给所有连接的客户端
 * <p>
 * 可选集成:
 * <ul>
 *   <li>{@link ChatStorage} - 消息持久化(SQLite 或内存)</li>
 *   <li>{@link MeshManager} - 跨节点消息中继</li>
 * </ul>
 * 端口默认 8788,可通过环境变量 MCTUNNEL_CHAT_PORT 覆盖.
 */
public class ChatServer {

    private final int port;
    private final String nodeId;
    private final ChatStorage storage;
    private final MeshManager mesh;
    private ServerSocket server;
    private final List<WebSocketConn> clients = new CopyOnWriteArrayList<>();
    private final List<ChatMessage> history = new CopyOnWriteArrayList<>();
    private static final int MAX_HISTORY = 500;

    public ChatServer(String nodeId) {
        this(nodeId, Integer.parseInt(
                System.getenv().getOrDefault("MCTUNNEL_CHAT_PORT", "8788")), null, null);
    }

    public ChatServer(String nodeId, int port) {
        this(nodeId, port, null, null);
    }

    public ChatServer(String nodeId, ChatStorage storage, MeshManager mesh) {
        this(nodeId, Integer.parseInt(
                System.getenv().getOrDefault("MCTUNNEL_CHAT_PORT", "8788")), storage, mesh);
    }

    public ChatServer(String nodeId, int port, ChatStorage storage, MeshManager mesh) {
        this.nodeId = nodeId;
        this.port = port;
        this.storage = storage;
        this.mesh = mesh;

        // 注册对等消息回调:从其他节点收到的消息注入本地广播
        if (mesh != null) {
            mesh.setPeerMessageHandler(this::broadcastFromPeer);
        }
    }

    public void start() throws IOException {
        server = new ServerSocket(port);
        // 接受连接的线程
        Thread acceptThread = new Thread(() -> {
            while (!server.isClosed()) {
                try {
                    Socket sock = server.accept();
                    // 每个连接一个线程
                    new Thread(() -> handleClient(sock), "chat-client").start();
                } catch (IOException e) {
                    if (!server.isClosed()) {
                        System.out.println("[MC-Tunnel] Chat accept error: " + e.getMessage());
                    }
                }
            }
        }, "chat-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        System.out.println("[MC-Tunnel] Chat server started at ws://localhost:" + port);

        // 从存储加载历史消息
        if (storage != null) {
            List<ChatMessage> stored = storage.loadRecentMessages(MAX_HISTORY);
            history.addAll(stored);
            if (!stored.isEmpty()) {
                System.out.println("[MC-Tunnel] Loaded " + stored.size()
                        + " chat messages from storage.");
            }
        }
    }

    public void stop() {
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {
            }
            server = null;
        }
        for (WebSocketConn c : clients) {
            c.close();
        }
        clients.clear();
    }

    public int getPort() {
        return port;
    }

    public String getNodeId() {
        return nodeId;
    }

    public ChatStorage getStorage() {
        return storage;
    }

    public MeshManager getMesh() {
        return mesh;
    }

    /**
     * 发送一条消息(从本节点发出),广播给所有本地客户端,持久化,并中继到对等节点.
     */
    public void broadcast(String sender, String content) {
        broadcast(null, sender, content);
    }

    /** 房间作用域广播:消息携带 roomId,存储按房间分区 */
    public void broadcast(String roomId, String sender, String content) {
        ChatMessage msg = ChatMessage.create(nodeId, sender, content, roomId);
        deliverLocal(msg);
        if (storage != null) storage.saveMessage(msg);
        if (mesh != null) mesh.relay(msg);
    }

    /** 对等节点消息:只做本地广播 + 持久化(不再中继,避免环路) */
    private void broadcastFromPeer(ChatMessage msg) {
        deliverLocal(msg);
        if (storage != null) storage.saveMessage(msg);
    }

    /** 本地广播 + 内存历史 */
    private void deliverLocal(ChatMessage msg) {
        history.add(msg);
        if (history.size() > MAX_HISTORY) history.remove(0);
        String json = toJson(msg);
        for (WebSocketConn c : clients) {
            try {
                c.sendText(json);
            } catch (IOException e) {
                clients.remove(c);
            }
        }
    }

    // ── WebSocket 握手与帧处理 ─────────────────────────────

    private void handleClient(Socket sock) {
        try (Socket socket = sock) {
            socket.setSoTimeout(0);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            // 1. 读取 HTTP 请求头
            String requestLine = readLine(in);
            if (requestLine == null) return;
            String wsKey = null;
            String upgrade = null;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    String header = line.substring(0, colon).trim().toLowerCase();
                    String value = line.substring(colon + 1).trim();
                    if ("sec-websocket-key".equals(header)) wsKey = value;
                    if ("upgrade".equals(header)) upgrade = value;
                }
            }

            if (!"websocket".equalsIgnoreCase(upgrade)) {
                // 非 WebSocket 请求,返回说明
                String body = "MC-Tunnel Chat WebSocket endpoint. Use ws:// protocol.";
                String resp = "HTTP/1.1 200 OK\r\n"
                        + "Content-Type: text/plain\r\n"
                        + "Content-Length: " + body.length() + "\r\n"
                        + "Connection: close\r\n\r\n" + body;
                out.write(resp.getBytes(StandardCharsets.UTF_8));
                out.flush();
                return;
            }

            // 2. 发送 WebSocket 升级响应
            String accept = computeAccept(wsKey);
            String handshake = "HTTP/1.1 101 Switching Protocols\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
            out.write(handshake.getBytes(StandardCharsets.UTF_8));
            out.flush();

            // 3. 升级完成,用原始流处理 WebSocket 帧
            WebSocketConn conn = new WebSocketConn(in, out);
            clients.add(conn);
            System.out.println("[MC-Tunnel] Chat client connected. Total: " + clients.size());

            // 发送历史消息
            for (ChatMessage m : history) {
                try {
                    conn.sendText(toJson(m));
                } catch (IOException ignored) {
                }
            }

            // 读取消息
            try {
                while (true) {
                    String text = conn.readText();
                    if (text == null) break; // 连接关闭
                    // 客户端可能发 JSON {sender, content} 或纯文本
                    String sender = "client";
                    String content = text;
                    String roomId = "";
                    String trimmed = text.trim();
                    if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                        try {
                            String s = extractJson(trimmed, "sender");
                            String c = extractJson(trimmed, "content");
                            String r = extractJson(trimmed, "roomId");
                            if (c != null) {
                                content = c;
                                if (s != null) sender = s;
                                if (r != null) roomId = r;
                            }
                        } catch (Exception ignored) {
                        }
                    }
                    ChatMessage msg = ChatMessage.create(nodeId, sender, content, roomId);
                    deliverLocal(msg);
                    if (storage != null) storage.saveMessage(msg);
                    if (mesh != null) mesh.relay(msg);
                }
            } catch (IOException e) {
                System.out.println("[MC-Tunnel] Chat read error: " + e.getMessage());
            } finally {
                clients.remove(conn);
                System.out.println("[MC-Tunnel] Chat client disconnected. Total: " + clients.size());
            }
        } catch (IOException e) {
            System.out.println("[MC-Tunnel] Chat client handler error: " + e.getMessage());
        }
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
            } else if (b == '\n') {
                break;
            } else {
                buf.write(b);
            }
        }
        return buf.size() == 0 && b == -1 ? null : buf.toString(StandardCharsets.UTF_8);
    }

    // ── WebSocket 帧封装 ───────────────────────────────────

    private static String computeAccept(String key) {
        try {
            String s = key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String toJson(ChatMessage m) {
        // 手动拼 JSON,避免引入依赖
        return "{\"id\":\"" + esc(m.id()) + "\","
                + "\"nodeId\":\"" + esc(m.nodeId()) + "\","
                + "\"sender\":\"" + esc(m.sender()) + "\","
                + "\"content\":\"" + esc(m.content()) + "\","
                + "\"timestamp\":" + m.timestamp() + "," + "\"roomId\":\"" + esc(m.roomId()) + "\"}";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    /** 极简 JSON 字符串字段提取(不引入依赖).返回 null 表示字段不存在. */
    private static String extractJson(String json, String field) {
        String key = "\"" + field + "\"";
        int idx = json.indexOf(key);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + key.length());
        if (colon < 0) return null;
        int q1 = json.indexOf('"', colon + 1);
        if (q1 < 0) return null;
        int q2 = json.indexOf('"', q1 + 1);
        if (q2 < 0) return null;
        // 简单反转义
        String val = json.substring(q1 + 1, q2);
        return val.replace("\\\"", "\"").replace("\\\\", "\\")
                .replace("\\n", "\n").replace("\\r", "\r");
    }

    /**
     * 单个 WebSocket 连接,处理帧读写.
     */
    private static class WebSocketConn {
        private final InputStream in;
        private final OutputStream out;
        private volatile boolean closed = false;

        WebSocketConn(InputStream in, OutputStream out) {
            this.in = in;
            this.out = out;
        }

        void close() {
            closed = true;
            try { in.close(); } catch (IOException ignored) {}
            try { out.close(); } catch (IOException ignored) {}
        }

        void sendText(String text) throws IOException {
            if (closed) {
                throw new IOException("WebSocket 连接已关闭");
            }
            byte[] payload = text.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            // FIN + text frame (0x81)
            frame.write(0x81);
            // 长度
            if (payload.length < 126) {
                frame.write(payload.length);
            } else if (payload.length < 65536) {
                frame.write(126);
                frame.write((payload.length >> 8) & 0xFF);
                frame.write(payload.length & 0xFF);
            } else {
                frame.write(127);
                for (int i = 7; i >= 0; i--) {
                    frame.write((int) ((payload.length >> (8 * i)) & 0xFF));
                }
            }
            frame.write(payload);
            synchronized (out) {
                out.write(frame.toByteArray());
                out.flush();
            }
        }

        String readText() throws IOException {
            int b0 = in.read();
            if (b0 == -1) return null;
            int opcode = b0 & 0x0F;
            if (opcode == 0x8) return null; // close

            int b1 = in.read();
            if (b1 == -1) return null;
            boolean masked = (b1 & 0x80) != 0;
            int len = b1 & 0x7F;
            if (len == 126) {
                len = (in.read() << 8) | in.read();
            } else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) {
                    len = (len << 8) | in.read();
                }
            }

            byte[] mask = null;
            if (masked) {
                mask = new byte[4];
                in.readNBytes(mask, 0, 4);
            }

            byte[] payload = in.readNBytes(len);
            if (masked) {
                for (int i = 0; i < payload.length; i++) {
                    payload[i] ^= mask[i % 4];
                }
            }

            if (opcode == 0x1) { // text
                return new String(payload, StandardCharsets.UTF_8);
            }
            return null;
        }
    }
}
