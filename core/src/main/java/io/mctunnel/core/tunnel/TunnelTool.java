package io.mctunnel.core.tunnel;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * 隧道/组网工具抽象接口.
 * <p>
 * 所有受支持的工具(ngrok/Tailscale/SyncThing)都实现此接口.
 * 实现类需负责:
 * <ul>
 *   <li>检测工具是否已安装</li>
 *   <li>下载并安装二进制</li>
 *   <li>启停进程</li>
 *   <li>提供运行状态与公网地址</li>
 * </ul>
 */
public interface TunnelTool {

    /** 工具类型 */
    TunnelType getType();

    /** 是否已安装(二进制存在且可执行) */
    boolean isInstalled();

    /**
     * 检查更新:本地版本 vs 官方最新版本(走官方 API/下载源).
     *
     * @return 检查结果(本地版本、最新版本、是否可更新)
     * @throws IOException 官方版本信息获取失败
     */
    UpdateCheck checkUpdate() throws IOException;

    /**
     * 安装工具(从官方渠道下载并解压).
     * 默认解压到缓存目录;Windows 上的 ngrok 需要
     * {@code options.extractDir()}(缺失时抛 {@link ExtractDirRequiredException}).
     * 已安装且 force=false 时跳过.
     *
     * @param options 安装选项
     * @throws IOException 下载或解压失败
     */
    void install(InstallOptions options) throws IOException;

    /**
     * 兼容旧调用:按默认选项安装.
     */
    default void install() throws IOException {
        install(InstallOptions.DEFAULT);
    }

    /**
     * 更新到官方最新版(= 强制重新下载覆盖安装).
     *
     * @param options 安装选项
     * @throws IOException 下载或解压失败
     */
    default void update(InstallOptions options) throws IOException {
        install(new InstallOptions(options.extractDir(), true));
    }

    /**
     * 指定已有工具的位置(用户已自行安装的场景).
     * 传入可执行文件或其所在目录;验证后记录,
     * 之后该工具直接从此位置运行,不再走下载安装.
     *
     * @param path 用户指定的路径
     * @throws IOException 路径无效或找不到可执行文件
     */
    void setBinaryPath(Path path) throws IOException;

    /**
     * 配置工具(如 ngrok authtoken).
     *
     * @param config 配置键值对
     */
    void configure(Map<String, String> config);

    /**
     * 启动工具.
     *
     * @param args 启动参数(如 {"http", "25565"})
     * @return 启动后的运行信息
     * @throws IOException 进程启动失败
     */
    TunnelInfo start(String... args) throws IOException;

    /**
     * 停止工具.
     */
    void stop();

    /**
     * 后台守护进程是否在运行(如 tailscaled / Windows 的 Tailscale 服务).
     * 无独立守护进程概念的工具恒返回 false.
     */
    default boolean isDaemonRunning() {
        return false;
    }

    /**
     * 启动后台守护进程(如 Linux 的 tailscaled 系统服务).
     *
     * @throws IOException                 启动失败
     * @throws SudoPasswordRequiredException 非 root 且需要 sudo 密码
     */
    default void startDaemon() throws IOException {
        startDaemon(null);
    }

    /**
     * 启动后台守护进程.
     *
     * @param sudoPassword sudo 密码;仅非 root 时使用,经 stdin 传给 sudo,不记录不持久化;
     *                     null 表示未提供(可能抛 {@link SudoPasswordRequiredException})
     */
    default void startDaemon(char[] sudoPassword) throws IOException {
        throw new IOException(getType() + " 不支持独立守护进程控制");
    }

    /**
     * 停止后台守护进程.
     *
     * @throws IOException                 停止失败
     * @throws SudoPasswordRequiredException 非 root 且需要 sudo 密码
     */
    default void stopDaemon() throws IOException {
        stopDaemon(null);
    }

    /**
     * 停止后台守护进程.
     *
     * @param sudoPassword 同 {@link #startDaemon(char[])}
     */
    default void stopDaemon(char[] sudoPassword) throws IOException {
        throw new IOException(getType() + " 不支持独立守护进程控制");
    }

    /** 当前状态 */
    TunnelStatus getStatus();

    /** 当前运行信息快照 */
    TunnelInfo getInfo();
}
