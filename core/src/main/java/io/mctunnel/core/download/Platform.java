package io.mctunnel.core.download;

import java.util.Locale;

/**
 * 运行平台.
 * <p>
 * 用于确定下载哪种架构的二进制.
 */
public enum Platform {
    WINDOWS_X64("windows", "amd64"),
    LINUX_X64("linux", "amd64"),
    LINUX_ARM64("linux", "arm64"),
    MACOS_X64("darwin", "amd64"),
    MACOS_ARM64("darwin", "arm64"),
    /** 未知平台 */
    UNKNOWN("unknown", "unknown");

    private final String osName;
    private final String arch;

    Platform(String osName, String arch) {
        this.osName = osName;
        this.arch = arch;
    }

    public String getOsName() {
        return osName;
    }

    public String getArch() {
        return arch;
    }

    /** 二进制可执行文件扩展名 */
    public String getExecutableSuffix() {
        return this == WINDOWS_X64 ? ".exe" : "";
    }

    /** 根据当前 JVM 检测平台 */
    public static Platform detect() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);

        boolean isWindows = os.contains("win");
        boolean isMac = os.contains("mac") || os.contains("darwin");
        boolean isLinux = os.contains("linux") || os.contains("nix") || os.contains("nux");

        boolean isArm64 = arch.contains("aarch64") || arch.contains("arm64");
        boolean isX64 = arch.contains("amd64") || arch.contains("x86_64");

        if (isWindows && isX64) return WINDOWS_X64;
        if (isLinux && isArm64) return LINUX_ARM64;
        if (isLinux && isX64) return LINUX_X64;
        if (isMac && isArm64) return MACOS_ARM64;
        if (isMac && isX64) return MACOS_X64;
        return UNKNOWN;
    }
}
