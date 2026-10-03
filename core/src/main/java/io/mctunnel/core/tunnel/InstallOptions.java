package io.mctunnel.core.tunnel;

import java.nio.file.Path;

/**
 * 安装选项.
 *
 * @param extractDir 解压目标目录;null 表示用默认缓存目录.
 *                   Windows 上的 ngrok 必须由用户指定(安装流程会询问).
 * @param force      强制重新下载覆盖(更新场景)
 */
public record InstallOptions(String extractDir, boolean force) {

    public static final InstallOptions DEFAULT = new InstallOptions(null, false);

    public static InstallOptions withExtractDir(String dir) {
        return new InstallOptions(dir, false);
    }

    public static InstallOptions forceUpdate() {
        return new InstallOptions(null, true);
    }

    public static InstallOptions forceUpdateWithExtractDir(String dir) {
        return new InstallOptions(dir, true);
    }

    /** 解压目录(Path 形式,可为 null) */
    public Path extractDirPath() {
        return extractDir == null || extractDir.isBlank() ? null : Path.of(extractDir);
    }
}
