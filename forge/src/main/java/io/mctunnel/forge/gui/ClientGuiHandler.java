package io.mctunnel.forge.gui;

import io.mctunnel.forge.MCTunnelMod;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 客户端事件:在主菜单添加 MC-Tunnel 按钮.
 */
@Mod.EventBusSubscriber(modid = MCTunnelMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ClientGuiHandler {

    @SubscribeEvent
    public static void onScreenInitPost(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof TitleScreen)) {
            return;
        }

        // 在主菜单右下角添加 MC-Tunnel 按钮
        int x = event.getScreen().width - 110;
        int y = event.getScreen().height - 30;

        event.addListener(Button.builder(
                Component.literal("MC-Tunnel"),
                b -> event.getScreen().getMinecraft().setScreen(
                        new TunnelScreen(event.getScreen())))
                .bounds(x, y, 100, 20)
                .build());
    }
}
