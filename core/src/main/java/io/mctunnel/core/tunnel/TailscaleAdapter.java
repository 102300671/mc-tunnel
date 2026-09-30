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
import java.util.concurrent.TimeUnit;

/**
 * Tailscale 适配器.
 * <p>
 * 二进制:
 * <ul>
 *   <li>Linux: 下载 tgz,解压出 {@code tailscale} + {@code tailscaled}</li>
 *   <li>Windows: 下载 MSI 静默安装,二进制在 {@code C:\Program Files\Tailscale\}</li>
 * </ul>
 * 命令:
 * <ul>
 *   <li>{@code tailscale up --authkey=<key>}</li>
 *   <li>{@code tailscale status --json}</li>
 *   <li>{@code tailscale down}</li>
 * </ul>
 */
public class TailscaleAdapter implements TunnelTool {

    /** Tailscale 稳定版本号 */
    private static final String VERSION = "1.102.4";

    private final BinaryDownloader downloader;
    private final Platform platform;

    private Path tailscaleBin;
    private Path tailscaledBin;
    private Process daemonProcess;
    private String authKey;
    private TunnelStatus status = TunnelStatus.NOT_INSTALLED;

    public TailscaleAdapter() {
        this(new BinaryDownloader(), Platform.detect());
    }

    TailscaleAdapter(BinaryDownloader downloader, Platform platform) {
        this.downloader = downloader;
        this.platform = platform;
        resolveBinaryPaths();
        if (isInstalled()) {
            status = TunnelStatus.STOPPED;
        }
    }

    private void resolveBinaryPaths() {
        String suffix = platform.getExecutableSuffix();
        if (platform == Platform.WINDOWS_X64) {
            // Windows 安装后二进制在 Program Files
            Path installDir = Paths.get("C:", "Program Files", "Tailscale");
            this.tailscaleBin = installDir.resolve("tailscale.exe");
            this.tailscaledBin = installDir.resolve("tailscaled.exe");
        } else {
            // Linux/Mac 解压到缓存目录
            Path dir = Paths.get(System.getProperty("user.home"),
                    ".mctunnel", "binaries", "tailscale",
                    platform.name().toLowerCase(),
                    "tailscale_" + VERSION + "_" + getArchForUrl());
            this.tailscaleBin = dir.resolve("tailscale" + suffix);
            this.tailscaledBin = dir.resolve("tailscaled" + suffix);
        }
    }

    private String getArchForUrl() {
        return switch (platform) {
            case LINUX_X64, MACOS_X64, WINDOWS_X64 -> "amd64";
            case LINUX_ARM64, MACOS_ARM64 -> "arm64";
            default -> "unknown";
        };
    }

    @Override
    public TunnelType getType() {
        return TunnelType.TAILSCALE;
    }

    @Override
    public boolean isInstalled() {
        return Files.isExecutable(tailscaleBin);
    }

    @Override
    public void install() throws IOException {
        if (isInstalled()) {
            return;
        }
        String url = getDownloadUrl();
        if (platform == Platform.WINDOWS_X64) {
            installWindows(url);
        } else {
            Path dir = downloader.downloadAndExtract("tailscale", url, platform);
            // tgz 解压后可能在子目录 tailscale_<version>_<arch>/
            // downloadAndExtract 已解压到 toolDir,这里直接用 resolveBinaryPaths 的路径
            // 重新解析路径指向解压后的实际位置
            resolveBinaryPathsAfterExtract(dir);
        }
        if (!isInstalled()) {
            throw new IOException("tailscale binary not found after install at " + tailscaleBin);
        }
        // 设置可执行权限(Linux/Mac)
        if (platform != Platform.WINDOWS_X64) {
            tailscaleBin.toFile().setExecutable(true, false);
            if (Files.exists(tailscaledBin)) {
                tailscaledBin.toFile().setExecutable(true, false);
            }
        }
        status = TunnelStatus.STOPPED;
    }

    private void resolveBinaryPathsAfterExtract(Path toolDir) {
        String suffix = platform.getExecutableSuffix();
        // tgz 解压后文件可能直接在 toolDir 或子目录
        Path direct = toolDir.resolve("tailscale" + suffix);
        if (Files.exists(direct)) {
            this.tailscaleBin = direct;
            this.tailscaledBin = toolDir.resolve("tailscaled" + suffix);
        } else {
            // 找子目录
            try (var stream = Files.list(toolDir)) {
                Path subdir = stream.filter(Files::isDirectory).findFirst().orElse(toolDir);
                this.tailscaleBin = subdir.resolve("tailscale" + suffix);
                this.tailscaledBin = subdir.resolve("tailscaled" + suffix);
            } catch (IOException ignored) {
                // 保持原路径
            }
        }
    }

    private String getDownloadUrl() throws IOException {
        String arch = getArchForUrl();
        if (platform == Platform.WINDOWS_X64) {
            return "https://pkgs.tailscale.com/stable/tailscale-setup-" + VERSION + "-amd64.msi";
        }
        // Linux/Mac 用 tgz
        return "https://pkgs.tailscale.com/stable/tailscale_" + VERSION + "_" + arch + ".tgz";
    }

