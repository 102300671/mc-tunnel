package io.mctunnel.forge.gui;

import io.mctunnel.core.tunnel.ExtractDirRequiredException;
import io.mctunnel.core.tunnel.InstallOptions;
import io.mctunnel.core.tunnel.TunnelType;
import io.mctunnel.forge.MCTunnelMod;
import io.mctunnel.forge.compat.FCLCompat;
import io.mctunnel.forge.controller.ToolController;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Map;

/**
 * 安装/更新向导 Screen.
 * <p>
 * 流程(后端驱动,按需出现):
 * <ol>
 *   <li>安装尝试 → 若 Windows ngrok 缺解压目录 → 输入目录 → 重试</li>
 *   <li>安装完成后(ngrok):询问是否注册/登录账户 → 打开官网 → 输入 Authtoken</li>
 *   <li>安装完成后(tailscale):询问是否登录 → 触发 tailscale login(浏览器授权)</li>
 * </ol>
 */
public class InstallWizardScreen extends Screen {

    private static final String SIGNUP_URL = "https://dashboard.ngrok.org/signup";

    private enum Step { INSTALLING, ASK_DIR, ASK_ACCOUNT, ASK_TOKEN, FINISHED }

    private final Screen parent;
    private final TunnelType type;
    private final boolean update;

    private Step step = Step.INSTALLING;
    private String statusLine = "";
    private String errorLine = "";
    /** null=进行中 true=成功 false=失败(后台安装线程写入) */
    private volatile Boolean installResult;
    private volatile String installError = "";
    /** 安装/更新完成后的工具信息(用于判断是否需要账户引导) */
    private volatile io.mctunnel.core.tunnel.TunnelInfo installInfo;
    /** 是否已发起首次安装(防止 rebuildWidgets 重复触发) */
    private boolean started;

    private EditBox dirBox;
    private EditBox tokenBox;

    public InstallWizardScreen(Screen parent, TunnelType type, boolean update) {
        super(Component.literal(type + (update ? " 更新" : " 安装")));
        this.parent = parent;
        this.type = type;
        this.update = update;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;

        // 首次进入:直接在后台发起安装(缺目录时后端会要求,再进入 ASK_DIR)
        if (!started) {
            started = true;
            runInstallInBackground(InstallOptions.DEFAULT);
        }

        switch (step) {
            case INSTALLING -> {
                // 无控件,后台线程执行中
            }
            case ASK_DIR -> {
                dirBox = new EditBox(this.font, cx - 150, 110, 250, 20,
                        Component.literal("解压目录"));
                dirBox.setValue("C:\\ngrok");
                dirBox.setHint(Component.literal("C:\\ngrok"));
                addRenderableWidget(dirBox);
                setInitialFocus(dirBox);
                addRenderableWidget(Button.builder(Component.literal("开始"), b -> submitDir())
                        .bounds(cx + 106, 110, 44, 20).build());
                addRenderableWidget(Button.builder(Component.literal("取消"), b -> onClose())
                        .bounds(cx - 50, this.height - 35, 100, 20).build());
            }
            case ASK_ACCOUNT -> {
                boolean ts = type == TunnelType.TAILSCALE;
                addRenderableWidget(Button.builder(
                                Component.literal(ts ? "登录 Tailscale" : "注册/登录"),
                                b -> { if (ts) startTailscaleLogin(); else openSignup(); })
                        .bounds(cx - 106, 130, 100, 20).build());
                addRenderableWidget(Button.builder(Component.literal("稍后再说"), b -> finish())
                        .bounds(cx + 6, 130, 100, 20).build());
                addRenderableWidget(Button.builder(Component.literal("返回"), b -> onClose())
                        .bounds(cx - 50, this.height - 35, 100, 20).build());
            }
            case ASK_TOKEN -> {
                tokenBox = new EditBox(this.font, cx - 150, 120, 300, 20,
                        Component.literal("Authtoken"));
                tokenBox.setHint(Component.literal("粘贴你的 Authtoken"));
                addRenderableWidget(tokenBox);
                setInitialFocus(tokenBox);
                addRenderableWidget(Button.builder(Component.literal("确认配置"), b -> submitToken())
                        .bounds(cx - 104, 150, 100, 20).build());
                addRenderableWidget(Button.builder(Component.literal("跳过"), b -> finish())
                        .bounds(cx + 4, 150, 100, 20).build());
                addRenderableWidget(Button.builder(Component.literal("复制网址"), b -> copyUrl())
                        .bounds(cx - 104, 180, 100, 20).build());
                addRenderableWidget(Button.builder(Component.literal("打开官网"), b -> openSignup())
                        .bounds(cx + 4, 180, 100, 20).build());
                addRenderableWidget(Button.builder(Component.literal("返回"), b -> onClose())
                        .bounds(cx - 50, this.height - 35, 100, 20).build());
            }
            case FINISHED -> {
                addRenderableWidget(Button.builder(Component.literal("完成"), b -> finish())
                        .bounds(cx - 50, this.height - 35, 100, 20).build());
            }
        }
    }

