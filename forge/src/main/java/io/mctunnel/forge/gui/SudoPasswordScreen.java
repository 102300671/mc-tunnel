package io.mctunnel.forge.gui;

import io.mctunnel.core.tunnel.TunnelType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * sudo 密码输入对话框(Linux 非 root 启停 tailscaled 系统服务时).
 * <p>
 * 安全设计:输入内容掩码显示(EditBox Formatter 把每个字符渲染成 *),
 * 不回显;密码仅通过回调交给本次操作,用后即弃——不记录、不写盘、不进日志.
 */
public class SudoPasswordScreen extends Screen {

    private final Screen parent;
    private final TunnelType type;
    /** true=启动服务,false=停止服务 */
    private final boolean start;
    /** 已输入密码后的重试回调;参数 null 表示用户取消 */
    private final Consumer<String> onSubmit;

    private EditBox passwordBox;
    private String statusLine = "";
    private int attempts = 0;

    public SudoPasswordScreen(Screen parent, TunnelType type, boolean start,
                              Consumer<String> onSubmit) {
        super(Component.literal("需要 sudo 密码"));
        this.parent = parent;
        this.type = type;
        this.start = start;
        this.onSubmit = onSubmit;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        passwordBox = new EditBox(this.font, cx - 150, 130, 300, 20,
                Component.literal("sudo 密码"));
        passwordBox.setHint(Component.literal("sudo 密码(不回显)"));
        // 掩码:所有字符显示为 *,真实值仍可通过 getValue 获取
        passwordBox.setFormatter((s, cursor) ->
                Component.literal(s.replaceAll(".", "*")).getVisualOrderText());
        passwordBox.setMaxLength(256);
        addRenderableWidget(passwordBox);
        setInitialFocus(passwordBox);
        addRenderableWidget(Button.builder(Component.literal("确认"), b -> submit())
                .bounds(cx - 104, 160, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("取消"), b -> onClose())
                .bounds(cx + 4, 160, 100, 20).build());
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        guiGraphics.fill(0, 0, this.width, this.height, 0xDD000000);
        int cx = this.width / 2;
        guiGraphics.drawCenteredString(this.font, this.title, cx, 30, 0x4ade80);
        guiGraphics.drawCenteredString(this.font,
                Component.literal((start ? "启动" : "停止") + " " + type + " 系统服务需要 sudo 权限"),
                cx, 72, 0xffffff);
        // 安全保证(明确告知用户)
        guiGraphics.drawCenteredString(this.font,
                Component.literal("安全保证:密码仅在本次 sudo systemctl 操作中使用"),
                cx, 92, 0x9fd49a);
        guiGraphics.drawCenteredString(this.font,
                Component.literal("不会显示、不会被记录、不会保存到任何文件"),
                cx, 106, 0x9fd49a);
        if (!statusLine.isEmpty()) {
            guiGraphics.drawCenteredString(this.font,
                    Component.literal(statusLine), cx, 190, 0xef4444);
        }
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    private void submit() {
        String pw = passwordBox.getValue();
        if (pw.isEmpty()) {
            statusLine = "请输入密码";
            return;
        }
        attempts++;
        statusLine = "";
        // 交回调用方执行;调用方根据结果决定关闭本屏或回来提示重试
        onSubmit.accept(pw);
    }

    /**
     * 显示操作结果:成功/取消由调用方切屏;失败(如密码错误)留在本屏提示重试.
     */
    public void showError(String message) {
        statusLine = (attempts > 0 ? "第 " + attempts + " 次尝试失败: " : "") + message;
        passwordBox.setValue("");
    }

    @Override
    public void onClose() {
        // 取消:清空已输入内容并返回
        passwordBox.setValue("");
        this.minecraft.setScreen(this.parent);
    }
}
