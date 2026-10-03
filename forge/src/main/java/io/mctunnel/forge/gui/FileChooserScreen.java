package io.mctunnel.forge.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * 游戏内文件选择器:浏览本机磁盘,选择工具可执行文件或其所在目录。
 * <p>
 * 不使用 AWT 原生文件对话框(在全屏 LWJGL 窗口 / FCL 等环境下不可靠),
 * 改为自绘可滚动列表:单击目录进入,单击文件选中(再点一次确认),
 * 底部按钮可直接选择当前目录。
 */
public class FileChooserScreen extends Screen {

    private static final int ROW_H = 16;

    private final Screen parent;
    /** 目标可执行文件名(不含扩展名),用于默认筛选 */
    private final String binaryName;
    private final Consumer<String> onSelected;

    private Path current;
    private List<Path> entries = new ArrayList<>();
    private Path selectedFile;
    /** 双击检测:上次单击的文件与时间 */
    private Path lastClickedFile;
    private long lastClickMs;
    /** true=只显示与 binaryName 匹配的文件;false=显示全部文件 */
    private boolean filtered = true;
    /** true=此电脑视图(列出所有盘符,Windows) */
    private boolean drivesView = false;
    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    private int scroll;

    private Button chooseButton;
    private Button filterButton;
    private Button upButton;
    private String statusLine = "";

    private int listTop = 74;
    private int listBottom;
    private int listLeft;
    private int listRight;

    public FileChooserScreen(Screen parent, String binaryName, Path startDir,
                             Consumer<String> onSelected) {
        super(Component.literal("选择文件位置"));
        this.parent = parent;
        this.binaryName = binaryName;
        this.onSelected = onSelected;
        Path home = new File(System.getProperty("user.home", "/")).toPath();
        if (startDir != null && Files.isDirectory(startDir)) {
            this.current = startDir;
        } else if (startDir != null && startDir.getParent() != null
                && Files.isDirectory(startDir.getParent())) {
            this.current = startDir.getParent();
        } else {
            this.current = home;
        }
    }

    @Override
    protected void init() {
        listLeft = this.width / 2 - 200;
        listRight = this.width / 2 + 200;
        listTop = 74;
        listBottom = this.height - 64;

        int y = this.height - 42;
        // 左侧按钮组(紧凑布局,给盘符按钮腾位置)
        int pos = listLeft;
        upButton = Button.builder(Component.literal("上一级"), b -> goUp())
                .bounds(pos, y, 58, 20).build();
        addRenderableWidget(upButton);
        pos += 62;
        if (WINDOWS) {
            addRenderableWidget(Button.builder(Component.literal("此电脑"), b -> showDrives())
                    .bounds(pos, y, 62, 20).build());
            pos += 66;
        }
        addRenderableWidget(Button.builder(Component.literal("刷新"), b -> refresh())
                .bounds(pos, y, 50, 20).build());
        pos += 54;
        filterButton = Button.builder(Component.literal(""), b -> toggleFilter())
                .bounds(pos, y, 86, 20).build();
        addRenderableWidget(filterButton);

        // 右侧
        addRenderableWidget(Button.builder(Component.literal("取消"), b -> onClose())
                .bounds(listRight - 52, y, 52, 20).build());
        chooseButton = Button.builder(Component.literal("选择此目录"), b -> confirm())
                .bounds(listRight - 128, y, 72, 20).build();
        addRenderableWidget(chooseButton);

        refresh();
    }

    private void toggleFilter() {
        filtered = !filtered;
        selectedFile = null;
        refresh();
    }

    /** 进入"此电脑"盘符视图 */
    private void showDrives() {
        drivesView = true;
        selectedFile = null;
        refresh();
    }

