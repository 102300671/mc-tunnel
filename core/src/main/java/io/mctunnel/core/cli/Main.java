package io.mctunnel.core.cli;

import io.mctunnel.core.DataDir;
import io.mctunnel.core.TunnelCore;
import io.mctunnel.core.chat.ChatServer;
import io.mctunnel.core.chat.MeshManager;
import io.mctunnel.core.network.NatTraversalNetwork;
import io.mctunnel.core.network.Network;
import io.mctunnel.core.network.VirtualNetwork;
import io.mctunnel.core.room.NetworkType;
import io.mctunnel.core.room.RoomManager;
import io.mctunnel.core.room.RoomNetwork;
import io.mctunnel.core.storage.ChatStorage;
import io.mctunnel.core.tunnel.NgrokAdapter;
import io.mctunnel.core.tunnel.SyncThingAdapter;
import io.mctunnel.core.tunnel.TailscaleAdapter;
import io.mctunnel.core.tunnel.TunnelInfo;
import io.mctunnel.core.tunnel.TunnelTool;
import io.mctunnel.core.tunnel.TunnelType;
import io.mctunnel.core.web.WebServer;

import java.io.Console;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * 独立 jar 入口.
 * <p>
 * 用法(模组 jar 同时是独立可执行 jar):
 * <pre>
 * java -jar mctunnel.jar serve                 # 启动 WebUI + 聊天服务
 * java -jar mctunnel.jar --game-dir . serve     # 指定游戏目录
 * java -jar mctunnel.jar install ngrok
 * java -jar mctunnel.jar start ngrok http 25565
 * java -jar mctunnel.jar status
 * </pre>
 * 数据目录与模组共用: {gameDir}/mctunnel/
 */
public final class Main {

    private static Map<TunnelType, TunnelTool> tools;
    private static ChatStorage storage;
    private static RoomManager roomManager;
    private static String nodeId;

    /** 默认云中继主机(初始网络主机),可通过环境变量覆盖 */
    private static final String DEFAULT_RELAY_HOST =
            System.getenv().getOrDefault("MCTUNNEL_RELAY_HOST", "59.110.163.88");
    private static final int DEFAULT_RELAY_PORT =
            Integer.parseInt(System.getenv().getOrDefault("MCTUNNEL_RELAY_PORT", "8721"));

    private Main() {
    }

