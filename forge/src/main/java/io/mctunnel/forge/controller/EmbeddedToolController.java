package io.mctunnel.forge.controller;

import io.mctunnel.core.tunnel.DaemonResult;
import io.mctunnel.core.tunnel.InstallOptions;
import io.mctunnel.core.tunnel.SudoPasswordRequiredException;
import io.mctunnel.core.tunnel.TunnelInfo;
import io.mctunnel.core.tunnel.TunnelTool;
import io.mctunnel.core.tunnel.TunnelType;
import io.mctunnel.core.tunnel.UpdateCheck;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 内嵌模式控制器:直接调用 core 层工具适配器.
 * <p>
 * 不经过 HTTP,因此 WebUI(8787 端口)是否开启不影响游戏内操作.
 */
public class EmbeddedToolController implements ToolController {

    private final Map<TunnelType, TunnelTool> tools;

    public EmbeddedToolController(Map<TunnelType, TunnelTool> tools) {
        this.tools = tools;
    }

    @Override
    public List<TunnelInfo> status() {
        List<TunnelInfo> list = new ArrayList<>();
        // 固定按枚举顺序:GUI 按钮行按 TunnelType.values() 排列,状态行顺序必须一致
        for (TunnelType type : TunnelType.values()) {
            TunnelTool tool = tools.get(type);
            if (tool != null) {
                list.add(tool.getInfo());
            }
        }
        return list;
    }

    @Override
    public UpdateCheck checkUpdate(TunnelType type) throws IOException {
        return tools.get(type).checkUpdate();
    }

    @Override
    public TunnelInfo install(TunnelType type, InstallOptions options) throws IOException {
        TunnelTool tool = tools.get(type);
        tool.install(options);
        return tool.getInfo();
    }

    @Override
    public TunnelInfo update(TunnelType type) throws IOException {
        TunnelTool tool = tools.get(type);
        tool.update(InstallOptions.DEFAULT);
        return tool.getInfo();
    }

    @Override
    public TunnelInfo configure(TunnelType type, Map<String, String> config) {
        TunnelTool tool = tools.get(type);
        tool.configure(config);
        return tool.getInfo();
    }

    @Override
    public TunnelInfo start(TunnelType type, String... args) throws IOException {
        return tools.get(type).start(args);
    }

    @Override
    public TunnelInfo stop(TunnelType type) {
        TunnelTool tool = tools.get(type);
        tool.stop();
        return tool.getInfo();
    }

    @Override
    public TunnelInfo locate(TunnelType type, String path) throws IOException {
        TunnelTool tool = tools.get(type);
        tool.setBinaryPath(Paths.get(path));
        return tool.getInfo();
    }

    @Override
    public DaemonResult startDaemon(TunnelType type) throws IOException {
        return startDaemon(type, null);
    }

    @Override
    public DaemonResult startDaemon(TunnelType type, String sudoPassword) throws IOException {
        TunnelTool tool = tools.get(type);
        char[] pw = sudoPassword == null ? null : sudoPassword.toCharArray();
        try {
            tool.startDaemon(pw);
            return DaemonResult.ok(tool.getInfo());
        } catch (SudoPasswordRequiredException e) {
            return DaemonResult.needPassword();
        }
    }

    @Override
    public DaemonResult stopDaemon(TunnelType type) throws IOException {
        return stopDaemon(type, null);
    }

    @Override
    public DaemonResult stopDaemon(TunnelType type, String sudoPassword) throws IOException {
        TunnelTool tool = tools.get(type);
        char[] pw = sudoPassword == null ? null : sudoPassword.toCharArray();
        try {
            tool.stopDaemon(pw);
            return DaemonResult.ok(tool.getInfo());
        } catch (SudoPasswordRequiredException e) {
            return DaemonResult.needPassword();
        }
    }

    @Override
    public String meshSaveToken(String apiToken) throws IOException {
        tailscale().saveApiToken(apiToken);
        return "API 令牌已验证并保存";
    }

    @Override
    public String meshShare(String emails) throws IOException {
        return tailscale().shareSelfTo(java.util.List.of(emails));
    }

    @Override
    public String meshInvite(String emails) throws IOException {
        return tailscale().inviteToTailnet(java.util.List.of(emails));
    }

    @Override
    public String meshClearToken() throws IOException {
        tailscale().clearApiToken();
        return "令牌已清除";
    }

    private io.mctunnel.core.tunnel.TailscaleAdapter tailscale()
            throws java.io.IOException {
        TunnelTool t = tools.get(TunnelType.TAILSCALE);
        if (!(t instanceof io.mctunnel.core.tunnel.TailscaleAdapter ts)) {
            throw new java.io.IOException("tailscale 不可用");
        }
        return ts;
    }

    @Override
    public String describeBackend() {
        return "内嵌(游戏进程)";
    }
}
