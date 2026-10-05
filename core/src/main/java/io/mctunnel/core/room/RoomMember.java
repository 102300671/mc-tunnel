package io.mctunnel.core.room;

/**
 * 房间成员信息.
 *
 * @param nodeId      成员节点 ID
 * @param displayName 显示名称
 * @param joinedAt    加入时间戳(ms)
 */
public record RoomMember(
        String nodeId,
        String displayName,
        long joinedAt
) {
}
