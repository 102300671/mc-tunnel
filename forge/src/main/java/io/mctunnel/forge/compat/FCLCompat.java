package io.mctunnel.forge.compat;

import java.awt.Desktop;
import java.io.File;
import java.net.URI;

/**
 * FCL (FoldCraftLauncher) 环境兼容工具.
 * <p>
 * FCL 在 Android 上运行 MC,通过 AWT 桥接实现了部分 java.awt 功能.
 * 此类负责:
 * - 检测是否运行在 FCL 环境
 * - 打开浏览器(优先 FCL AWT 桥接,回退 am start)
 * - 复制文本到剪贴板
 */
public class FCLCompat {

    private static final Boolean IS_FCL = detectFcl();

    private static Boolean detectFcl() {
        // 1. 手动覆盖
        String override = System.getProperty("mctunnel.fcl");
        if ("true".equalsIgnoreCase(override)) return true;
        if ("false".equalsIgnoreCase(override)) return false;

        // 2. FCL/Pojav 会设置特定系统属性
        String[] fclProps = {
                System.getProperty("fcl.version", ""),
                System.getProperty("molaunch.version", ""),
                System.getProperty("pojav.version", ""),
                System.getProperty("fcl.home", ""),
        };
        for (String p : fclProps) {
            if (!p.isEmpty()) return true;
        }

        // 3. java.library.path 含 fcl/pojav/molaunch
        String libPath = System.getProperty("java.library.path", "")
                + File.pathSeparator + System.getProperty("java.class.path", "");
        String lower = libPath.toLowerCase();
        if (lower.contains("fcl") || lower.contains("pojav") || lower.contains("molaunch")) {
            return true;
        }

        // 4. JVM 参数含 FCL 特征
        String sunCmd = System.getProperty("sun.java.command", "");
        if (sunCmd.toLowerCase().contains("fcl") || sunCmd.toLowerCase().contains("molaunch")) {
            return true;
        }

        // 5. Android 运行时特征: vendor 含 android
        String vendor = System.getProperty("java.vendor", "");
        String vmName = System.getProperty("java.vm.name", "");
        if (vendor.toLowerCase().contains("android") || vmName.toLowerCase().contains("android")) {
            return true;
        }

        // 6. 尝试加载 FCLBridge 类(在 OpenJDK 侧可能不存在,但试一下)
        try {
            Class.forName("com.tungsten.fclauncher.bridge.FCLBridge",
                    false, FCLCompat.class.getClassLoader());
            return true;
        } catch (Throwable ignored) {
        }

        // 7. /sdcard 目录存在 + 非标准 Linux 桌面环境
        if (new File("/sdcard").exists() && !new File("/usr/bin/xdg-open").exists()) {
            return true;
        }

        return false;
    }

    public static boolean isFcl() {
        return Boolean.TRUE.equals(IS_FCL);
    }

    /**
     * 打开浏览器访问指定 URL.
     *
     * @return null 表示成功, 否则返回错误信息
     */
    public static String openBrowser(String url) {
        StringBuilder errors = new StringBuilder();

        // 1. Desktop (FCL AWT 桥接或桌面系统)
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(new URI(url));
                System.out.println("[MC-Tunnel] openBrowser: Desktop.browse OK");
                return null;
            }
        } catch (Throwable t) {
            errors.append("Desktop: ").append(t.getMessage()).append("; ");
        }

        // 2. 再试一次 browse (FCL AWT 桥接可能 isDesktopSupported=false 但 browse 可用)
        try {
            Desktop.getDesktop().browse(new URI(url));
            System.out.println("[MC-Tunnel] openBrowser: browse OK (no isDesktopSupported)");
            return null;
        } catch (Throwable t) {
            errors.append("browse: ").append(t.getMessage()).append("; ");
        }

        // 3. Android am start (用绝对路径, 不依赖 PATH 和 isFcl 检测)
        String[] amPaths = {"/system/bin/am", "/system/xbin/am", "am"};
        for (String am : amPaths) {
            try {
                Process p = new ProcessBuilder(am, "start",
                        "-a", "android.intent.action.VIEW",
                        "-d", url).redirectErrorStream(true).start();
                int code = p.waitFor();
                if (code == 0) {
                    System.out.println("[MC-Tunnel] openBrowser: am OK via " + am);
                    return null;
                }
                errors.append("am(").append(am).append(") exit=").append(code).append("; ");
            } catch (Throwable t) {
                errors.append("am(").append(am).append("): ").append(t.getMessage()).append("; ");
            }
        }

        // 4. Linux xdg-open
        try {
            new ProcessBuilder("xdg-open", url).start();
            System.out.println("[MC-Tunnel] openBrowser: xdg-open OK");
            return null;
        } catch (Throwable t) {
            errors.append("xdg-open: ").append(t.getMessage()).append("; ");
        }

        // 5. Windows
        try {
            new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
            System.out.println("[MC-Tunnel] openBrowser: rundll32 OK");
            return null;
        } catch (Throwable t) {
            errors.append("windows: ").append(t.getMessage()).append("; ");
        }

        System.err.println("[MC-Tunnel] openBrowser failed: " + errors);
        return errors.toString();
    }

    private static boolean isAndroid() {
        return System.getProperty("java.vendor", "").toLowerCase().contains("android")
                || System.getProperty("os.name", "").toLowerCase().contains("android");
    }
}
