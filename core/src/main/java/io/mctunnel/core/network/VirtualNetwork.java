package io.mctunnel.core.network;

import io.mctunnel.core.room.NetworkStatus;
import io.mctunnel.core.room.NetworkType;
import io.mctunnel.core.room.Room;
import io.mctunnel.core.room.RoomNetwork;
import io.mctunnel.core.tunnel.TailscaleAdapter;

import java.io.IOException;
import java.util.UUID;

/**
 * 虚拟组网网络:包装 {@link TailscaleAdapter}.
 * <p>
 * 房主设备作为逻辑服务端;成员加入同一 Tailscale tailnet 后,
 * 网络层互通,成员直接用房主的 100.x.x.x 地址连接.
 */
public class VirtualNetwork implements Network {

    private final TailscaleAdapter adapter;
    private String networkId;
    private String roomId;
    private String name;
    private String endpoint;

    public VirtualNetwork(TailscaleAdapter adapter) {
        this.adapter = adapter;
    }

    @Override
    public NetworkType getType() {
        return NetworkType.VIRTUAL;
    }

    @Override
    public RoomNetwork create(Room room, String name) {
        this.networkId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        this.roomId = room.id();
        this.name = name;
        return new RoomNetwork(networkId, roomId, NetworkType.VIRTUAL,
                NetworkStatus.CREATED, null, room.hostNodeId(), System.currentTimeMillis());
    }

    @Override
    public void start() throws IOException {
        // 虚拟组网:确保 tailscaled 运行且已登录
        // Tailscale 的 start 主要确认状态;成员通过 tailscale login 加入同一 tailnet
        try {
            adapter.start();
        } catch (IOException e) {
            // start 可能因 tailscale 已运行而抛异常,忽略
        }
        // 尝试获取本机 Tailscale 地址作为端点
        this.endpoint = detectTailscaleIp();
    }

    @Override
    public void stop() {
        // 虚拟组网不主动停止 tailscale(可能影响其他应用),仅标记
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

    /** 探测本机 Tailscale IP(100.x.x.x),失败返回 null */
    private String detectTailscaleIp() {
        try {
            var info = adapter.getInfo();
            // TailscaleAdapter 的 publicUrl 字段可能承载 tailscale 地址或状态
            if (info != null && info.publicUrl() != null) {
                return info.publicUrl();
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
