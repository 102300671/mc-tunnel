package io.mctunnel.core.download;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 简单的 HTTP GET 文本抓取工具(用于版本检查 API).
 */
public final class HttpText {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private HttpText() {
    }

    /**
     * GET 一个 URL 并以 UTF-8 文本返回响应体.
     *
     * @param url 目标 URL
     * @return 响应文本
     * @throws IOException 网络错误或非 2xx 状态
     */
    public static String get(String url) throws IOException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "MC-Tunnel")
                .GET()
                .build();
        try {
            HttpResponse<String> response = CLIENT.send(request,
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IOException("HTTP " + response.statusCode() + " for " + url);
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Request interrupted", e);
        }
    }
}
