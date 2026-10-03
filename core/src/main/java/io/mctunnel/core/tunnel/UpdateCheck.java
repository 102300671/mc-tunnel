package io.mctunnel.core.tunnel;

/**
 * 更新检查结果.
 *
 * @param type            工具类型
 * @param localVersion    本地已装版本;null 表示未安装
 * @param latestVersion   官方最新版本;null 表示获取失败
 * @param installed       本地是否已安装
 * @param updateAvailable 是否有可用更新
 */
public record UpdateCheck(
        TunnelType type,
        String localVersion,
        String latestVersion,
        boolean installed,
        boolean updateAvailable
) {
    public static UpdateCheck of(TunnelType type, String local, String latest) {
        boolean installed = local != null;
        boolean update = installed && latest != null
                && io.mctunnel.core.util.VersionCompare.isNewer(latest, local);
        return new UpdateCheck(type, local, latest, installed, update);
    }
}
