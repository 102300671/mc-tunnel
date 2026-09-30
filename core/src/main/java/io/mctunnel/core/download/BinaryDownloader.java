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
import java.nio.file.Paths;
import java.time.Duration;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 工具二进制下载器.
 * <p>
 * 负责从官方 URL 下载工具二进制到本地缓存,并解压.
 * 缓存目录: {user.home}/.mctunnel/binaries/{toolName}/{platform}/
 */
public final class BinaryDownloader {

    private static final Path CACHE_ROOT = Paths.get(
            System.getProperty("user.home"), ".mctunnel", "binaries");

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
        Path toolDir = CACHE_ROOT.resolve(toolName).resolve(platform.name().toLowerCase());
        Files.createDirectories(toolDir);

        // 下载压缩包到临时文件
        String fileName = url.substring(url.lastIndexOf('/') + 1);
        Path archive = toolDir.resolve(fileName);

        if (!Files.exists(archive)) {
            download(url, archive);
        }

        // 解压
        extract(archive, toolDir, fileName);

        return toolDir;
    }

    private void download(String url, Path target) throws IOException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .GET()
                .build();

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
