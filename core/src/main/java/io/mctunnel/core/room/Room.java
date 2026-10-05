package io.mctunnel.core.room;

/**
 * 房间(数据快照).
 *
 * @param id         房间 ID
 * @param name       房间名称
 * @param hostNodeId 房主节点 ID
 * @param createdAt  创建时间戳(ms)
 */
public record Room(
        String id,
        String name,
        String hostNodeId,
        long createdAt
) {
}
