package io.mctunnel.core.room;

import io.mctunnel.core.tunnel.TunnelInfo;
import io.mctunnel.core.tunnel.TunnelTool;
import io.mctunnel.core.tunnel.TunnelType;

import java.util.Map;

/**
 * 本机设备网络状态采集与摘要.
 * <p>
 * 加入房间后,每台设备把自身的联机工具运行状态(ngrok/Tailscale/SyncThing)
 * 上报给房间其他成员,用于显示"这台设备的网络状况".
 */
public final class DeviceStatus {

    private DeviceStatus() {
    }

    /** 采集本机各联机工具状态,返回 JSON 对象字符串 */
    public static String collect(Map<TunnelType, TunnelTool> tools) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (TunnelType t : TunnelType.values()) {
            TunnelTool tool = tools == null ? null : tools.get(t);
            TunnelInfo info = tool == null ? TunnelInfo.notInstalled(t) : tool.getInfo();
            if (!first) sb.append(",");
            sb.append("\"").append(t.name()).append("\":{")
              .append("\"status\":\"").append(info.status().name())
              .append("\",\"publicUrl\":").append(jsonOrNull(info.publicUrl()))
              .append(",\"localPort\":").append(info.localPort())
              .append(",\"daemonRunning\":").append(info.daemonRunning())
              .append(",\"needsAccount\":").append(info.needsAccount())
              .append("}");
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * 从状态 JSON 生成人类可读摘要,如
     * {@code ngrok隧道:https://xxx.ngrok-free.app | tailscale运行中 | syncthing停止}
     */
    public static String summary(String statusJson) {
        if (statusJson == null || statusJson.isBlank()) return "未上报状态";
        StringBuilder sb = new StringBuilder();
        for (TunnelType t : TunnelType.values()) {
            String seg = segment(statusJson, t.name());
            if (seg == null) continue;
            String st = field(seg, "status");
            if (st == null) continue;
            String label = switch (t) {
                case NGROK -> "ngrok";
                case TAILSCALE -> "tailscale";
                case SYNCTHING -> "syncthing";
            };
            switch (st) {
                case "RUNNING" -> {
                    String url = field(seg, "publicUrl");
                    sb.append(label).append(url != null && !url.isBlank()
                            ? "隧道:" + url : "运行中").append(" | ");
                }
                case "STOPPED" -> sb.append(label).append("停止").append(" | ");
                case "NOT_INSTALLED" -> sb.append(label).append("未装").append(" | ");
                case "ERROR" -> sb.append(label).append("出错").append(" | ");
                default -> { }
            }
        }
        String out = sb.toString();
        if (out.endsWith(" | ")) return out.substring(0, out.length() - 3);
        return out.isEmpty() ? "无状态" : out;
    }

    /** 提取 "KEY":{...} 对象子串 */
    private static String segment(String json, String key) {
        String k = "\"" + key + "\":";
        int idx = json.indexOf(k);
        if (idx < 0) return null;
        int start = idx + k.length();
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        if (start >= json.length() || json.charAt(start) != '{') return null;
        int depth = 0;
        boolean inStr = false;
        for (int i = start; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (inStr) {
                if (ch == '\\') i++;
                else if (ch == '"') inStr = false;
            } else if (ch == '"') inStr = true;
            else if (ch == '{') depth++;
            else if (ch == '}') {
                depth--;
                if (depth == 0) return json.substring(start, i + 1);
            }
        }
        return null;
    }

    /** 在对象子串内提取字符串/数值字段 */
    private static String field(String seg, String key) {
        String k = "\"" + key + "\":";
        int idx = seg.indexOf(k);
        if (idx < 0) return null;
        int start = idx + k.length();
        while (start < seg.length() && Character.isWhitespace(seg.charAt(start))) start++;
        if (start >= seg.length()) return null;
        char c = seg.charAt(start);
        if (c == '"') {
            for (int i = start + 1; i < seg.length(); i++) {
                if (seg.charAt(i) == '\\') i++;
                else if (seg.charAt(i) == '"') return seg.substring(start + 1, i);
            }
            return null;
        }
        int end = start;
        while (end < seg.length() && seg.charAt(end) != ',' && seg.charAt(end) != '}') end++;
        return seg.substring(start, end).trim();
    }

    private static String jsonOrNull(String s) {
        return s == null ? "null" : "\"" + escape(s) + "\"";
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
