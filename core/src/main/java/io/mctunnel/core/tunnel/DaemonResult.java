package io.mctunnel.core.tunnel;

/**
 * 守护进程启停操作结果.
 *
 * @param info           操作后的工具状态;需要密码时为 null
 * @param needsSudoPassword 非 root 且 sudo 需要密码,调用方安全收集密码后应重试
 */
public record DaemonResult(TunnelInfo info, boolean needsSudoPassword) {

    public static DaemonResult ok(TunnelInfo info) {
        return new DaemonResult(info, false);
    }

    public static DaemonResult needPassword() {
        return new DaemonResult(null, true);
    }
}
