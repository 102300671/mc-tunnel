package io.mctunnel.core.network;

import io.mctunnel.core.room.NetworkStatus;
import io.mctunnel.core.room.NetworkType;
import io.mctunnel.core.room.Room;
import io.mctunnel.core.room.RoomNetwork;
import io.mctunnel.core.tunnel.NgrokAdapter;
import io.mctunnel.core.tunnel.TunnelInfo;

import java.io.IOException;
import java.util.UUID;

/**
 * 内网穿透网络:包装 {@link NgrokAdapter}.
 * <p>
 * 房主设备运行 ngrok,对外暴露公网 URL 作为端点;
 * 成员通过该 URL 连接房主的 Minecraft 服务.
 */
public class NatTraversalNetwork implements Network {

    private final NgrokAdapter adapter;
    private String networkId;
    private String roomId;
    private String name;
    private String endpoint;
    /** 默认映射的本地 Minecraft 端口 */
    private int localPort = 25565;

    public NatTraversalNetwork(NgrokAdapter adapter) {
        this.adapter = adapter;
    }

    public void setLocalPort(int port) {
        this.localPort = port;
    }

    @Override
    public NetworkType getType() {
        return NetworkType.NAT_TRAVERSAL;
    }

    @Override
    public RoomNetwork create(Room room, String name) {
        this.networkId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        this.roomId = room.id();
        this.name = name;
        return new RoomNetwork(networkId, roomId, NetworkType.NAT_TRAVERSAL,
                NetworkStatus.CREATED, null, room.hostNodeId(), System.currentTimeMillis());
    }

    @Override
    public void start() throws IOException {
        // 启动 ngrok http 隧道,映射本地 Minecraft 端口
        TunnelInfo info = adapter.start("http", String.valueOf(localPort));
        if (info.publicUrl() != null) {
            this.endpoint = info.publicUrl();
        }
    }

    @Override
    public void stop() {
        adapter.stop();
        this.endpoint = null;
    }

    @Override
    public NetworkStatus getStatus() {
        return switch (adapter.getStatus()) {
            case RUNNING -> NetworkStatus.ACTIVE;
            case STOPPED -> NetworkStatus.STOPPED;
            case ERROR -> NetworkStatus.ERROR;
            default -> NetworkStatus.CREATED;
        };
    }

    @Override
    public String getEndpoint() {
        return endpoint;
    }

    @Override
    public String getNetworkId() {
        return networkId;
    }
}
