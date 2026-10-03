package io.mctunnel.core.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.mctunnel.core.tunnel.ExtractDirRequiredException;
import io.mctunnel.core.tunnel.InstallOptions;
import io.mctunnel.core.tunnel.SudoPasswordRequiredException;
import io.mctunnel.core.tunnel.TunnelInfo;
import io.mctunnel.core.tunnel.TunnelTool;
import io.mctunnel.core.tunnel.TunnelType;
import io.mctunnel.core.tunnel.UpdateCheck;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST API 处理器.
 * <p>
 * 端点:
 * <ul>
 *   <li>GET  /api/status          → 所有工具状态+版本信息(JSON)</li>
 *   <li>POST /api/check/{tool}    → 检查更新(官方渠道)</li>
 *   <li>POST /api/install/{tool}  → 安装工具(body 可选 {"extractDir":"...", "force":true});
 *       Windows ngrok 缺 extractDir 时返回 {"needsExtractDir":true}</li>
 *   <li>POST /api/update/{tool}   → 更新到官方最新版</li>
 *   <li>POST /api/configure/{tool}→ 配置工具(如 {"authtoken":"..."});
 *       特殊键 {"binaryPath":"..."} 指定已有工具位置(可执行文件或所在目录)</li>
 *   <li>POST /api/start/{tool}    → 启动工具(body 可选 JSON {"args":["http","25565"]})</li>
 *   <li>POST /api/stop/{tool}     → 停止工具</li>
 *   <li>POST /api/daemon-start/{tool} → 启动后台守护进程(tailscaled/Tailscale 服务)</li>
 *   <li>POST /api/daemon-stop/{tool}  → 停止后台守护进程</li>
 *   <li>GET  /api/signup-url      → ngrok 注册页地址</li>
 * </ul>
 */
class ApiHandler implements HttpHandler {

