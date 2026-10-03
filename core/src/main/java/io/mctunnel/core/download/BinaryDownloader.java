package io.mctunnel.core.download;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 工具二进制下载器.
 * <p>
 * 负责从官方 URL 下载工具二进制到本地缓存,并解压.
 * 缓存目录: {DataDir}/binaries/{toolName}/{platform}/
 */
public final class BinaryDownloader {

    private static Path cacheRoot() {
        return io.mctunnel.core.DataDir.binaries();
    }

    private final HttpClient httpClient;

    public BinaryDownloader() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * 下载并解压二进制到缓存目录.
     *
     * @param toolName 工具名(用于缓存子目录,如 "ngrok")
     * @param url      下载 URL
     * @param platform 当前平台
     * @return 解压后的目录
     * @throws IOException 下载或解压失败
     */
    public Path downloadAndExtract(String toolName, String url, Platform platform) throws IOException {
        Path toolDir = cacheRoot().resolve(toolName).resolve(platform.name().toLowerCase());
        Files.createDirectories(toolDir);

        // 下载压缩包到临时文件
        String fileName = fileNameOf(url);
        Path archive = toolDir.resolve(fileName);

        download(url, archive, false);

        // 解压
        extract(archive, toolDir, fileName);

        return toolDir;
    }

    /**
     * 下载文件到指定路径.
     *
     * @param url   下载 URL
     * @param target 目标文件路径
     * @param force true 表示覆盖已有文件(强制重新下载)
     * @return 目标文件路径
     * @throws IOException 下载失败
     */
    public Path download(String url, Path target, boolean force) throws IOException {
        if (!force && Files.exists(target) && Files.size(target) > 0) {
            return target;
        }
        Files.createDirectories(target.getParent());
        download(url, target);
        return target;
    }

    /**
     * 解压压缩包(zip/tgz/tar.gz)到目标目录;非压缩文件直接复制.
     *
     * @param archive   压缩包路径
     * @param targetDir 解压目标目录
     * @throws IOException 解压失败
     */
    public void extractArchive(Path archive, Path targetDir) throws IOException {
        Files.createDirectories(targetDir);
        extract(archive, targetDir, archive.getFileName().toString());
    }

    /** 从 URL 提取文件名 */
    public static String fileNameOf(String url) {
        return url.substring(url.lastIndexOf('/') + 1);
    }

    /** 下载重试次数 */
    private static final int DOWNLOAD_RETRIES = 3;

    private void download(String url, Path target) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= DOWNLOAD_RETRIES; attempt++) {
            try {
                downloadOnce(url, target, attempt > 1);
                return;
            } catch (IOException e) {
                last = e;
                // 清理半截文件,避免残留损坏包
                try {
                    Files.deleteIfExists(target);
                } catch (IOException ignored) {
                }
                if (attempt < DOWNLOAD_RETRIES) {
                    System.err.println("[MC-Tunnel] 下载失败(第" + attempt + "次): "
                            + e.getMessage() + ",重试中...");
                    try {
                        Thread.sleep(1500L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Download interrupted", ie);
                    }
                }
            }
        }
        throw last;
    }

    /** forceHttp1: HTTP/2 在部分网络下会中途 EOF,重试时降级 HTTP/1.1 */
    private void downloadOnce(String url, Path target, boolean forceHttp1) throws IOException {
        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .GET();
        if (forceHttp1) {
            rb.version(HttpClient.Version.HTTP_1_1);
        }
        HttpRequest request = rb.build();

        try {
            HttpResponse<InputStream> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                throw new IOException("Download failed: HTTP " + response.statusCode()
                        + " for " + url);
            }

            try (InputStream in = new BufferedInputStream(response.body());
                 FileOutputStream out = new FileOutputStream(target.toFile())) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted", e);
        }
    }

    private void extract(Path archive, Path targetDir, String fileName) throws IOException {
        if (fileName.endsWith(".zip")) {
            extractZip(archive, targetDir);
        } else if (fileName.endsWith(".tgz") || fileName.endsWith(".tar.gz")) {
            extractTgz(archive, targetDir);
        } else {
            // 无压缩,直接就是二进制
            Files.copy(archive, targetDir.resolve(fileName),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void extractZip(Path archive, Path targetDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(
                new BufferedInputStream(Files.newInputStream(archive)))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path outPath = targetDir.resolve(entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(outPath);
                } else {
                    Files.createDirectories(outPath.getParent());
                    try (BufferedOutputStream out = new BufferedOutputStream(
                            Files.newOutputStream(outPath))) {
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = zis.read(buffer)) != -1) {
                            out.write(buffer, 0, read);
                        }
                    }
                }
                zis.closeEntry();
            }
        }
    }

    private void extractTgz(Path archive, Path targetDir) throws IOException {
        // 用系统 tar 命令解压(Linux/Mac 必有)
        ProcessBuilder pb = new ProcessBuilder(
                "tar", "xzf", archive.toString(), "-C", targetDir.toString());
        pb.redirectErrorStream(true);
        try {
            Process process = pb.start();
            // 消耗输出避免管道阻塞
            try (InputStream ignored = process.getInputStream()) {
                ignored.readAllBytes();
            }
            int code = process.waitFor();
            if (code != 0) {
                throw new IOException("tar extraction failed with code " + code);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("tar extraction interrupted", e);
        }

        // 设置可执行权限
        for (File f : targetDir.toFile().listFiles()) {
            if (f.isFile() && !f.getName().endsWith(".tgz")) {
                f.setExecutable(true, false);
            }
        }
    }
}
