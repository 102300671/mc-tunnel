package io.mctunnel.forge.gui;

import io.mctunnel.core.tunnel.TunnelInfo;
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
 * 游戏内 ngrok 启动配置屏.
 * <p>
 * 我的世界联机走 TCP,游戏内不提供协议选择(协议选择保留给 WebUI),
 * 玩家只需填写本地端口(默认 25565);首次使用在此粘贴 Authtoken。
 * 启动成功后自动把公网地址复制到剪贴板,方便发给玩家。
 */
public class NgrokStartScreen extends Screen {

    private static final String SIGNUP_URL =
            "https://dashboard.ngrok.com/get-started/your-authtoken";

    private final Screen parent;
    /** 进入本屏时的状态:用于判断 Authtoken 是否已配置 */
    private final boolean tokenConfigured;

    private EditBox portBox;
    private EditBox tokenBox;
    private Button saveTokenBtn;
    private Button startBtn;

    private String statusLine = "";
    private int statusColor = 0xef4444;
    private boolean busy;

    public NgrokStartScreen(Screen parent, boolean tokenConfigured) {
        super(Component.literal("ngrok 开服隧道"));
        this.parent = parent;
        this.tokenConfigured = tokenConfigured;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;

        portBox = new EditBox(this.font, cx - 100, 96, 200, 20,
                Component.literal("本地端口"));
        portBox.setHint(Component.literal("本地端口,默认 25565"));
        portBox.setValue("25565");
        portBox.setFilter(s -> s.isEmpty() || s.matches("\\d{1,5}"));
        portBox.setMaxLength(5);
        addRenderableWidget(portBox);
        setInitialFocus(portBox);

        tokenBox = new EditBox(this.font, cx - 100, 138, 200, 20,
                Component.literal("Authtoken"));
        tokenBox.setHint(Component.literal(
                tokenConfigured ? "已配置,留空则不修改" : "首次使用必填,粘贴 Authtoken"));
        tokenBox.setMaxLength(256);
        // Token 是敏感信息,掩码显示
        tokenBox.setFormatter((s, cursor) ->
                Component.literal(s.replaceAll(".", "*")).getVisualOrderText());
        addRenderableWidget(tokenBox);

        int y = 172;
        addRenderableWidget(Button.builder(Component.literal("获取 Authtoken"),
                        b -> openSignup())
                .bounds(cx - 200, y, 96, 20).build());
        saveTokenBtn = Button.builder(Component.literal("保存 Token"), b -> saveToken(false))
                .bounds(cx - 100, y, 96, 20).build();
        addRenderableWidget(saveTokenBtn);
        startBtn = Button.builder(Component.literal("启动 TCP 隧道"), b -> startTunnel())
                .bounds(cx + 4, y, 104, 20).build();
        addRenderableWidget(startBtn);
        addRenderableWidget(Button.builder(Component.literal("取消"), b -> onClose())
                .bounds(cx + 112, y, 88, 20).build());
    }

    private void openSignup() {
        String err = FCLCompat.openBrowser(SIGNUP_URL);
        if (err == null) {
            setStatus("已打开浏览器,登录后复制 Authtoken 粘贴到下方", 0x9fd49a);
        } else {
            setStatus("浏览器未弹出,请手动访问: " + SIGNUP_URL, 0xfbbf24);
        }
    }

    /** 保存 Token;saveAndStart=true 时保存成功后继续启动 */
    private void saveToken(boolean saveAndStart) {
        ToolController controller = MCTunnelMod.getToolController();
        if (controller == null) {
            setStatus("控制器未初始化", 0xef4444);
            return;
        }
        String token = tokenBox.getValue().trim();
        if (token.isEmpty()) {
            setStatus("请先粘贴 Authtoken", 0xef4444);
            return;
        }
        setBusy(true, "正在保存 Authtoken...");
        Thread t = new Thread(() -> {
            try {
                controller.configure(TunnelType.NGROK, Map.of("authtoken", token));
                this.minecraft.execute(() -> {
                    setBusy(false, "");
                    tokenBox.setValue("");
                    tokenBox.setHint(Component.literal("已配置,留空则不修改"));
                    setStatus("Authtoken 已保存", 0x4ade80);
                    if (saveAndStart) {
                        startTunnel();
                    }
                });
            } catch (Exception e) {
                this.minecraft.execute(() -> {
                    setBusy(false, "");
                    setStatus("保存失败: " + msg(e), 0xef4444);
                });
            }
        }, "mctunnel-ngrok-cfg");
        t.setDaemon(true);
        t.start();
    }