    /** ngrok 注册/登录页 */
    static final String NGROK_SIGNUP_URL = "https://dashboard.ngrok.org/signup";

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
            } else if ("GET".equalsIgnoreCase(method) && path.equals("/api/signup-url")) {
                WebServer.sendResponse(exchange, 200,
                        "{\"url\":\"" + NGROK_SIGNUP_URL + "\"}");
            } else if ("POST".equalsIgnoreCase(method) && path.startsWith("/api/check/")) {
                handleCheck(exchange, path);
            } else if ("POST".equalsIgnoreCase(method) && path.startsWith("/api/install/")) {
                handleInstall(exchange, path);
            } else if ("POST".equalsIgnoreCase(method) && path.startsWith("/api/update/")) {
                handleUpdate(exchange, path);
            } else if ("POST".equalsIgnoreCase(method) && path.startsWith("/api/configure/")) {
                handleConfigure(exchange, path);
            } else if ("POST".equalsIgnoreCase(method) && path.startsWith("/api/start/")) {
                handleStart(exchange, path);
            } else if ("POST".equalsIgnoreCase(method) && path.startsWith("/api/stop/")) {
                handleStop(exchange, path);
            } else if ("POST".equalsIgnoreCase(method) && path.startsWith("/api/daemon-start/")) {
                handleDaemon(exchange, path, "/api/daemon-start/", true);
            } else if ("POST".equalsIgnoreCase(method) && path.startsWith("/api/daemon-stop/")) {
                handleDaemon(exchange, path, "/api/daemon-stop/", false);
            } else if ("POST".equalsIgnoreCase(method)
                    && path.startsWith("/api/mesh/tailscale/")) {
                handleMesh(exchange, path);
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

    private void handleCheck(HttpExchange exchange, String path) throws IOException {
        TunnelTool tool = resolveTool(path, "/api/check/");
        if (tool == null) {
            WebServer.sendResponse(exchange, 404, "{\"error\":\"Unknown tool\"}");
            return;
        }
        try {
            UpdateCheck check = tool.checkUpdate();
            WebServer.sendResponse(exchange, 200, String.format(
                    "{\"type\":\"%s\",\"installed\":%b,\"localVersion\":%s,"
                            + "\"latestVersion\":%s,\"updateAvailable\":%b}",
                    tool.getType(), check.installed(),
                    jsonString(check.localVersion()),
                    jsonString(check.latestVersion()),
                    check.updateAvailable()));
        } catch (IOException e) {
            WebServer.sendResponse(exchange, 502,
                    "{\"error\":\"" + escape(e.getMessage()) + "\"}");
        }
    }

    private void handleInstall(HttpExchange exchange, String path) throws IOException {
        TunnelTool tool = resolveTool(path, "/api/install/");
        if (tool == null) {
            WebServer.sendResponse(exchange, 404, "{\"error\":\"Unknown tool\"}");
            return;
        }
        String body = readBody(exchange);
        String extractDir = extractStringField(body, "extractDir");
        boolean force = "true".equals(extractStringField(body, "force"));
        try {
            tool.install(new InstallOptions(extractDir, force));
            WebServer.sendResponse(exchange, 200, infoToJson(tool.getInfo()));
        } catch (ExtractDirRequiredException e) {
            // Windows ngrok:需要前端先询问用户解压位置
            WebServer.sendResponse(exchange, 200, "{\"needsExtractDir\":true}");
        } catch (IOException e) {
            WebServer.sendResponse(exchange, 502,
                    "{\"error\":\"" + escape(e.getMessage()) + "\"}");
        }
    }

    private void handleUpdate(HttpExchange exchange, String path) throws IOException {
        TunnelTool tool = resolveTool(path, "/api/update/");
        if (tool == null) {
            WebServer.sendResponse(exchange, 404, "{\"error\":\"Unknown tool\"}");
            return;
        }
        String body = readBody(exchange);
        String extractDir = extractStringField(body, "extractDir");
        try {
            tool.update(new InstallOptions(extractDir, true));
            WebServer.sendResponse(exchange, 200, infoToJson(tool.getInfo()));
        } catch (ExtractDirRequiredException e) {
            WebServer.sendResponse(exchange, 200, "{\"needsExtractDir\":true}");
        } catch (IOException e) {
            WebServer.sendResponse(exchange, 502,
                    "{\"error\":\"" + escape(e.getMessage()) + "\"}");
        }
    }

    private void handleConfigure(HttpExchange exchange, String path) throws IOException {
        TunnelTool tool = resolveTool(path, "/api/configure/");
        if (tool == null) {
            WebServer.sendResponse(exchange, 404, "{\"error\":\"Unknown tool\"}");
            return;
        }
        String body = readBody(exchange);
        Map<String, String> config = extractAllStringFields(body);
        try {
            // 特殊键:指定已有工具位置(用户已自行安装的场景)
            String binaryPath = config.remove("binaryPath");
            if (binaryPath != null && !binaryPath.isBlank()) {
                tool.setBinaryPath(java.nio.file.Path.of(binaryPath));
            }
            if (!config.isEmpty()) {
                tool.configure(config);
            }
            WebServer.sendResponse(exchange, 200, infoToJson(tool.getInfo()));
        } catch (IOException | RuntimeException e) {
            WebServer.sendResponse(exchange, 502,
                    "{\"error\":\"" + escape(e.getMessage()) + "\"}");
        }
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

    private void handleDaemon(HttpExchange exchange, String path, String prefix, boolean start)
            throws IOException {
        TunnelTool tool = resolveTool(path, prefix);
        if (tool == null) {
            WebServer.sendResponse(exchange, 404, "{\"error\":\"Unknown tool\"}");
            return;
        }
        // 可选 body:{"sudoPassword":"..."};密码不落盘、不写日志,仅本次透传给 sudo
        String body = readBody(exchange);
        String sudoPassword = extractStringField(body, "sudoPassword");
        char[] pw = (sudoPassword == null || sudoPassword.isEmpty())
                ? null : sudoPassword.toCharArray();
        try {
            if (start) {
                tool.startDaemon(pw);
            } else {
                tool.stopDaemon(pw);
            }
            WebServer.sendResponse(exchange, 200, infoToJson(tool.getInfo()));
        } catch (SudoPasswordRequiredException e) {
            // 非 root 且需要 sudo 密码:前端安全收集后带密码重试
            WebServer.sendResponse(exchange, 200, "{\"needsSudoPassword\":true}");
        } catch (IOException | RuntimeException e) {
            WebServer.sendResponse(exchange, 502,
                    "{\"error\":\"" + escape(e.getMessage()) + "\"}");
        }
    }

    private void handleMesh(HttpExchange exchange, String path) throws IOException {
        if (!(tools.get(TunnelType.TAILSCALE)
                instanceof io.mctunnel.core.tunnel.TailscaleAdapter ts)) {
            WebServer.sendResponse(exchange, 404, "{\"error\":\"tailscale 不可用\"}");
            return;
        }
        String action = path.substring("/api/mesh/tailscale/".length());
        String body = readBody(exchange);
        try {
            String result;
            switch (action) {
                case "token" -> {
                    String token = extractStringField(body, "apiToken");
                    ts.saveApiToken(token);
                    result = "API 令牌已验证并保存";
                }
                case "share" -> {
                    String emails = extractStringField(body, "emails");
                    result = ts.shareSelfTo(java.util.List.of(emails == null ? "" : emails));
                }
                case "invite" -> {
                    String emails = extractStringField(body, "emails");
                    result = ts.inviteToTailnet(java.util.List.of(emails == null ? "" : emails));
                }
                case "clear-token" -> {
                    ts.clearApiToken();
                    result = "令牌已清除";
                }
                default -> {
                    WebServer.sendResponse(exchange, 404,
                            "{\"error\":\"Unknown mesh action: " + action + "\"}");
                    return;
                }
            }
            WebServer.sendResponse(exchange, 200,
                    "{\"result\":\"" + escape(result) + "\"}");
        } catch (IOException | RuntimeException e) {
            WebServer.sendResponse(exchange, 502,
                    "{\"error\":\"" + escape(e.getMessage()) + "\"}");
        }
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

    private String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).trim();
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

    /** 极简字符串字段提取: "field":"value" */
    private String extractStringField(String json, String field) {
        if (json == null || json.isEmpty()) return null;
        int idx = json.indexOf("\"" + field + "\"");
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + field.length() + 2);
        if (colon < 0) return null;
        int quoteStart = json.indexOf('"', colon);
        if (quoteStart < 0) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = quoteStart + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                sb.append(json.charAt(++i));
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 提取 JSON 顶层所有字符串字段(用于 configure) */
    private Map<String, String> extractAllStringFields(String json) {
        Map<String, String> map = new LinkedHashMap<>();
        if (json == null || json.isEmpty()) return map;
        // 极简:逐个 "key":"value" 匹配
        java.util.regex.Pattern p =
                java.util.regex.Pattern.compile("\"(\\w+)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
        java.util.regex.Matcher m = p.matcher(json);
        while (m.find()) {
            map.put(m.group(1), m.group(2).replace("\\\"", "\"").replace("\\\\", "\\"));
        }
        return map;
    }

    private String infoToJson(TunnelInfo info) {
        return String.format(
                "{\"type\":\"%s\",\"status\":\"%s\",\"localPort\":%d,\"publicUrl\":%s,\"pid\":%d,"
                        + "\"error\":%s,\"localVersion\":%s,\"latestVersion\":%s,"
                        + "\"updateAvailable\":%b,\"needsAccount\":%b,\"daemonRunning\":%b}",
                info.type(),
                info.status(),
                info.localPort(),
                jsonString(info.publicUrl()),
                info.pid(),
                jsonString(info.error()),
                jsonString(info.localVersion()),
                jsonString(info.latestVersion()),
                info.updateAvailable(),
                info.needsAccount(),
                info.daemonRunning());
    }

    private String jsonString(String s) {
        return s != null ? "\"" + escape(s) + "\"" : "null";
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
