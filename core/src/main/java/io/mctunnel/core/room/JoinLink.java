package io.mctunnel.core.room;

/**
 * 加入链接:解析后的结构化数据.
 *
 * @param relayHost 中继服务器主机
 * @param relayPort 中继服务器端口
 * @param roomId    房间 ID
 */
public record JoinLink(
        String relayHost,
        int relayPort,
        String roomId
) {
}
