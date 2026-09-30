package io.mctunnel.core.chat;

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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 网状组网管理器.
 * <p>
 * 负责后端实例之间的消息中继:
 * <ul>
 *   <li>连接到对等节点(其他后端实例)的 ChatServer WebSocket</li>
 *   <li>本地发出的消息通过 {@link #relay(ChatMessage)} 广播给所有对等节点</li>
 *   <li>对等节点发来的消息通过 {@link #setPeerMessageHandler} 回调注入本地广播</li>
 *   <li>基于消息 ID 去重,避免环路</li>
 * </ul>
 * 对等地址格式: ws://host:8788 (即对方 ChatServer 的地址)
 * <p>
 * 零依赖:手写 WebSocket 客户端帧.
 */
public class MeshManager {

    private final String selfNodeId;
    private final List<PeerConn> peers = new CopyOnWriteArrayList<>();
    private final Set<String> seenIds = ConcurrentHashMap.newKeySet();
    private PeerMessageHandler peerMessageHandler;

    public MeshManager(String selfNodeId) {
        this.selfNodeId = selfNodeId;
    }

    /** 对等消息回调(由 ChatServer 注入) */
    public interface PeerMessageHandler {
        void onPeerMessage(ChatMessage msg);
    }

    public void setPeerMessageHandler(PeerMessageHandler handler) {
        this.peerMessageHandler = handler;
    }

    /**
     * 添加一个对等节点并建立 WebSocket 连接.
     *
     * @param wsUrl 对等节点 ChatServer 地址,如 ws://192.168.1.10:8788
     */
    public void addPeer(String wsUrl) {
        // 避免重复连接同一地址
        for (PeerConn p : peers) {
            if (p.url.equals(wsUrl)) return;
        }
        PeerConn peer = new PeerConn(wsUrl);
        peers.add(peer);
        // 异步连接,避免阻塞
        new Thread(peer::connectLoop, "mesh-peer-" + wsUrl).start();
    }

    /** 移除并断开一个对等节点 */
    public void removePeer(String wsUrl) {
        peers.removeIf(p -> {
            if (p.url.equals(wsUrl)) {
                p.close();
                return true;
            }
            return false;
        });
    }

    /**
     * 将本地消息中继给所有对等节点.
     * 若消息来自其他节点(nodeId != self),跳过(避免环路).
     */
    public void relay(ChatMessage msg) {
        if (seenIds.contains(msg.id())) return;
        seenIds.add(msg.id());
        if (seenIds.size() > 5000) {
            // 简单清理,保留最近的一半
            seenIds.clear();
            seenIds.add(msg.id());
        }
        // 自己发出的消息才中继
        if (!selfNodeId.equals(msg.nodeId())) return;
        String json = ChatCodec.toJson(msg);
        for (PeerConn p : peers) {
            try {
                p.sendText(json);
            } catch (IOException ignored) {
                // 连接异常,稍后重连
            }
        }
    }

    /** 处理从对等节点收到的消息 */
    private void handlePeerMessage(String raw) {
        ChatMessage msg = ChatCodec.parse(raw);
        if (msg == null) return;
        if (seenIds.contains(msg.id())) return; // 去重
        seenIds.add(msg.id());
        if (msg.nodeId().equals(selfNodeId)) return; // 自己的消息不回流
        if (peerMessageHandler != null) {
            peerMessageHandler.onPeerMessage(msg);
        }
    }

    public List<String> getPeerUrls() {
        return peers.stream().map(p -> p.url).toList();
    }

    public void shutdown() {
        for (PeerConn p : peers) {
            p.close();
        }
        peers.clear();
    }

    // ── 单个对等节点 WebSocket 连接(原始 Socket 实现) ───────

    private class PeerConn {
        final String url;
        private volatile Socket socket;
        private volatile OutputStream out;
        private volatile InputStream in;
        private volatile boolean running = true;
        private volatile boolean connected = false;

        PeerConn(String url) {
            this.url = url;
        }

        void connectLoop() {
            int backoff = 1;
            while (running) {
                try {
                    connect();
                    backoff = 1;
                    readLoop();
                } catch (Exception e) {
                    connected = false;
                    System.out.println("[MC-Tunnel] Mesh peer " + url
                            + " disconnected: " + e.getMessage());
                }
                if (!running) break;
                try {
                    Thread.sleep(Math.min(backoff, 30) * 1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                backoff = Math.min(backoff * 2, 30);
            }
        }

        private void connect() throws IOException {
            URI uri = URI.create(url);
            String host = uri.getHost();
            int port = uri.getPort();
            if (port == -1) port = 80;

            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setSoTimeout(0);
            out = socket.getOutputStream();
            in = socket.getInputStream();

            // 构造 WebSocket 握手请求
            byte[] keyBytes = new byte[16];
            new Random().nextBytes(keyBytes);
            String wsKey = Base64.getEncoder().encodeToString(keyBytes);

            String path = uri.getRawPath();
            if (path == null || path.isEmpty()) path = "/";

            String handshake = "GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + host + ":" + port + "\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + wsKey + "\r\n"
                    + "Sec-WebSocket-Version: 13\r\n"
                    + "\r\n";
            out.write(handshake.getBytes(StandardCharsets.UTF_8));
            out.flush();

            // 读取握手响应头(读到空行)
            String line;
            boolean upgraded = false;
            while ((line = readLine(in)) != null) {
                if (line.isEmpty()) break;
                if (line.startsWith("HTTP/") && line.contains("101")) {
                    upgraded = true;
                }
            }
            if (!upgraded) {
                throw new IOException("WebSocket handshake failed (no 101)");
            }
            connected = true;
            System.out.println("[MC-Tunnel] Mesh peer connected: " + url);
        }

        private void readLoop() throws IOException {
            while (running) {
                String text = readText();
                if (text == null) break;
                handlePeerMessage(text);
            }
        }

        private String readLine(InputStream in) throws IOException {
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
            return buf.toString(StandardCharsets.UTF_8);
        }

        void sendText(String text) throws IOException {
            if (out == null || !connected) throw new IOException("not connected");
            byte[] payload = text.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            frame.write(0x81); // FIN + text
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

        private String readText() throws IOException {
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
            if (opcode == 0x1) {
                return new String(payload, StandardCharsets.UTF_8);
            }
            return null;
        }

        void close() {
            running = false;
            connected = false;
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    // ── 消息编解码(与 ChatServer 保持一致) ──────────────────

    static final class ChatCodec {
        static String toJson(ChatMessage m) {
            return "{\"id\":\"" + esc(m.id()) + "\","
                    + "\"nodeId\":\"" + esc(m.nodeId()) + "\","
                    + "\"sender\":\"" + esc(m.sender()) + "\","
                    + "\"content\":\"" + esc(m.content()) + "\","
                    + "\"timestamp\":" + m.timestamp() + "}";
        }

        static ChatMessage parse(String json) {
            if (json == null) return null;
            String id = extract(json, "id");
            String nodeId = extract(json, "nodeId");
            String sender = extract(json, "sender");
            String content = extract(json, "content");
            long ts = extractLong(json, "timestamp");
            if (id == null || content == null) return null;
            return new ChatMessage(id, nodeId, sender, content, ts);
        }

        private static String extract(String json, String field) {
            String key = "\"" + field + "\"";
            int idx = json.indexOf(key);
            if (idx < 0) return null;
            int colon = json.indexOf(':', idx + key.length());
            if (colon < 0) return null;
            int q1 = json.indexOf('"', colon + 1);
            if (q1 < 0) return null;
            int q2 = json.indexOf('"', q1 + 1);
            if (q2 < 0) return null;
            return json.substring(q1 + 1, q2).replace("\\\"", "\"")
                    .replace("\\\\", "\\").replace("\\n", "\n").replace("\\r", "\r");
        }

        private static long extractLong(String json, String field) {
            String key = "\"" + field + "\"";
            int idx = json.indexOf(key);
            if (idx < 0) return 0;
            int colon = json.indexOf(':', idx + key.length());
            if (colon < 0) return 0;
            int end = colon + 1;
            while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
            try {
                return Long.parseLong(json.substring(colon + 1, end).trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        private static String esc(String s) {
            if (s == null) return "";
            return s.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\r", "\\r");
        }
    }
}
