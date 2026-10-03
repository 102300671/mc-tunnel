package io.mctunnel.core.tunnel;

import java.io.IOException;

/**
 * 安装需要用户指定解压目录时抛出.
 * <p>
 * 典型场景:Windows 上的 ngrok zip 需要用户选择解压位置.
 * WebUI 收到此异常后返回 {@code {"needsExtractDir":true}},
 * 前端提示用户输入目录后携带 extractDir 重新调用安装.
 */
public class ExtractDirRequiredException extends IOException {

    public ExtractDirRequiredException() {
        super("NEEDS_EXTRACT_DIR");
    }
}
