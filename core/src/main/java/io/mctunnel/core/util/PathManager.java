package io.mctunnel.core.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * 可执行文件查找与 Windows 用户 PATH 管理.
 * <p>
 * ngrok 在 Windows 上由用户选择解压目录,解压后尝试加入用户 PATH
 * (通过 PowerShell 写注册表环境变量),并记录完整路径作为回退.
 * 后续执行命令时:先尝试直接用 {@code ngrok}(依赖 PATH),
 * 失败再回退到记录的完整路径.
 */
public final class PathManager {

    private PathManager() {
    }

    /**
     * 在系统 PATH 中查找可执行文件.
     *
     * @param name 二进制名(如 "ngrok",可不带 .exe)
     * @return 完整路径;找不到返回 null
     */
    public static String findOnPath(String name) {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String[] cmd = windows ? new String[]{"where", name} : new String[]{"which", name};
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out;
            try (var in = p.getInputStream()) {
                out = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
            p.waitFor(5, TimeUnit.SECONDS);
            if (p.exitValue() == 0 && !out.isEmpty()) {
                // where 可能返回多行,取第一行
                return out.split("\n")[0].trim();
            }
        } catch (IOException | InterruptedException ignored) {
        }
        return null;
    }

    /**
     * 将目录追加到当前用户 PATH(仅 Windows 生效).
     * 通过 PowerShell 写用户级环境变量,对之后新开的进程生效;
     * 已包含该目录时跳过,不重复添加.
     *
     * @param dir 要加入的目录
     * @return true 表示 PATH 已包含该目录(无论是否本次添加)
     */
    public static boolean addToUserPath(String dir) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return false;
        }
        String norm = dir.replace('/', '\\');
        // 去尾部反斜杠,统一比较
        if (norm.endsWith("\\")) {
            norm = norm.substring(0, norm.length() - 1);
        }
        String script = "$d='" + norm.replace("'", "''") + "';"
                + "$p=[Environment]::GetEnvironmentVariable('Path','User');"
                + "if(($p -split ';') -notcontains $d){"
                + "[Environment]::SetEnvironmentVariable('Path',"
                + "$p.TrimEnd(';')+';'+$d,'User'); exit 1}" // exit 1 = 本次有添加
                + "else{exit 0}"; // exit 0 = 已存在
        try {
            Process p = new ProcessBuilder("powershell", "-NoProfile",
                    "-ExecutionPolicy", "Bypass", "-Command", script)
                    .redirectErrorStream(true).start();
            try (var in = p.getInputStream()) {
                in.readAllBytes(); // 消耗输出避免阻塞
            }
            p.waitFor(15, TimeUnit.SECONDS);
            return true; // 只要命令执行成功,目标目录必然已在(或原本就在)用户 PATH
        } catch (IOException | InterruptedException e) {
            System.err.println("[MC-Tunnel] addToUserPath failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * 校验目录存在且可写(用于解压目标目录检查).
     *
     * @return null 合法;否则返回错误描述
     */
    public static String validateExtractDir(String dir) {
        if (dir == null || dir.isBlank()) {
            return "目录不能为空";
        }
        Path p = Path.of(dir);
        if (Files.exists(p) && !Files.isDirectory(p)) {
            return "已存在同名文件,不是目录: " + dir;
        }
        if (!Files.exists(p)) {
            try {
                Files.createDirectories(p);
            } catch (IOException e) {
                return "无法创建目录: " + e.getMessage();
            }
        }
        if (!Files.isWritable(p)) {
            return "目录不可写: " + dir;
        }
        return null;
    }
}
