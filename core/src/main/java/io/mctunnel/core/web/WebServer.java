package io.mctunnel.core.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.mctunnel.core.room.RoomManager;
import io.mctunnel.core.tunnel.TunnelTool;
import io.mctunnel.core.tunnel.TunnelType;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * 内嵌 HTTP 服务器,提供 WebUI 与 REST API.
 * <p>
 * 零依赖,使用 JDK 内置的 {@link HttpServer}.
 * 端口默认 8787,可通过环境变量 MCTUNNEL_WEB_PORT 覆盖.
 */
public class WebServer {

    private final int port;
    private final Map<TunnelType, TunnelTool> tools;
    private final RoomManager roomManager;
    private HttpServer server;

    public WebServer(Map<TunnelType, TunnelTool> tools) {
        this(tools, null, Integer.parseInt(
                System.getenv().getOrDefault("MCTUNNEL_WEB_PORT", "8787")));
    }

    public WebServer(Map<TunnelType, TunnelTool> tools, RoomManager roomManager) {
        this(tools, roomManager, Integer.parseInt(
                System.getenv().getOrDefault("MCTUNNEL_WEB_PORT", "8787")));
    }

    public WebServer(Map<TunnelType, TunnelTool> tools, RoomManager roomManager, int port) {
        this.tools = tools;
        this.roomManager = roomManager;
        this.port = port;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newCachedThreadPool());

        // 静态页面
        server.createContext("/", new StaticHandler());
        // REST API
        server.createContext("/api", new ApiHandler(tools, roomManager));

        server.start();
        System.out.println("[MC-Tunnel] WebUI started at http://localhost:" + port);
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    public int getPort() {
        return port;
    }

    /** 静态资源处理器:返回内置的 HTML 仪表盘 */
    private static class StaticHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if ("/".equals(path) || path.isEmpty()) {
                path = "/index.html";
            }

            String resource = "web" + path;
            try (InputStream in = WebServer.class.getClassLoader().getResourceAsStream(resource)) {
                if (in == null) {
                    sendResponse(exchange, 404, "Not Found");
                    return;
                }
                byte[] body = in.readAllBytes();
                exchange.getResponseHeaders().set("Content-Type", contentTypeFor(path));
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
        }

        private String contentTypeFor(String path) {
            if (path.endsWith(".html")) return "text/html; charset=utf-8";
            if (path.endsWith(".css")) return "text/css";
            if (path.endsWith(".js")) return "application/javascript";
            return "application/octet-stream";
        }
    }

    static void sendResponse(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    static void sendCors(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
    }
}
