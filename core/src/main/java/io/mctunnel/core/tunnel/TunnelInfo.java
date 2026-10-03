package io.mctunnel.core.tunnel;

/**
 * 隧道运行信息快照.
 */
public record TunnelInfo(
        TunnelType type,
        TunnelStatus status,
        /** 监听的本地端口 */
        int localPort,
        /** 隧道公网地址(ngrok 形如 https://xxx.ngrok-free.app) */
        String publicUrl,
        /** 进程 PID,-1 表示无进程 */
        long pid,
        /** 错误信息(仅 status=ERROR 时有值) */
        String error,
        /** 本地已装版本;null 表示未安装或未知 */
        String localVersion,
        /** 官方最新版本(来自最近一次检查更新);null 表示未知 */
        String latestVersion,
        /** 是否有可用更新 */
        boolean updateAvailable,
        /** 是否需要配置账户(ngrok: 未配置 authtoken) */
        boolean needsAccount,
        /** 后台守护进程是否在运行(tailscale: tailscaled/Windows 服务;其他工具恒 false) */
        boolean daemonRunning
) {
    public static TunnelInfo notInstalled(TunnelType type) {
        return new TunnelInfo(type, TunnelStatus.NOT_INSTALLED, 0, null, -1, null,
                null, null, false, false, false);
    }

    public static TunnelInfo stopped(TunnelType type) {
        return new TunnelInfo(type, TunnelStatus.STOPPED, 0, null, -1, null,
                null, null, false, false, false);
    }

    public static TunnelInfo running(TunnelType type, int localPort, String publicUrl, long pid) {
        return new TunnelInfo(type, TunnelStatus.RUNNING, localPort, publicUrl, pid, null,
                null, null, false, false, false);
    }

    public static TunnelInfo error(TunnelType type, String error) {
        return new TunnelInfo(type, TunnelStatus.ERROR, 0, null, -1, error,
                null, null, false, false, false);
    }

    /**
     * 附加版本信息,生成新快照(原快照不变).
     *
     * @param localVersion  本地版本(可为 null)
     * @param latestVersion 最新版本(可为 null)
     * @param needsAccount  是否需要配置账户
     */
    public TunnelInfo withVersionInfo(String localVersion, String latestVersion, boolean needsAccount) {
        boolean update = localVersion != null && latestVersion != null
                && io.mctunnel.core.util.VersionCompare.isNewer(latestVersion, localVersion);
        return new TunnelInfo(type, status, localPort, publicUrl, pid, error,
                localVersion, latestVersion, update, needsAccount, daemonRunning);
    }

    /** 附加守护进程运行状态,生成新快照(原快照不变) */
    public TunnelInfo withDaemonRunning(boolean running) {
        return new TunnelInfo(type, status, localPort, publicUrl, pid, error,
                localVersion, latestVersion, updateAvailable, needsAccount, running);
    }
}
