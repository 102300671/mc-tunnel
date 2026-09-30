package io.mctunnel.forge.chat;

import io.mctunnel.core.chat.ChatMessage;
import io.mctunnel.core.chat.ChatWebSocketClient;
import io.mctunnel.forge.MCTunnelMod;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientChatEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;

/**
 * 游戏内聊天同步处理器.
 * <p>
 * 职责:
 * <ul>
 *   <li>连接后端 ChatServer WebSocket,接收跨设备消息并显示在游戏聊天栏</li>
 *   <li>拦截玩家发送的聊天消息({@link ClientChatEvent}),转发到 WebSocket 进行跨设备同步</li>
 * </ul>
 * 玩家自己发出的消息既会同步到其他设备,也会在本地聊天栏显示(由 Minecraft 自身处理).
 * 从其他设备收到的消息以 {@code [节点名] 玩家: 内容} 格式显示.
 */
@Mod.EventBusSubscriber(modid = MCTunnelMod.MODID, value = Dist.CLIENT)
public class IngameChatHandler {

    private static ChatWebSocketClient client;
    private static volatile boolean enabled = false;

    private IngameChatHandler() {
    }

    /**
     * 初始化聊天同步客户端.
     *
     * @param wsUrl ChatServer WebSocket 地址,如 ws://localhost:8788
     */
    public static void init(String wsUrl) {
        if (client != null) {
            client.close();
        }
        client = new ChatWebSocketClient(wsUrl);
        client.setMessageHandler(IngameChatHandler::displayIncoming);
        try {
            client.connect();
            enabled = true;
            System.out.println("[MC-Tunnel] In-game chat sync connected to " + wsUrl);
        } catch (IOException e) {
            System.err.println("[MC-Tunnel] Failed to connect chat WebSocket: " + e.getMessage());
            enabled = false;
        }
    }

    public static boolean isEnabled() {
        return enabled && client != null && client.isRunning();
    }

    public static void shutdown() {
        enabled = false;
        if (client != null) {
            client.close();
            client = null;
        }
    }

    /** 拦截玩家聊天,转发到 WebSocket */
    @SubscribeEvent
    public static void onClientChat(ClientChatEvent event) {
        if (!isEnabled()) return;
        String content = event.getMessage();
        // 命令(以 / 开头)不同步
        if (content.startsWith("/")) return;

        String sender = getPlayerName();
        try {
            client.send(sender, content);
        } catch (IOException e) {
            System.err.println("[MC-Tunnel] Failed to sync chat: " + e.getMessage());
        }
        // 不取消事件,让 Minecraft 正常发送本地聊天(单/多人服内玩家也能看到)
    }

    /** 显示从其他节点收到的消息 */
    private static void displayIncoming(ChatMessage msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null) return;
        // 在主线程执行(消息回调在 WS 读取线程)
        mc.execute(() -> {
            String prefix = msg.nodeId() != null ? "[" + msg.nodeId() + "] " : "";
            Component component = Component.literal(
                    prefix + msg.sender() + ": " + msg.content());
            mc.gui.getChat().addMessage(component);
        });
    }

    private static String getPlayerName() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null) {
            return mc.player.getGameProfile().getName();
        }
        return "Player";
    }
}
