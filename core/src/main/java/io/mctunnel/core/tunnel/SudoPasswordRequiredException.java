package io.mctunnel.core.tunnel;

import java.io.IOException;

/**
 * 需要 sudo 密码才能继续(非 root 用户执行 systemctl 启停系统服务时).
 * <p>
 * 调用方应安全地向用户收集密码(不回显、不记录、不持久化),
 * 然后通过带密码参数的方法重试;密码仅经 sudo 的 stdin 传递,
 * 绝不出现在命令行参数/日志/配置中.
 */
public class SudoPasswordRequiredException extends IOException {

    public SudoPasswordRequiredException(String message) {
        super(message);
    }
}
