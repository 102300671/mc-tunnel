package io.mctunnel.core.tunnel;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP 客户端,调用后端服务(standalone jar 内嵌的 WebServer)的 REST API.
 * <p>
 * 模组层和任何上层都只通过此客户端操作工具,不直接调用二进制.
 * 桌面端: 后端服务由模组内嵌启动(localhost).
 * Android: 后端服务跑在 Termux,地址可配置.
 */
public class TunnelClient {

    private static final Pattern FIELD_PATTERN =
            Pattern.compile("\"(\\w+)\":(\"[^\"]*\"|null|-?\\d+)");

    private final String baseUrl;
    private final HttpClient httpClient;

    public TunnelClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /** 获取所有工具状态 */
    public List<TunnelInfo> status() throws IOException {
        String json = get("/api/status");
        return parseInfoArray(json);
    }

    /** 安装工具 */
    public TunnelInfo install(TunnelType type) throws IOException {
        String json = post("/api/install/" + type.name().toLowerCase(), null);
        return parseInfo(json);
    }

    /** 启动工具 */
    public TunnelInfo start(TunnelType type, String... args) throws IOException {
        String body = args.length > 0
                ? "{\"args\":[" + toJsonArray(args) + "]}"
                : null;
        String json = post("/api/start/" + type.name().toLowerCase(), body);
        return parseInfo(json);
    }

    /** 停止工具 */
    public TunnelInfo stop(TunnelType type) throws IOException {
        String json = post("/api/stop/" + type.name().toLowerCase(), null);
        return parseInfo(json);
    }

    /** 检测后端服务是否可达 */
    public boolean isReachable() {
        try {
            status();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    // ── HTTP 辅助 ──────────────────────────────────────

    private String get(String path) throws IOException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        return send(req);
    }

    private String post(String path, String jsonBody) throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json");
        if (jsonBody != null) {
            builder.POST(HttpRequest.BodyPublishers.ofString(jsonBody));
        } else {
            builder.POST(HttpRequest.BodyPublishers.noBody());
        }
        return send(builder.build());
    }

    private String send(HttpRequest req) throws IOException {
        try {
            HttpResponse<String> res = httpClient.send(req,
                    HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 400) {
                throw new IOException("HTTP " + res.statusCode() + ": " + res.body());
            }
            return res.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Request interrupted", e);
        }
    }

    // ── JSON 解析(极简,无第三方依赖) ─────────────────────

    private List<TunnelInfo> parseInfoArray(String json) {
        List<TunnelInfo> list = new ArrayList<>();
        // 按 },{ 分割单个对象
        String trimmed = json.trim();
        if (trimmed.startsWith("[")) trimmed = trimmed.substring(1);
        if (trimmed.endsWith("]")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        for (String obj : splitObjects(trimmed)) {
            list.add(parseInfo(obj));
        }
        return list;
    }

    private List<String> splitObjects(String json) {
        List<String> result = new ArrayList<>();
        int depth = 0;
        int start = 0;
        boolean inString = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"' && (i == 0 || json.charAt(i - 1) != '\\')) {
                inString = !inString;
            } else if (!inString) {
                if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        result.add(json.substring(start, i + 1));
                        start = i + 1;
                    }
                }
            }
        }
        return result;
    }

    private TunnelInfo parseInfo(String json) {
        TunnelType type = TunnelType.NGROK;
        TunnelStatus status = TunnelStatus.STOPPED;
        int localPort = 0;
        String publicUrl = null;
        long pid = -1;
        String error = null;

        Matcher m = FIELD_PATTERN.matcher(json);
        while (m.find()) {
            String key = m.group(1);
            String val = m.group(2);
            String strVal = val.equals("null") ? null : val.replace("\"", "");
            switch (key) {
                case "type" -> {
                    try { type = TunnelType.valueOf(strVal); } catch (Exception ignored) {}
                }
                case "status" -> {
                    try { status = TunnelStatus.valueOf(strVal); } catch (Exception ignored) {}
                }
                case "localPort" -> {
                    try { localPort = Integer.parseInt(val); } catch (Exception ignored) {}
                }
                case "publicUrl" -> publicUrl = strVal;
                case "pid" -> {
                    try { pid = Long.parseLong(val); } catch (Exception ignored) {}
                }
                case "error" -> error = strVal;
            }
        }
        return new TunnelInfo(type, status, localPort, publicUrl, pid, error);
    }

    private String toJsonArray(String[] arr) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(arr[i].replace("\"", "\\\"")).append("\"");
        }
        return sb.toString();
    }
}
