package io.mctunnel.forge.controller;

import io.mctunnel.core.tunnel.DaemonResult;
import io.mctunnel.core.tunnel.InstallOptions;
import io.mctunnel.core.tunnel.TunnelClient;
import io.mctunnel.core.tunnel.TunnelInfo;
import io.mctunnel.core.tunnel.TunnelType;
import io.mctunnel.core.tunnel.UpdateCheck;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 远程模式控制器:通过 HTTP 调用外部后端(Android/Termux 场景).
 */
public class RemoteToolController implements ToolController {

    private final TunnelClient client;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    public RemoteToolController(TunnelClient client) {
        this.client = client;
    }

    @Override
    public List<TunnelInfo> status() throws IOException {
        return client.status();
    }

    @Override
    public UpdateCheck checkUpdate(TunnelType type) throws IOException {
        return client.checkUpdate(type);
    }

    @Override
    public TunnelInfo install(TunnelType type, InstallOptions options) throws IOException {
        return client.install(type, options);
    }

    @Override
    public TunnelInfo update(TunnelType type) throws IOException {
        return client.update(type);
    }

    @Override
    public TunnelInfo configure(TunnelType type, Map<String, String> config) throws IOException {
        return client.configure(type, config);
    }

    @Override
    public TunnelInfo locate(TunnelType type, String path) throws IOException {
        // 走后端 configure 的特殊键 binaryPath
        return client.configure(type, Map.of("binaryPath", path));
    }

    @Override
    public TunnelInfo start(TunnelType type, String... args) throws IOException {
        return client.start(type, args);
    }

    @Override
    public TunnelInfo stop(TunnelType type) throws IOException {
        return client.stop(type);
    }

    @Override
    public DaemonResult startDaemon(TunnelType type) throws IOException {
        return client.startDaemon(type);
    }

    @Override
    public DaemonResult startDaemon(TunnelType type, String sudoPassword) throws IOException {
        return client.startDaemon(type, sudoPassword);
    }

    @Override
    public DaemonResult stopDaemon(TunnelType type) throws IOException {
        return client.stopDaemon(type);
    }

    @Override
    public DaemonResult stopDaemon(TunnelType type, String sudoPassword) throws IOException {
        return client.stopDaemon(type, sudoPassword);
    }

    @Override
    public String meshSaveToken(String apiToken) throws IOException {
        return client.meshSaveToken(apiToken);
    }

    @Override
    public String meshShare(String emails) throws IOException {
        return client.meshShare(emails);
    }

    @Override
    public String meshInvite(String emails) throws IOException {
        return client.meshInvite(emails);
    }

    @Override
    public String meshClearToken() throws IOException {
        return client.meshClearToken();
    }

    // ── 房间 & 网络(直接走 HTTP,后端 ApiHandler) ──

    @Override
    public String roomCreate(String name) throws IOException {
        return httpPost("/api/rooms/create", "{\"name\":\"" + name + "\"}");
    }

    @Override
    public String roomJoin(String link) throws IOException {
        return httpPost("/api/rooms/join", "{\"link\":\"" + link + "\"}");
    }

    @Override
    public void roomLeave() throws IOException {
        httpPost("/api/rooms/leave", null);
    }

    @Override
    public String roomCurrent() throws IOException {
        return httpGet("/api/rooms/current");
    }

    @Override
    public String networkList() throws IOException {
        return httpGet("/api/rooms/networks");
    }

    @Override
    public String networkCreate(String type, String name) throws IOException {
        return httpPost("/api/rooms/networks",
                "{\"type\":\"" + type + "\",\"name\":\"" + name + "\"}");
    }

    private String httpPost(String path, String body) throws IOException {
        try {
            HttpRequest.Builder rb = HttpRequest.newBuilder()
                    .uri(URI.create(client.getBaseUrl() + path))
                    .timeout(Duration.ofSeconds(10));
            if (body != null) {
                rb.header("Content-Type", "application/json")
                  .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            } else {
                rb.POST(HttpRequest.BodyPublishers.noBody());
            }
            HttpResponse<String> resp = http.send(rb.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return resp.body();
        } catch (Exception e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    private String httpGet(String path) throws IOException {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(client.getBaseUrl() + path))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return resp.body();
        } catch (Exception e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    @Override
    public String describeBackend() {
        return client.getBaseUrl();
    }
}
