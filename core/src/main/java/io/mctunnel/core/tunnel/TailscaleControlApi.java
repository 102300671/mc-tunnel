package io.mctunnel.core.tunnel;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tailscale 控制面 API(api.tailscale.com)客户端,仅覆盖联机组网需要的操作:
 * <ul>
 *   <li>验证 API 访问令牌(tskey-api-...);</li>
 *   <li>列出 tailnet 设备,把本机(nodeId)映射到 API 用的数字设备 ID;</li>
 *   <li>把本机分享给指定邮箱的外部用户(POST /device/{id}/shares);</li>
 *   <li>邀请指定邮箱加入同一 tailnet(POST /tailnet/-/invitations)。</li>
 * </ul>
 * 认证:HTTP Basic(令牌作为用户名,空密码)。令牌由调用方持有,本类不持久化。
 * 极简 JSON 处理,不引入第三方依赖。
 */
public class TailscaleControlApi {

    private static final String BASE = "https://api.tailscale.com/api/v2";
    /** tailnet="-" 表示令牌所有者自己的 tailnet */
    private static final String OWN_TAILNET = "-";

    /** 设备引用 */
    public record DeviceRef(String id, String nodeId, String hostname, List<String> ips) {
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** 校验令牌:能列出设备即有效 */
    public void validateToken(String token) throws IOException {
        listDevices(token);
    }

    /** 列出 tailnet 全部设备 */
    public List<DeviceRef> listDevices(String token) throws IOException {
        String json = get(token, BASE + "/tailnet/" + OWN_TAILNET + "/devices");
        return parseDevices(json);
    }

    /** 解析 GET devices 响应(包级可见便于单测) */
    static List<DeviceRef> parseDevices(String json) {
        List<DeviceRef> out = new ArrayList<>();
        // 设备对象里有 clientConnectivity 等嵌套对象,用大括号深度感知的切分
        int arr = json.indexOf("\"devices\"");
        for (String block : extractArrayObjects(arr >= 0 ? json.substring(arr) : json)) {
            String id = firstGroup(ID_PATTERN, block);
            String nodeId = firstGroup(NODE_ID_PATTERN, block);
            String host = firstGroup(HOST_PATTERN, block);
            List<String> ips = new ArrayList<>();
            Matcher im = IP_PATTERN.matcher(block);
            while (im.find()) {
                ips.add(im.group(1));
            }
            if (id != null) {
                out.add(new DeviceRef(id, nodeId, host, ips));
            }
        }
        return out;
    }

    /**
     * 提取 JSON 数组中第一层对象字符串(按大括号深度切分,容忍嵌套对象;
     * 跳过字符串里的括号,并处理转义引号)。
     */
    static List<String> extractArrayObjects(String s) {
        List<String> out = new ArrayList<>();
        int lb = s.indexOf('[');
        if (lb < 0) {
            return out;
        }
        int depth = 0;
        int start = -1;
        boolean inStr = false;
        boolean esc = false;
        for (int p = lb + 1; p < s.length(); p++) {
            char c = s.charAt(p);
            if (inStr) {
                if (esc) {
                    esc = false;
                } else if (c == '\\') {
                    esc = true;
                } else if (c == '"') {
                    inStr = false;
                }
                continue;
            }
            if (c == '"') {
                inStr = true;
            } else if (c == '{') {
                if (depth == 0) {
                    start = p;
                }
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    out.add(s.substring(start, p + 1));
                    start = -1;
                }
            } else if (c == ']' && depth == 0) {
                break;
            }
        }
        return out;
    }

    private static final Pattern ID_PATTERN =
            Pattern.compile("\"id\"\\s*:\\s*\"?([^\",}]+?)\"?\\s*[,}]");
    private static final Pattern NODE_ID_PATTERN =
            Pattern.compile("\"nodeId\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern HOST_PATTERN =
            Pattern.compile("\"hostname\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern IP_PATTERN =
            Pattern.compile("\"(100\\.\\d+\\.\\d+\\.\\d+)\"");

    private static String firstGroup(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    /**
     * 把指定设备分享给一批外部用户(各自用自己的账号)。
     *
     * @param deviceId 设备的数字 ID(GET devices 的 id 字段)
     * @param emails   接收方邮箱
     */
    public void shareDevice(String token, String deviceId, List<String> emails)
            throws IOException {
        String body = "{\"users\":[" + joinQuoted(emails) + "]}";
        post(token, BASE + "/device/" + deviceId + "/shares", body,
                "分享设备", emails.size() + " 个邮箱");
    }

    /**
     * 邀请一批外部用户加入 tailnet(角色 member:可使用设备、不能进管理控制台)。
     *
     * @return API 返回的邀请信息(用于界面展示)
     */
    public String inviteUsers(String token, List<String> emails) throws IOException {
        String body = "{\"emails\":[" + joinQuoted(emails) + "],\"role\":\"member\"}";
        return post(token, BASE + "/tailnet/" + OWN_TAILNET + "/invitations", body,
                "发送 tailnet 邀请", emails.size() + " 个邮箱");
    }

    // ── HTTP 基础 ───────────────────────────────────────

    private String get(String token, String url) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", basic(token))
                .GET()
                .build();
        return send(req, "请求 Tailscale API");
    }

    private String post(String token, String url, String jsonBody,
                        String action, String target) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", basic(token))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();
        return send(req, action + "(" + target + ")");
    }

    private String send(HttpRequest req, String action) throws IOException {
        HttpResponse<String> res;
        try {
            res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Tailscale API 请求被中断", e);
        }
        int sc = res.statusCode();
        if (sc >= 200 && sc < 300) {
            return res.body() == null ? "" : res.body();
        }
        String msg = extractMessage(res.body());
        throw switch (sc) {
            case 401 -> new IOException("Tailscale API 令牌无效或已过期,请重新生成:"
                    + (msg == null ? "401" : msg));
            case 403 -> new IOException("令牌权限不足:该操作需要 tailnet 的 Owner/Admin/IT admin 角色"
                    + (msg == null ? "" : "(" + msg + ")"));
            case 404 -> new IOException("对象不存在(设备 ID 或 tailnet 无效)"
                    + (msg == null ? "" : ":" + msg));
            case 429 -> new IOException("Tailscale API 限流,请稍后再试");
            default -> new IOException(action + "失败: HTTP " + sc
                    + (msg == null ? "" : " " + msg));
        };
    }

    /** 从错误 JSON 中提取 message 字段 */
    private static String extractMessage(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        Matcher m = Pattern.compile("\"message\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        return m.find() ? m.group(1) : (body.length() > 200 ? body.substring(0, 200) : body);
    }

    private static String basic(String token) {
        return "Basic " + Base64.getEncoder().encodeToString(
                (token.trim() + ":").getBytes(StandardCharsets.UTF_8));
    }

    /** 邮箱列表 → "a@b.com","c@d.com"(做基本 JSON 转义) */
    private static String joinQuoted(List<String> emails) {
        var sb = new StringBuilder();
        for (int i = 0; i < emails.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(emails.get(i).replace("\\", "\\\\").replace("\"", "\\\""))
                    .append('"');
        }
        return sb.toString();
    }
}
