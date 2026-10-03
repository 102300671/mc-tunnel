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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tailscale 适配器.
 * <p>
 * 二进制:
 * <ul>
 *   <li>Linux: 下载 tgz,解压出 {@code tailscale} + {@code tailscaled}</li>
 *   <li>Windows: 官方 exe 安装包(tailscale-setup-latest.exe)由用户按向导安装,
 *       默认路径 {@code C:\Program Files\Tailscale},安装包自注册 PATH
 *       并注册系统服务;未运行时优先启动 {@code tailscale-ipn.exe}(GUI 客户端);
 *       命令统一先试 PATH 中的 {@code tailscale},失败回退完整路径</li>
 * </ul>
 * 命令:
 * <ul>
 *   <li>{@code tailscale login} — 交互登录(打开浏览器授权)</li>
 *   <li>{@code tailscale up --authkey=<key>}</li>
 *   <li>{@code tailscale status --json}</li>
 *   <li>{@code tailscale down}</li>
 * </ul>
 */
public class TailscaleAdapter implements TunnelTool {

    /** 版本检查失败时的回退稳定版本号 */
    private static final String FALLBACK_VERSION = "1.102.4";

    /** Windows 官方 exe 安装包直链(永远指向最新版) */
    private static final String WINDOWS_SETUP_EXE_URL =
            "https://pkgs.tailscale.com/stable/tailscale-setup-latest.exe";

    private static final Pattern VERSION_JSON_PATTERN =
            Pattern.compile("\"Version\"\\s*:\\s*\"([^\"]+)\"");

    private final BinaryDownloader downloader;
    private final Platform platform;
    private ToolConfigStore configStore = ToolConfigStore.inMemory();

    private Path tailscaleBin;
    private Path tailscaledBin;
    private String authKey;
    private TunnelStatus status = TunnelStatus.NOT_INSTALLED;

    /** 会话内缓存:本地/远程版本,null=未探测 */
    private String cachedLocalVersion;
    private String cachedLatestVersion;
    /** 登录状态探测缓存,null=未探测 */
    private Boolean cachedLoggedIn;

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

    /** 绑定持久化配置存储(记录安装路径),绑定后重新解析 */
    public void setConfigStore(ToolConfigStore store) {
        this.configStore = store != null ? store : ToolConfigStore.inMemory();
        resolveBinaryPaths();
    }

    /** 使用用户已有的 tailscale(指定可执行文件或其所在目录) */
    @Override
    public void setBinaryPath(Path path) throws IOException {
        Path bin = Binaries.resolveExecutable(path,
                TunnelType.TAILSCALE.getBinaryName() + platform.getExecutableSuffix());
        this.tailscaleBin = bin;
        String suffix = platform.getExecutableSuffix();
        Path sibling = bin.getParent().resolve("tailscaled" + suffix);
        this.tailscaledBin = Files.exists(sibling) ? sibling : bin;
        configStore.put("tailscale", "binaryPath", bin.toString());
        cachedLocalVersion = Binaries.detectVersion(bin);
        cachedLoggedIn = null; // 重新探测登录状态
        if (status == TunnelStatus.NOT_INSTALLED) {
            status = TunnelStatus.STOPPED;
        }
    }

    private void resolveBinaryPaths() {
        String suffix = platform.getExecutableSuffix();
        String binName = TunnelType.TAILSCALE.getBinaryName() + suffix;
        // 1. 先扫系统 PATH(安装包/包管理器自注册;模组首装时数据库为空,这是首要来源)
        String onPath = io.mctunnel.core.util.PathManager.findOnPath(binName);
        if (onPath != null && Files.isExecutable(Path.of(onPath))) {
            applyTailscaleBin(Path.of(onPath), suffix);
            configStore.put("tailscale", "binaryPath", onPath);
            return;
        }
        // 2. 记录的安装路径(模组安装/用户指定位置后才有)
        String recorded = configStore.get("tailscale", "binaryPath");
        if (recorded != null && !recorded.isBlank()
                && Files.isExecutable(Path.of(recorded))) {
            applyTailscaleBin(Path.of(recorded), suffix);
            return;
        }
        if (platform == Platform.WINDOWS_X64) {
            // 3. Windows 默认安装位置
            Path installDir = Paths.get("C:", "Program Files", "Tailscale");
            this.tailscaleBin = installDir.resolve("tailscale.exe");
            this.tailscaledBin = installDir.resolve("tailscaled.exe");
        } else {
            // 3. Linux/Mac 解压到缓存目录
            Path dir = io.mctunnel.core.DataDir.binaries()
                    .resolve("tailscale")
                    .resolve(platform.name().toLowerCase())
                    .resolve("tailscale_" + FALLBACK_VERSION + "_" + getArchForUrl());
            this.tailscaleBin = dir.resolve("tailscale" + suffix);
            this.tailscaledBin = dir.resolve("tailscaled" + suffix);
        }
    }

