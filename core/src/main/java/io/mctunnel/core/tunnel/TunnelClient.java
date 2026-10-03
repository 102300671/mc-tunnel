package io.mctunnel.core.tunnel;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP 客户端,调用后端服务(同一 jar 以 serve 模式运行的 WebServer)的 REST API.
 * <p>
 * 仅远程模式使用(Android: 后端跑在 Termux,地址可配置).
 * 桌面端模组内嵌直连工具,不经过此客户端.
 */
public class TunnelClient {

    private static final Pattern FIELD_PATTERN =
            Pattern.compile("\"(\\w+)\":(\"[^\"]*\"|null|true|false|-?\\d+)");

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

    /** 检查更新 */
    public UpdateCheck checkUpdate(TunnelType type) throws IOException {
        String json = post("/api/check/" + type.name().toLowerCase(), null);
        // 解析失败时后端返回 {"error":...}
        if (json.contains("\"error\"")) {
            throw new IOException(extractStringField(json, "error"));
        }
        return new UpdateCheck(
                type,
                extractStringField(json, "localVersion"),
                extractStringField(json, "latestVersion"),
                !"false".equals(extractStringField(json, "installed")),
                "true".equals(extractStringField(json, "updateAvailable")));
    }

    /** 安装工具(默认选项) */
    public TunnelInfo install(TunnelType type) throws IOException {
        return install(type, InstallOptions.DEFAULT);
    }

    /**
     * 安装工具.
     * Windows 上的 ngrok 缺少 extractDir 时,后端返回 needsExtractDir,
     * 此处转成 {@link ExtractDirRequiredException} 供 GUI 引导用户选目录.
     */
    public TunnelInfo install(TunnelType type, InstallOptions options) throws IOException {
        StringBuilder body = new StringBuilder("{");
        if (options.extractDir() != null) {
            body.append("\"extractDir\":\"").append(
                    options.extractDir().replace("\\", "\\\\").replace("\"", "\\\"")).append("\"");
        }
        if (options.force()) {
            body.append(body.length() > 1 ? "," : "").append("\"force\":true");
        }
        body.append("}");
        String json = post("/api/install/" + type.name().toLowerCase(),
                body.length() > 2 ? body.toString() : null);
        if (json.contains("\"needsExtractDir\":true")) {
            throw new ExtractDirRequiredException();
        }
        if (json.contains("\"error\"")) {
            throw new IOException(extractStringField(json, "error"));
        }
        return parseInfo(json);
    }

    /** 更新到最新版 */
    public TunnelInfo update(TunnelType type) throws IOException {
        String json = post("/api/update/" + type.name().toLowerCase(), null);
        if (json.contains("\"needsExtractDir\":true")) {
            throw new ExtractDirRequiredException();
        }
        if (json.contains("\"error\"")) {
            throw new IOException(extractStringField(json, "error"));
        }
        return parseInfo(json);
    }

    /** 配置工具(如 ngrok authtoken) */
    public TunnelInfo configure(TunnelType type, Map<String, String> config) throws IOException {
        StringBuilder body = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : config.entrySet()) {
            if (!first) body.append(",");
            body.append("\"").append(e.getKey()).append("\":\"")
                    .append(e.getValue() == null ? "" :
                            e.getValue().replace("\\", "\\\\").replace("\"", "\\\""))
                    .append("\"");
            first = false;
        }
        body.append("}");
        String json = post("/api/configure/" + type.name().toLowerCase(), body.toString());
        if (json.contains("\"error\"")) {
            throw new IOException(extractStringField(json, "error"));
        }
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

    /** 启动后台守护进程(无需密码场景) */
    public DaemonResult startDaemon(TunnelType type) throws IOException {
        return daemonAction("/api/daemon-start/" + type.name().toLowerCase(), null);
    }