    @Override
    public void tick() {
        super.tick();
        // 后台安装完成 → 转步
        if (step == Step.INSTALLING && installResult != null) {
            if (installResult) {
                // 仅当账户未配置/未登录(needsAccount)时才进入账户引导步
                boolean needsAccount = installInfo != null && installInfo.needsAccount();
                if (needsAccount && (type == TunnelType.NGROK || type == TunnelType.TAILSCALE)) {
                    step = Step.ASK_ACCOUNT;
                    this.rebuildWidgets();
                } else {
                    finish();
                }
            } else {
                if (installError.contains("NEEDS_EXTRACT_DIR")) {
                    // Windows ngrok:需要用户选择解压位置
                    step = Step.ASK_DIR;
                    this.rebuildWidgets();
                } else {
                    errorLine = installError;
                    step = Step.FINISHED;
                    this.rebuildWidgets();
                }
            }
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        guiGraphics.fill(0, 0, this.width, this.height, 0xCC000000);
        guiGraphics.drawCenteredString(this.font, this.title,
                this.width / 2, 30, 0x4ade80);

        int cx = this.width / 2;
        switch (step) {
            case INSTALLING -> {
                guiGraphics.drawCenteredString(this.font,
                        Component.literal((update ? "正在更新 " : "正在安装 ") + type
                                + "(官方渠道下载)"), cx, 80, 0xffffff);
                guiGraphics.drawCenteredString(this.font,
                        Component.literal("首次下载约 20-40MB,请稍候..."), cx, 96, 0x94a3b8);
            }
            case ASK_DIR -> {
                guiGraphics.drawCenteredString(this.font,
                        Component.literal("Windows 上的 ngrok 需要选择解压位置"), cx, 70, 0xffffff);
                guiGraphics.drawCenteredString(this.font,
                        Component.literal("解压后将尝试加入 PATH 并记录完整路径"), cx, 86, 0x94a3b8);
            }
            case ASK_ACCOUNT -> {
                boolean ts = type == TunnelType.TAILSCALE;
                guiGraphics.drawCenteredString(this.font,
                        Component.literal(type + " 安装完成!"), cx, 60, 0x4ade80);
                guiGraphics.drawCenteredString(this.font,
                        Component.literal(ts ? "是否登录 Tailscale?" : "是否注册/登录 ngrok 账户?"), cx, 80, 0xffffff);
                if (ts) {
                    guiGraphics.drawCenteredString(this.font,
                            Component.literal("登录需要浏览器授权,点击后请在浏览器中完成认证。"), cx, 96, 0x94a3b8);
                } else {
                    guiGraphics.drawCenteredString(this.font,
                            Component.literal("区别: 未登录无法启动隧道(免费账户也需要 Authtoken);"), cx, 96, 0x94a3b8);
                    guiGraphics.drawCenteredString(this.font,
                            Component.literal("登录后在 Dashboard 获得 Authtoken,即可正常使用。"), cx, 110, 0x94a3b8);
                }
            }
            case ASK_TOKEN -> {
                guiGraphics.drawCenteredString(this.font,
                        Component.literal("已尝试自动打开官网,若未弹出请手动访问/复制:"), cx, 70, 0xffffff);
                guiGraphics.drawCenteredString(this.font,
                        Component.literal(SIGNUP_URL), cx, 84, 0x60a5fa);
                guiGraphics.drawCenteredString(this.font,
                        Component.literal("登录后在 Dashboard → Cloud Edge → Authtoken 复制,粘贴到下面:"),
                        cx, 102, 0x94a3b8);
                if (!statusLine.isEmpty()) {
                    guiGraphics.drawCenteredString(this.font,
                            Component.literal(statusLine), cx, 210, 0x4ade80);
                }
            }
            case FINISHED -> {
                guiGraphics.drawCenteredString(this.font,
                        Component.literal("操作未完成"), cx, 60, 0xef4444);
                if (!errorLine.isEmpty()) {
                    guiGraphics.drawCenteredString(this.font,
                            Component.literal(errorLine), cx, 80, 0xef4444);
                }
            }
        }

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    // ── 动作 ──────────────────────────────────────────────

    /** 提交解压目录,重新执行安装 */
    private void submitDir() {
        String dir = dirBox != null ? dirBox.getValue().trim() : "";
        if (dir.isEmpty()) {
            return;
        }
        runInstallInBackground(InstallOptions.withExtractDir(dir));
    }

    /** tailscale:触发 tailscale login(后端执行,会打开浏览器授权),然后结束向导 */
    private void startTailscaleLogin() {
        ToolController controller = MCTunnelMod.getToolController();
        if (controller != null) {
            try {
                controller.configure(type, Map.of("login", "true"));
            } catch (Exception ignored) {
                // 触发失败不阻塞,用户可稍后手动 tailscale login
            }
        }
        finish();
    }

    private void openSignup() {
        String err = FCLCompat.openBrowser(SIGNUP_URL);
        if (err != null) {
            statusLine = "自动打开失败,请复制网址手动打开";
        } else {
            statusLine = "已在浏览器打开官网";
        }
        if (step != Step.ASK_TOKEN) {
            step = Step.ASK_TOKEN;
            this.rebuildWidgets();
        }
    }

    private void copyUrl() {
        try {
            this.minecraft.keyboardHandler.setClipboard(SIGNUP_URL);
            statusLine = "已复制: " + SIGNUP_URL;
        } catch (Exception e) {
            statusLine = "复制失败";
        }
    }

    private void submitToken() {
        String token = tokenBox != null ? tokenBox.getValue().trim() : "";
        if (token.isEmpty()) {
            statusLine = "请先粘贴 Authtoken";
            return;
        }
        ToolController controller = MCTunnelMod.getToolController();
        if (controller == null) {
            finish();
            return;
        }
        try {
            controller.configure(type, Map.of("authtoken", token));
            statusLine = "Authtoken 已配置";
            finish();
        } catch (Exception e) {
            statusLine = "配置失败: " + e.getMessage();
        }
    }

    private void finish() {
        this.minecraft.setScreen(parent);
    }

    /** 后台线程执行安装(避免阻塞渲染线程) */
    private void runInstallInBackground(InstallOptions options) {
        ToolController controller = MCTunnelMod.getToolController();
        if (controller == null) {
            installError = "控制器未初始化";
            installResult = false;
            return;
        }
        step = Step.INSTALLING;
        installResult = null;
        installError = "";
        Thread worker = new Thread(() -> {
            try {
                if (update) {
                    installInfo = controller.update(type);
                } else {
                    installInfo = controller.install(type, options);
                }
                installResult = true;
            } catch (ExtractDirRequiredException e) {
                installError = "NEEDS_EXTRACT_DIR";
                installResult = false;
            } catch (Exception e) {
                installError = e.getMessage() != null ? e.getMessage() : e.toString();
                installResult = false;
            }
        }, "mctunnel-install");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }
}
