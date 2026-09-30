package io.mctunnel.core.tunnel;

import io.mctunnel.core.download.BinaryDownloader;
import io.mctunnel.core.download.Platform;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ngrok 适配器.
 * <p>
 * ngrok v3 命令行:
 * <ul>
 *   <li>{@code ngrok config add-authtoken <token>}</li>
 *   <li>{@code ngrok http <port>} / {@code ngrok tcp <port>}</li>
 * </ul>
 * 启动后 stdout 会输出公网地址,形如:
 * {@code Forwarding  https://abc.ngrok-free.app -> http://localhost:25565}
 */
public class NgrokAdapter implements TunnelTool {

    private static final Pattern URL_PATTERN =
            Pattern.compile("Forwarding\\s+(\\S+)\\s+->");

    private final BinaryDownloader downloader;
    private final Platform platform;

    private Path binaryPath;
    private Process process;
    private String publicUrl;
    private int localPort;
    private TunnelStatus status = TunnelStatus.NOT_INSTALLED;

    public NgrokAdapter() {
        this(new BinaryDownloader(), Platform.detect());
    }

    NgrokAdapter(BinaryDownloader downloader, Platform platform) {
        this.downloader = downloader;
        this.platform = platform;
        resolveBinaryPath();
        if (isInstalled()) {
            status = TunnelStatus.STOPPED;
        }
    }

    private void resolveBinaryPath() {
        String name = TunnelType.NGROK.getBinaryName() + platform.getExecutableSuffix();
        this.binaryPath = Paths.get(System.getProperty("user.home"),
                ".mctunnel", "binaries", "ngrok",
                platform.name().toLowerCase(), name);
    }

    @Override
    public TunnelType getType() {
        return TunnelType.NGROK;
    }

    @Override
    public boolean isInstalled() {
        return Files.isExecutable(binaryPath);
    }

    @Override
    public void install() throws IOException {
        if (isInstalled()) {
            return;
        }
        String url = getDownloadUrl();
        downloader.downloadAndExtract("ngrok", url, platform);
        if (!isInstalled()) {
            throw new IOException("ngrok binary not found after install at " + binaryPath);
        }
        status = TunnelStatus.STOPPED;
    }

    private String getDownloadUrl() throws IOException {
        // ngrok v3 稳定版下载地址
        return switch (platform) {
            case LINUX_X64 -> "https://bin.equinox.io/c/bNyj1mQVY4c/ngrok-v3-stable-linux-amd64.tgz";
            case LINUX_ARM64 -> "https://bin.equinox.io/c/bNyj1mQVY4c/ngrok-v3-stable-linux-arm64.tgz";
            case WINDOWS_X64 -> "https://bin.equinox.io/c/bNyj1mQVY4c/ngrok-v3-stable-windows-amd64.zip";
            case MACOS_X64 -> "https://bin.equinox.io/c/bNyj1mQVY4c/ngrok-v3-stable-darwin-amd64.tgz";
            case MACOS_ARM64 -> "https://bin.equinox.io/c/bNyj1mQVY4c/ngrok-v3-stable-darwin-arm64.tgz";
            case UNKNOWN -> throw new IOException("Unsupported platform for ngrok: " + platform);
        };
    }

    @Override
    public void configure(Map<String, String> config) {
        String token = config.get("authtoken");
        if (token == null || token.isBlank()) {
            return;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    binaryPath.toString(), "config", "add-authtoken", token);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            // 消耗输出
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                reader.lines().forEach(line -> { /* 丢弃 */ });
            }
            p.waitFor();
        } catch (IOException | InterruptedException e) {
            status = TunnelStatus.ERROR;
            throw new RuntimeException("Failed to configure ngrok authtoken", e);
        }
    }

    @Override
    public TunnelInfo start(String... args) throws IOException {
        if (!isInstalled()) {
            throw new IOException("ngrok not installed, call install() first");
        }
        if (process != null && process.isAlive()) {
            return getInfo();
        }

        // 解析本地端口
        localPort = parsePort(args);

        String[] cmd = new String[args.length + 1];
        cmd[0] = binaryPath.toString();
        System.arraycopy(args, 0, cmd, 1, args.length);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        process = pb.start();

        // 后台线程解析输出,提取公网 URL
        Thread parser = new Thread(() -> parseOutput(process), "ngrok-output-parser");
        parser.setDaemon(true);
        parser.start();

        // 等待 URL 出现或超时
        long deadline = System.currentTimeMillis() + 15_000;
        while (publicUrl == null && process.isAlive()
                && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        if (publicUrl != null) {
            status = TunnelStatus.RUNNING;
        } else if (!process.isAlive()) {
            status = TunnelStatus.ERROR;
        } else {
            status = TunnelStatus.RUNNING; // 进程在跑,只是还没解析到 URL
        }
        return getInfo();
    }

    private int parsePort(String[] args) {
        // args 形如 {"http", "25565"} 或 {"tcp", "25565"}
        for (int i = 0; i < args.length; i++) {
            try {
                return Integer.parseInt(args[i]);
            } catch (NumberFormatException ignored) {
                // 不是数字,继续找
            }
        }
        return 0;
    }

    private void parseOutput(Process proc) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher m = URL_PATTERN.matcher(line);
                if (m.find()) {
                    publicUrl = m.group(1);
                }
            }
        } catch (IOException ignored) {
            // 进程退出
        }
    }

    @Override
    public void stop() {
        if (process != null) {
            process.destroy();
            try {
                process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            process = null;
        }
        publicUrl = null;
        localPort = 0;
        status = TunnelStatus.STOPPED;
    }

    @Override
    public TunnelStatus getStatus() {
        if (status == TunnelStatus.RUNNING && process != null && !process.isAlive()) {
            status = TunnelStatus.STOPPED;
        }
        return status;
    }

    @Override
    public TunnelInfo getInfo() {
        return switch (getStatus()) {
            case NOT_INSTALLED -> TunnelInfo.notInstalled(TunnelType.NGROK);
            case STOPPED -> TunnelInfo.stopped(TunnelType.NGROK);
            case RUNNING -> TunnelInfo.running(
                    TunnelType.NGROK, localPort, publicUrl,
                    process != null ? process.pid() : -1);
            case ERROR -> TunnelInfo.error(TunnelType.NGROK, "ngrok process error");
        };
    }

    /** 获取二进制路径(供测试/CLI 调试用) */
    public Path getBinaryPath() {
        return binaryPath;
    }
}
