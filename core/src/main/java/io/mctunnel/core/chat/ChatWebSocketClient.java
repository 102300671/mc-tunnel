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
import java.util.Random;
import java.util.function.Consumer;

/**
 * 聊天 WebSocket 客户端.
 * <p>
 * 供 Forge 模组连接后端 ChatServer 使用.
 * 零依赖,手写 WebSocket 握手与帧.
 * <p>
 * 用法:
 * <pre>
 * ChatWebSocketClient client = new ChatWebSocketClient("ws://localhost:8788");
 * client.setMessageHandler(msg -> System.out.println(msg.content()));
 * client.connect();
 * client.send("player", "hello");
 * </pre>
 */
public class ChatWebSocketClient {

    private final String wsUrl;
    private final Random random = new Random();
    private volatile Socket socket;
    private volatile OutputStream out;
    private volatile InputStream in;
    private volatile boolean running = false;
    private Consumer<ChatMessage> messageHandler;
    private Thread readThread;

    public ChatWebSocketClient(String wsUrl) {
        this.wsUrl = wsUrl;
    }

    public void setMessageHandler(Consumer<ChatMessage> handler) {
        this.messageHandler = handler;
    }

    /** 建立 WebSocket 连接并启动读取线程 */
    public void connect() throws IOException {
        URI uri = URI.create(wsUrl);
        String host = uri.getHost();
        int port = uri.getPort();
        if (port == -1) port = 80;

        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 5000);
        socket.setSoTimeout(0);
        out = socket.getOutputStream();
        in = socket.getInputStream();

        byte[] keyBytes = new byte[16];
        random.nextBytes(keyBytes);
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

        // 读取响应头
        String line;
        boolean upgraded = false;
        while ((line = readLine()) != null) {
            if (line.isEmpty()) break;
            if (line.startsWith("HTTP/") && line.contains("101")) upgraded = true;
        }
        if (!upgraded) {
            throw new IOException("WebSocket handshake failed");
        }

        running = true;
        readThread = new Thread(this::readLoop, "chat-ws-client");
        readThread.setDaemon(true);
        readThread.start();
    }

    /** 发送一条消息到服务器 */
    public void send(String sender, String content) throws IOException {
        String json = "{\"sender\":\"" + esc(sender) + "\","
                + "\"content\":\"" + esc(content) + "\"}";
        sendRaw(json);
    }

    /** 发送原始文本帧 */
    public void sendRaw(String text) throws IOException {
        if (out == null || !running) throw new IOException("not connected");
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

    private void readLoop() {
        try {
            while (running) {
                String text = readText();
                if (text == null) break;
                ChatMessage msg = MeshManager.ChatCodec.parse(text);
                if (msg != null && messageHandler != null) {
                    messageHandler.accept(msg);
                }
            }
        } catch (IOException ignored) {
        } finally {
            running = false;
        }
    }

    private String readLine() throws IOException {
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

    private String readText() throws IOException {
        int b0 = in.read();
        if (b0 == -1) return null;
        int opcode = b0 & 0x0F;
        if (opcode == 0x8) return null;
        int b1 = in.read();
        if (b1 == -1) return null;
        boolean masked = (b1 & 0x80) != 0;
        int len = b1 & 0x7F;
        if (len == 126) {
            len = (in.read() << 8) | in.read();
        } else if (len == 127) {
            len = 0;
            for (int i = 0; i < 8; i++) len = (len << 8) | in.read();
        }
        byte[] mask = null;
        if (masked) {
            mask = new byte[4];
            in.readNBytes(mask, 0, 4);
        }
        byte[] payload = in.readNBytes(len);
        if (masked) {
            for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
        }
        if (opcode == 0x1) return new String(payload, StandardCharsets.UTF_8);
        return null;
    }

    public boolean isRunning() {
        return running;
    }

    public void close() {
        running = false;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
