package io.mctunnel.forge.gui;

import io.mctunnel.forge.MCTunnelMod;
import io.mctunnel.forge.controller.ToolController;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * MC-Tunnel 房间/网络管理 Screen.
 * <p>
 * 提供房间创建/加入/离开/刷新,以及网络创建(穿透/组网)入口.
 * 所有控制器调用在后台线程执行,避免阻塞游戏线程.
 */
public class RoomScreen extends Screen {

    private final Screen parent;
    private final ToolController controller;
    /** 加入链接输入框 */
    private EditBox linkInput;
    /** 当前房间信息(JSON 字符串),null 表示未加入 */
    private String currentRoomJson;
    /** 当前房间的网络列表(JSON 数组字符串) */
    private String networksJson;
    private String message = "";
    private long messageUntil = 0;

    public RoomScreen(Screen parent, ToolController controller) {
        super(Component.literal("MC-Tunnel 房间管理"));
        this.parent = parent;
        this.controller = controller;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y = 45;

        // 顶部行: 创建房间 + 加入房间 + 离开房间 + 刷新 + 加入链接输入框
        int startX = cx - 164;
        addRenderableWidget(Button.builder(Component.literal("创建房间"), b -> roomCreate())
                .bounds(startX, y, 44, 20).build());
        addRenderableWidget(Button.builder(Component.literal("加入房间"), b -> roomJoin())
                .bounds(startX + 47, y, 44, 20).build());
        addRenderableWidget(Button.builder(Component.literal("离开房间"), b -> roomLeave())
                .bounds(startX + 94, y, 44, 20).build());
        addRenderableWidget(Button.builder(Component.literal("刷新"), b -> refresh())
                .bounds(startX + 141, y, 32, 20).build());
        // 加入链接输入框(右侧)
        linkInput = new EditBox(this.font, startX + 179, y, 150, 20,
                Component.literal("加入链接"));
        linkInput.setHint(Component.literal("输入加入链接"));
        linkInput.setMaxLength(512);
        addRenderableWidget(linkInput);

        // 网络行(y=105): 创建穿透(nat/ngrok) + 创建组网(lan/tailscale)
        int ny = 105;
        addRenderableWidget(Button.builder(Component.literal("创建穿透"),
                        b -> networkCreate("nat", "ngrok"))
                .bounds(cx - 90, ny, 80, 20).build());
        addRenderableWidget(Button.builder(Component.literal("创建组网"),
                        b -> networkCreate("virtual", "tailscale"))
                .bounds(cx + 10, ny, 80, 20).build());

        // 返回
        addRenderableWidget(Button.builder(Component.literal("返回"), b -> onClose())
                .bounds(cx - 50, this.height - 35, 100, 20).build());

        // 首次刷新
        refresh();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // 1. 半透明背景(盖住主菜单)
        guiGraphics.fill(0, 0, this.width, this.height, 0xCC000000);

        // 2. 标题
        guiGraphics.drawCenteredString(this.font, this.title,
                this.width / 2, 18, 0x4ade80);

        // 3. 房间信息(y=75)
        String roomDisplay = formatRoomInfo();
        guiGraphics.drawCenteredString(this.font, Component.literal(roomDisplay),
                this.width / 2, 75, 0xffffff);

        // 4. 网络列表(y=105+,在按钮下方)
        String netDisplay = formatNetworks();
        guiGraphics.drawCenteredString(this.font, Component.literal(netDisplay),
                this.width / 2, 130, 0xffffff);

        // 4.1 成员网络状态(y=150 起逐行)
        int sy = 150;
        String membersArr = extractJsonArray(currentRoomJson, "members");
        if (membersArr != null) {
            int shown = 0;
            for (String m : splitJsonArray(membersArr)) {
                if (sy > this.height - 85 || shown >= 12) break;
                String mname = extractJsonField(m, "displayName");
                String summary = extractJsonField(m, "statusSummary");
                String line = (mname == null ? "?" : mname)
                        + "  " + (summary == null || summary.isEmpty() ? "未上报状态" : summary);
                guiGraphics.drawString(this.font, Component.literal(line),
                        20, sy, 0xffffff);
                sy += 12;
                shown++;
            }
        }

        // 5. 提示消息
        if (System.currentTimeMillis() < messageUntil) {
            guiGraphics.drawCenteredString(this.font, Component.literal(message),
                    this.width / 2, this.height - 65, 0xffffff);
        }

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    // ── 格式化显示 ──────────────────────────────────────

    private String formatRoomInfo() {
        if (currentRoomJson == null || currentRoomJson.isEmpty()
                || "null".equals(currentRoomJson)) {
            return "未加入房间";
        }
        String name = extractJsonField(currentRoomJson, "name");
        String id = extractJsonField(currentRoomJson, "id");
        String members = extractJsonField(currentRoomJson, "members");
        StringBuilder sb = new StringBuilder("房间: ");
        sb.append(name != null ? name : "未知");
        if (id != null) sb.append("  ID: ").append(id);
        if (members != null) sb.append("  成员: ").append(members);
        return sb.toString();
    }

    private String formatNetworks() {
        if (networksJson == null || networksJson.isEmpty()
                || "[]".equals(networksJson) || "null".equals(networksJson)) {
            return "暂无网络";
        }
        return "网络: " + truncate(networksJson, 80);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /** 提取 "field":[...] 数组子串(平衡括号,支持嵌套) */
    private static String extractJsonArray(String json, String field) {
        if (json == null) return null;
        String key = "\"" + field + "\":";
        int idx = json.indexOf(key);
        if (idx < 0) return null;
        int start = idx + key.length();
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        if (start >= json.length() || json.charAt(start) != '[') return null;
        int depth = 0;
        boolean inStr = false;
        for (int i = start; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (inStr) {
                if (ch == '\\') i++;
                else if (ch == '"') inStr = false;
            } else if (ch == '"') inStr = true;
            else if (ch == '[') depth++;
            else if (ch == ']') {
                depth--;
                if (depth == 0) return json.substring(start, i + 1);
            }
        }
        return null;
    }

    /** 把 JSON 数组按元素拆分(元素间用逗号,支持对象嵌套) */
    private static java.util.List<String> splitJsonArray(String arr) {
        java.util.List<String> out = new java.util.ArrayList<>();
        int i = 0;
        while (i < arr.length()) {
            char c = arr.charAt(i);
            if (c == '{') {
                int depth = 0;
                boolean inStr = false;
                int j = i;
                for (; j < arr.length(); j++) {
                    char ch = arr.charAt(j);
                    if (inStr) {
                        if (ch == '\\') j++;
                        else if (ch == '"') inStr = false;
                    } else if (ch == '"') inStr = true;
                    else if (ch == '{') depth++;
                    else if (ch == '}') {
                        depth--;
                        if (depth == 0) break;
                    }
                }
                out.add(arr.substring(i, j + 1));
                i = j + 1;
            } else {
                i++;
            }
        }
        return out;
    }

    /** 简易 JSON 字段提取,支持 "field":"value" 与 "field":value 两种形式 */
    private static String extractJsonField(String json, String field) {
        if (json == null) return null;
        String key = "\"" + field + "\":";
        int idx = json.indexOf(key);
        if (idx < 0) return null;
        int start = idx + key.length();
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        if (start >= json.length()) return null;
        char c = json.charAt(start);
        if (c == '"') {
            int end = json.indexOf('"', start + 1);
            if (end < 0) return null;
            return json.substring(start + 1, end);
        }
        int end = start;
        while (end < json.length()
                && json.charAt(end) != ',' && json.charAt(end) != '}'
                && json.charAt(end) != ']') {
            end++;
        }
        return json.substring(start, end).trim();
    }

    // ── 操作(后台线程执行,避免阻塞) ────────────────────

    /** 创建房间,返回加入链接 */
    private void roomCreate() {
        showMessage("创建房间...");
        Thread t = new Thread(() -> {
            try {
                String link = controller.roomCreate("MC-Tunnel 房间");
                this.minecraft.execute(() -> {
                    showMessage(link != null && !link.isEmpty()
                            ? "房间已创建: " + link : "房间已创建");
                    refresh();
                });
            } catch (Exception e) {
                this.minecraft.execute(() -> showMessage("创建失败: " + e.getMessage()));
            }
        }, "mctunnel-room-create");
        t.setDaemon(true);
        t.start();
    }

    /** 通过输入框中的链接加入房间 */
    private void roomJoin() {
        if (linkInput == null) return;
        String link = linkInput.getValue();
        if (link == null || link.trim().isEmpty()) {
            showMessage("请输入加入链接");
            return;
        }
        showMessage("加入房间...");
        Thread t = new Thread(() -> {
            try {
                String result = controller.roomJoin(link.trim());
                this.minecraft.execute(() -> {
                    showMessage(result != null && !result.isEmpty()
                            ? "已加入: " + result : "已加入房间");
                    refresh();
                });
            } catch (Exception e) {
                this.minecraft.execute(() -> showMessage("加入失败: " + e.getMessage()));
            }
        }, "mctunnel-room-join");
        t.setDaemon(true);
        t.start();
    }

    /** 离开当前房间 */
    private void roomLeave() {
        showMessage("离开房间...");
        Thread t = new Thread(() -> {
            try {
                controller.roomLeave();
                this.minecraft.execute(() -> {
                    currentRoomJson = null;
                    networksJson = null;
                    showMessage("已离开房间");
                });
            } catch (Exception e) {
                this.minecraft.execute(() -> showMessage("离开失败: " + e.getMessage()));
            }
        }, "mctunnel-room-leave");
        t.setDaemon(true);
        t.start();
    }

    /** 刷新:先上报本机网络状态,再取房间信息与网络列表 */
    private void refresh() {
        Thread t = new Thread(() -> {
            try {
                try {
                    controller.reportStatus();
                } catch (Exception ignored) {
                    // 上报失败不阻塞刷新(如未加入房间)
                }
                String room = controller.roomCurrent();
                String nets = controller.networkList();
                this.minecraft.execute(() -> {
                    currentRoomJson = room;
                    networksJson = nets;
                    showMessage("已刷新");
                });
            } catch (Exception e) {
                this.minecraft.execute(() -> showMessage("刷新失败: " + e.getMessage()));
            }
        }, "mctunnel-room-refresh");
        t.setDaemon(true);
        t.start();
    }

    /** 在当前房间创建网络(type=nat/lan, name=ngrok/tailscale 等) */
    private void networkCreate(String type, String name) {
        showMessage("创建网络(" + type + "/" + name + ")...");
        Thread t = new Thread(() -> {
            try {
                String result = controller.networkCreate(type, name);
                this.minecraft.execute(() -> {
                    showMessage(result != null && !result.isEmpty()
                            ? "网络已创建: " + result : "网络已创建");
                    refresh();
                });
            } catch (Exception e) {
                this.minecraft.execute(() -> showMessage("创建网络失败: " + e.getMessage()));
            }
        }, "mctunnel-net-create");
        t.setDaemon(true);
        t.start();
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
