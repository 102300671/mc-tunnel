package io.mctunnel.core.tunnel;

/**
 * 隧道工具运行状态.
 */
public enum TunnelStatus {
    /** 未安装 */
    NOT_INSTALLED,
    /** 已安装但未运行 */
    STOPPED,
    /** 正在运行 */
    RUNNING,
    /** 启动/运行出错 */
    ERROR
}
