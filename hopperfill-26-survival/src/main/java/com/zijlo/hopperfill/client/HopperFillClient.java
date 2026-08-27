package com.zijlo.hopperfill.client;

import com.zijlo.hopperfill.HopperFillMod;
import com.zijlo.hopperfill.client.gui.SettingsScreen;
import com.zijlo.hopperfill.client.gui.TemplateScreen;
import com.zijlo.hopperfill.network.SettingsPayloads;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 客户端入口（26.x / Mojang 非混淆命名）。
 * 注册设置界面的 S2C 接收器，以及区域扫描结果的 S2C 接收器。
 * 注意：载荷 codec 已在公共入口（SettingsNetworking.registerServer，由 onInitialize 在两侧调用）注册一次，
 *       此处不可重复注册，否则集成环境下同一 ID 会重复注册导致崩溃。
 */
public class HopperFillClient implements ClientModInitializer {
    /** 聊天框排版预算宽度（像素），防止列过多导致自动换行 */
    private static final int SCAN_CHAT_WIDTH_PX = 300;
    /** 单条聊天消息最多包含的行数，避免消息过长被截断 */
    private static final int SCAN_MESSAGE_BATCH = 10;

    @Override
    public void onInitializeClient() {
        MenuScreens.register(HopperFillMod.TEMPLATE_SCREEN_HANDLER, TemplateScreen::new);

        ClientPlayNetworking.registerGlobalReceiver(SettingsPayloads.OpenSettingsPayload.TYPE, (payload, ctx) -> {
            Minecraft client = ctx.client();
            Set<String> blacklist = new LinkedHashSet<>(payload.blacklist());
            Set<String> skipBlocks = new LinkedHashSet<>(payload.skipBlocks());
            Set<String> boxItems = new LinkedHashSet<>(payload.boxItems());
            client.execute(() -> client.setScreenAndShow(new SettingsScreen(blacklist, skipBlocks, boxItems)));
        });

        ClientPlayNetworking.registerGlobalReceiver(SettingsPayloads.ScanResultPayload.TYPE, (payload, ctx) -> {
            Minecraft client = ctx.client();
            client.execute(() -> renderScanResult(client, payload));
        });
    }

    /**
     * 用客户端的真实字体（Font.width）量出每个物品名的像素宽度，据此对齐多列排版的统计表格。
     * 服务端无法获知客户端字体，所以这里才接入网络层精确排版——彻底解决原「中文=2/其他=1」估算造成的错位。
     */
    private static void renderScanResult(Minecraft client, SettingsPayloads.ScanResultPayload payload) {
        Font font = client.font; // 26.x 亦可用 client.gui.getFont()，二者等价
        ChatComponent chat = getChatComponent(client);

        chat.addClientSystemMessage(Component.literal("===== 区域内容统计 =====").withStyle(ChatFormatting.GOLD));

        List<String> names = payload.names();
        List<Boolean> nonStackable = payload.nonStackable();

        if (!names.isEmpty()) {
            int spaceW = Math.max(1, font.width(" "));
            int maxW = 0;
            for (int i = 0; i < names.size(); i++) {
                int w = font.width(names.get(i) + (nonStackable.get(i) ? "*" : ""));
                if (w > maxW) maxW = w;
            }
            int colW = maxW + spaceW * 2;
            int cols = Math.max(1, SCAN_CHAT_WIDTH_PX / colW);

            MutableComponent batch = Component.literal("");
            int lines = 0;
            for (int i = 0; i < names.size(); i += cols) {
                if (lines > 0) batch.append(Component.literal("\n"));
                for (int c = 0; c < cols; c++) {
                    int idx = i + c;
                    if (idx >= names.size()) break;
                    String name = names.get(idx);
                    boolean ns = nonStackable.get(idx);
                    String visible = name + (ns ? "*" : "");
                    batch.append(Component.literal(visible)
                            .withStyle(ns ? ChatFormatting.RED : ChatFormatting.GRAY));
                    int pad = (colW - font.width(visible)) / spaceW;
                    if (pad > 0) {
                        StringBuilder sb = new StringBuilder(pad);
                        for (int s = 0; s < pad; s++) sb.append(' ');
                        batch.append(Component.literal(sb.toString()));
                    }
                }
                lines++;
                if (lines >= SCAN_MESSAGE_BATCH) {
                    chat.addClientSystemMessage(batch);
                    batch = Component.literal("");
                    lines = 0;
                }
            }
            if (lines > 0) {
                chat.addClientSystemMessage(batch);
            }
        }

        chat.addClientSystemMessage(Component.literal(
                        "共 " + payload.totalPositions() + " 个位置（" + names.size() + " 个有效物品）")
                .withStyle(ChatFormatting.GOLD));

        List<String> nsNames = payload.nonStackableNames();
        if (!nsNames.isEmpty()) {
            MutableComponent warn = Component.literal(
                    "⚠ 检测到 " + nsNames.size() + " 种不可堆叠物品，无法填充漏斗：\n")
                    .withStyle(ChatFormatting.RED);
            int listed = 0;
            for (String n : nsNames) {
                if (listed >= 8) {
                    warn.append(Component.literal("   ……等共 " + nsNames.size() + " 种\n")
                            .withStyle(ChatFormatting.GRAY));
                    break;
                }
                warn.append(Component.literal("   • " + n + "\n").withStyle(ChatFormatting.RED));
                listed++;
            }
            warn.append(Component.literal("请将这些物品加入跳过方块（/hf set gui）后重新 scan")
                    .withStyle(ChatFormatting.GRAY));
            chat.addClientSystemMessage(warn);
        }
    }

    /**
     * 跨 26.1 / 26.2 获取聊天组件 ChatComponent。
     * 26.1.x 为 Gui.getChat()；26.2 起 GUI 重构，改为 Gui.hud.getChat()。
     * 通过反射兼容两种签名，避免同一份源码在不同小版本编译失败。
     */
    private static ChatComponent getChatComponent(Minecraft client) {
        Object gui = client.gui;
        try {
            return (ChatComponent) gui.getClass().getMethod("getChat").invoke(gui);
        } catch (NoSuchMethodException e) {
            try {
                Field hudField = gui.getClass().getField("hud");
                Object hud = hudField.get(gui);
                return (ChatComponent) hud.getClass().getMethod("getChat").invoke(hud);
            } catch (ReflectiveOperationException e2) {
                throw new IllegalStateException("无法获取聊天组件", e2);
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法获取聊天组件", e);
        }
    }
}