    /** 启动后台守护进程,附带 sudo 密码(仅本次传输,用后即弃) */
    public DaemonResult startDaemon(TunnelType type, String sudoPassword) throws IOException {
        return daemonAction("/api/daemon-start/" + type.name().toLowerCase(), sudoPassword);
    }

    /** 停止后台守护进程(无需密码场景) */
    public DaemonResult stopDaemon(TunnelType type) throws IOException {
        return daemonAction("/api/daemon-stop/" + type.name().toLowerCase(), null);
    }

    /** 停止后台守护进程,附带 sudo 密码(仅本次传输,用后即弃) */
    public DaemonResult stopDaemon(TunnelType type, String sudoPassword) throws IOException {
        return daemonAction("/api/daemon-stop/" + type.name().toLowerCase(), sudoPassword);
    }

    private DaemonResult daemonAction(String path, String sudoPassword) throws IOException {
        String body = sudoPassword == null ? null
                : "{\"sudoPassword\":" + jsonQuote(sudoPassword) + "}";
        String json = post(path, body);
        if (json.contains("\"needsSudoPassword\":true")) {
            return DaemonResult.needPassword();
        }
        return DaemonResult.ok(parseInfo(json));
    }

    /** 极简 JSON 字符串转义(密码中可能含引号/反斜杠) */
    private static String jsonQuote(String s) {
        var sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    /** 保存并验证 Tailscale API 令牌 */
    public String meshSaveToken(String apiToken) throws IOException {
        return meshAction("token", "\"apiToken\":" + jsonQuote(apiToken));
    }

    /** 模式二:把本机分享给邮箱列表(逗号/分号/空白分隔) */
    public String meshShare(String emails) throws IOException {
        return meshAction("share", "\"emails\":" + jsonQuote(emails));
    }

    /** 模式三:邀请邮箱加入同一 tailnet */
    public String meshInvite(String emails) throws IOException {
        return meshAction("invite", "\"emails\":" + jsonQuote(emails));
    }

    /** 清除已保存的 API 令牌 */
    public String meshClearToken() throws IOException {
        return meshAction("clear-token", null);
    }

    private String meshAction(String action, String body) throws IOException {
        String json = post("/api/mesh/tailscale/" + action,
                body == null ? null : "{" + body + "}");
        Matcher m = RESULT_FIELD.matcher(json);
        if (m.find()) {
            return m.group(1);
        }
        Matcher e = ERROR_FIELD.matcher(json);
        if (e.find()) {
            throw new IOException(e.group(1));
        }
        return json;
    }

    private static final java.util.regex.Pattern RESULT_FIELD =
            java.util.regex.Pattern.compile("\"result\"\\s*:\\s*\"([^\"]*)\"");
    private static final java.util.regex.Pattern ERROR_FIELD =
            java.util.regex.Pattern.compile("\"error\"\\s*:\\s*\"([^\"]*)\"");

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
        // 安装/更新需要下载大文件,超时放宽到 10 分钟
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofMinutes(10))
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
        String localVersion = null;
        String latestVersion = null;
        boolean updateAvailable = false;
        boolean needsAccount = false;
        boolean daemonRunning = false;

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
                case "localVersion" -> localVersion = strVal;
                case "latestVersion" -> latestVersion = strVal;
                case "updateAvailable" -> updateAvailable = val.equals("true");
                case "needsAccount" -> needsAccount = val.equals("true");
                case "daemonRunning" -> daemonRunning = val.equals("true");
            }
        }
        return new TunnelInfo(type, status, localPort, publicUrl, pid, error,
                localVersion, latestVersion, updateAvailable, needsAccount, daemonRunning);
    }

    /** 从 JSON 中提取字符串字段(极简) */
    private String extractStringField(String json, String field) {
        Matcher m = FIELD_PATTERN.matcher(json);
        while (m.find()) {
            if (m.group(1).equals(field)) {
                String val = m.group(2);
                return val.equals("null") ? null : val.replace("\"", "");
            }
        }
        return null;
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
