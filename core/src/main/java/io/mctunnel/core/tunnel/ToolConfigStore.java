package io.mctunnel.core.tunnel;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具元数据存取(键值对),如 ngrok 安装目录、已记录的二进制完整路径.
 * <p>
 * 宿主把实现绑定到持久化存储(如 ChatStorage 的 tunnel_configs 表);
 * 未绑定时用内存实现,仅当前进程有效.
 */
public interface ToolConfigStore {

    String get(String tool, String key);

    void put(String tool, String key, String value);

    /** 默认读取(带默认值) */
    default String get(String tool, String key, String def) {
        String v = get(tool, key);
        return v == null ? def : v;
    }

    /** 内存实现(进程级,不持久化) */
    static ToolConfigStore inMemory() {
        return new ToolConfigStore() {
            private final Map<String, Map<String, String>> data = new ConcurrentHashMap<>();

            @Override
            public String get(String tool, String key) {
                Map<String, String> m = data.get(tool);
                return m == null ? null : m.get(key);
            }

            @Override
            public void put(String tool, String key, String value) {
                data.computeIfAbsent(tool, k -> new ConcurrentHashMap<>()).put(key, value);
            }
        };
    }
}
