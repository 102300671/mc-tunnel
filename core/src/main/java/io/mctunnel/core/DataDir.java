package io.mctunnel.core;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 数据目录持有者.
 * <p>
 * 所有运行时文件(数据库、二进制缓存等)的根目录由此类决定.
 * 宿主(Forge 模组或独立 CLI)在启动早期通过 {@link #set(Path)} 设置,
 * 未设置时默认为 {@code ~/.mctunnel/}.
 */
public final class DataDir {

    private static volatile Path root = Paths.get(
            System.getProperty("user.home"), ".mctunnel");

    private DataDir() {
    }

    /** 数据根目录 */
    public static Path get() {
        return root;
    }

    /** binaries 子目录 */
    public static Path binaries() {
        return root.resolve("binaries");
    }

    /** 数据库文件路径 */
    public static Path dbFile() {
        return root.resolve("mctunnel.db");
    }

    /**
     * 设置数据根目录(由宿主调用).
     *
     * @param dir 新的数据根目录
     */
    public static void set(Path dir) {
        root = dir;
    }
}