    private void startTunnel() {
        ToolController controller = MCTunnelMod.getToolController();
        if (controller == null) {
            setStatus("控制器未初始化", 0xef4444);
            return;
        }
        // 端口校验
        int port;
        try {
            port = Integer.parseInt(portBox.getValue().trim());
        } catch (NumberFormatException e) {
            setStatus("请输入本地端口", 0xef4444);
            return;
        }
        if (port < 1 || port > 65535) {
            setStatus("端口需在 1-65535 之间", 0xef4444);
            return;
        }
        // 填了 Token 就先保存再启动
        if (!tokenBox.getValue().trim().isEmpty()) {
            saveToken(true);
            return;
        }
        final int p = port;
        setBusy(true, "正在启动 ngrok tcp " + p + " ...(最多等待 15 秒)");
        Thread t = new Thread(() -> {
            try {
                TunnelInfo info = controller.start(TunnelType.NGROK, "tcp", String.valueOf(p));
                this.minecraft.execute(() -> {
                    setBusy(false, "");
                    if (info.publicUrl() != null) {
                        // 自动复制公网地址,玩家直接粘贴即可联机
                        this.minecraft.keyboardHandler.setClipboard(info.publicUrl());
                        this.minecraft.setScreen(parent);
                        if (parent instanceof TunnelScreen ts) {
                            ts.flashMessage("ngrok 已启动(TCP),公网地址已复制: "
                                    + info.publicUrl());
                        }
                    } else if (info.error() != null) {
                        // 实际错误由 core 从 ngrok 输出提炼(如免费账号开 TCP 需绑卡)
                        setStatus(info.error(), 0xef4444);
                    } else {
                        this.minecraft.setScreen(parent);
                        if (parent instanceof TunnelScreen ts) {
                            ts.flashMessage("ngrok 已启动,正在获取公网地址,请刷新状态");
                        }
                    }
                });
            } catch (Exception e) {
                this.minecraft.execute(() -> {
                    setBusy(false, "");
                    setStatus("启动失败: " + msg(e), 0xef4444);
                });
            }
        }, "mctunnel-ngrok-start");
        t.setDaemon(true);
        t.start();
    }

    private void setBusy(boolean b, String text) {
        busy = b;
        if (saveTokenBtn != null) saveTokenBtn.active = !b;
        if (startBtn != null) startBtn.active = !b;
        if (b) {
            setStatus(text, 0xfbbf24);
        }
    }

    private void setStatus(String text, int color) {
        this.statusLine = text;
        this.statusColor = color;
    }

    private static String msg(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.toString();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, 0xCC000000);
        int cx = this.width / 2;
        g.drawCenteredString(this.font, this.title, cx, 28, 0x4ade80);
        g.drawCenteredString(this.font,
                Component.literal("协议固定 TCP(我的世界联机),只需填写本地端口"),
                cx, 58, 0xffffff);
        g.drawString(this.font, Component.literal("本地端口:"),
                cx - 100, 84, 0x9fd49a, false);
        g.drawString(this.font,
                        Component.literal("Authtoken:  "
                                + (tokenConfigured ? "[已配置]" : "[未配置]")),
                cx - 100, 126,
                tokenConfigured ? 0x4ade80 : 0xef4444, false);
        g.drawCenteredString(this.font,
                Component.literal("免费注册: dashboard.ngrok.com → Your Authtoken"),
                cx, 204, 0x94a3b8);
        if (!statusLine.isEmpty()) {
            // 错误可能较长(含解决链接),按宽度自动换行居中显示
            var lines = this.font.split(Component.literal(statusLine), this.width - 40);
            int y0 = 218;
            for (int i = 0; i < lines.size(); i++) {
                g.drawCenteredString(this.font, lines.get(i), cx, y0 + i * 11, statusColor);
            }
        }
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        if (!busy) {
            this.minecraft.setScreen(this.parent);
        }
    }
}
