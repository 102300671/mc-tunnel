package io.mctunnel.core.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.mctunnel.core.tunnel.TunnelInfo;
import io.mctunnel.core.tunnel.TunnelTool;
import io.mctunnel.core.tunnel.TunnelType;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * REST API 处理器.
 * <p>
 * 端点:
 * <ul>
 *   <li>GET  /api/status          → 所有工具状态(JSON)</li>
 *   <li>POST /api/install/{tool}  → 安装工具</li>
 *   <li>POST /api/start/{tool}    → 启动工具(body 可选 JSON {"args":["http","25565"]})</li>
 *   <li>POST /api/stop/{tool}     → 停止工具</li>
 * </ul>
 */
class ApiHandler implements HttpHandler {

    private final Map<TunnelType, TunnelTool> tools;

    ApiHandler(Map<TunnelType, TunnelTool> tools) {
        this.tools = tools;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        WebServer.sendCors(exchange);

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            WebServer.sendResponse(exchange, 204, "");
            return;
        }

        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();

        try {
            if ("GET".equalsIgnoreCase(method) && path.equals("/api/status")) {
                handleStatus(exchange);
            } else if ("POST".equalsIgnoreCase(method) && path.startsWith("/api/install/")) {
                handleInstall(exchange, path);
            } else if ("POST".equalsIgnoreCase(method) && path.startsWith("/api/start/")) {
                handleStart(exchange, path);
            } else if ("POST".equalsIgnoreCase(method) && path.startsWith("/api/stop/")) {
                handleStop(exchange, path);
            } else {
                WebServer.sendResponse(exchange, 404,
                        "{\"error\":\"Not found: " + path + "\"}");
            }
        } catch (Exception e) {
            WebServer.sendResponse(exchange, 500,
                    "{\"error\":\"" + escape(e.getMessage()) + "\"}");
        }
    }

    private void handleStatus(HttpExchange exchange) throws IOException {
        StringBuilder json = new StringBuilder("[");
        boolean first = true;
        for (TunnelTool tool : tools.values()) {
            if (!first) json.append(",");
            json.append(infoToJson(tool.getInfo()));
            first = false;
        }
        json.append("]");
        WebServer.sendResponse(exchange, 200, json.toString());
    }

    private void handleInstall(HttpExchange exchange, String path) throws IOException {
        TunnelTool tool = resolveTool(path, "/api/install/");
        if (tool == null) {
            WebServer.sendResponse(exchange, 404, "{\"error\":\"Unknown tool\"}");
            return;
        }
        tool.install();
        WebServer.sendResponse(exchange, 200, infoToJson(tool.getInfo()));
    }

    private void handleStart(HttpExchange exchange, String path) throws IOException {
        TunnelTool tool = resolveTool(path, "/api/start/");
        if (tool == null) {
            WebServer.sendResponse(exchange, 404, "{\"error\":\"Unknown tool\"}");
            return;
        }

        // 可选 body: {"args":["http","25565"]}
        String[] args = parseArgs(exchange.getRequestBody());
        TunnelInfo info = tool.start(args);
        WebServer.sendResponse(exchange, 200, infoToJson(info));
    }

    private void handleStop(HttpExchange exchange, String path) throws IOException {
        TunnelTool tool = resolveTool(path, "/api/stop/");
        if (tool == null) {
            WebServer.sendResponse(exchange, 404, "{\"error\":\"Unknown tool\"}");
            return;
        }
        tool.stop();
        WebServer.sendResponse(exchange, 200, infoToJson(tool.getInfo()));
    }

    private TunnelTool resolveTool(String path, String prefix) {
        String name = path.substring(prefix.length()).toUpperCase();
        try {
            TunnelType type = TunnelType.valueOf(name);
            return tools.get(type);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 极简 JSON args 解析: {"args":["http","25565"]} */
    private String[] parseArgs(InputStream body) throws IOException {
        String json = new String(body.readAllBytes(), StandardCharsets.UTF_8).trim();
        if (json.isEmpty()) return new String[0];

        // 提取 args 数组
        int start = json.indexOf("\"args\"");
        if (start < 0) return new String[0];
        int arrStart = json.indexOf('[', start);
        int arrEnd = json.indexOf(']', arrStart);
        if (arrStart < 0 || arrEnd < 0) return new String[0];

        String arrContent = json.substring(arrStart + 1, arrEnd).trim();
        if (arrContent.isEmpty()) return new String[0];

        // 按逗号分割,去除引号和空格
        String[] parts = arrContent.split(",");
        String[] result = new String[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = parts[i].trim().replace("\"", "");
        }
        return result;
    }

    private String infoToJson(TunnelInfo info) {
        return String.format(
                "{\"type\":\"%s\",\"status\":\"%s\",\"localPort\":%d,\"publicUrl\":%s,\"pid\":%d,\"error\":%s}",
                info.type(),
                info.status(),
                info.localPort(),
                info.publicUrl() != null ? "\"" + info.publicUrl() + "\"" : "null",
                info.pid(),
                info.error() != null ? "\"" + escape(info.error()) + "\"" : "null"
        );
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
