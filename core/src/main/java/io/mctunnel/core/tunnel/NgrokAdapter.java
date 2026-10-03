package io.mctunnel.core.tunnel;

import io.mctunnel.core.DataDir;
import io.mctunnel.core.download.BinaryDownloader;
import io.mctunnel.core.download.Platform;
import io.mctunnel.core.util.PathManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * ngrok 适配器.
 * <p>
 * ngrok 无版本号查询 API:检查更新 = 下载 v3 stable 包
 * (直链永远指向最新版)解压后跑 {@code --version} 探测版本号,
 * 结果会话内缓存,下载的包直接复用给安装/更新,不二次下载.
 * <p>
 * Windows 安装流程:下载 zip → 用户指定解压目录 → 解压 →
 * 尝试加入用户 PATH(查重)→ 记录完整路径.
 * 后续执行命令先尝试直接用 {@code ngrok}(PATH 解析),
 * 失败回退到记录的完整路径.
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
    /** ngrok 错误码,如 ERR_NGROK_8013 */
    private static final Pattern CODE_PATTERN =
            Pattern.compile("ERR_NGROK_\\d+");

    private final BinaryDownloader downloader;
    private final Platform platform;
    private ToolConfigStore configStore = ToolConfigStore.inMemory();

    private Path binaryPath;
    private Process process;
    private String publicUrl;
    /** 最近一次启动失败的实际原因(从 ngrok 输出提炼),成功/停止后清空 */
    private volatile String lastError;
    /** 本次隧道进程的完整输出(StringBuffer 自带同步,供解析线程写、启动线程读) */
    private final StringBuffer tunnelOutput = new StringBuffer();
    private int localPort;
    private TunnelStatus status = TunnelStatus.NOT_INSTALLED;

    /** 会话内缓存:本地/远程版本,null=未探测 */
    private String cachedLocalVersion;
    private String cachedLatestVersion;
    /** 会话内缓存:检查更新时下载的 stable 包,安装/更新直接复用 */
    private Path cachedArchive;
    /** 账户是否已配置(探测过);null=未探测 */
    private Boolean accountConfigured;

    public NgrokAdapter() {
        this(new BinaryDownloader(), Platform.detect());
    }

    NgrokAdapter(BinaryDownloader downloader, Platform platform) {
        this.downloader = downloader;
        this.platform = platform;
        resolveBinaryPath();
        refreshStatus();
    }

    /** 绑定持久化配置存储(记录安装路径等),绑定后重新解析 */
    public void setConfigStore(ToolConfigStore store) {
        this.configStore = store != null ? store : ToolConfigStore.inMemory();
        resolveBinaryPath();
        refreshStatus();
    }

    /** 使用用户已有的 ngrok(指定可执行文件或其所在目录) */
    @Override
    public void setBinaryPath(Path path) throws IOException {
        Path bin = Binaries.resolveExecutable(path,
                TunnelType.NGROK.getBinaryName() + platform.getExecutableSuffix());
        this.binaryPath = bin;
        configStore.put("ngrok", "binaryPath", bin.toString());
        cachedLocalVersion = Binaries.detectVersion(bin);
        accountConfigured = null; // 重新探测账户配置
        // Windows 上 ngrok 无安装包(只有 zip/单文件),尝试把所在目录注册到用户 PATH,
        // 之后可直接用 ngrok 命令(执行时本就会先试 PATH 再回退完整路径)
        if (platform == Platform.WINDOWS_X64 && bin.getParent() != null) {
            boolean onPath = PathManager.addToUserPath(bin.getParent().toString());
            if (onPath) {
                configStore.put("ngrok", "addedToPath", "true");
            }
        }
        refreshStatus();
    }

    private void resolveBinaryPath() {
        String name = TunnelType.NGROK.getBinaryName() + platform.getExecutableSuffix();
        // 1. 先扫系统 PATH(where/which):模组首装时数据库为空,
        //    用户自行下载并配置过 PATH 的 ngrok 只能从这里发现;扫到才记录
        String onPath = PathManager.findOnPath(name);
        if (onPath != null && Files.isExecutable(Path.of(onPath))) {
            this.binaryPath = Path.of(onPath);
            configStore.put("ngrok", "binaryPath", onPath);
            return;
        }
        // 2. 记录的完整路径(模组安装/用户指定位置后才有)
        String recorded = configStore.get("ngrok", "binaryPath");
        if (recorded != null && !recorded.isBlank() && Files.isExecutable(Path.of(recorded))) {
            this.binaryPath = Path.of(recorded);
            return;
        }
        // 3. 默认缓存目录(模组在 Linux/Mac 的安装位置)
        this.binaryPath = DataDir.binaries()
                .resolve("ngrok")
                .resolve(platform.name().toLowerCase())
                .resolve(name);
    }

    private void refreshStatus() {
        status = isInstalled() && status == TunnelStatus.NOT_INSTALLED
                ? TunnelStatus.STOPPED
                : (isInstalled() ? status : TunnelStatus.NOT_INSTALLED);
    }

    @Override
    public TunnelType getType() {
        return TunnelType.NGROK;
    }

    @Override
    public boolean isInstalled() {
        return binaryPath != null && Files.isExecutable(binaryPath);
    }

    // ── 检查更新 ──────────────────────────────────────────

    @Override
    public UpdateCheck checkUpdate() throws IOException {
        String latest = fetchLatestVersion();
        cachedLatestVersion = latest;
        cachedLocalVersion = Binaries.detectVersion(binaryPath);
        return UpdateCheck.of(TunnelType.NGROK, cachedLocalVersion, latest);
    }

    /**
     * 下载 v3 stable 包并探测版本号.
     * stable 直链永远指向最新版且无版本号可查,只能下载后跑 --version;
     * 下载的包缓存在 {@link #cachedArchive},安装/更新直接复用.
     */
    private String fetchLatestVersion() throws IOException {
        if (cachedLatestVersion != null) {
            return cachedLatestVersion;
        }
        String url = getDownloadUrl();
        Path tmp = Files.createTempDirectory("mctunnel-ngrok-check");
        Path archive = tmp.resolve(BinaryDownloader.fileNameOf(url));
        downloader.download(url, archive, true); // 文件名固定,强制重下才拿得到最新
        cachedArchive = archive;

        Path extracted = tmp.resolve("unpacked");
        downloader.extractArchive(archive, extracted);
        Path bin = findBinary(extracted);
        if (bin == null) {
            throw new IOException("ngrok binary not found in downloaded package");
        }
        String version = Binaries.detectVersion(bin);
        if (version == null) {
            throw new IOException("cannot detect version from " + bin);
        }
        return version;
    }

    // ── 安装 / 更新 ───────────────────────────────────────

    /**
     * 更新 ngrok:优先使用自带的 {@code ngrok update} 自更新命令
     * (就地更新二进制,保留安装位置与 PATH 配置,无需重新下载覆盖);
     * 自更新失败时回退到官方 stable 包下载覆盖安装.
     */
    @Override
    public void update(InstallOptions options) throws IOException {
        if (!isInstalled()) {
            install(options);
            return;
        }
        // 更新前停掉运行中的隧道进程(Windows 下 exe 被占用会更新失败)
        stop();
        try {
            runNgrok("update");
            // 就地更新完成,重新探测版本
            cachedLocalVersion = null;
            cachedLocalVersion = Binaries.detectVersion(binaryPath);
            status = TunnelStatus.STOPPED;
            return;
        } catch (IOException e) {
            System.err.println("[MC-Tunnel] `ngrok update` 失败,回退到重新下载安装: "
                    + e.getMessage());
        }
        // 回退:官方 stable 包下载覆盖(install force)
        TunnelTool.super.update(options);
    }

    @Override
    public void install(InstallOptions options) throws IOException {
        if (isInstalled() && !options.force()) {
            return;
        }
        if (platform == Platform.WINDOWS_X64) {
            Path dir = options.extractDirPath();
            if (dir == null) {
                // 更新场景:复用已记录的安装目录,不再询问
                String recordedDir = configStore.get("ngrok", "installDir");
                if (recordedDir != null && !recordedDir.isBlank()) {
                    dir = Path.of(recordedDir);
                }
            }
            if (dir == null) {
                throw new ExtractDirRequiredException();
            }
            installWindows(dir);
        } else {
            installUnix();
        }
        accountConfigured = null;
        cachedLocalVersion = Binaries.detectVersion(binaryPath);
        if (!isInstalled()) {
            throw new IOException("ngrok binary not found after install at " + binaryPath);
        }
        status = TunnelStatus.STOPPED;
    }

    /** Windows:解压到用户指定目录 → 加入用户 PATH → 记录完整路径 */
    private void installWindows(Path dir) throws IOException {
        Path archive = obtainStableArchive();
        downloader.extractArchive(archive, dir);
        Path exe = findBinary(dir);
        if (exe == null) {
            throw new IOException("ngrok.exe not found after extract to " + dir);
        }
        boolean added = PathManager.addToUserPath(dir.toString());
        configStore.put("ngrok", "installDir", dir.toString());
        configStore.put("ngrok", "binaryPath", exe.toString());
        configStore.put("ngrok", "addedToPath", String.valueOf(added));
        this.binaryPath = exe;
    }

    /** Linux/Mac:解压 tgz 到默认缓存目录 */
    private void installUnix() throws IOException {
        Path archive = obtainStableArchive();
        Path toolDir = DataDir.binaries()
                .resolve("ngrok")
                .resolve(platform.name().toLowerCase());
        downloader.extractArchive(archive, toolDir);
        Path bin = findBinary(toolDir);
        if (bin == null) {
            throw new IOException("ngrok binary not found after extract to " + toolDir);
        }
        bin.toFile().setExecutable(true, false);
        configStore.put("ngrok", "binaryPath", bin.toString());
        this.binaryPath = bin;
    }

    /** 获取 stable 包:优先复用检查更新时下载的包(本会话内即为最新) */
    private Path obtainStableArchive() throws IOException {
        if (cachedArchive != null && Files.exists(cachedArchive)
                && Files.size(cachedArchive) > 0) {
            return cachedArchive;
        }
        String url = getDownloadUrl();
        Path tmp = Files.createTempDirectory("mctunnel-ngrok-install");
        Path archive = tmp.resolve(BinaryDownloader.fileNameOf(url));
        return downloader.download(url, archive, true);
    }

    private Path findBinary(Path root) {
        String name = TunnelType.NGROK.getBinaryName() + platform.getExecutableSuffix();
        try (Stream<Path> stream = Files.walk(root, 4)) {
            return stream.filter(p -> p.getFileName() != null
                            && p.getFileName().toString().equals(name))
                    .findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private String getDownloadUrl() throws IOException {
        // ngrok v3 稳定版下载地址(永远指向最新)
        return switch (platform) {
            case LINUX_X64 -> "https://bin.equinox.io/c/bNyj1mQVY4c/ngrok-v3-stable-linux-amd64.tgz";
            case LINUX_ARM64 -> "https://bin.equinox.io/c/bNyj1mQVY4c/ngrok-v3-stable-linux-arm64.tgz";
            case WINDOWS_X64 -> "https://bin.equinox.io/c/bNyj1mQVY4c/ngrok-v3-stable-windows-amd64.zip";
            case MACOS_X64 -> "https://bin.equinox.io/c/bNyj1mQVY4c/ngrok-v3-stable-darwin-amd64.tgz";
            case MACOS_ARM64 -> "https://bin.equinox.io/c/bNyj1mQVY4c/ngrok-v3-stable-darwin-arm64.tgz";
            case UNKNOWN -> throw new IOException("Unsupported platform for ngrok: " + platform);
        };
    }

    // ── 命令执行(PATH 优先,失败回退完整路径) ─────────────

    private String executableName() {
        return TunnelType.NGROK.getBinaryName() + platform.getExecutableSuffix();
    }

    /** 运行一次性 ngrok 命令并返回输出;非 0 退出抛 IOException */
    private String runNgrok(String... args) throws IOException {
        Process p = startNgrokProcess(args);
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line).append('\n');
            }
        }
        try {
            p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("ngrok command interrupted", e);
        }
        if (p.exitValue() != 0) {
            throw new IOException("ngrok " + String.join(" ", args)
                    + " failed (exit " + p.exitValue() + "): " + out);
        }
        return out.toString();
    }

    /**
     * 启动 ngrok 进程:先尝试直接用 PATH 中的 ngrok,
     * 失败(找不到命令)回退到记录的完整路径.
     */
    private Process startNgrokProcess(String... args) throws IOException {
        String[] cmd = new String[args.length + 1];
        System.arraycopy(args, 0, cmd, 1, args.length);
        try {
            cmd[0] = executableName();
            return new ProcessBuilder(cmd).redirectErrorStream(true).start();
        } catch (IOException e) {
            // PATH 中没有,回退完整路径
            cmd[0] = binaryPath.toString();
            return new ProcessBuilder(cmd).redirectErrorStream(true).start();
        }
    }

    // ── 配置 / 启停 ───────────────────────────────────────

    @Override
    public void configure(Map<String, String> config) {
        String token = config.get("authtoken");
        if (token == null || token.isBlank()) {
            return;
        }
        try {
            runNgrok("config", "add-authtoken", token);
            accountConfigured = true;
        } catch (IOException e) {
            status = TunnelStatus.ERROR;
            throw new RuntimeException("Failed to configure ngrok authtoken", e);
        }
    }

    /** 账户是否已配置(探测结果会话内缓存) */
    public boolean isAccountConfigured() {
        if (!isInstalled()) {
            return false;
        }
        if (accountConfigured == null) {
            try {
                runNgrok("config", "check");
                accountConfigured = true;
            } catch (IOException e) {
                accountConfigured = false;
            }
        }
        return accountConfigured;
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
        // 新一轮启动:清空上一次的输出与错误
        publicUrl = null;
        lastError = null;
        tunnelOutput.setLength(0);

        process = startNgrokProcess(args);

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
        } else {
            // 没拿到 URL:进程可能已带着错误退出。等解析线程读完尾部输出,
            // 再从 ERROR 行里提炼实际原因(如 ERR_NGROK_8013 需绑卡)
            if (!process.isAlive()) {
                try {
                    parser.join(1500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            String err = extractNgrokError(tunnelOutput.toString());
            if (err != null) {
                status = TunnelStatus.ERROR;
                lastError = err;
            } else if (!process.isAlive()) {
                status = TunnelStatus.ERROR;
                lastError = "ngrok 进程异常退出,未输出错误信息";
            } else {
                status = TunnelStatus.RUNNING; // 进程在跑,只是还没解析到 URL
            }
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
                tunnelOutput.append(line).append('\n');
                Matcher m = URL_PATTERN.matcher(line);
                if (m.find()) {
                    publicUrl = m.group(1);
                }
            }
        } catch (IOException ignored) {
            // 进程退出
        }
    }

    /**
     * 从 ngrok 输出中提炼"实际错误":剥离 ERROR: 前缀、空行、反引号噪音,
     * 识别 ERR_NGROK_xxxx 错误码;已知错误码给出中文说明与解决链接,
     * 未知错误码保留原始英文要点。返回 null 表示输出中没有错误。
     */
    static String extractNgrokError(String output) {
        if (output == null || output.isBlank()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        String code = null;
        for (String raw : output.split("\\R")) {
            String line = raw.trim();
            // ngrok 把错误行统一写成 "ERROR:  ..."(可能多行)
            int idx = line.indexOf("ERROR:");
            if (idx < 0) {
                continue;
            }
            line = line.substring(idx + 6).trim();
            if (line.isEmpty()) {
                continue;
            }
            // 去掉消息里包裹 URL 的反引号
            line = line.replace("`", "");
            Matcher cm = CODE_PATTERN.matcher(line);
            if (cm.matches()) {
                code = cm.group();
                continue;
            }
            // 官方文档链接对玩家无用,跳过
            if (line.startsWith("https://ngrok.com/docs/errors/")) {
                continue;
            }
            if (!parts.contains(line)) {
                parts.add(line);
            }
        }
        if (parts.isEmpty() && code == null) {
            return null;
        }
        String suffix = code != null ? " [" + code + "]" : "";
        if (code != null) {
            String mapped = explainCode(code, parts);
            if (mapped != null) {
                return mapped + suffix;
            }
        }
        // 未知错误:保留实际英文要点(限制长度,避免刷屏)
        String joined = String.join(" ", parts);
        if (joined.length() > 400) {
            joined = joined.substring(0, 400) + "…";
        }
        return joined + suffix;
    }

    /** 已知 ngrok 错误码的中文解释(只收录常见、玩家可自行解决的) */
    private static String explainCode(String code, List<String> parts) {
        switch (code) {
            case "ERR_NGROK_8013":
                return "免费账号不能直接使用 TCP 端点:必须先在官网绑定一张信用卡或借记卡(不会扣款)。"
                        + "绑定地址: https://dashboard.ngrok.com/settings#id-verification";
            case "ERR_NGROK_108":
                return "免费账号同时只能运行 1 个 ngrok 代理会话,请先停止其他正在运行的 ngrok";
            case "ERR_NGROK_105":
                return "ngrok 账号未注册或 Authtoken 无效,请重新在官网获取 Authtoken 并保存";
            case "ERR_NGROK_4018":
                return "尚未配置 Authtoken,请先在本界面粘贴 Authtoken 并保存";
            default:
                return null;
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
        lastError = null;
        tunnelOutput.setLength(0);
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
        TunnelStatus s = getStatus();
        TunnelInfo base = switch (s) {
            case NOT_INSTALLED -> TunnelInfo.notInstalled(TunnelType.NGROK);
            case STOPPED -> TunnelInfo.stopped(TunnelType.NGROK);
            case RUNNING -> TunnelInfo.running(
                    TunnelType.NGROK, localPort, publicUrl,
                    process != null ? process.pid() : -1);
            case ERROR -> TunnelInfo.error(TunnelType.NGROK,
                    lastError != null ? lastError : "ngrok process error");
        };
        String local = s == TunnelStatus.NOT_INSTALLED ? null : localVersion();
        boolean needsAccount = s != TunnelStatus.NOT_INSTALLED && !isAccountConfigured();
        return base.withVersionInfo(local, cachedLatestVersion, needsAccount);
    }

    /** 本地版本(懒探测,会话内缓存) */
    public String localVersion() {
        if (cachedLocalVersion == null && isInstalled()) {
            cachedLocalVersion = Binaries.detectVersion(binaryPath);
        }
        return cachedLocalVersion;
    }

    /** 获取二进制路径(供测试/CLI 调试用) */
    public Path getBinaryPath() {
        return binaryPath;
    }
}
