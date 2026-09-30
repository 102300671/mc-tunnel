package io.mctunnel.core.tunnel;

import io.mctunnel.core.download.BinaryDownloader;
import io.mctunnel.core.download.Platform;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SyncThing 适配器.
 * <p>
 * SyncThing 是 P2P 文件同步工具,用于在多设备间同步 Minecraft 存档.
 * <ul>
 *   <li>二进制下载自 GitHub releases</li>
 *   <li>启动后监听 REST API: http://localhost:8384</li>
 *   <li>通过 API 配置设备 ID、共享文件夹、获取同步状态</li>
 * </ul>
 * 配置键(见 {@link #configure(Map)}):
 * <ul>
 *   <li>{@code apiKey} - REST API 密钥</li>
 *   <li>{@code folderId} - 共享文件夹 ID</li>
 *   <li>{@code folderPath} - 本地存档路径</li>
 *   <li>{@code peerDevice} - 对等设备 ID(逗号分隔,可选)</li>
 * </ul>
 */
public class SyncThingAdapter implements TunnelTool {

    private static final String VERSION = "1.28.0";
    private static final Pattern DEVICE_ID_PATTERN =
            Pattern.compile("\"myID\"\\s*:\\s*\"([^\"]+)\"");

    private final BinaryDownloader downloader;
    private final Platform platform;

    private Path binaryPath;
    private Process process;
    private String apiKey;
    private String folderId = "mctunnel-saves";
    private String folderPath;
    private String peerDevices;
    private TunnelStatus status = TunnelStatus.NOT_INSTALLED;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public SyncThingAdapter() {
        this(new BinaryDownloader(), Platform.detect());
    }

    SyncThingAdapter(BinaryDownloader downloader, Platform platform) {
        this.downloader = downloader;
        this.platform = platform;
        resolveBinaryPath();
        if (isInstalled()) {
            status = TunnelStatus.STOPPED;
        }
    }

    private void resolveBinaryPath() {
        String name = TunnelType.SYNCTHING.getBinaryName() + platform.getExecutableSuffix();
        this.binaryPath = Paths.get(System.getProperty("user.home"),
                ".mctunnel", "binaries", "syncthing",
                platform.name().toLowerCase(), name);
    }

    @Override
    public TunnelType getType() {
        return TunnelType.SYNCTHING;
    }

    @Override
    public boolean isInstalled() {
        return Files.isExecutable(binaryPath);
    }

    @Override
    public void install() throws IOException {
        if (isInstalled()) return;
        String url = getDownloadUrl();
        downloader.downloadAndExtract("syncthing", url, platform);
        // 解压后可能在子目录 syncthing-<version>-<os>-<arch>/
        relocateBinary();
        if (!isInstalled()) {
            throw new IOException("syncthing binary not found after install at " + binaryPath);
        }
        status = TunnelStatus.STOPPED;
    }

    private void relocateBinary() {
        // 直接路径不存在时,找子目录里的 syncthing
        if (Files.isExecutable(binaryPath)) return;
        Path toolDir = binaryPath.getParent();
        try (var stream = Files.walk(toolDir, 3)) {
            Path found = stream.filter(p -> p.getFileName() != null
                            && p.getFileName().toString().equals(
                                    "syncthing" + platform.getExecutableSuffix()))
                    .findFirst().orElse(null);
            if (found != null && !found.equals(binaryPath)) {
                Files.move(found, binaryPath,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                binaryPath.toFile().setExecutable(true, false);
            }
        } catch (IOException ignored) {
        }
    }

    private String getDownloadUrl() throws IOException {
        String os = platform.getOsName();
        String arch = platform.getArch();
        if (platform == Platform.WINDOWS_X64) {
            return "https://github.com/syncthing/syncthing/releases/download/v"
                    + VERSION + "/syncthing-windows-amd64-v" + VERSION + ".zip";
        }
        if (platform == Platform.MACOS_ARM64 || platform == Platform.MACOS_X64) {
            return "https://github.com/syncthing/syncthing/releases/download/v"
                    + VERSION + "/syncthing-macos-" + arch + "-v" + VERSION + ".tar.gz";
        }
        return "https://github.com/syncthing/syncthing/releases/download/v"
                + VERSION + "/syncthing-" + os + "-" + arch + "-v" + VERSION + ".tar.gz";
    }

    @Override
    public void configure(Map<String, String> config) {
        if (config.containsKey("apiKey")) apiKey = config.get("apiKey");
        if (config.containsKey("folderId")) folderId = config.get("folderId");
        if (config.containsKey("folderPath")) folderPath = config.get("folderPath");
        if (config.containsKey("peerDevice")) peerDevices = config.get("peerDevice");
    }

    @Override
    public TunnelInfo start(String... args) throws IOException {
        if (!isInstalled()) {
            throw new IOException("syncthing not installed, call install() first");
        }
        if (process != null && process.isAlive()) {
            return getInfo();
        }

        // 启动 syncthing (无浏览器 GUI,仅 API)
        ProcessBuilder pb = new ProcessBuilder(
                binaryPath.toString(), "serve", "--no-browser",
                "--gui-address=127.0.0.1:8384");
        pb.redirectErrorStream(true);
        process = pb.start();

        // 等待 API 可用
        long deadline = System.currentTimeMillis() + 30_000;
        while (process.isAlive() && System.currentTimeMillis() < deadline) {
            if (isApiReachable()) break;
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        if (apiKey == null || apiKey.isBlank()) {
            apiKey = readApiKeyFromConfig();
        }

        // 配置共享文件夹
        if (folderPath != null && !folderPath.isBlank()) {
            configureFolder();
        }
        if (peerDevices != null && !peerDevices.isBlank()) {
            addPeerDevices();
        }

        status = isApiReachable() ? TunnelStatus.RUNNING : TunnelStatus.ERROR;
        return getInfo();
    }

    private boolean isApiReachable() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:8384/rest/system/status"))
                    .timeout(Duration.ofSeconds(3))
                    .GET().build();
            HttpResponse<String> res = httpClient.send(req,
                    HttpResponse.BodyHandlers.ofString());
            return res.statusCode() == 200 || res.statusCode() == 401; // 401 表示服务在跑
        } catch (Exception e) {
            return false;
        }
    }

    private String readApiKeyFromConfig() {
        // syncthing 配置文件: ~/.local/state/syncthing/config.xml (Linux)
        // 或 ~/Library/Application Support/Syncthing/config.xml (mac)
        // 或 %LOCALAPPDATA%\Syncthing\config.xml (Windows)
        Path config;
        if (platform == Platform.WINDOWS_X64) {
            String localApp = System.getenv("LOCALAPPDATA");
            config = Paths.get(localApp != null ? localApp : ".", "Syncthing", "config.xml");
        } else if (platform == Platform.MACOS_ARM64 || platform == Platform.MACOS_X64) {
            config = Paths.get(System.getProperty("user.home"),
                    "Library", "Application Support", "Syncthing", "config.xml");
        } else {
            config = Paths.get(System.getProperty("user.home"),
                    ".local", "state", "syncthing", "config.xml");
        }
        try {
            String xml = Files.readString(config, StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("<apikey>([^<]+)</apikey>",
                    Pattern.CASE_INSENSITIVE).matcher(xml);
            if (m.find()) return m.group(1);
        } catch (IOException ignored) {
        }
        return null;
    }

    private void configureFolder() {
        // 通过 REST API 添加文件夹配置
        String body = "{\"id\":\"" + folderId + "\",\"label\":\"MC-Tunnel Saves\","
                + "\"path\":\"" + folderPath.replace("\\", "\\\\") + "\","
                + "\"type\":\"sendreceive\","
                + "\"rescanIntervalS\":60,\"fsWatcherEnabled\":true}";
        apiPost("/rest/config/folders", body);
    }

    private void addPeerDevices() {
        for (String dev : peerDevices.split(",")) {
            String d = dev.trim();
            if (d.isEmpty()) continue;
            String body = "{\"deviceID\":\"" + d + "\"}";
            apiPost("/rest/config/devices", body);
            // 把该设备加入文件夹共享
            apiPatch("/rest/config/folders/" + folderId,
                    "{\"devices\":[{\"deviceID\":\"" + d + "\"}]}");
        }
    }

    private String apiGet(String path) throws IOException {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:8384" + path))
                .timeout(Duration.ofSeconds(10)).GET();
        if (apiKey != null) b.header("X-API-Key", apiKey);
        try {
            HttpResponse<String> res = httpClient.send(b.build(),
                    HttpResponse.BodyHandlers.ofString());
            return res.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("syncthing API interrupted", e);
        }
    }

    private void apiPost(String path, String jsonBody) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:8384" + path))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
            if (apiKey != null) b.header("X-API-Key", apiKey);
            httpClient.send(b.build(), HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            System.err.println("[SyncThing] apiPost " + path + " failed: " + e.getMessage());
        }
    }

    private void apiPatch(String path, String jsonBody) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:8384" + path))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(jsonBody));
            if (apiKey != null) b.header("X-API-Key", apiKey);
            httpClient.send(b.build(), HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            System.err.println("[SyncThing] apiPatch " + path + " failed: " + e.getMessage());
        }
    }

    /** 获取本设备 ID */
    public String getDeviceId() {
        try {
            String json = apiGet("/rest/system/status");
            Matcher m = DEVICE_ID_PATTERN.matcher(json);
            if (m.find()) return m.group(1);
        } catch (IOException ignored) {
        }
        return null;
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
            if (process.isAlive()) process.destroyForcibly();
            process = null;
        }
        status = TunnelStatus.STOPPED;
    }

    @Override
    public TunnelStatus getStatus() {
        if (status == TunnelStatus.RUNNING
                && (process == null || !process.isAlive())) {
            status = TunnelStatus.STOPPED;
        }
        return status;
    }

    @Override
    public TunnelInfo getInfo() {
        TunnelStatus s = getStatus();
        return switch (s) {
            case NOT_INSTALLED -> TunnelInfo.notInstalled(TunnelType.SYNCTHING);
            case STOPPED -> TunnelInfo.stopped(TunnelType.SYNCTHING);
            case RUNNING -> {
                String devId = getDeviceId();
                yield TunnelInfo.running(TunnelType.SYNCTHING, 8384,
                        devId != null ? "device:" + devId : "running",
                        process != null ? process.pid() : -1);
            }
            case ERROR -> TunnelInfo.error(TunnelType.SYNCTHING, "syncthing process error");
        };
    }

    public Path getBinaryPath() {
        return binaryPath;
    }
}