    /** 设置 tailscale 主程序路径,并在同目录找 tailscaled(找不到则回退主程序自身) */
    private void applyTailscaleBin(Path bin, String suffix) {
        this.tailscaleBin = bin;
        Path sibling = bin.getParent().resolve("tailscaled" + suffix);
        this.tailscaledBin = Files.exists(sibling) ? sibling : bin;
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

    // ── 检查更新 ──────────────────────────────────────────

    @Override
    public UpdateCheck checkUpdate() throws IOException {
        // 官方 stable 版本 JSON:https://pkgs.tailscale.com/stable/?mode=json
        String json = io.mctunnel.core.download.HttpText.get(
                "https://pkgs.tailscale.com/stable/?mode=json");
        Matcher m = VERSION_JSON_PATTERN.matcher(json);
        if (!m.find()) {
            throw new IOException("Cannot parse tailscale version from pkgs.tailscale.com");
        }
        cachedLatestVersion = m.group(1);
        cachedLocalVersion = Binaries.detectVersion(tailscaleBin);
        return UpdateCheck.of(TunnelType.TAILSCALE, cachedLocalVersion, cachedLatestVersion);
    }

    // ── 安装 / 更新 ───────────────────────────────────────

    @Override
    public void install(InstallOptions options) throws IOException {
        if (isInstalled() && !options.force()) {
            return;
        }
        if (platform == Platform.WINDOWS_X64) {
            installWindows();
        } else {
            String url = getDownloadUrl();
            Path dir = downloader.downloadAndExtract("tailscale", url, platform);
            // tgz 解压后可能在子目录 tailscale_<version>_<arch>/
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
        configStore.put("tailscale", "binaryPath", tailscaleBin.toString());
        cachedLocalVersion = Binaries.detectVersion(tailscaleBin);
        cachedLoggedIn = null;
        status = TunnelStatus.STOPPED;
        // 安装后确保守护进程在运行(Windows 系统服务 / Linux tailscaled)
        ensureDaemonRunning();
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
        // 优先使用检查更新拿到的最新版本,失败回退 FALLBACK_VERSION
        String ver = cachedLatestVersion != null ? cachedLatestVersion : FALLBACK_VERSION;
        String arch = getArchForUrl();
        if (platform == Platform.WINDOWS_X64) {
            return WINDOWS_SETUP_EXE_URL;
        }
        // Linux/Mac 用 tgz
        return "https://pkgs.tailscale.com/stable/tailscale_" + ver + "_" + arch + ".tgz";
    }

    /**
     * Windows:下载官方 exe 安装包并运行,由用户按安装向导完成安装.
     * 默认装到 {@code C:\Program Files\Tailscale},安装包自注册 PATH 与系统服务.
     */
    private void installWindows() throws IOException {
        Path tmp = Files.createTempDirectory("mctunnel-tailscale-setup");
        Path exe = tmp.resolve("tailscale-setup-latest.exe");
        downloader.download(WINDOWS_SETUP_EXE_URL, exe, true);
        try {
            // 非静默:弹出安装向导,等待用户走完流程
            Process p = new ProcessBuilder(exe.toString())
                    .redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                r.lines().forEach(l -> { /* 丢弃 */ });
            }
            int code = p.waitFor();
            if (code != 0) {
                throw new IOException("tailscale 安装包退出码 " + code);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("tailscale install interrupted", e);
        }
    }

    /**
     * 更新:优先用自带 {@code tailscale update} 自更新(就地更新、保留安装位置),
     * 失败(旧版本无此命令/包管理器安装/网络问题等)回退到官方包下载覆盖.
     */
    @Override
    public void update(InstallOptions options) throws IOException {
        if (!isInstalled()) {
            install(options);
            return;
        }
        try {
            runTailscaleChecked(300, "update");
            System.out.println("[Tailscale] 已通过 tailscale update 自更新");
            cachedLocalVersion = Binaries.detectVersion(tailscaleBin);
            return;
        } catch (IOException e) {
            System.out.println("[Tailscale] 自更新失败,回退到官方包下载覆盖: " + e.getMessage());
        }
        TunnelTool.super.update(options);
    }

    /** 运行 tailscale 命令并等待,非 0 退出码抛 IOException(含输出) */
    private void runTailscaleChecked(long timeoutSec, String... args) throws IOException {
        Process p = execTailscale(args);
        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                out.append(line).append('\n');
            }
        }
        try {
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("tailscale " + String.join(" ", args) + " 超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("tailscale " + String.join(" ", args) + " interrupted", e);
        }
        if (p.exitValue() != 0) {
            throw new IOException("exit " + p.exitValue() + ": " + out.toString().trim());
        }
    }

    @Override
    public void configure(Map<String, String> config) {
        String key = config.get("authkey");
        if (key != null && !key.isBlank()) {
            this.authKey = key;
        }
        // login=true: 触发 tailscale login(后台线程,login 会阻塞等待浏览器授权)
        if ("true".equals(config.get("login"))) {
            Thread t = new Thread(() -> {
                try {
                    login();
                } catch (IOException e) {
                    System.err.println("[Tailscale] login failed: " + e.getMessage());
                }
            }, "tailscale-login");
            t.setDaemon(true);
            t.start();
        }
    }

    // ── 命令执行(PATH 优先,失败回退完整路径) ─────────────

    /**
     * 执行 tailscale 命令.
     * Windows 安装包自注册 PATH,先试 {@code tailscale},失败回退完整路径;
     * Linux/Mac 直接用二进制路径(连接系统 tailscaled 默认 socket).
     */
    private Process execTailscale(String... args) throws IOException {
        String[] cmd = new String[args.length + 1];
        System.arraycopy(args, 0, cmd, 1, args.length);
        if (platform == Platform.WINDOWS_X64) {
            try {
                cmd[0] = "tailscale";
                return new ProcessBuilder(cmd).redirectErrorStream(true).start();
            } catch (IOException e) {
                // PATH 中没有,回退完整路径
            }
        }
        cmd[0] = tailscaleBin.toString();
        return new ProcessBuilder(cmd).redirectErrorStream(true).start();
    }

    /** 运行 tailscale 命令并返回输出(不检查退出码) */
    private String runTailscale(long timeoutSec, String... args) throws IOException {
        Process p = execTailscale(args);
        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                out.append(line).append('\n');
            }
        }
        try {
            p.waitFor(timeoutSec, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return out.toString();
    }

    // ── 守护进程(tailscaled / Windows 系统服务) ──────────

    /**
     * 守护进程是否在运行.
     * Windows:{@code tailscale status} 能连通;Linux/macOS:{@code systemctl is-active tailscaled}
     * (普通用户也可查询,无需密码).
     */
    @Override
    public boolean isDaemonRunning() {
        if (!isInstalled()) {
            return false;
        }
        if (platform == Platform.WINDOWS_X64) {
            try {
                Process p = execTailscale("status");
                String out;
                try (var in = p.getInputStream()) {
                    out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                p.waitFor(10, TimeUnit.SECONDS);
                if (p.exitValue() == 0) {
                    return true;
                }
                return !out.contains("failed to connect") && !out.contains("is it running");
            } catch (IOException | InterruptedException e) {
                return false;
            }
        }
        try {
            Process p = new ProcessBuilder("systemctl", "is-active", "tailscaled")
                    .redirectErrorStream(true).start();
            String out;
            try (var in = p.getInputStream()) {
                out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            p.waitFor(10, TimeUnit.SECONDS);
            return p.exitValue() == 0 || out.trim().equalsIgnoreCase("active");
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    /**
     * 安装/启动/登录前的自动兜底:守护没跑就尝试拉起,拉不动只提示不抛错
     * (交互入口请走 {@link #startDaemon(char[])},那里会向用户索要 sudo 密码).
     */
    private void ensureDaemonRunning() {
        if (isDaemonRunning()) {
            return;
        }
        if (platform == Platform.WINDOWS_X64) {
            startWindowsServiceBestEffort();
            return;
        }
        // Linux/macOS:root 直接起;非 root 只试免密 sudo,需要密码则提示用户走交互入口
        try {
            if (isRoot()) {
                runProcess(30, "systemctl", "start", "tailscaled");
            } else if (sudoAvailableWithoutPassword()) {
                runProcess(30, "sudo", "-n", "systemctl", "start", "tailscaled");
            } else {
                System.out.println("[Tailscale] tailscaled 未运行:请点[启服务]输入 sudo 密码,"
                        + "或在终端执行 sudo systemctl start tailscaled");
            }
        } catch (IOException e) {
            System.out.println("[Tailscale] 自动启动 tailscaled 失败: " + safeMessage(e));
        }
    }

    /** 用户手动启动守护进程:启动后复查,未起来则抛错(含原因) */
    @Override
    public void startDaemon(char[] sudoPassword) throws IOException {
        if (!isInstalled()) {
            throw new IOException("tailscale 未安装");
        }
        if (platform == Platform.WINDOWS_X64) {
            startWindowsServiceBestEffort();
        } else {
            systemctlControl("start", sudoPassword);
        }
        if (!isDaemonRunning()) {
            throw new IOException(platform == Platform.WINDOWS_X64
                    ? "tailscaled 未能启动(启动 tailscale-ipn 与 Tailscale 服务均失败,可能需要管理员权限)"
                    : "tailscaled 服务启动后仍未运行,请检查 systemctl status tailscaled");
        }
    }

    /** 用户手动停止守护进程 */
    @Override
    public void stopDaemon(char[] sudoPassword) throws IOException {
        if (!isInstalled()) {
            throw new IOException("tailscale 未安装");
        }
        if (platform == Platform.WINDOWS_X64) {
            // 停止系统服务(需要管理员权限;同时退出托盘 GUI)
            String out = runProcess(20, "net", "stop", "Tailscale");
            // net stop 已在运行/已停止等文本不视为致命;isDaemonRunning 复查即可
            if (!out.isEmpty() && out.toLowerCase().contains("access is denied")) {
                throw new IOException("net stop Tailscale 失败(需要管理员权限)");
            }
        } else {
            systemctlControl("stop", sudoPassword);
        }
    }

    /**
     * Linux/macOS 用 systemctl 启停 tailscaled 服务.
     * root 直接执行;非 root:
     * <ul>
     *   <li>未提供密码 → 先测免密 sudo,不可用则抛 {@link SudoPasswordRequiredException};</li>
     *   <li>提供密码 → {@code sudo -S -p ''}(不显示提示语),密码只写入 sudo 的 stdin,
     *       不出现在命令行参数/日志中,用完立即清空.</li>
     * </ul>
     */
    private void systemctlControl(String action, char[] password) throws IOException {
        if (isRoot()) {
            String out = runProcess(30, "systemctl", action, "tailscaled");
            ensureSystemctlOk(action, out);
            return;
        }
        if (password == null || password.length == 0) {
            if (sudoAvailableWithoutPassword()) {
                String out = runProcess(30, "sudo", "-n", "systemctl", action, "tailscaled");
                ensureSystemctlOk(action, out);
                return;
            }
            throw new SudoPasswordRequiredException(
                    "执行 sudo systemctl " + action + " tailscaled 需要密码");
        }
        // sudo -S:从 stdin 读密码; -p '' :不输出 "Password:" 提示(避免污染输出/日志)
        Process p = new ProcessBuilder("sudo", "-S", "-p", "",
                        "systemctl", action, "tailscaled")
                .redirectErrorStream(true).start();
        String out;
        try {
            try (var os = p.getOutputStream()) {
                // 仅此处短暂使用密码字节,写后即关 stdin(sudo 读到换行即停止读取)
                byte[] pwBytes = new String(password).getBytes(StandardCharsets.UTF_8);
                os.write(pwBytes);
                os.write('\n');
                os.flush();
                java.util.Arrays.fill(pwBytes, (byte) 0);
            }
            try (var in = p.getInputStream()) {
                out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("sudo systemctl " + action + " 超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("操作被中断", e);
        } finally {
            // 密码用毕立即从内存清除(不记录、不持久化、不进任何日志)
            java.util.Arrays.fill(password, '\0');
        }
        if (p.exitValue() != 0) {
            String low = out.toLowerCase();
            // 兼容中英文 sudo 输出:英文 "Sorry, try again"/"incorrect password attempt",
            // 中文 "对不起,请重试"/"N 次错误密码尝试"
            if (low.contains("sorry, try again") || low.contains("incorrect password")
                    || low.contains("authentication failed") || low.contains("3 incorrect")
                    || out.contains("对不起") || out.contains("错误密码")) {
                throw new IOException("sudo 密码错误或鉴权被拒绝");
            }
            if (low.contains("not in the sudoers") || low.contains("not allowed to execute")
                    || out.contains("不在 sudoers")) {
                throw new IOException("当前用户无权执行 sudo,请联系管理员或改用 root");
            }
            throw new IOException("sudo systemctl " + action + " tailscaled 失败: "
                    + out.trim());
        }
    }

    private void ensureSystemctlOk(String action, String out) throws IOException {
        // systemctl 失败时输出通常含 "Failed to ..."
        if (out != null && out.toLowerCase().contains("failed to")) {
            throw new IOException("systemctl " + action + " tailscaled 失败: " + out.trim());
        }
    }

    private static boolean isRoot() {
        try {
            Process p = new ProcessBuilder("id", "-u").redirectErrorStream(true).start();
            String out;
            try (var in = p.getInputStream()) {
                out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            p.waitFor(5, TimeUnit.SECONDS);
            return "0".equals(out.trim());
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    /** 是否配置了免密 sudo(sudo -n 立即返回,不弹密码) */
    private static boolean sudoAvailableWithoutPassword() {
        try {
            Process p = new ProcessBuilder("sudo", "-n", "true")
                    .redirectErrorStream(true).start();
            try (var in = p.getInputStream()) {
                in.readAllBytes();
            }
            p.waitFor(5, TimeUnit.SECONDS);
            return p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    /** 运行命令并收集全部输出(合并 stderr) */
    private static String runProcess(long timeoutSec, String... cmd) throws IOException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out;
        try (var in = p.getInputStream()) {
            out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try {
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException(String.join(" ", cmd) + " 执行超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("操作被中断", e);
        }
        return out;
    }

    /** Windows:优先启动 tailscale-ipn.exe 托盘程序,不存在才回退 net start */
    private void startWindowsServiceBestEffort() {
        Path ipn = tailscaleBin.getParent().resolve("tailscale-ipn.exe");
        try {
            if (Files.exists(ipn)) {
                // 常规手动运行方式:启动 GUI 客户端,由它拉起系统服务
                new ProcessBuilder(ipn.toString()).start();
                Thread.sleep(5000);
            } else {
                runProcess(20, "net", "start", "Tailscale");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            System.out.println("[Tailscale] Windows 启动 tailscaled 失败: " + safeMessage(e));
        }
    }

    private static String safeMessage(IOException e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    // ── 登录 ─────────────────────────────────────────────

    /**
     * 是否已登录(结果会话内缓存).
     * 用 {@code tailscale status --json} 的 BackendState 精确判定:
     * NeedsLogin/NoState=未登录;Running/Stopped 等=已登录;
     * 守护进程连不上(无 JSON)时不误报,按已登录处理.
     */
    public boolean isLoggedIn() {
        if (!isInstalled()) {
            return false;
        }
        if (cachedLoggedIn == null) {
            try {
                String json = runTailscale(10, "status", "--json");
                if (json.contains("\"BackendState\":\"NeedsLogin\"")
                        || json.contains("\"BackendState\":\"NoState\"")) {
                    cachedLoggedIn = false;
                } else {
                    // Running/Stopped=已登录;守护进程未运行时无法判定,不误报
                    cachedLoggedIn = true;
                }
            } catch (IOException e) {
                cachedLoggedIn = true; // 命令失败不误报
            }
        }
        return cachedLoggedIn;
    }

    /**
     * 触发 {@code tailscale login}(阻塞等待用户在浏览器完成授权,超时 180s).
     * 命令输出(含认证 URL)会转发到 stdout 供 CLI 用户查看.
     */
    public void login() throws IOException {
        if (!isInstalled()) {
            throw new IOException("tailscale not installed");
        }
        ensureDaemonRunning();
        Process p = execTailscale("login");
        // 转发输出(含认证 URL)到 stdout
        Thread pipe = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    System.out.println("[tailscale] " + line);
                }
            } catch (IOException ignored) {
            }
        }, "tailscale-login-out");
        pipe.setDaemon(true);
        pipe.start();
        try {
            if (!p.waitFor(180, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("tailscale login 超时(180s)");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("tailscale login interrupted", e);
        }
        cachedLoggedIn = null; // 重新探测
    }

    @Override
    public TunnelInfo start(String... args) throws IOException {
        if (!isInstalled()) {
            throw new IOException("tailscale not installed, call install() first");
        }

        // 1. 确保守护进程在运行(Windows 系统服务 / Linux tailscaled)
        ensureDaemonRunning();

        // 2. tailscale up
        String[] cmd;
        if (authKey != null && !authKey.isBlank()) {
            cmd = new String[]{"up", "--authkey=" + authKey, "--accept-routes"};
        } else {
            cmd = new String[]{"up", "--accept-routes"};
        }

        try {
            Process p = execTailscale(cmd);
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

    @Override
    public void stop() {
        // tailscale down(不断开系统服务;停服务请用 daemon stop)
        try {
            Process p = execTailscale("down");
            try (var ignored = p.getInputStream()) {
                ignored.readAllBytes();
            }
            p.waitFor(10, TimeUnit.SECONDS);
        } catch (IOException | InterruptedException ignored) {
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
        Process p = execTailscale("status", "--json");
        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                out.append(line);
            }
        }
        try {
            p.waitFor(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return out.toString();
    }

    @Override
    public TunnelInfo getInfo() {
        TunnelStatus s = getStatus();
        TunnelInfo base = switch (s) {
            case NOT_INSTALLED -> TunnelInfo.notInstalled(TunnelType.TAILSCALE);
            case STOPPED -> TunnelInfo.stopped(TunnelType.TAILSCALE);
            case RUNNING -> TunnelInfo.running(
                    TunnelType.TAILSCALE, 0, getTailscaleIp(), -1);
            case ERROR -> TunnelInfo.error(TunnelType.TAILSCALE, "tailscale error");
        };
        String local = s == TunnelStatus.NOT_INSTALLED ? null
                : (cachedLocalVersion != null ? cachedLocalVersion
                : (cachedLocalVersion = Binaries.detectVersion(tailscaleBin)));
        boolean needsLogin = s != TunnelStatus.NOT_INSTALLED && !isLoggedIn();
        boolean daemon = s != TunnelStatus.NOT_INSTALLED && isDaemonRunning();
        return base.withVersionInfo(local, cachedLatestVersion, needsLogin)
                .withDaemonRunning(daemon);
    }

    // ── 虚拟组网(控制面 API) ─────────────────────────────

    /** 配置存储中的 API 令牌键 */
    private static final String CFG_API_TOKEN = "apiToken";
    private final TailscaleControlApi controlApi = new TailscaleControlApi();

    /** 是否已保存 API 令牌 */
    public boolean hasApiToken() {
        String t = configStore.get("tailscale", CFG_API_TOKEN);
        return t != null && !t.isBlank();
    }

    /**
     * 保存 API 访问令牌(先调 API 验证有效性)。
     * 令牌保存在本地数据库(与 ngrok authtoken 同级),用于分享设备/邀请成员。
     */
    public void saveApiToken(String token) throws IOException {
        if (token == null || !token.trim().startsWith("tskey-")) {
            throw new IOException("令牌格式不正确,应以 tskey- 开头"
                    + "(在管理控制台 Settings → API access tokens 生成)");
        }
        controlApi.validateToken(token.trim());
        configStore.put("tailscale", CFG_API_TOKEN, token.trim());
    }

    /** 清除已保存的令牌 */
    public void clearApiToken() {
        configStore.put("tailscale", CFG_API_TOKEN, "");
    }

    /** 本机 Tailscale IPv4(给成员的联机地址),未登录/未运行时为 null */
    public String getOwnTailscaleIp() {
        return getTailscaleIp();
    }

    /**
     * 模式二:把本机分享给各成员(成员各自用自己的账号)。
     * 返回给界面的结果说明。
     */
    public String shareSelfTo(List<String> emails) throws IOException {
        String token = requireToken();
        List<String> mails = normalizeEmails(emails);
        TailscaleControlApi.DeviceRef self = resolveSelfDevice(token);
        controlApi.shareDevice(token, self.id(), mails);
        String ip = firstIp(self);
        if (ip == null || ip.isBlank()) {
            ip = getTailscaleIp(); // 控制面没返回 IP 时用本机 tailscale ip -4 兜底
        }
        return "已把本机(" + self.hostname() + ", " + ip + ")分享给 "
                + mails.size() + " 个邮箱。成员收到邮件后点击接受,"
                + "即可用 Tailscale IP " + ip + " 联机";
    }

    /**
     * 模式三:邀请成员加入同一 tailnet(角色 member:可使用设备、不能管理控制台)。
     */
    public String inviteToTailnet(List<String> emails) throws IOException {
        String token = requireToken();
        List<String> mails = normalizeEmails(emails);
        controlApi.inviteUsers(token, mails);
        String ip = getTailscaleIp();
        return "已向 " + mails.size() + " 个邮箱发送 tailnet 邀请(角色 member)。"
                + "成员接受邀请并在客户端登录后,用 Tailscale IP "
                + (ip != null ? ip : "<本机 IP>") + " 联机";
    }

    private String requireToken() throws IOException {
        String t = configStore.get("tailscale", CFG_API_TOKEN);
        if (t == null || t.isBlank()) {
            throw new IOException("尚未配置 Tailscale API 令牌:请先在组网向导中粘贴令牌"
                    + "(生成地址 https://login.tailscale.com/admin/settings/keys)");
        }
        return t.trim();
    }

    /**
     * 找到本机在控制面 API 中的设备:
     * tailscale status --json 的 Self.ID(nodeXXXX)匹配 devices 的 nodeId;
     * 旧版客户端 ID 为纯数字时匹配 id;再不行用主机名兜底。
     */
    private TailscaleControlApi.DeviceRef resolveSelfDevice(String token) throws IOException {
        String selfId = null;
        String selfHost = null;
        String json = runStatusJson();
        if (json != null) {
            int si = json.indexOf("\"Self\"");
            String head = si >= 0
                    ? json.substring(si, Math.min(json.length(), si + 4000)) : json;
            Matcher idm = Pattern.compile("\"ID\"\\s*:\\s*\"([^\"]+)\"").matcher(head);
            if (idm.find()) {
                selfId = idm.group(1);
            }
            Matcher hm = Pattern.compile("\"HostName\"\\s*:\\s*\"([^\"]+)\"").matcher(head);
            if (hm.find()) {
                selfHost = hm.group(1);
            }
        }
        List<TailscaleControlApi.DeviceRef> devices = controlApi.listDevices(token);
        if (devices.isEmpty()) {
            throw new IOException("控制面返回的设备列表为空,确认本机已登录并执行 tailscale up");
        }
        TailscaleControlApi.DeviceRef byHost = null;
        for (TailscaleControlApi.DeviceRef d : devices) {
            if (selfId != null && (selfId.equals(d.nodeId()) || selfId.equals(d.id()))) {
                return d;
            }
            if (selfHost != null && selfHost.equalsIgnoreCase(d.hostname()) && byHost == null) {
                byHost = d;
            }
        }
        if (byHost != null) {
            return byHost;
        }
        throw new IOException("在 tailnet 设备列表中找不到本机(Self.ID=" + selfId
                + "),请确认 tailscale 已登录并在线");
    }

    private static String firstIp(TailscaleControlApi.DeviceRef d) {
        return d.ips().isEmpty() ? "" : d.ips().get(0);
    }

    /** 规范化邮箱输入:去空白/去重/简单校验,逗号、分号或空白分隔 */
    static List<String> normalizeEmails(List<String> raw) throws IOException {
        List<String> out = new ArrayList<>();
        for (String r : raw) {
            if (r == null) {
                continue;
            }
            for (String part : r.split("[,;\\s]+")) {
                String e = part.trim();
                if (e.isEmpty() || out.contains(e)) {
                    continue;
                }
                if (!e.contains("@") || e.startsWith("@") || e.endsWith("@")) {
                    throw new IOException("邮箱格式不正确: " + e);
                }
                out.add(e);
            }
        }
        if (out.isEmpty()) {
            throw new IOException("请输入至少一个成员邮箱");
        }
        return out;
    }

    private String getTailscaleIp() {
        try {
            Process p = execTailscale("ip", "-4");
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
