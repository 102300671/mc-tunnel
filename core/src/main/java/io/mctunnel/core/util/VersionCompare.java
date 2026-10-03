package io.mctunnel.core.util;

/**
 * 简单语义版本比较.
 * <p>
 * 只做数字逐段比较,兼容前缀 {@code v}/{@code V}(如 {@code v2.1.5}).
 * 位数不同时按 0 补齐:{@code 1.2} vs {@code 1.2.0} 视为相等.
 */
public final class VersionCompare {

    private VersionCompare() {
    }

    /**
     * @param candidate 候选版本(如官方最新)
     * @param current   当前版本(如本地已装)
     * @return candidate 是否比 current 新
     */
    public static boolean isNewer(String candidate, String current) {
        return compare(candidate, current) > 0;
    }

    /**
     * 比较两个版本号.
     *
     * @return 负数 a&lt;b,0 相等,正数 a&gt;b;无法解析的段按 0 处理
     */
    public static int compare(String a, String b) {
        if (a == null || a.isBlank()) a = "0";
        if (b == null || b.isBlank()) b = "0";
        String[] pa = normalize(a).split("\\.");
        String[] pb = normalize(b).split("\\.");
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            int va = i < pa.length ? parseIntSafe(pa[i]) : 0;
            int vb = i < pb.length ? parseIntSafe(pb[i]) : 0;
            if (va != vb) return Integer.compare(va, vb);
        }
        return 0;
    }

    /** 去掉 v/V 前缀与尾部非数字字符 */
    private static String normalize(String v) {
        String s = v.trim();
        if (s.length() > 0 && (s.charAt(0) == 'v' || s.charAt(0) == 'V')) {
            s = s.substring(1);
        }
        // 截掉第一段非 [0-9.] 之后的内容(如 "1.80.2-beta" → "1.80.2")
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (Character.isDigit(c) || c == '.') {
                sb.append(c);
            } else {
                break;
            }
        }
        return sb.toString();
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
