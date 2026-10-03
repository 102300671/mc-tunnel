package io.mctunnel.forge.gui;

import io.mctunnel.core.tunnel.TunnelType;
import io.mctunnel.forge.MCTunnelMod;
import io.mctunnel.forge.controller.ToolController;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 指定已有工具位置的对话框 Screen.
 * <p>
 * 用户输入工具可执行文件的完整路径或其所在目录,
 * 模组验证后记录并直接使用该位置的工具,不再下载安装.
 */
public class LocateToolScreen extends Screen {

    private final Screen parent;
    private final TunnelType type;
    private EditBox pathBox;
    /** 跨 init() 保留的路径(从文件选择器返回时 Screen 会重新初始化) */
    private String pendingPath = "";
    private String statusLine = "";

    public LocateToolScreen(Screen parent, TunnelType type) {
        super(Component.literal("指定 " + type + " 位置"));
        this.parent = parent;
        this.type = type;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        // 输入框 + 浏览按钮(文件选择器)
        pathBox = new EditBox(this.font, cx - 150, 110, 236, 20,
                Component.literal("工具位置"));
        pathBox.setHint(Component.literal("可执行文件或其所在目录"));
        if (!pendingPath.isEmpty()) {
            pathBox.setValue(pendingPath);
        }
        addRenderableWidget(pathBox);
        setInitialFocus(pathBox);
        addRenderableWidget(Button.builder(Component.literal("浏览…"), b -> browse())
                .bounds(cx + 92, 110, 58, 20).build());
        addRenderableWidget(Button.builder(Component.literal("确认"), b -> submit())
                .bounds(cx - 104, 140, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("取消"), b -> onClose())
                .bounds(cx + 4, 140, 100, 20).build());
    }

    /** 打开游戏内文件选择器,选中后回填路径(再由用户点确认) */
    private void browse() {
        java.nio.file.Path start = null;
        if (!pendingPath.isEmpty()) {
            try {
                start = java.nio.file.Paths.get(pendingPath);
            } catch (Exception ignored) {
                start = null;
            }
        }
        this.minecraft.setScreen(new FileChooserScreen(this, type.getBinaryName(),
                start, picked -> pendingPath = picked));
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        guiGraphics.fill(0, 0, this.width, this.height, 0xCC000000);
        guiGraphics.drawCenteredString(this.font, this.title,
                this.width / 2, 30, 0x4ade80);
        int cx = this.width / 2;
        guiGraphics.drawCenteredString(this.font,
                Component.literal("输入 " + type.getBinaryName()
                        + " 可执行文件完整路径,或其所在目录"), cx, 78, 0xffffff);
        guiGraphics.drawCenteredString(this.font,
                Component.literal("模组将直接使用该位置的工具,不再下载安装"), cx, 92, 0x94a3b8);
        if (!statusLine.isEmpty()) {
            guiGraphics.drawCenteredString(this.font,
                    Component.literal(statusLine), cx, 170, 0xef4444);
        }
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    private void submit() {
        String path = pathBox.getValue().trim();
        if (path.isEmpty()) {
            statusLine = "请输入路径";
            return;
        }
        ToolController controller = MCTunnelMod.getToolController();
        if (controller == null) {
            statusLine = "控制器未初始化";
            return;
        }
        try {
            controller.locate(type, path);
            this.minecraft.setScreen(parent);
        } catch (Exception e) {
            statusLine = "失败: " + (e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }
}
