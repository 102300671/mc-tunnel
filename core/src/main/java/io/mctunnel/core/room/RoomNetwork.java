package io.mctunnel.core.room;

/**
 * 房间内注册的网络(数据快照).
 * <p>
 * 这是网络在房间层面的数据描述;实际运行由 {@code io.mctunnel.core.network.Network}
 * 行为接口驱动.
 *
 * @param id         网络 ID
 * @param roomId     所属房间 ID
 * @param type       网络类型
 * @param status     运行状态
 * @param endpoint   对外端点(ngrok 公网 URL / Tailscale tailnet 名称或节点地址)
 * @param hostNodeId 网络主机(房主)节点 ID
 * @param createdAt  创建时间戳(ms)
 */
public record RoomNetwork(
        String id,
        String roomId,
        NetworkType type,
        NetworkStatus status,
        String endpoint,
        String hostNodeId,
        long createdAt
) {
}
