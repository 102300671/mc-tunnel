package io.mctunnel.forge.gui;

import io.mctunnel.core.tunnel.TunnelInfo;
import io.mctunnel.core.tunnel.TunnelType;
import io.mctunnel.forge.MCTunnelMod;
import io.mctunnel.forge.compat.FCLCompat;
import io.mctunnel.forge.controller.ToolController;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * MC-Tunnel 管理面板 Screen.
 */
public class TunnelScreen extends Screen {

    private final Screen parent;
    private List<TunnelInfo> infos = List.of();
    private String message = "";
    private long messageUntil = 0;
    private boolean backendConnected = false;
    private Button webUiToggle;
    /** 各工具的安装/更新按钮(刷新后动态改标签与可用性) */
    private final java.util.Map<TunnelType, Button> installButtons = new java.util.HashMap<>();
    /** tailscale 守护进程启停按钮(刷新后动态改标签) */
    private Button tailscaleDaemonBtn;

    public TunnelScreen(Screen parent) {
        super(Component.literal("MC-Tunnel 管理面板"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int btnW = 72;
        int y = 45;

        // 顶部: 刷新 + WebUI 开关 + 打开 WebUI + 复制 URL
        addRenderableWidget(Button.builder(Component.literal("刷新状态"), b -> refresh())
                .bounds(cx - 155, y, btnW, 20).build());

        // WebUI 开关仅内嵌模式可用(远程模式下 WebUI 由外部后端管理)
        if (MCTunnelMod.isEmbedded()) {
            webUiToggle = Button.builder(webUiToggleLabel(), b -> toggleWebUi())
                    .bounds(cx - 78, y, btnW, 20).build();
            addRenderableWidget(webUiToggle);
        }

        addRenderableWidget(Button.builder(Component.literal("打开 WebUI"), b -> openWebUi())
                .bounds(cx - 1, y, btnW, 20).build());
        addRenderableWidget(Button.builder(Component.literal("复制 URL"), b -> copyUrl())
                .bounds(cx + 76, y, btnW, 20).build());

        // 每个工具: 名称 + 状态行 + 三个按钮
        y = 85;
        for (TunnelType type : TunnelType.values()) {
            addToolRow(type, cx, y);
            y += 55;
        }

        // 返回
        addRenderableWidget(Button.builder(Component.literal("返回"), b -> onClose())
                .bounds(cx - 50, this.height - 35, 100, 20).build());

        refresh();
    }

    private Component webUiToggleLabel() {
        return Component.literal(MCTunnelMod.isWebUiRunning() ? "WebUI: 开" : "WebUI: 关");
    }

    private void addToolRow(TunnelType type, int cx, int y) {
        // tailscale 多一个[组网]入口:7 个按钮用更窄的宽度
        boolean ts = type == TunnelType.TAILSCALE;
        int btnW = ts ? 50 : 56;
        int gap = ts ? 4 : 5;
        int count = ts ? 7 : 5;
        int totalW = btnW * count + gap * (count - 1);
        int startX = cx - totalW / 2;

        addRenderableWidget(Button.builder(Component.literal("检查更新"), b -> checkUpdate(type))
                .bounds(startX, y, btnW, 20).build());
        Button installBtn = Button.builder(Component.literal("安装"), b -> install(type))
                .bounds(startX + (btnW + gap), y, btnW, 20).build();
        addRenderableWidget(installBtn);
        installButtons.put(type, installBtn);
        addRenderableWidget(Button.builder(Component.literal("启动"), b -> start(type))
                .bounds(startX + (btnW + gap) * 2, y, btnW, 20).build());
        addRenderableWidget(Button.builder(Component.literal("停止"), b -> stop(type))
                .bounds(startX + (btnW + gap) * 3, y, btnW, 20).build());
        addRenderableWidget(Button.builder(Component.literal("位置"), b -> locate(type))
                .bounds(startX + (btnW + gap) * 4, y, btnW, 20).build());
        if (type == TunnelType.TAILSCALE) {
            // 守护进程(tailscaled)启停
            tailscaleDaemonBtn = Button.builder(Component.literal("启服务"),
                            b -> toggleDaemon(type))
                    .bounds(startX + (btnW + gap) * 5, y, btnW, 20).build();
            addRenderableWidget(tailscaleDaemonBtn);
            // 虚拟组网向导(共用账号/分享设备/邀请入网 + 成员指引)
            addRenderableWidget(Button.builder(Component.literal("组网"),
                            b -> this.minecraft.setScreen(new MeshWizardScreen(this)))
                    .bounds(startX + (btnW + gap) * 6, y, btnW, 20).build());
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // 1. 半透明背景(盖住主菜单)
        guiGraphics.fill(0, 0, this.width, this.height, 0xCC000000);

        // 2. 标题 + 连接状态指示器
        guiGraphics.drawCenteredString(this.font, this.title,
                this.width / 2, 18, 0x4ade80);

        // 连接状态: 标题右侧显示圆点 + 文字
        String connText = backendConnected ? "● 已连接" : "● 未连接";
        int connColor = backendConnected ? 0x4ade80 : 0xef4444;
        guiGraphics.drawString(this.font, connText,
                this.width / 2 + 70, 18, connColor, false);

        // FCL 环境标签
        if (FCLCompat.isFcl()) {
            guiGraphics.drawString(this.font, "[FCL/Android]",
                    8, 8, 0x60a5fa, false);
        }

        // 3. 每个工具的状态文字(在按钮上方 20px)
        int y = 70;
        for (TunnelInfo info : infos) {
            String text = info.type() + "  :  " + info.status();
            if (info.localVersion() != null) text += "  v" + info.localVersion();
            if (info.updateAvailable()) text += "  →v" + info.latestVersion() + " 可更新";
            if (info.needsAccount()) {
                text += info.type() == TunnelType.TAILSCALE ? "  [未登录]" : "  [需配置 Authtoken]";
            }
            if (info.type() == TunnelType.TAILSCALE
                    && info.status() != io.mctunnel.core.tunnel.TunnelStatus.NOT_INSTALLED) {
                text += info.daemonRunning() ? "  [守护:运行中]" : "  [守护:未运行]";
            }
            if (info.publicUrl() != null) text += "   " + info.publicUrl();
            if (info.error() != null) text += "   " + info.error();
            int color = switch (info.status()) {
                case RUNNING -> 0x4ade80;
                case ERROR -> 0xef4444;
                case NOT_INSTALLED -> 0x94a3b8;
                default -> 0xfbbf24;
            };
            guiGraphics.drawCenteredString(this.font, Component.literal(text),
                    this.width / 2, y, color);
            y += 55;
        }

        // 4. 提示消息
        if (System.currentTimeMillis() < messageUntil) {
            guiGraphics.drawCenteredString(this.font, Component.literal(message),
                    this.width / 2, this.height - 65, 0xffffff);
        }

        // 5. 后端地址
        ToolController controller = MCTunnelMod.getToolController();
        String backend = controller != null ? controller.describeBackend() : "未连接";
        guiGraphics.drawString(this.font, "后端: " + backend, 8, this.height - 12, 0x888888, false);

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    // ── 操作 ──────────────────────────────────────────────

    private void refresh() {
        ToolController controller = MCTunnelMod.getToolController();
        if (controller == null) {
            backendConnected = false;
            showMessage("控制器未初始化");
            return;
        }
        try {
            infos = controller.status();
            backendConnected = true;
            // 动态更新安装按钮:未安装→"安装";可更新→"更新到x.y.z";已最新→禁用
            for (TunnelInfo info : infos) {
                Button btn = installButtons.get(info.type());
                if (btn == null) continue;
                switch (info.status()) {
                    case NOT_INSTALLED -> {
                        btn.setMessage(Component.literal("安装"));
                        btn.active = true;
                    }
                    default -> {
                        if (info.updateAvailable()) {
                            btn.setMessage(Component.literal("更新→" + info.latestVersion()));
                            btn.active = true;
                        } else if (info.latestVersion() != null) {
                            btn.setMessage(Component.literal("已最新"));
                            btn.active = false;
                        } else {
                            btn.setMessage(Component.literal("安装"));
                            btn.active = true;
                        }
                    }
                }
                // tailscale 守护进程按钮:运行中→"停服务",未运行→"启服务"
                if (info.type() == TunnelType.TAILSCALE && tailscaleDaemonBtn != null) {
                    boolean installed =
                            info.status() != io.mctunnel.core.tunnel.TunnelStatus.NOT_INSTALLED;
                    tailscaleDaemonBtn.setMessage(Component.literal(
                            info.daemonRunning() ? "停服务" : "启服务"));
                    tailscaleDaemonBtn.active = installed;
                }
            }
        } catch (Exception e) {
            backendConnected = false;
            showMessage("刷新失败: " + e.getMessage());
        }
    }

    /** 检查更新(官方渠道),结果体现在状态行与安装按钮上 */
    private void checkUpdate(TunnelType type) {
        ToolController controller = MCTunnelMod.getToolController();
        if (controller == null) return;
        showMessage("检查 " + type + " 更新(官方渠道)...");
        try {
            var check = controller.checkUpdate(type);
            if (!check.installed()) {
                showMessage(type + " 未安装,最新版: "
                        + (check.latestVersion() != null ? check.latestVersion() : "未知"));
            } else if (check.updateAvailable()) {
                showMessage(type + " v" + check.localVersion() + " → v"
                        + check.latestVersion() + ",点[安装]更新");
            } else {
                showMessage(type + " 已是最新版 v" + check.localVersion() + ",直接使用");
            }
            refresh();
        } catch (Exception e) {
            showMessage("检查失败: " + e.getMessage());
        }
    }

    /** 安装/更新:走向导(未装→安装;已知可更新→更新;已最新→提示) */
    private void install(TunnelType type) {
        ToolController controller = MCTunnelMod.getToolController();
        if (controller == null) return;
        TunnelInfo info = findInfo(type);
        if (info != null && info.status() != io.mctunnel.core.tunnel.TunnelStatus.NOT_INSTALLED) {
            if (info.updateAvailable()) {
                this.minecraft.setScreen(new InstallWizardScreen(this, type, true));
            } else if (info.latestVersion() != null) {
                showMessage(type + " 已是最新版,直接使用");
            } else {
                showMessage(type + " 已安装,可先[检查更新]");
            }
            return;
        }
        this.minecraft.setScreen(new InstallWizardScreen(this, type, false));
    }

    private TunnelInfo findInfo(TunnelType type) {
        for (TunnelInfo info : infos) {
            if (info.type() == type) return info;
        }
        return null;
    }

    /** 指定已有工具位置(用户已自行安装的场景) */
    private void locate(TunnelType type) {
        this.minecraft.setScreen(new LocateToolScreen(this, type));
    }

    private void start(TunnelType type) {
        ToolController controller = MCTunnelMod.getToolController();
        if (controller == null) return;
        // ngrok:游戏内固定 TCP(联机),弹配置屏让玩家只填端口,并可设置 Authtoken
        if (type == TunnelType.NGROK) {
            TunnelInfo info = findInfo(type);
            this.minecraft.setScreen(new NgrokStartScreen(this,
                    info == null || !info.needsAccount()));
            return;
        }
        showMessage("启动 " + type + " ...");
        try {
            TunnelInfo info = controller.start(type);
            showMessage(type + " -> " + info.status()
                    + (info.publicUrl() != null ? " " + info.publicUrl() : ""));
            refresh();
        } catch (Exception e) {
            showMessage("启动失败: " + e.getMessage());
        }
    }

    private void stop(TunnelType type) {
        ToolController controller = MCTunnelMod.getToolController();
        if (controller == null) return;
        try {
            TunnelInfo info = controller.stop(type);
            showMessage(type + " 已停止 -> " + info.status());
            refresh();
        } catch (Exception e) {
            showMessage("停止失败: " + e.getMessage());
        }
    }

    /** 启动/停止 tailscaled 守护进程(后台线程执行,避免阻塞界面;Windows 启动 GUI 较慢) */
    private void toggleDaemon(TunnelType type) {
        ToolController controller = MCTunnelMod.getToolController();
        if (controller == null) return;
        TunnelInfo info = findInfo(type);
        boolean start = info == null || !info.daemonRunning();
        runDaemonAction(type, start, null);
    }

    /**
     * 后台执行守护进程启停.
     *
     * @param sudoPassword null=首次(无密码);非 null=用户在 SudoPasswordScreen 输入
     */
    private void runDaemonAction(TunnelType type, boolean start, String sudoPassword) {
        ToolController controller = MCTunnelMod.getToolController();
        if (controller == null) return;
        showMessage((start ? "启动" : "停止") + " tailscaled 守护进程...");
        if (tailscaleDaemonBtn != null) tailscaleDaemonBtn.active = false;
        Thread t = new Thread(() -> {
            try {
                io.mctunnel.core.tunnel.DaemonResult result = sudoPassword == null
                        ? (start ? controller.startDaemon(type) : controller.stopDaemon(type))
                        : (start ? controller.startDaemon(type, sudoPassword)
                                 : controller.stopDaemon(type, sudoPassword));
                this.minecraft.execute(() -> {
                    if (result.needsSudoPassword()) {
                        // 非 root 需要 sudo 密码:打开安全密码输入屏(掩码+保证文案),确认后带密码重试
                        this.minecraft.setScreen(new SudoPasswordScreen(
                                this, type, start,
                                pw -> runDaemonAction(type, start, pw)));
                        return;
                    }
                    showMessage("守护进程: "
                            + (result.info() != null && result.info().daemonRunning()
                                    ? "运行中" : "已停止"));
                    refresh();
                });
            } catch (Exception e) {
                this.minecraft.execute(() -> {
                    // 若密码屏正开着,把错误显示在密码屏上供重试;否则提示在主面板
                    Screen cur = this.minecraft.screen;
                    if (cur instanceof SudoPasswordScreen sp) {
                        sp.showError(e.getMessage() == null ? e.toString() : e.getMessage());
                    } else {
                        showMessage((start ? "启动" : "停止") + "守护进程失败: " + e.getMessage());
                    }
                    refresh();
                });
            }
        }, "mctunnel-daemon");
        t.setDaemon(true);
        t.start();
    }

    /** 切换 WebUI 开关(立即生效并写配置) */
    private void toggleWebUi() {
        boolean running = MCTunnelMod.toggleWebUi();
        if (webUiToggle != null) {
            webUiToggle.setMessage(webUiToggleLabel());
        }
        showMessage(running
                ? "WebUI 已开启: " + webUiUrl()
                : "WebUI 已关闭");
    }

    private void openWebUi() {
        if (MCTunnelMod.isEmbedded() && !MCTunnelMod.isWebUiRunning()) {
            showMessage("WebUI 未启用,请先打开开关");
            return;
        }
        String url = webUiUrl();
        String err = FCLCompat.openBrowser(url);
        if (err == null) {
            showMessage("已打开浏览器: " + url);
        } else {
            showMessage("打开失败,请手动访问: " + url);
        }
    }

    private void copyUrl() {
        String url = webUiUrl();
        try {
            // MC 自带剪贴板
            this.minecraft.keyboardHandler.setClipboard(url);
            showMessage("已复制: " + url);
        } catch (Exception e) {
            showMessage("复制失败: " + e.getMessage());
        }
    }

    private String webUiUrl() {
        ToolController controller = MCTunnelMod.getToolController();
        if (!MCTunnelMod.isEmbedded() && controller != null) {
            return controller.describeBackend();
        }
        int port = MCTunnelMod.getWebUiPort();
        return "http://localhost:" + (port > 0 ? port : 8787);
    }

    private void showMessage(String msg) {
        this.message = msg;
        this.messageUntil = System.currentTimeMillis() + 5000;
    }

    /** 供子屏(如 ngrok 配置屏)返回时在主面板提示 */
    public void flashMessage(String msg) {
        showMessage(msg);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }
}
