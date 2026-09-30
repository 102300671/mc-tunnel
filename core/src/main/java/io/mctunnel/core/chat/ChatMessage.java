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
 */
public record ChatMessage(
        String id,
        String nodeId,
        String sender,
        String content,
        long timestamp
) {
    public static ChatMessage create(String nodeId, String sender, String content) {
        return new ChatMessage(
                UUID.randomUUID().toString(),
                nodeId,
                sender,
                content,
                System.currentTimeMillis()
        );
    }
}
