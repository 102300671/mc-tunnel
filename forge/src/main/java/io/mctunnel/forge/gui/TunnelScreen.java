package io.mctunnel.forge.gui;

import io.mctunnel.core.tunnel.TunnelClient;
import io.mctunnel.core.tunnel.TunnelInfo;
import io.mctunnel.core.tunnel.TunnelType;
import io.mctunnel.forge.MCTunnelMod;
import io.mctunnel.forge.compat.FCLCompat;
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

    public TunnelScreen(Screen parent) {
        super(Component.literal("MC-Tunnel 管理面板"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;

        // 顶部: 刷新 + 打开 WebUI + 复制 URL
        addRenderableWidget(Button.builder(Component.literal("刷新状态"), b -> refresh())
                .bounds(cx - 155, 45, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("打开 WebUI"), b -> openWebUi())
                .bounds(cx - 50, 45, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("复制 URL"), b -> copyUrl())
                .bounds(cx + 55, 45, 100, 20).build());

        // 每个工具: 名称 + 状态行 + 三个按钮
        int y = 85;
        for (TunnelType type : TunnelType.values()) {
            addToolRow(type, cx, y);
            y += 55;
        }

        // 返回
        addRenderableWidget(Button.builder(Component.literal("返回"), b -> onClose())
                .bounds(cx - 50, this.height - 35, 100, 20).build());

        refresh();
    }

    private void addToolRow(TunnelType type, int cx, int y) {
        int btnW = 60;
        int gap = 6;
        int totalW = btnW * 3 + gap * 2;
        int startX = cx - totalW / 2;

        addRenderableWidget(Button.builder(Component.literal("安装"), b -> install(type))
                .bounds(startX, y, btnW, 20).build());
        addRenderableWidget(Button.builder(Component.literal("启动"), b -> start(type))
                .bounds(startX + btnW + gap, y, btnW, 20).build());
        addRenderableWidget(Button.builder(Component.literal("停止"), b -> stop(type))
                .bounds(startX + (btnW + gap) * 2, y, btnW, 20).build());
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
        String backend = MCTunnelMod.getTunnelClient() != null
                ? MCTunnelMod.getTunnelClient().getBaseUrl() : "未连接";
        guiGraphics.drawString(this.font, "后端: " + backend, 8, this.height - 12, 0x888888, false);

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    // ── 操作 ──────────────────────────────────────────────

    private void refresh() {
        TunnelClient client = MCTunnelMod.getTunnelClient();
        if (client == null) {
            backendConnected = false;
            showMessage("客户端未初始化");
            return;
        }
        try {
            infos = client.status();
            backendConnected = true;
        } catch (Exception e) {
            backendConnected = false;
            showMessage("刷新失败: " + e.getMessage());
        }
    }

    private void install(TunnelType type) {
        TunnelClient client = MCTunnelMod.getTunnelClient();
        if (client == null) return;
        showMessage("安装 " + type + " ...");
        try {
            TunnelInfo info = client.install(type);
            showMessage(type + " -> " + info.status());
            refresh();
        } catch (Exception e) {
            showMessage("安装失败: " + e.getMessage());
        }
    }

    private void start(TunnelType type) {
        TunnelClient client = MCTunnelMod.getTunnelClient();
        if (client == null) return;
        showMessage("启动 " + type + " ...");
        try {
            String[] args = type == TunnelType.NGROK
                    ? new String[]{"http", "25565"} : new String[0];
            TunnelInfo info = client.start(type, args);
            showMessage(type + " -> " + info.status()
                    + (info.publicUrl() != null ? " " + info.publicUrl() : ""));
            refresh();
        } catch (Exception e) {
            showMessage("启动失败: " + e.getMessage());
        }
    }

    private void stop(TunnelType type) {
        TunnelClient client = MCTunnelMod.getTunnelClient();
        if (client == null) return;
        try {
            TunnelInfo info = client.stop(type);
            showMessage(type + " 已停止 -> " + info.status());
            refresh();
        } catch (Exception e) {
            showMessage("停止失败: " + e.getMessage());
        }
    }

    private void openWebUi() {
        String url = MCTunnelMod.getTunnelClient() != null
                ? MCTunnelMod.getTunnelClient().getBaseUrl() : "http://localhost:8787";
        String err = FCLCompat.openBrowser(url);
        if (err == null) {
            showMessage("已打开浏览器: " + url);
        } else {
            showMessage("打开失败,请手动访问: " + url);
        }
    }

    private void copyUrl() {
        String url = MCTunnelMod.getTunnelClient() != null
                ? MCTunnelMod.getTunnelClient().getBaseUrl() : "http://localhost:8787";
        try {
            // MC 自带剪贴板
            this.minecraft.keyboardHandler.setClipboard(url);
            showMessage("已复制: " + url);
        } catch (Exception e) {
            showMessage("复制失败: " + e.getMessage());
        }
    }

    private void showMessage(String msg) {
        this.message = msg;
        this.messageUntil = System.currentTimeMillis() + 5000;
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }
}