    private void refresh() {
        statusLine = "";
        if (filterButton != null) {
            filterButton.setMessage(Component.literal(
                    filtered ? "筛选: 仅匹配文件" : "显示全部文件"));
            // 盘符视图下没有文件可筛选
            filterButton.active = !drivesView;
        }
        if (drivesView) {
            // File.listRoots():Windows 返回所有盘符(A:\ C:\ D:\ ...)。
            // exists() 顺带排除未插介质的可移动盘(空软驱/U 盘),避免访问卡死。
            List<Path> roots = new ArrayList<>();
            for (File f : File.listRoots()) {
                try {
                    if (f.exists() && f.isDirectory()) {
                        roots.add(f.toPath());
                    }
                } catch (SecurityException ignored) {
                    // 无权限访问的盘跳过
                }
            }
            Collections.sort(roots, Comparator.comparing(Path::toString));
            entries = roots;
            if (entries.isEmpty()) {
                statusLine = "未发现可用磁盘";
            }
            scroll = 0;
            updateChooseButton();
            return;
        }
        List<Path> dirs = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(current)) {
            stream.forEach(p -> {
                if (Files.isDirectory(p)) {
                    dirs.add(p);
                } else if (Files.isRegularFile(p) && (!filtered || matches(p))) {
                    files.add(p);
                }
            });
        } catch (Exception e) {
            statusLine = "无法读取目录: " + e.getMessage();
        }
        Comparator<Path> byName = Comparator.comparing(
                p -> p.getFileName().toString().toLowerCase(Locale.ROOT));
        Collections.sort(dirs, byName);
        Collections.sort(files, byName);
        entries = new ArrayList<>();
        entries.addAll(dirs);
        entries.addAll(files);
        scroll = 0;
        updateChooseButton();
    }

    /** 文件名与目标工具匹配:ngrok / tailscale / tailscaled / syncthing 等前缀,带不带扩展名均可 */
    private boolean matches(Path p) {
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        String target = binaryName.toLowerCase(Locale.ROOT);
        String stem = name.endsWith(".exe") ? name.substring(0, name.length() - 4) : name;
        return name.startsWith(target) || stem.startsWith(target);
    }

    private void goUp() {
        // 盘符视图下"上一级"无意义
        if (drivesView) {
            return;
        }
        Path parent = current.getParent();
        if (parent != null) {
            current = parent;
            selectedFile = null;
            refresh();
        } else if (WINDOWS) {
            // 已在盘符根目录(如 C:\),再往上回到"此电脑"盘符列表
            showDrives();
        }
    }

    private void enter(Path dir) {
        current = dir;
        drivesView = false;
        selectedFile = null;
        refresh();
    }

    private void updateChooseButton() {
        if (chooseButton != null) {
            // 盘符视图没有"当前目录"概念,必须先进入某个盘
            chooseButton.active = !drivesView;
            chooseButton.setMessage(Component.literal(
                    selectedFile != null ? "选择此文件" : "选择此目录"));
        }
        if (upButton != null) {
            upButton.active = !drivesView;
        }
    }

    private void confirm() {
        Path picked = selectedFile != null ? selectedFile : current;
        onSelected.accept(picked.toAbsolutePath().toString());
        this.minecraft.setScreen(parent);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button != 0 || mouseX < listLeft || mouseX > listRight
                || mouseY < listTop || mouseY > listBottom) {
            return false;
        }
        int visible = (listBottom - listTop) / ROW_H;
        int idx = (int) ((mouseY - listTop) / ROW_H) + scroll;
        if (idx < 0 || idx >= entries.size()) {
            return false;
        }
        Path p = entries.get(idx);
        if (Files.isDirectory(p)) {
            enter(p);
        } else {
            long now = System.currentTimeMillis();
            if (p.equals(lastClickedFile) && now - lastClickMs < 600) {
                selectedFile = p;
                confirm();
                return true;
            }
            lastClickedFile = p;
            lastClickMs = now;
            selectedFile = p;
            updateChooseButton();
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int visible = (listBottom - listTop) / ROW_H;
        int maxScroll = Math.max(0, entries.size() - visible);
        scroll = Math.max(0, Math.min(maxScroll, scroll - (int) Math.signum(delta) * 3));
        return true;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, 0xDD000000);
        int cx = this.width / 2;
        g.drawCenteredString(this.font, this.title, cx, 18, 0x4ade80);

        // 当前位置(过长则截断)
        String location = drivesView ? "此电脑(可用磁盘)" : current.toAbsolutePath().toString();
        String shown = this.font.plainSubstrByWidth("位置: " + location,
                listRight - listLeft);
        g.drawString(this.font, Component.literal(shown), listLeft, 44, 0x9fd49a, false);
        g.drawString(this.font,
                Component.literal(drivesView
                        ? "单击盘符进入 · 点[此电脑]可随时回到本页"
                        : "单击文件夹进入 · 单击文件选中(双击直接选择) · 滚轮翻页"),
                listLeft, 58, 0x888888, false);

        // 列表背景 + 裁剪
        g.fill(listLeft - 2, listTop - 2, listRight + 2, listBottom + 2, 0xFF1e1e1e);
        g.enableScissor(listLeft - 2, listTop - 2, listRight + 2, listBottom + 2);
        int visible = (listBottom - listTop) / ROW_H;
        for (int i = 0; i < visible; i++) {
            int idx = i + scroll;
            if (idx >= entries.size()) {
                break;
            }
            Path p = entries.get(idx);
            int rowTop = listTop + i * ROW_H;
            boolean hover = mouseX >= listLeft && mouseX <= listRight
                    && mouseY >= rowTop && mouseY <= rowTop + ROW_H;
            boolean isSel = p.equals(selectedFile);
            if (isSel) {
                g.fill(listLeft, rowTop, listRight, rowTop + ROW_H, 0xFF33402f);
            } else if (hover) {
                g.fill(listLeft, rowTop, listRight, rowTop + ROW_H, 0xFF2c2c2c);
            }
            boolean isDir = Files.isDirectory(p);
            // 盘符根目录(C:\)没有文件名段,直接显示完整路径
            String namePart = p.getFileName() != null ? p.getFileName().toString()
                    : p.toString();
            String label = (isDir ? "[目录] " : "[文件] ") + namePart;
            // 目录补尾部斜杠表示可进入;盘符根目录已以 \ 结尾,不能再补,否则变 C:\/
            if (isDir && !namePart.endsWith("/") && !namePart.endsWith("\\")) {
                label += "/";
            }
            label = this.font.plainSubstrByWidth(label, listRight - listLeft - 8);
            int color = isDir ? 0x7db7ff : 0xdddddd;
            g.drawString(this.font, Component.literal(label),
                    listLeft + 4, rowTop + 4, color, false);
        }
        g.disableScissor();

        // 滚动条
        if (entries.size() > visible) {
            int trackH = listBottom - listTop;
            int barH = Math.max(12, trackH * visible / entries.size());
            int maxScroll = Math.max(1, entries.size() - visible);
            int barY = listTop + (trackH - barH) * scroll / maxScroll;
            g.fill(listRight - 2, barY, listRight + 2, barY + barH, 0xFF777777);
        }

        if (!statusLine.isEmpty()) {
            g.drawString(this.font, Component.literal(statusLine),
                    listLeft, listBottom + 6, 0xef4444, false);
        }
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }
}