    private void installWindows(String url) throws IOException {
        // 下载 MSI 到临时目录
        Path tmpDir = Files.createTempDirectory("mctunnel-tailscale");
        Path msi = tmpDir.resolve("tailscale-setup.msi");

        // 复用 downloader 的下载逻辑:用临时 URL  trick
        // downloader 会按 url 文件名保存,所以传一个以 .msi 结尾的 url 即可
        downloader.downloadAndExtract("tailscale-installer", url, platform);

        // downloader 把 MSI 放在缓存目录,需要找到它
        Path cacheDir = Paths.get(System.getProperty("user.home"),
                ".mctunnel", "binaries", "tailscale-installer",
                platform.name().toLowerCase());
        Path msiFile = cacheDir.resolve("tailscale-setup-" + VERSION + "-amd64.msi");
        if (!Files.exists(msiFile)) {
            // 尝试找目录下的 msi
            try (var stream = Files.list(cacheDir)) {
                msiFile = stream.filter(p -> p.toString().endsWith(".msi"))
                        .findFirst().orElseThrow(() ->
                                new IOException("MSI not found in " + cacheDir));
            }
        }

        // 静默安装 MSI
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "msiexec", "/i", msiFile.toString(), "/qn", "/norestart");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                r.lines().forEach(l -> { /* 丢弃 */ });
            }
            int code = p.waitFor();
            if (code != 0) {
                throw new IOException("MSI install failed with code " + code);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("MSI install interrupted", e);
        }
    }

    @Override
    public void configure(Map<String, String> config) {
        String key = config.get("authkey");
        if (key != null && !key.isBlank()) {
            this.authKey = key;
        }
    }

    @Override
    public TunnelInfo start(String... args) throws IOException {
        if (!isInstalled()) {
            throw new IOException("tailscale not installed, call install() first");
        }

        // 1. 启动守护进程(Windows 上是系统服务,Linux 上是 tailscaled)
        if (platform != Platform.WINDOWS_X64) {
            startDaemonLinux();
        }
        // Windows 上 Tailscale 服务通常安装后自动运行,这里假设已运行

        // 2. tailscale up
        String[] cmd;
        if (authKey != null && !authKey.isBlank()) {
            cmd = new String[]{tailscaleBin.toString(), "up", "--authkey=" + authKey, "--accept-routes"};
        } else {
            cmd = new String[]{tailscaleBin.toString(), "up", "--accept-routes"};
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    out.append(line).append("\n");
                }
            }
            boolean finished = p.waitFor(30, TimeUnit.SECONDS);
            if (finished && p.exitValue() == 0) {
                // tailscale up 成功(或已登录)
                status = TunnelStatus.RUNNING;
            } else {
                // 可能需要交互式登录或超时,不算完全失败
                status = TunnelStatus.RUNNING;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            status = TunnelStatus.ERROR;
        }
        return getInfo();
    }

    private void startDaemonLinux() throws IOException {
        if (daemonProcess != null && daemonProcess.isAlive()) {
            return;
        }
        if (!Files.isExecutable(tailscaledBin)) {
            // Windows 或无守护进程,跳过
            return;
        }
        // 需要 root 权限;若无权限则尝试用 sudo
        ProcessBuilder pb = new ProcessBuilder(
                tailscaledBin.toString(), "--socket=/tmp/mctunnel-tailscaled.sock");
        pb.redirectErrorStream(true);
        try {
            daemonProcess = pb.start();
        } catch (IOException e) {
            // 可能权限不足,记录但不中断
            System.err.println("[Tailscale] Failed to start tailscaled: " + e.getMessage());
        }
    }

    @Override
    public void stop() {
        // tailscale down
        try {
            ProcessBuilder pb = new ProcessBuilder(tailscaleBin.toString(), "down");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (var ignored = p.getInputStream()) {
                ignored.readAllBytes();
            }
            p.waitFor(10, TimeUnit.SECONDS);
        } catch (IOException | InterruptedException ignored) {
        }

        // 停止守护进程
        if (daemonProcess != null) {
            daemonProcess.destroy();
            try {
                daemonProcess.waitFor(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (daemonProcess.isAlive()) {
                daemonProcess.destroyForcibly();
            }
            daemonProcess = null;
        }
        status = TunnelStatus.STOPPED;
    }

    @Override
    public TunnelStatus getStatus() {
        if (status == TunnelStatus.RUNNING) {
            // 检查 tailscale 是否真的在线
            try {
                String json = runStatusJson();
                if (json != null && json.contains("\"Online\":true")) {
                    status = TunnelStatus.RUNNING;
                } else {
                    status = TunnelStatus.STOPPED;
                }
            } catch (IOException ignored) {
                status = TunnelStatus.ERROR;
            }
        }
        return status;
    }

    private String runStatusJson() throws IOException {
        ProcessBuilder pb = new ProcessBuilder(
                tailscaleBin.toString(), "status", "--json");
        pb.redirectErrorStream(false);
        try {
            Process p = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    out.append(line);
                }
            }
            p.waitFor(10, TimeUnit.SECONDS);
            return out.toString();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    @Override
    public TunnelInfo getInfo() {
        TunnelStatus s = getStatus();
        return switch (s) {
            case NOT_INSTALLED -> TunnelInfo.notInstalled(TunnelType.TAILSCALE);
            case STOPPED -> TunnelInfo.stopped(TunnelType.TAILSCALE);
            case RUNNING -> TunnelInfo.running(
                    TunnelType.TAILSCALE, 0, getTailscaleIp(),
                    daemonProcess != null ? daemonProcess.pid() : -1);
            case ERROR -> TunnelInfo.error(TunnelType.TAILSCALE, "tailscale error");
        };
    }

    private String getTailscaleIp() {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    tailscaleBin.toString(), "ip", "-4");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String ip = r.readLine();
                p.waitFor(5, TimeUnit.SECONDS);
                return ip;
            }
        } catch (IOException | InterruptedException ignored) {
            return null;
        }
    }

    /** 获取二进制路径(供调试) */
    public Path getTailscaleBin() {
        return tailscaleBin;
    }

    public Path getTailscaledBin() {
        return tailscaledBin;
    }
}
