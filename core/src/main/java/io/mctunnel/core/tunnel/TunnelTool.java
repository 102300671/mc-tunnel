package io.mctunnel.core.tunnel;

import java.io.IOException;
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
     * 安装工具(下载二进制并解压到缓存目录).
     * 若已安装则跳过.
     *
     * @throws IOException 下载或解压失败
     */
    void install() throws IOException;

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

    /** 当前状态 */
    TunnelStatus getStatus();

    /** 当前运行信息快照 */
    TunnelInfo getInfo();
}
