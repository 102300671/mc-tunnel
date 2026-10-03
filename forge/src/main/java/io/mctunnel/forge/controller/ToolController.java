package io.mctunnel.forge.controller;

import io.mctunnel.core.tunnel.DaemonResult;
import io.mctunnel.core.tunnel.InstallOptions;
import io.mctunnel.core.tunnel.TunnelInfo;
import io.mctunnel.core.tunnel.TunnelType;
import io.mctunnel.core.tunnel.UpdateCheck;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 工具控制抽象:游戏内 GUI 通过它操作隧道工具.
 * <p>
 * 内嵌模式(桌面端): 直接调用 core 的工具适配器,不依赖 HTTP 端口,
 * 因此 WebUI 关闭时游戏内功能仍完整可用.
 * 远程模式(Android/FCL): 通过 HTTP 调用 Termux 中的后端服务.
 */
public interface ToolController {

    /** 所有工具状态 */
    List<TunnelInfo> status() throws IOException;

    /** 检查更新(官方渠道) */
    UpdateCheck checkUpdate(TunnelType type) throws IOException;

    /** 安装工具(默认选项) */
    default TunnelInfo install(TunnelType type) throws IOException {
        return install(type, InstallOptions.DEFAULT);
    }

    /**
     * 安装工具.
     * Windows 上的 ngrok 需要 options.extractDir(),
     * 缺失时抛 {@link io.mctunnel.core.tunnel.ExtractDirRequiredException}.
     */
    TunnelInfo install(TunnelType type, InstallOptions options) throws IOException;

    /** 更新到官方最新版 */
    TunnelInfo update(TunnelType type) throws IOException;

    /** 配置工具(如 ngrok authtoken) */
    TunnelInfo configure(TunnelType type, Map<String, String> config) throws IOException;

    /**
     * 指定已有工具的位置(用户已自行安装的场景).
     *
     * @param path 可执行文件或其所在目录的完整路径
     */
    TunnelInfo locate(TunnelType type, String path) throws IOException;

    /** 启动工具 */
    TunnelInfo start(TunnelType type, String... args) throws IOException;

    /** 停止工具 */
    TunnelInfo stop(TunnelType type) throws IOException;

    /** 启动后台守护进程(tailscaled);非 root 需要 sudo 密码时结果带 needsSudoPassword */
    DaemonResult startDaemon(TunnelType type) throws IOException;

    /** 启动后台守护进程并附带 sudo 密码(密码不记录不持久化,仅本次使用) */
    DaemonResult startDaemon(TunnelType type, String sudoPassword) throws IOException;

    /** 停止后台守护进程;非 root 需要 sudo 密码时结果带 needsSudoPassword */
    DaemonResult stopDaemon(TunnelType type) throws IOException;

    /** 停止后台守护进程并附带 sudo 密码(密码不记录不持久化,仅本次使用) */
    DaemonResult stopDaemon(TunnelType type, String sudoPassword) throws IOException;

    // ── Tailscale 虚拟组网(控制面 API) ──

    /** 保存并验证 Tailscale API 访问令牌 */
    String meshSaveToken(String apiToken) throws IOException;

    /** 模式二:把本机分享给邮箱列表(逗号/分号/空白分隔),返回结果说明 */
    String meshShare(String emails) throws IOException;

    /** 模式三:邀请邮箱加入同一 tailnet,返回结果说明 */
    String meshInvite(String emails) throws IOException;

    /** 清除已保存的 API 令牌 */
    String meshClearToken() throws IOException;

    /** 后端描述(用于 GUI 显示) */
    String describeBackend();
}
