package io.mctunnel.core;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 数据目录持有者.
 * <p>
 * 所有运行时文件(数据库、二进制缓存等)的根目录由此类决定.
 * <b>必须由宿主在启动早期显式调用 {@link #set(Path)}</b>:
 * <ul>
 *   <li>Forge 模组: {@code FMLPaths.GAMEDIR.get().resolve("mctunnel")}</li>
 *   <li>独立 CLI: {@code Main#resolveGameDir()} 解析出的 {@code {gameDir}/mctunnel}</li>
 * </ul>
 * 未设置即读取会抛 {@link IllegalStateException} —— 本类不再提供 {@code ~/.mctunnel}
 * 这类隐式兜底:静默落到家目录会让多实例各自的数据混在同一处,且很难察觉.
 */
public final class DataDir {

    /** 宿主未调用 set() 前为 null，此时读取 get() 会抛 IllegalStateException */
    private static volatile Path root;

    private DataDir() {
    }

    /**
     * 数据根目录.
     *
     * @throws IllegalStateException 宿主未调用 {@link #set(Path)} 时
     */
    public static Path get() {
        Path r = root;
        if (r == null) {
            throw new IllegalStateException(
                    "DataDir 尚未初始化：必须由宿主在启动早期调用 DataDir.set(...)。"
                  + " 模组侧见 MCTunnelMod，独立运行请用 java -jar mctunnel.jar [--game-dir <目录>] <子命令>。");
        }
        return r;
    }

    /** binaries 子目录 */
    public static Path binaries() {
        return get().resolve("binaries");
    }

    /** 数据库文件路径 */
    public static Path dbFile() {
        return get().resolve("mctunnel.db");
    }

    /**
     * 设置数据根目录(由宿主调用).
     *
     * @param dir 新的数据根目录，不得为 null
     */
    public static void set(Path dir) {
        if (dir == null) {
            throw new IllegalArgumentException("DataDir.set 不接受 null");
        }
        root = dir.toAbsolutePath().normalize();
    }

    /** 是否已由宿主设置 */
    public static boolean isInitialized() {
        return root != null;
    }

    /** 家目录（仅供宿主做回退推算使用，不作为数据目录默认值） */
    public static Path home() {
        return Paths.get(System.getProperty("user.home"));
    }
}
