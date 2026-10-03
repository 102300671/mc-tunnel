package io.mctunnel.forge;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

/**
 * 模组配置(common).
 * <p>
 * 当前仅包含 WebUI 开关,游戏内可随时切换并即时生效.
 */
public final class MCTunnelConfig {

    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    /** 是否启用 WebUI/HTTP API 服务(默认 8787 端口) */
    private static final ForgeConfigSpec.BooleanValue ENABLE_WEB_UI = BUILDER
            .comment("Whether to start the embedded WebUI/HTTP API server (port 8787).",
                    "Can be toggled in-game from the MC-Tunnel screen.")
            .define("enableWebUi", true);

    static final ForgeConfigSpec SPEC = BUILDER.build();

    private MCTunnelConfig() {
    }

    /** 在模组构造时注册配置 */
    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, SPEC);
    }

    public static boolean isWebUiEnabled() {
        return ENABLE_WEB_UI.get();
    }

    /** 更新配置值(立即写盘) */
    public static void setWebUiEnabled(boolean enabled) {
        ENABLE_WEB_UI.set(enabled);
    }
}
