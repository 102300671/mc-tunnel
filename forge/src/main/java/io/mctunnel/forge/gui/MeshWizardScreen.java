package io.mctunnel.forge.gui;

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
 * Tailscale 虚拟组网向导(游戏内).
 * <p>
 * 房主三种方式:1 共用账号(走 tailscale login);2 按邮箱分享本机设备;
 * 3 按邮箱邀请成员加入同一 tailnet。方式2/3 需要 API 访问令牌,
 * 经后台线程调用控制面 API,避免阻塞渲染线程。成员页给出三种入队指引。
 */
public class MeshWizardScreen extends Screen {

    private static final String KEYS_URL =
            "https://login.tailscale.com/admin/settings/keys";

    private final Screen parent;

    private boolean hostRole = true;
    /** 1=共用账号 2=分享设备 3=tailnet 邀请 */
    private int mode = 1;
    private String tokenText = "";
    private String emailsText = "";

    private EditBox tokenBox;
    private EditBox emailsBox;
    private String status = "";
    private int statusColor = 0xffffff;
    private boolean busy = false;

    public MeshWizardScreen(Screen parent) {
        super(Component.literal("Tailscale 虚拟组网向导"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int w = 300;
        int x = cx - w / 2;

        // 角色切换
        addRenderableWidget(Button.builder(Component.literal("我是房主(开服)"),
                        b -> setRole(true))
                .bounds(x, 40, 146, 20).build());
        addRenderableWidget(Button.builder(Component.literal("我是成员(加入)"),
                        b -> setRole(false))
                .bounds(x + 154, 40, 146, 20).build());

        if (!hostRole) {
            addRenderableWidget(Button.builder(Component.literal("返回"), b -> onClose())
                    .bounds(cx - 50, this.height - 35, 100, 20).build());
            return;
        }

        // 三种方式
        addRenderableWidget(Button.builder(Component.literal("方式1 共用账号(所有人登录同一账号)"),
                        b -> setMode(1))
                .bounds(x, 74, w, 20).build());
        addRenderableWidget(Button.builder(Component.literal("方式2 分享本机(成员各自账号)"),
                        b -> setMode(2))
                .bounds(x, 98, w, 20).build());
        addRenderableWidget(Button.builder(Component.literal("方式3 邀请入网(成员加入同一 tailnet)"),
                        b -> setMode(3))
                .bounds(x, 122, w, 20).build());

        if (mode == 1) {
            addRenderableWidget(Button.builder(
                            Component.literal("去登录 Tailscale(浏览器授权)"), b -> doLogin())
                    .bounds(x, 176, w, 20).build());
        } else {
            // 令牌(掩码输入,不回显)
            tokenBox = new EditBox(this.font, x, 158, w, 20,
                    Component.literal("API 令牌"));
            tokenBox.setHint(Component.literal("粘贴 tskey-api-...(保存后可留空复用)"));
            tokenBox.setFormatter((s, cursor) ->
                    Component.literal(s.replaceAll(".", "*")).getVisualOrderText());
            tokenBox.setMaxLength(256);
            tokenBox.setValue(tokenText);
            addRenderableWidget(tokenBox);
            setInitialFocus(tokenBox);

            addRenderableWidget(Button.builder(Component.literal("打开令牌页"),
                            b -> openKeysPage())
                    .bounds(x, 182, 96, 20).build());
            addRenderableWidget(Button.builder(Component.literal("验证并保存"),
                            b -> saveToken())
                    .bounds(x + 102, 182, 96, 20).build());
            addRenderableWidget(Button.builder(Component.literal("清除已存"),
                            b -> clearToken())
                    .bounds(x + 204, 182, 96, 20).build());

            emailsBox = new EditBox(this.font, x, 214, w, 20,
                    Component.literal("成员邮箱"));
            emailsBox.setHint(Component.literal("成员邮箱,多个用逗号分隔"));
            emailsBox.setMaxLength(512);
            emailsBox.setValue(emailsText);
            addRenderableWidget(emailsBox);

            String label = mode == 2 ? "分享本机给以上邮箱" : "发送 tailnet 邀请";
            Button run = Button.builder(Component.literal(label), b -> runMesh())
                    .bounds(x, 240, w, 20).build();
            run.active = !busy;
            addRenderableWidget(run);
        }

        addRenderableWidget(Button.builder(Component.literal("返回"), b -> onClose())
                .bounds(cx - 50, this.height - 35, 100, 20).build());
    }

    private void setRole(boolean host) {
        if (saveInputs()) {
            hostRole = host;
            status = "";
            rebuild();
        }
    }

    private void setMode(int m) {
        saveInputs();
        mode = m;
        status = "";
        rebuild();
    }

    /** 切换前保存输入框内容 */
    private boolean saveInputs() {
        if (tokenBox != null) {
            tokenText = tokenBox.getValue();
        }
        if (emailsBox != null) {
            emailsText = emailsBox.getValue();
        }
        return true;
    }

    private void rebuild() {
        this.clearWidgets();
        this.init();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, 0xDD000000);
        int cx = this.width / 2;
        g.drawCenteredString(this.font, this.title, cx, 18, 0x4ade80);

        if (hostRole) {
            g.drawString(this.font, Component.literal("选择组网方式:"), cx - 150, 62,
                    0x9fd49a, false);
            String desc = modeDesc();
            g.drawWordWrap(this.font, Component.literal(desc), cx - 150, 142, 300, 0xcccccc);
            if (mode != 1) {
                g.drawString(this.font,
                        Component.literal("令牌生成页: admin → Settings → API access tokens"),
                        cx - 150, 206, 0x888888, false);
            }
            if (!status.isEmpty()) {
                g.drawWordWrap(this.font, Component.literal(status),
                        cx - 150, this.height - 62, 300, statusColor);
            }
        } else {
            String guide = """
                    成员加入步骤:

                    1. 先安装 Tailscale 并登录。

                    2. 按房主选择的方式操作:
                    · 方式1:运行 tailscale login,登录房主给的同一个账号;
                    · 方式2:打开 Tailscale 分享邮件 → 点接受,设备出现后联机;
                    · 方式3:打开邀请邮件 → 接受邀请 → tailscale login 登录自己的账号。

                    3. MC 多人游戏 → 直接连接房主给的 100.x.x.x 地址。
                    联机时保持 Tailscale 状态为 Connected。""";
            g.drawWordWrap(this.font, Component.literal(guide),
                    cx - 170, 70, 340, 0xdddddd);
        }
        super.render(g, mouseX, mouseY, partialTick);
    }

    private String modeDesc() {
        return switch (mode) {
            case 1 -> "房主登录账号,所有成员也登录同一个账号。最简单,但成员能看到账号下全部设备。";
            case 2 -> "成员各自注册账号,房主按邮箱把开服设备分享给他们。成员被隔离,只能连入本机。";
            default -> "成员各自注册账号,房主按邮箱邀请他们加入同一 tailnet(member 角色,互通且无管理后台)。";
        };
    }

    // ── 后台操作(网络调用,必须在 daemon 线程) ──────────

    private ToolController controller() {
        ToolController c = MCTunnelMod.getToolController();
        if (c == null) {
            status = "控制器未初始化";
            statusColor = 0xef4444;
        }
        return c;
    }

    private void doLogin() {
        ToolController c = controller();
        if (c == null) return;
        busy = true;
        rebuild();
        setStatus("已发起 tailscale login,请在浏览器完成授权(最长 180s)...", 0xfbbf24);
        Thread t = new Thread(() -> {
            try {
                c.configure(TunnelType.TAILSCALE, Map.of("login", "true"));
                this.minecraft.execute(() -> {
                    busy = false;
                    setStatus("登录流程已结束,返回面板刷新查看登录状态。", 0x4ade80);
                    rebuild();
                });
            } catch (Exception e) {
                this.minecraft.execute(() -> {
                    busy = false;
                    setStatus("登录失败: " + e.getMessage(), 0xef4444);
                    rebuild();
                });
            }
        }, "mctunnel-mesh-login");
        t.setDaemon(true);
        t.start();
    }

    private void openKeysPage() {
        String err = FCLCompat.openBrowser(KEYS_URL);
        if (err == null) {
            setStatus("已打开令牌生成页,生成后粘贴到上方输入框。", 0x9fd49a);
        } else {
            setStatus("无法打开浏览器,请手动访问: " + KEYS_URL, 0xfbbf24);
        }
    }

    private void saveToken() {
        saveInputs();
        String token = tokenText.trim();
        if (token.isEmpty()) {
            setStatus("请先粘贴令牌", 0xef4444);
            return;
        }
        runAsync("正在验证令牌...", () -> {
            ToolController c = controller();
            if (c == null) {
                return;
            }
            String r = c.meshSaveToken(token);
            this.minecraft.execute(() -> {
                tokenText = "";
                setStatus(r, 0x4ade80);
                rebuild();
            });
        });
    }

    private void clearToken() {
        runAsync("正在清除令牌...", () -> {
            ToolController c = controller();
            if (c == null) {
                return;
            }
            String r = c.meshClearToken();
            this.minecraft.execute(() -> setStatus(r, 0x4ade80));
        });
    }

    private void runMesh() {
        saveInputs();
        String emails = emailsText.trim();
        if (emails.isEmpty()) {
            setStatus("请输入至少一个成员邮箱(多个用逗号分隔)", 0xef4444);
            return;
        }
        boolean share = mode == 2;
        runAsync(share ? "正在分享本机设备..." : "正在发送 tailnet 邀请...", () -> {
            ToolController c = controller();
            if (c == null) {
                return;
            }
            String r = share ? c.meshShare(emails) : c.meshInvite(emails);
            this.minecraft.execute(() -> setStatus(r, 0x4ade80));
        });
    }

    /** 允许抛检查异常的后台动作 */
    @FunctionalInterface
    private interface MeshAction {
        void run() throws Exception;
    }

    /** 后台线程执行网络操作;异常回到主线程显示 */
    private void runAsync(String pending, MeshAction action) {
        busy = true;
        setStatus(pending, 0xfbbf24);
        rebuild();
        Thread t = new Thread(() -> {
            try {
                action.run();
                this.minecraft.execute(() -> {
                    busy = false;
                    rebuild();
                });
            } catch (Exception e) {
                this.minecraft.execute(() -> {
                    busy = false;
                    setStatus("操作失败: " + e.getMessage(), 0xef4444);
                    rebuild();
                });
            }
        }, "mctunnel-mesh");
        t.setDaemon(true);
        t.start();
    }

    private void setStatus(String s, int color) {
        status = s == null ? "" : s;
        statusColor = color;
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }
}