    public static void main(String[] args) {
        System.out.println(TunnelCore.greet());

        // 解析全局参数,设置数据目录(必须在工具初始化之前)
        args = parseGlobalArgs(args);

        // 检测游戏目录并设置 DataDir
        resolveGameDir();

        // 初始化工具实例(此时 DataDir 已就绪)
        initTools();

        if (args.length == 0) {
            printHelp();
            return;
        }

        String command = args[0].toLowerCase();
        String[] rest = Arrays.copyOfRange(args, 1, args.length);

        try {
            switch (command) {
                case "install" -> cmdInstall(rest);
                case "locate" -> cmdLocate(rest);
                case "daemon" -> cmdDaemon(rest);
                case "mesh" -> cmdMesh(rest);
                case "start" -> cmdStart(rest);
                case "stop" -> cmdStop(rest);
                case "status" -> cmdStatus();
                case "serve" -> cmdServe();
                case "room" -> cmdRoom(rest);
                case "network" -> cmdNetwork(rest);
                case "help" -> printHelp();
                default -> {
                    System.err.println("Unknown command: " + command);
                    printHelp();
                    System.exit(1);
                }
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(2);
        }
    }

    // ── 全局参数 & 数据目录 ──────────────────────────────

    /**
     * 解析并剥离全局参数(目前仅 --game-dir).
     *
     * @return 剩余的命令参数
     */
    private static String[] parseGlobalArgs(String[] args) {
        var remaining = new java.util.ArrayList<String>();
        for (int i = 0; i < args.length; i++) {
            if ("--game-dir".equals(args[i]) && i + 1 < args.length) {
                System.setProperty("mctunnel.gameDir", args[i + 1]);
                i++; // 跳过值
            } else {
                remaining.add(args[i]);
            }
        }
        return remaining.toArray(new String[0]);
    }

    /**
     * 检测游戏目录并设置 DataDir.
     * 优先级: --game-dir 参数 > 环境变量 > jar 所在 mods/ 的上一级 > CWD 下有 .minecraft > ~/.minecraft > CWD
     */
    private static void resolveGameDir() {
        // 1. 显式指定
        String explicit = System.getProperty("mctunnel.gameDir");
        if (explicit == null || explicit.isBlank()) {
            explicit = System.getenv("MCTUNNEL_GAME_DIR");
        }

        Path gameDir;
        if (explicit != null && !explicit.isBlank()) {
            gameDir = Paths.get(explicit).toAbsolutePath();
        } else {
            // 2. jar 自身位置: 若在 mods/ 内,游戏目录是上一级
            Path jarDir = getJarDir();
            if (jarDir != null && "mods".equalsIgnoreCase(jarDir.getFileName().toString())) {
                gameDir = jarDir.getParent();
            } else {
                // 3. CWD 下有 .minecraft?
                Path cwd = Paths.get(".").toAbsolutePath();
                if (Files.isDirectory(cwd.resolve(".minecraft"))) {
                    gameDir = cwd.resolve(".minecraft");
                } else if (Files.isDirectory(Paths.get(System.getProperty("user.home"), ".minecraft"))) {
                    // 4. 默认安装位置 ~/.minecraft
                    gameDir = Paths.get(System.getProperty("user.home"), ".minecraft");
                } else {
                    // 5. 回退到 CWD
                    gameDir = cwd;
                }
            }
        }

        DataDir.set(gameDir.resolve("mctunnel"));
        System.out.println("[MC-Tunnel] Data dir: " + DataDir.get());
    }

    /** 获取 jar 所在目录(非 jar 场景返回 null) */
    private static Path getJarDir() {
        try {
            var loc = Main.class.getProtectionDomain().getCodeSource().getLocation();
            if (loc == null) return null;
            Path p = Paths.get(loc.toURI());
            return Files.isDirectory(p) ? p : p.getParent();
        } catch (Exception e) {
            return null;
        }
    }

    private static void initTools() {
        storage = new ChatStorage();
        nodeId = System.getenv().getOrDefault("MCTUNNEL_NODE_ID",
                "node-" + UUID.randomUUID().toString().substring(0, 8));
        // EnumMap:遍历顺序固定为枚举声明顺序(ngrok→tailscale→syncthing),
        // 否则 HashMap 乱序会让 GUI 状态行与按钮行错位(组网按钮被看成挂在 ngrok 行)
        tools = new java.util.EnumMap<>(TunnelType.class);
        NgrokAdapter ngrok = new NgrokAdapter();
        TailscaleAdapter tailscale = new TailscaleAdapter();
        SyncThingAdapter syncthing = new SyncThingAdapter();
        // 绑定持久化配置存储(记录 ngrok 安装目录/二进制路径等)
        ngrok.setConfigStore(storage);
        tailscale.setConfigStore(storage);
        syncthing.setConfigStore(storage);
        tools.put(TunnelType.NGROK, ngrok);
        tools.put(TunnelType.TAILSCALE, tailscale);
        tools.put(TunnelType.SYNCTHING, syncthing);
    }

    // ── 命令 ──────────────────────────────────────────────

    /**
     * 交互式安装/更新.
     * <pre>
     * install &lt;tool&gt; [--yes] [--extract-dir &lt;path&gt;] [--authtoken &lt;token&gt;]
     * </pre>
     * 流程:先检查本地已装版本与官方最新版 →
     * 已装且最新直接使用;已装但非最新提供更新;未装提供安装.
     * Windows 上 ngrok 会询问解压位置,首装完成后引导注册/登录并配置 authtoken.
     */
    private static void cmdInstall(String[] args) throws IOException {
        // 拆分 flags
        String toolName = args.length > 0 ? args[0] : null;
        boolean yes = false;
        String extractDir = null;
        String authtoken = null;
        for (int i = (toolName == null ? 0 : 1); i < args.length; i++) {
            switch (args[i]) {
                case "--yes", "-y" -> yes = true;
                case "--extract-dir" -> extractDir = ++i < args.length ? args[i] : null;
                case "--authtoken" -> authtoken = ++i < args.length ? args[i] : null;
                default -> {
                    if (toolName == null) toolName = args[i];
                }
            }
        }
        if (toolName == null) {
            System.err.println("Usage: install <tool> [--yes] [--extract-dir <path>] [--authtoken <token>]");
            System.exit(1);
        }
        TunnelTool tool = resolveTool(new String[]{toolName});

        java.util.Scanner in = new java.util.Scanner(System.in);

        boolean installed = tool.isInstalled();
        // 用户已在交互中选择"下载安装"(避免后面再问一次"是否安装?")
        boolean choseInstall = false;

        // 1. PATH/记录/默认位置都没找到 → 让用户选:
        //    装了但没配 PATH → 指定路径;没装过 → 下载安装
        if (!installed && !yes) {
            System.out.println("PATH 与默认安装位置均未找到 " + tool.getType().getDisplayName() + "。");
            System.out.println("  1) 我已下载过但没配 PATH,指定它的位置(可执行文件或所在目录)");
            System.out.println("  2) 没下载过,从官方渠道下载安装最新版");
            if ("1".equals(prompt(in, "请选择 [1/2]", "2"))) {
                String p = prompt(in, "请输入可执行文件完整路径或所在目录", "");
                if (p.isBlank()) {
                    System.out.println("已取消。");
                    return;
                }
                try {
                    tool.setBinaryPath(Paths.get(p.trim()));
                } catch (IOException e) {
                    System.err.println("指定路径无效: " + e.getMessage());
                    return;
                }
                installed = true;
                System.out.println("已使用该位置: " + tool.getInfo());
            } else {
                choseInstall = true;
            }
        }

        // 2. 检查本地 vs 官方最新
        System.out.println("正在从官方渠道检查最新版本...");
        String local = null;
        String latest = null;
        try {
            io.mctunnel.core.tunnel.UpdateCheck check = tool.checkUpdate();
            local = check.localVersion();
            latest = check.latestVersion();
        } catch (IOException e) {
            System.err.println("[警告] 检查最新版本失败: " + e.getMessage());
            System.err.println("将尝试直接安装(官方下载地址始终指向最新版)。");
        }

        boolean updateAvailable = installed && latest != null
                && io.mctunnel.core.util.VersionCompare.isNewer(latest, local);

        // 3. 已装且最新 → 直接使用(账户未配置时仍会引导)
        if (installed && !updateAvailable) {
            System.out.println(tool.getType() + " 已是最新版"
                    + (local != null ? " v" + local : "") + ",直接使用。");
            accountGuideIfNeeded(in, tool, authtoken);
            return;
        }

        // 4. 已装但非最新 → 提供更新选项
        if (installed) {
            System.out.println("已安装: v" + local + "   官方最新: v" + latest);
            if (!yes && !confirm(in, "是否更新到 v" + latest + "?")) {
                accountGuideIfNeeded(in, tool, authtoken);
                return;
            }
        } else {
            // 5. 未安装 → 提供安装选项(用户已在前面选过"下载安装"时不再重复询问)
            System.out.println("未安装。官方最新版: " + (latest != null ? "v" + latest : "未知"));
            if (!yes && !choseInstall && !confirm(in, "是否安装?")) return;
        }

        // 6. Windows ngrok:询问解压位置
        io.mctunnel.core.tunnel.InstallOptions options =
                io.mctunnel.core.tunnel.InstallOptions.DEFAULT;
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        if (tool.getType() == TunnelType.NGROK && isWindows && extractDir == null) {
            extractDir = prompt(in, "请输入 ngrok 解压位置", "C:\\ngrok");
        }
        if (extractDir != null) {
            options = io.mctunnel.core.tunnel.InstallOptions.withExtractDir(extractDir);
        }

        // 7. 安装/更新(官方渠道)
        System.out.println((installed ? "更新" : "安装") + " "
                + tool.getType().getDisplayName() + " ...(官方渠道下载)");
        if (installed) {
            tool.update(options);
        } else {
            tool.install(options);
        }
        System.out.println("完成: " + tool.getInfo());

        // 8. 账户引导(内部先检查配置/登录状态,已就绪则不问)
        accountGuideIfNeeded(in, tool, authtoken);
    }

    /** 账户引导入口:ngrok 检查 authtoken,tailscale 检查登录态,已就绪则跳过 */
    private static void accountGuideIfNeeded(java.util.Scanner in, TunnelTool tool, String authtoken) {
        if (tool.getType() == TunnelType.NGROK) {
            ngrokAccountFlow(in, (NgrokAdapter) tool, authtoken);
        } else if (tool.getType() == TunnelType.TAILSCALE) {
            tailscaleLoginFlow(in, (TailscaleAdapter) tool);
        }
    }

    // ── Tailscale 虚拟组网向导 ───────────────────────────

    /** Tailscale 官方令牌生成页 */
    private static final String TS_KEYS_URL =
            "https://login.tailscale.com/admin/settings/keys";

    private static void cmdMesh(String[] rest) throws IOException {
        TailscaleAdapter ts = (TailscaleAdapter) tools.get(TunnelType.TAILSCALE);
        String sub = rest.length == 0 ? "wizard" : rest[0].toLowerCase();
        String arg = rest.length > 1 ? String.join(",", Arrays.copyOfRange(rest, 1, rest.length)) : "";
        switch (sub) {
            case "wizard" -> meshWizard(ts);
            case "token" -> {
                if (arg.isBlank()) {
                    System.err.println("Usage: mesh token <tskey-api-...>");
                    System.exit(1);
                }
                ts.saveApiToken(arg.trim());
                System.out.println("API 令牌已验证并保存。");
            }
            case "share" -> System.out.println(ts.shareSelfTo(java.util.List.of(arg)));
            case "invite" -> System.out.println(ts.inviteToTailnet(java.util.List.of(arg)));
            case "clear-token" -> {
                ts.clearApiToken();
                System.out.println("令牌已清除。");
            }
            case "join" -> printMemberGuide();
            default -> {
                System.err.println("Unknown subcommand: " + sub);
                System.err.println("Usage: mesh [wizard|token <key>|share <emails>|invite <emails>|join]");
                System.exit(1);
            }
        }
    }

    /** 交互式组网向导 */
    private static void meshWizard(TailscaleAdapter ts) {
        var in = new java.util.Scanner(System.in, StandardCharsets.UTF_8);
        System.out.println();
        System.out.println("===== Tailscale 虚拟组网向导 =====");
        System.out.println("1. 房主 —— 我要开 MC 服务端,组织大家联机");
        System.out.println("2. 成员 —— 我要加入别人的房间");
        String role = prompt(in, "请选择 [1/2]", "1");

        if ("2".equals(role)) {
            printMemberGuide();
            return;
        }

        System.out.println();
        System.out.println("房主选择组网方式:");
        System.out.println("1. 共用账号:房主创建账号,所有人登录同一个 Tailscale 账号");
        System.out.println("   (最简单;但成员能看到账号下所有设备)");
        System.out.println("2. 分享设备:成员各自注册账号,房主只把开服这台设备分享给他们");
        System.out.println("   (需要 API 令牌;按邮箱发送,成员只能连被分享的设备)");
        System.out.println("3. tailnet 邀请:成员各自注册账号,房主邀请他们加入同一 tailnet");
        System.out.println("   (需要 API 令牌;成员成为 member,互通且看不到管理后台)");
        String mode = prompt(in, "请选择 [1/2/3]", "1");

        switch (mode) {
            case "1" -> meshModeSharedAccount(in, ts);
            case "2" -> meshModeShare(in, ts);
            case "3" -> meshModeInvite(in, ts);
            default -> System.out.println("无效选项,已取消。");
        }
    }

    /** 方式一:共用账号,房主登录即可,成员也登录同一账号 */
    private static void meshModeSharedAccount(java.util.Scanner in, TailscaleAdapter ts) {
        System.out.println();
        tailscaleLoginFlow(in, ts);
        String ip = ts.getOwnTailscaleIp();
        System.out.println();
        System.out.println("接下来让每位成员:");
        System.out.println("  1) 安装 Tailscale 后运行: tailscale login");
        System.out.println("  2) 用同一个账号登录(房主把登录方式告诉成员即可)");
        if (ip != null && !ip.isBlank()) {
            System.out.println("  3) MC 多人游戏 → 直接连接 → " + ip);
        }
    }

    /** 方式二:分享本机给成员邮箱 */
    private static void meshModeShare(java.util.Scanner in, TailscaleAdapter ts) {
        System.out.println();
        try {
            ensureMeshToken(in, ts);
            String emails = promptEmails(in);
            System.out.println(ts.shareSelfTo(java.util.List.of(emails)));
            System.out.println();
            System.out.println("成员操作:打开收到的 Tailscale 邮件 → 接受分享 → "
                    + "在 MC 中直接连接上面的 Tailscale IP。");
            System.out.println("提示:被分享设备默认隔离(quarantine),成员只能连入本机,看不到你的其他设备。");
        } catch (IOException e) {
            System.err.println("分享失败: " + e.getMessage());
        }
    }

    /** 方式三:邀请成员加入同一 tailnet */
    private static void meshModeInvite(java.util.Scanner in, TailscaleAdapter ts) {
        System.out.println();
        try {
            ensureMeshToken(in, ts);
            String emails = promptEmails(in);
            System.out.println(ts.inviteToTailnet(java.util.List.of(emails)));
            System.out.println();
            System.out.println("成员操作:打开邀请邮件 → 接受邀请 → 运行 tailscale login 登录自己的账号");
            System.out.println("之后 tailnet 内设备互通,在 MC 中直接连接房主的 Tailscale IP。");
        } catch (IOException e) {
            System.err.println("邀请失败: " + e.getMessage());
        }
    }

    /** 确保已有 API 令牌:已保存则询问是否复用,否则引导生成并输入 */
    private static void ensureMeshToken(java.util.Scanner in, TailscaleAdapter ts)
            throws IOException {
        if (ts.hasApiToken()) {
            System.out.println("检测到已保存的 API 令牌。");
            if (confirm(in, "使用已保存的令牌?")) {
                return;
            }
        }
        System.out.println("需要 Tailscale API 访问令牌(不是账号密码):");
        System.out.println("  1) 浏览器打开: " + TS_KEYS_URL);
        System.out.println("  2) Generate API access token,复制 tskey-api- 开头的字符串");
        openBrowser(TS_KEYS_URL);
        String token;
        Console console = System.console();
        if (console != null) {
            token = new String(console.readPassword("粘贴令牌(输入不显示,直接回车取消): "));
        } else {
            System.out.print("粘贴令牌(直接回车取消): ");
            token = in.hasNextLine() ? in.nextLine() : "";
        }
        token = token.trim();
        if (token.isEmpty()) {
            throw new IOException("未输入令牌,已取消");
        }
        ts.saveApiToken(token);
        System.out.println("令牌有效,已保存。");
    }

    private static String promptEmails(java.util.Scanner in) {
        System.out.println();
        System.out.print("输入成员邮箱(多个用逗号分隔): ");
        return in.hasNextLine() ? in.nextLine() : "";
    }

    /** 成员侧指引(三种入队方式) */
    private static void printMemberGuide() {
        System.out.println();
        System.out.println("===== 成员加入指引 =====");
        System.out.println("先安装 Tailscale 并登录,然后按房主使用的方式操作:");
        System.out.println("· 方式1 共用账号:运行 tailscale login,登录房主提供的同一个账号");
        System.out.println("· 方式2 设备分享:打开 Tailscale 发来的分享邮件 → 接受,");
        System.out.println("  本机出现在你的客户端后,MC 直接连接房主给的 100.x.x.x 地址");
        System.out.println("· 方式3 tailnet 邀请:打开邀请邮件 → 接受邀请 → tailscale login 登录自己的账号,");
        System.out.println("  再用房主给的 100.x.x.x 地址联机");
        System.out.println("成员也需要保持 Tailscale 客户端在线(状态 Connected)。");
    }

    /** tailscale 首装后的登录引导:询问后触发 tailscale login(阻塞等待浏览器授权) */
    private static void tailscaleLoginFlow(java.util.Scanner in, TailscaleAdapter tailscale) {
        System.out.println();
        if (tailscale.isLoggedIn()) {
            System.out.println("Tailscale 已登录,无需操作。");
            return;
        }
        System.out.println("Tailscale 需要登录账户才能加入虚拟组网。");
        if (!confirm(in, "是否现在登录 Tailscale 账户?(tailscale login 会打开浏览器授权)")) {
            System.out.println("已跳过。稍后可在 WebUI/游戏内面板点[登录],或手动运行: tailscale login");
            return;
        }
        System.out.println("正在执行 tailscale login,请在浏览器中完成授权(最长等待 180s)...");
        try {
            tailscale.login();
            System.out.println(tailscale.isLoggedIn() ? "登录成功。" : "登录流程已结束。");
        } catch (IOException e) {
            System.err.println("登录失败: " + e.getMessage());
        }
    }

    /** ngrok 首装后的账户引导:询问是否注册/登录,打开官网,收集 authtoken */
    private static void ngrokAccountFlow(java.util.Scanner in, NgrokAdapter ngrok, String authtoken) {
        System.out.println();
        if (ngrok.isAccountConfigured()) {
            System.out.println("ngrok 已配置 Authtoken,无需注册/登录。");
            return;
        }
        System.out.println("ngrok 需要配置账户的 Authtoken 才能启动隧道:");
        System.out.println("  · 未登录: 无法启动隧道(免费账户也需要 Authtoken)");
        System.out.println("  · 登录后: 获得 Authtoken,可正常开隧道");
        if (authtoken != null && !authtoken.isBlank()) {
            ngrok.configure(Map.of("authtoken", authtoken));
            System.out.println("Authtoken 已配置。");
            return;
        }
        if (!confirm(in, "是否现在注册/登录 ngrok 账户?")) {
            System.out.println("已跳过。稍后可运行: install ngrok --authtoken <token> 或在 WebUI 中设置。");
            return;
        }
        // 打开官网(带失败提示)
        String signupUrl = "https://dashboard.ngrok.org/signup";
        String err = openBrowser(signupUrl);
        if (err == null) {
            System.out.println("已打开官网: " + signupUrl);
        } else {
            System.out.println("无法自动打开浏览器,请手动访问: " + signupUrl);
        }
        System.out.println("(若浏览器未打开,请复制上面链接手动打开)");
        System.out.println("登录后进入 Dashboard → Cloud Edge → Authtoken,复制粘贴到下面:");
        String token = prompt(in, "Authtoken (直接回车跳过)", "");
        if (token != null && !token.isBlank()) {
            ngrok.configure(Map.of("authtoken", token.trim()));
            System.out.println("Authtoken 已配置(ngrok config add-authtoken)。");
        } else {
            System.out.println("已跳过。");
        }
    }

    /** 打开浏览器;返回 null 成功,否则错误信息 */
    private static String openBrowser(String url) {
        try {
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().browse(new java.net.URI(url));
                return null;
            }
        } catch (Exception ignored) {
        }
        String[][] cmds = {
                {"xdg-open", url},
                {"open", url},
                {"rundll32", "url.dll,FileProtocolHandler", url},
        };
        for (String[] cmd : cmds) {
            try {
                new ProcessBuilder(cmd).start();
                return null;
            } catch (IOException ignored) {
            }
        }
        return "no browser available";
    }

