package io.mctunnel.core.network;

import io.mctunnel.core.room.NetworkStatus;
import io.mctunnel.core.room.NetworkType;
import io.mctunnel.core.room.Room;

import java.io.IOException;

/**
 * 房间内网络抽象.
 * <p>
 * 包装底层隧道工具(ngrok/Tailscale),提供统一的网络生命周期接口.
 * 网络属于某个房间,由房主创建并作为服务端.
 * <p>
 * 实现:
 * <ul>
 *   <li>{@link NatTraversalNetwork} - 内网穿透(ngrok),房主设备对外暴露公网端点</li>
 *   <li>{@link VirtualNetwork} - 虚拟组网(Tailscale),成员加入后互通</li>
 * </ul>
 */
public interface Network {

    /** 网络类型 */
    NetworkType getType();

    /**
     * 在指定房间内创建网络(记录到房间网络列表,不自动启动).
     *
     * @param room 所属房间
     * @param name 网络名称
     * @return 网络数据快照
     */
    io.mctunnel.core.room.RoomNetwork create(Room room, String name);

    /**
     * 启动网络(启动底层工具).
     * 内网穿透:启动 ngrok 并暴露端点;
     * 虚拟组网:确认 Tailscale 已登录并加入 tailnet.
     */
    void start() throws IOException;

    /** 停止网络 */
    void stop();

    /** 当前状态 */
    NetworkStatus getStatus();

    /**
     * 对外端点(成员用来连接的地址).
     * 内网穿透:ngrok 公网 URL;
     * 虚拟组网:房主的 Tailscale 地址(100.x.x.x)或 tailnet 名称.
     */
    String getEndpoint();

    /** 网络 ID */
    String getNetworkId();
}
