package io.mctunnel.core.bootstrap;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 独立运行({@code java -jar mctunnel.jar})的启动入口。
 * <p>
 * 背景:同一个 mctunnel.jar 既是 Forge 模组又是可执行 jar。Forge 启动层自带
 * {@code org.slf4j} 模块,因此 slf4j-api 的 class <b>不能</b>展开打进模组 jar,
 * 否则模块层报 split package(ResolutionException),游戏在加载模组前崩溃。
 * 所以 slf4j-api / slf4j-nop 以完整 jar 的形式作为资源放在 {@code standalone-lib/}
 * 里(Forge 加载时忽略这些资源)。独立运行时由本启动器把它们解包到临时目录,
 * 用一个以 platform classloader 为父的 URLClassLoader 同时加载本 jar 与这些库,
 * 再反射进入真正的入口 {@link io.mctunnel.core.cli.Main}。
 * <p>
 * 本类本身只能使用 JDK API,不能引用任何第三方类。
 */
public final class StandaloneLauncher {

    private static final String REAL_MAIN = "io.mctunnel.core.cli.Main";
    private static final String BUNDLED_LIB_DIR = "standalone-lib";

    private StandaloneLauncher() {
    }

    public static void main(String[] args) throws Exception {
        File self = new File(
                StandaloneLauncher.class.getProtectionDomain()
                        .getCodeSource().getLocation().toURI());

        List<URL> classpath = new ArrayList<>();
        classpath.add(self.toURI().toURL());
        if (self.isFile()) {
            // 从 fat jar 中解包 standalone-lib/*.jar(slf4j-api/nop)
            classpath.addAll(extractBundledLibs(self));
        }
        // 开发环境下 self 是 classes 目录(slf4j 已在 Gradle 运行类路径上),
        // 没有嵌套库时直接用系统类加载器即可
        ClassLoader loader = classpath.size() == 1
                ? ClassLoader.getSystemClassLoader()
                : new URLClassLoader(classpath.toArray(new URL[0]),
                        ClassLoader.getPlatformClassLoader());

        Thread.currentThread().setContextClassLoader(loader);
        Class<?> mainClass = Class.forName(REAL_MAIN, true, loader);
        Method main = mainClass.getMethod("main", String[].class);
        main.invoke(null, (Object) args);
    }

    /**
     * 把自包含 jar 内 {@code standalone-lib/} 下的嵌套 jar 解到临时目录,
     * 返回其 file: URL。JVM 退出时尽力删除(Windows 下运行中锁定属正常)。
     */
    private static List<URL> extractBundledLibs(File selfJar) throws Exception {
        List<URL> urls = new ArrayList<>();
        Path tmp = Files.createTempDirectory("mctunnel-lib");
        tmp.toFile().deleteOnExit();
        try (JarFile jar = new JarFile(selfJar)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory()
                        || !name.startsWith(BUNDLED_LIB_DIR + "/")
                        || !name.endsWith(".jar")) {
                    continue;
                }
                String fileName = name.substring(name.lastIndexOf('/') + 1);
                Path target = tmp.resolve(fileName);
                try (InputStream in = jar.getInputStream(entry)) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
                target.toFile().deleteOnExit();
                urls.add(target.toUri().toURL());
            }
        }
        return urls;
    }
}
