package io.mctunnel.core.tunnel;

/**
 * 受支持的隧道/组网工具类型.
 */
public enum TunnelType {
    /** 内网穿透 */
    NGROK("ngrok", "内网穿透"),
    /** 虚拟组网 */
    TAILSCALE("tailscale", "虚拟组网"),
    /** P2P 文件同步(用于存档) */
    SYNCTHING("syncthing", "存档同步");

    private final String binaryName;
    private final String displayName;

    TunnelType(String binaryName, String displayName) {
        this.binaryName = binaryName;
        this.displayName = displayName;
    }

    /** 二进制可执行文件名(不含扩展名) */
    public String getBinaryName() {
        return binaryName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
