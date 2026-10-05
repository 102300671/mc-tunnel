package io.mctunnel.core.room;

/**
 * 房间内网络的类型.
 * <p>
 * 仅包含两类网络语义,不含存档同步(SyncThing).
 */
public enum NetworkType {
    /** 内网穿透(如 ngrok):房主设备作为服务端,对外暴露公网端点 */
    NAT_TRAVERSAL("内网穿透"),
    /** 虚拟组网(如 Tailscale):成员加入同一虚拟网络后互通,房主为逻辑服务端 */
    VIRTUAL("虚拟组网");

    private final String displayName;

    NetworkType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
