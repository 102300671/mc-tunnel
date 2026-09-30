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
        String error
) {
    public static TunnelInfo notInstalled(TunnelType type) {
        return new TunnelInfo(type, TunnelStatus.NOT_INSTALLED, 0, null, -1, null);
    }

    public static TunnelInfo stopped(TunnelType type) {
        return new TunnelInfo(type, TunnelStatus.STOPPED, 0, null, -1, null);
    }

    public static TunnelInfo running(TunnelType type, int localPort, String publicUrl, long pid) {
        return new TunnelInfo(type, TunnelStatus.RUNNING, localPort, publicUrl, pid, null);
    }

    public static TunnelInfo error(TunnelType type, String error) {
        return new TunnelInfo(type, TunnelStatus.ERROR, 0, null, -1, error);
    }
}
