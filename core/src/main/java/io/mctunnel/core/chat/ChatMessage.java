package io.mctunnel.core.chat;

import java.util.UUID;

/**
 * 聊天消息.
 *
 * @param id        消息唯一 ID
 * @param nodeId    发送节点 ID
 * @param sender    发送者名称
 * @param content   消息内容
 * @param timestamp 发送时间戳(ms)
 * @param roomId    所属房间 ID(空字符串表示全局/未分组)
 */
public record ChatMessage(
        String id,
        String nodeId,
        String sender,
        String content,
        long timestamp,
        String roomId
) {
    public static ChatMessage create(String nodeId, String sender, String content) {
        return create(nodeId, sender, content, "");
    }

    public static ChatMessage create(String nodeId, String sender, String content, String roomId) {
        return new ChatMessage(
                UUID.randomUUID().toString(),
                nodeId,
                sender,
                content,
                System.currentTimeMillis(),
                roomId == null ? "" : roomId
        );
    }
}
