package io.mctunnel.core;

/**
 * 核心层入口.
 * <p>
 * 该类被 Forge 模组和独立 jar 共用.
 * 严禁引用任何 Forge 或 Minecraft 类.
 */
public final class TunnelCore {

    public static final String NAME = "MC-Tunnel";
    public static final String VERSION = "1.0.0-SNAPSHOT";

    private TunnelCore() {
        // 工具类,不实例化
    }

    /**
     * 给上层(模组/独立入口)调用的问候方法.
     * 用于验证 core 层独立可用.
     *
     * @return 状态字符串
     */
    public static String greet() {
        return NAME + " v" + VERSION + " core layer online";
    }
}
