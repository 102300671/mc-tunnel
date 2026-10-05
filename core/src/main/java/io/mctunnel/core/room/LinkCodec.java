package io.mctunnel.core.room;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * 加入链接编解码.
 * <p>
 * 链接格式: {@code mctunnel://<relayHost>:<relayPort>/<roomId>}
 * <p>
 * 示例: {@code mctunnel://59.110.163.88:8721/abc123}
 */
public final class LinkCodec {

    /** URL scheme */
    public static final String SCHEME = "mctunnel";

    private LinkCodec() {
    }

    /**
     * 生成加入链接.
     *
     * @param relayHost 中继主机
     * @param relayPort 中继端口
     * @param roomId    房间 ID
     * @return 可分享的链接字符串
     */
    public static String encode(String relayHost, int relayPort, String roomId) {
        return SCHEME + "://" + relayHost + ":" + relayPort + "/" + roomId;
    }

    /**
     * 解析加入链接.
     *
     * @param link 链接字符串
     * @return 解析后的 JoinLink
     * @throws IllegalArgumentException 链接格式无效
     */
    public static JoinLink decode(String link) {
        if (link == null || link.isBlank()) {
            throw new IllegalArgumentException("链接为空");
        }
        String s = link.trim();
        // 允许 https?:// 前缀(便于通过浏览器跳转),统一提取 authority + path
        URI uri;
        try {
            uri = new URI(s);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("链接格式无效: " + e.getMessage(), e);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !SCHEME.equalsIgnoreCase(scheme)
                && !"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("不支持的链接 scheme: " + scheme);
        }
        String host = uri.getHost();
        int port = uri.getPort();
        String path = uri.getPath();
        if (host == null || port <= 0) {
            throw new IllegalArgumentException("链接缺少主机或端口: " + s);
        }
        if (path == null || path.isEmpty() || "/".equals(path)) {
            throw new IllegalArgumentException("链接缺少房间 ID: " + s);
        }
        String roomId = path.startsWith("/") ? path.substring(1) : path;
        if (roomId.isEmpty()) {
            throw new IllegalArgumentException("链接缺少房间 ID: " + s);
        }
        return new JoinLink(host, port, roomId);
    }
}
