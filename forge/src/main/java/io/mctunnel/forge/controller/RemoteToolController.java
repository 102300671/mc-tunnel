package io.mctunnel.forge.controller;

import io.mctunnel.core.tunnel.DaemonResult;
import io.mctunnel.core.tunnel.InstallOptions;
import io.mctunnel.core.tunnel.TunnelClient;
import io.mctunnel.core.tunnel.TunnelInfo;
import io.mctunnel.core.tunnel.TunnelType;
import io.mctunnel.core.tunnel.UpdateCheck;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 远程模式控制器:通过 HTTP 调用外部后端(Android/Termux 场景).
 */
public class RemoteToolController implements ToolController {

    private final TunnelClient client;

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

    @Override
    public String describeBackend() {
        return client.getBaseUrl();
    }
}