    private static boolean confirm(java.util.Scanner in, String question) {
        System.out.print(question + " [y/N] ");
        String line = in.hasNextLine() ? in.nextLine().trim().toLowerCase() : "";
        return line.equals("y") || line.equals("yes");
    }

    private static String prompt(java.util.Scanner in, String label, String def) {
        System.out.print(label + (def.isEmpty() ? ": " : " [" + def + "]: "));
        if (!in.hasNextLine()) return def;
        String line = in.nextLine().trim();
        return line.isEmpty() ? def : line;
    }

    /**
     * 指定已有工具位置: {@code locate <tool> <可执行文件或所在目录>}
     * 用于用户已自行安装工具的场景,验证后直接使用,不再下载安装.
     */
    private static void cmdLocate(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("Usage: locate <tool> <可执行文件完整路径或所在目录>");
            System.err.println("Example: locate tailscale \"C:\\Program Files\\Tailscale\"");
            System.exit(1);
        }
        TunnelTool tool = resolveTool(new String[]{args[0]});
        tool.setBinaryPath(Paths.get(args[1]));
        System.out.println("已记录并生效: " + tool.getInfo());
    }

    /**
     * 守护进程管理: {@code daemon <start|stop|status> <tool>}(主要用于 tailscale/tailscaled)
     */
    private static void cmdDaemon(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("Usage: daemon <start|stop|status> <tool>");
            System.err.println("Example: daemon start tailscale");
            System.exit(1);
        }
        String action = args[0].toLowerCase();
        TunnelTool tool = resolveTool(new String[]{args[1]});
        try {
            switch (action) {
                case "start" -> {
                    System.out.println("启动 " + tool.getType() + " 守护进程...");
                    daemonControlWithPassword(tool, true);
                    System.out.println("完成: " + tool.getInfo());
                }
                case "stop" -> {
                    System.out.println("停止 " + tool.getType() + " 守护进程...");
                    daemonControlWithPassword(tool, false);
                    System.out.println("完成: " + tool.getInfo());
                }
                case "status" -> System.out.println(
                        tool.getType() + " 守护进程: " + (tool.isDaemonRunning() ? "运行中" : "未运行"));
                default -> {
                    System.err.println("未知操作: " + action + "(应为 start|stop|status)");
                    System.exit(1);
                }
            }
        } catch (IOException e) {
            System.err.println("操作失败: " + e.getMessage());
            System.exit(2);
        }
    }

    /**
     * 执行守护进程启停,非 root 需要 sudo 密码时安全收集(终端不回显,不记录不持久化,
     * 仅经 sudo 的 stdin 使用一次).密码错误允许重试 3 次.
     */
    private static void daemonControlWithPassword(TunnelTool tool, boolean start) throws IOException {
        for (int attempt = 0; ; attempt++) {
            try {
                if (attempt == 0) {
                    if (start) {
                        tool.startDaemon();
                    } else {
                        tool.stopDaemon();
                    }
                } else {
                    char[] pw = readSudoPassword(start ? "启动" : "停止");
                    try {
                        if (start) {
                            tool.startDaemon(pw);
                        } else {
                            tool.stopDaemon(pw);
                        }
                    } finally {
                        // readSudoPassword 返回数组的所有权交给适配器,它用后会清空;
                        // 防御性再清一次
                        if (pw != null) {
                            java.util.Arrays.fill(pw, '\0');
                        }
                    }
                }
                return;
            } catch (io.mctunnel.core.tunnel.SudoPasswordRequiredException e) {
                if (attempt >= 3) {
                    throw new IOException("需要 sudo 密码,重试次数过多");
                }
                System.out.println(e.getMessage());
            } catch (IOException e) {
                // 密码错误/鉴权被拒 → 重新输入重试;其余错误直接上抛
                if (e.getMessage() != null && e.getMessage().contains("sudo 密码错误")
                        && attempt < 3) {
                    System.out.println(e.getMessage() + ",请重试。");
                } else {
                    throw e;
                }
            }
        }
    }

    /** 从终端不回显读取 sudo 密码 */
    private static char[] readSudoPassword(String actionText) throws IOException {
        Console console = System.console();
        if (console == null) {
            throw new IOException("当前环境没有交互式终端,无法输入 sudo 密码;"
                    + "请在终端中手动执行: sudo systemctl "
                    + ("启动".equals(actionText) ? "start" : "stop") + " tailscaled");
        }
        System.out.println("提示:密码仅用于本次 sudo systemctl,不会显示、不会被记录或保存。");
        char[] pw = console.readPassword("请输入 sudo 密码: ");
        if (pw == null || pw.length == 0) {
            throw new IOException("未输入密码");
        }
        return pw;
    }

    private static void cmdStart(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("Usage: start <tool> [args...]");
            System.err.println("Example: start ngrok http 25565");
            System.err.println("Example: start tailscale");
            System.exit(1);
        }
        TunnelTool tool = resolveTool(new String[]{args[0]});
        String[] toolArgs = args.length > 1
                ? Arrays.copyOfRange(args, 1, args.length)
                : new String[0];

        if (!tool.isInstalled()) {
            System.out.println("Not installed, installing...");
            try {
                tool.install();
            } catch (io.mctunnel.core.tunnel.ExtractDirRequiredException e) {
                System.err.println("Windows ngrok needs an extract dir. Run first:");
                System.err.println("  install ngrok --extract-dir <path>");
                System.exit(1);
            }
        }

        TunnelInfo info = tool.start(toolArgs);
        System.out.println("Started: " + info);
    }

    private static void cmdStop(String[] args) {
        TunnelTool tool = resolveTool(args);
        tool.stop();
        System.out.println("Stopped: " + tool.getInfo());
    }

    private static void cmdStatus() {
        for (TunnelTool tool : tools.values()) {
            System.out.println(tool.getInfo());
        }
    }

    private static void cmdServe() throws IOException, InterruptedException {
        // 复用 initTools 中初始化的 nodeId / storage
        MeshManager mesh = new MeshManager(nodeId);
        // 房间管理器(初始网络主机)
        RoomManager rm = new RoomManager(nodeId, System.getProperty("user.name"), storage);
        rm.loadLastRoom();

        WebServer web = new WebServer(tools, rm);
        web.start();
        ChatServer chat = new ChatServer(nodeId, storage, mesh);
        chat.start();

        // 从存储加载已知对等节点并连接
        for (ChatStorage.NodeInfo node : storage.loadNodes()) {
            if (node.address() != null && !node.address().isBlank()) {
                mesh.addPeer(node.address());
                System.out.println("[MC-Tunnel] Connecting to peer: " + node.nodeId()
                        + " @ " + node.address());
            }
        }

        // 支持通过环境变量 MCTUNNEL_PEERS=ws://a:8788,ws://b:8788 添加对等节点
        String peersEnv = System.getenv("MCTUNNEL_PEERS");
        if (peersEnv != null && !peersEnv.isBlank()) {
            for (String peer : peersEnv.split(",")) {
                String p = peer.trim();
                if (!p.isEmpty()) {
                    mesh.addPeer(p);
                    storage.upsertNode("peer-" + p, p);
                }
            }
        }

        System.out.println("Node ID: " + nodeId);
        System.out.println("Storage: " + (storage.isPersistent() ? "SQLite" : "in-memory"));
        System.out.println("WebUI + Chat running. Press Ctrl+C to stop.");

        // 注册关闭钩子,清理资源
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            mesh.shutdown();
            storage.close();
            web.stop();
            chat.stop();
        }));

        // 主线程阻塞,等待中断
        Thread.currentThread().join();
    }

    private static TunnelTool resolveTool(String[] args) {
        if (args.length == 0) {
            System.err.println("Usage: <tool> (ngrok|tailscale|syncthing)");
            System.exit(1);
        }
        TunnelType type;
        try {
            type = TunnelType.valueOf(args[0].toUpperCase());
        } catch (IllegalArgumentException e) {
            System.err.println("Unknown tool: " + args[0]);
            System.exit(1);
            return null;
        }
        TunnelTool tool = tools.get(type);
        if (tool == null) {
            System.err.println("Tool not yet implemented: " + type);
            System.exit(1);
        }
        return tool;
    }

    // ── 房间 & 网络命令 ────────────────────────────────────

    private static void cmdRoom(String[] args) throws IOException {
        if (args.length == 0) {
            System.out.println("Usage: room <create|join|leave|list> [args...]");
            return;
        }
        String sub = args[0].toLowerCase();
        switch (sub) {
            case "create" -> {
                String name = args.length > 1 ? args[1] : "MC-Tunnel 房间";
                ensureRoomManager();
                String link = roomManager.createRoom(DEFAULT_RELAY_HOST, DEFAULT_RELAY_PORT, name);
                System.out.println("房间已创建: " + name);
                System.out.println("加入链接(发给成员): " + link);
            }
            case "join" -> {
                if (args.length < 2) {
                    System.err.println("Usage: room join <link>");
                    return;
                }
                ensureRoomManager();
                roomManager.joinRoom(args[1]);
                System.out.println("已加入房间: " + roomManager.getCurrentRoom().name());
                System.out.println("成员: " + roomManager.getMembers().size());
            }
            case "leave" -> {
                if (roomManager != null) roomManager.leaveRoom();
                System.out.println("已离开房间");
            }
            case "list" -> {
                if (roomManager == null || roomManager.getCurrentRoom() == null) {
                    System.out.println("当前未加入任何房间");
                    return;
                }
                var r = roomManager.getCurrentRoom();
                System.out.println("房间: " + r.name() + " (id=" + r.id() + ")");
                System.out.println("成员:");
                for (var m : roomManager.getMembers()) {
                    System.out.println("  - " + m.displayName() + " (" + m.nodeId() + ")");
                }
            }
            default -> System.err.println("Unknown room subcommand: " + sub);
        }
    }

    private static void cmdNetwork(String[] args) throws IOException {
        ensureRoomManager();
        if (roomManager.getCurrentRoom() == null) {
            System.err.println("请先加入或创建房间");
            return;
        }
        if (args.length == 0) {
            System.out.println("Usage: network <create|list> [args...]");
            return;
        }
        String sub = args[0].toLowerCase();
        switch (sub) {
            case "create" -> {
                if (args.length < 3) {
                    System.err.println("Usage: network create <nat|virtual> <name>");
                    return;
                }
                NetworkType type = "virtual".equalsIgnoreCase(args[1])
                        ? NetworkType.VIRTUAL : NetworkType.NAT_TRAVERSAL;
                String name = args[2];
                Network net;
                if (type == NetworkType.NAT_TRAVERSAL) {
                    net = new NatTraversalNetwork((NgrokAdapter) tools.get(TunnelType.NGROK));
                } else {
                    net = new VirtualNetwork((TailscaleAdapter) tools.get(TunnelType.TAILSCALE));
                }
                RoomNetwork data = net.create(roomManager.getCurrentRoom(), name);
                roomManager.registerNetwork(data);
                System.out.println("网络已创建: " + name + " (" + type + ", id=" + data.id() + ")");
                System.out.println("如需启动: 调用 network start " + data.id());
            }
            case "list" -> {
                var nets = roomManager.getNetworks();
                if (nets.isEmpty()) {
                    System.out.println("当前房间暂无网络");
                    return;
                }
                for (RoomNetwork n : nets) {
                    System.out.println("  - " + n.id() + " [" + n.type() + "/" + n.status()
                            + "] endpoint=" + (n.endpoint() == null ? "-" : n.endpoint()));
                }
            }
            default -> System.err.println("Unknown network subcommand: " + sub);
        }
    }

    private static void ensureRoomManager() {
        if (roomManager == null) {
            roomManager = new RoomManager(nodeId, System.getProperty("user.name"), storage);
            // 从存储恢复上次加入的房间(不重连中继,仅恢复上下文)
            roomManager.loadLastRoom();
        }
    }

    private static void printHelp() {
        System.out.println("""
                MC-Tunnel CLI
                Global options:
                  --game-dir <path>   Specify game directory (default: auto-detect)

                Commands:
                  install <tool> [options]  Install/update a tool (interactive check first)
                                            --yes / -y              Skip confirm prompts
                                            --extract-dir <path>    Windows ngrok extract dir
                                            --authtoken <token>     Configure ngrok token after install
                  locate <tool> <path>    Use an existing tool (binary path or its directory)
                  daemon <start|stop|status> <tool>  Manage background daemon (tailscaled)
                  mesh [wizard|token|share|invite|join]  Tailscale virtual-network wizard
                  start <tool> [args...]  Start a tool (e.g. start ngrok http 25565)
                  stop <tool>             Stop a running tool
                  status                  Show status of all tools
                  serve                   Start the WebUI dashboard
                  room <create|join|leave|list> [args]
                                          Manage rooms (cloud relay: 59.110.163.88:8721)
                                            room create [name]       Create a room, print join link
                                            room join <link>         Join a room via link
                                            room leave               Leave current room
                                            room list                 Show current room & members
                  network <create|list> [args]
                                          Manage networks in the current room
                                            network create <nat|virtual> <name>
                                            network list
                  help                    Show this help
                Tools: ngrok, tailscale, syncthing""");
    }
}
