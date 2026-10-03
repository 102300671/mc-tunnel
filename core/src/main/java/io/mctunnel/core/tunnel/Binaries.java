package io.mctunnel.core.tunnel;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 已安装二进制的通用操作:版本探测等.
 */
public final class Binaries {

    private static final Pattern VERSION_PATTERN =
            Pattern.compile("\\d+\\.\\d+(?:\\.\\d+)?");

    private Binaries() {
    }

    /**
     * 解析用户指定的工具位置:可以是可执行文件本身,也可以是包含它的目录.
     *
     * @param userPath   用户指定的路径
     * @param binaryName 期望的可执行文件名(含平台扩展名,如 ngrok.exe)
     * @return 可执行文件完整路径
     * @throws IOException 找不到可执行文件
     */
    public static Path resolveExecutable(Path userPath, String binaryName) throws IOException {
        Path candidate = Files.isDirectory(userPath) ? userPath.resolve(binaryName) : userPath;
        if (!Files.isRegularFile(candidate)) {
            throw new IOException("找不到 " + binaryName + ": " + candidate);
        }
        // 确保可执行(Windows 上无作用)
        candidate.toFile().setExecutable(true, false);
        return candidate;
    }

    /**
     * 运行 {@code <bin> --version} 并解析出 x.y(.z) 版本号.
     *
     * @param bin 二进制完整路径
     * @return 版本号(如 "3.12.0");运行失败返回 null
     */
    public static String detectVersion(Path bin) {
        if (bin == null || !Files.isExecutable(bin)) {
            return null;
        }
        try {
            Process p = new ProcessBuilder(bin.toString(), "--version")
                    .redirectErrorStream(true).start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    out.append(line).append('\n');
                }
            }
            p.waitFor(10, TimeUnit.SECONDS);
            Matcher m = VERSION_PATTERN.matcher(out);
            return m.find() ? m.group() : null;
        } catch (IOException | InterruptedException e) {
            return null;
        }
    }
}
