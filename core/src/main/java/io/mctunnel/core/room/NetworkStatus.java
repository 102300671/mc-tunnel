package io.mctunnel.core.room;

/**
 * 房间内网络的运行状态.
 */
public enum NetworkStatus {
    /** 已创建但未启动 */
    CREATED,
    /** 运行中(内网穿透已暴露端点 / 虚拟组网已就绪) */
    ACTIVE,
    /** 已停止 */
    STOPPED,
    /** 出错 */
    ERROR
}
