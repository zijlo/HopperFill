package com.zijlo.hopperfill.client;

import com.zijlo.hopperfill.HopperFillMod;
import com.zijlo.hopperfill.client.gui.SettingsScreen;
import com.zijlo.hopperfill.client.gui.TemplateScreen;
import com.zijlo.hopperfill.network.SettingsNetwork;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.screen.ingame.HandledScreens;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 客户端入口（1.21.11 / Yarn 映射）。
 * 1) 注册模板界面（复用原版漏斗样式 + 16/64 切换按钮）。
 * 2) 注册设置界面的 S2C 接收器，收到 open_settings 时打开 SettingsScreen。
 * 3) 注册区域扫描结果的 S2C 接收器，用客户端真实字体宽度精确对齐多列排版。
 * 注意：载荷 codec 已在公共入口（SettingsNetworking.registerServer）注册一次，
 *       此处不可重复注册，否则集成环境下同一 ID 会重复注册导致崩溃。
 */
public class HopperFillClient implements ClientModInitializer {
    /** 聊天框排版预算宽度（像素），防止列过多导致自动换行 */
    private static final int SCAN_CHAT_WIDTH_PX = 300;
    /** 单条聊天消息最多包含的行数，避免消息过长被截断 */
    private static final int SCAN_MESSAGE_BATCH = 10;

    @Override
    public void onInitializeClient() {
        HandledScreens.register(HopperFillMod.TEMPLATE_SCREEN_HANDLER, TemplateScreen::new);

        ClientPlayNetworking.registerGlobalReceiver(SettingsNetwork.OpenSettingsPayload.ID, (payload, ctx) -> {
            MinecraftClient client = ctx.client();
            Set<String> blacklist = new LinkedHashSet<>(payload.blacklist());
            Set<String> skipBlocks = new LinkedHashSet<>(payload.skipBlocks());
            Set<String> boxItems = new LinkedHashSet<>(payload.boxItems());
            client.execute(() -> client.setScreen(new SettingsScreen(blacklist, skipBlocks, boxItems)));
        });

        ClientPlayNetworking.registerGlobalReceiver(SettingsNetwork.ScanResultPayload.ID, (payload, ctx) -> {
            MinecraftClient client = ctx.client();
            client.execute(() -> renderScanResult(client, payload));
        });
    }

    /**
     * 用客户端的真实字体（TextRenderer.getWidth）量出每个物品名的像素宽度，据此对齐多列排版的统计表格。
     * 服务端无法获知客户端字体，所以这里才接入网络层精确排版——彻底解决原「中文=2/其他=1」估算造成的错位。
     */
    private static void renderScanResult(MinecraftClient client, SettingsNetwork.ScanResultPayload payload) {
        TextRenderer font = client.textRenderer;
        ChatHud chat = client.inGameHud.getChatHud();

        chat.addMessage(Text.literal("===== 区域内容统计 =====").formatted(Formatting.GOLD));

        List<String> names = payload.names();
        List<Boolean> nonStackable = payload.nonStackable();

        if (!names.isEmpty()) {
            int spaceW = Math.max(1, font.getWidth(" "));
            int maxW = 0;
            for (int i = 0; i < names.size(); i++) {
                int w = font.getWidth(names.get(i) + (nonStackable.get(i) ? "*" : ""));
                if (w > maxW) maxW = w;
            }
            int colW = maxW + spaceW * 2;
            int cols = Math.max(1, SCAN_CHAT_WIDTH_PX / colW);

            MutableText batch = Text.literal("");
            int lines = 0;
            for (int i = 0; i < names.size(); i += cols) {
                if (lines > 0) batch.append(Text.literal("\n"));
                for (int c = 0; c < cols; c++) {
                    int idx = i + c;
                    if (idx >= names.size()) break;
                    String name = names.get(idx);
                    boolean ns = nonStackable.get(idx);
                    String visible = name + (ns ? "*" : "");
                    batch.append(Text.literal(visible)
                            .formatted(ns ? Formatting.RED : Formatting.GRAY));
                    int pad = (colW - font.getWidth(visible)) / spaceW;
                    if (pad > 0) {
                        StringBuilder sb = new StringBuilder(pad);
                        for (int s = 0; s < pad; s++) sb.append(' ');
                        batch.append(Text.literal(sb.toString()));
                    }
                }
                lines++;
                if (lines >= SCAN_MESSAGE_BATCH) {
                    chat.addMessage(batch);
                    batch = Text.literal("");
                    lines = 0;
                }
            }
            if (lines > 0) {
                chat.addMessage(batch);
            }
        }

        chat.addMessage(Text.literal(
                        "共 " + payload.totalPositions() + " 个位置（" + names.size() + " 个有效物品）")
                .formatted(Formatting.GOLD));

        List<String> nsNames = payload.nonStackableNames();
        if (!nsNames.isEmpty()) {
            MutableText warn = Text.literal(
                    "⚠ 检测到 " + nsNames.size() + " 种不可堆叠物品，无法填充漏斗：\n")
                    .formatted(Formatting.RED);
            int listed = 0;
            for (String n : nsNames) {
                if (listed >= 8) {
                    warn.append(Text.literal("   ……等共 " + nsNames.size() + " 种\n")
                            .formatted(Formatting.GRAY));
                    break;
                }
                warn.append(Text.literal("   • " + n + "\n").formatted(Formatting.RED));
                listed++;
            }
            warn.append(Text.literal("请将这些物品加入跳过方块（/hf set gui）后重新 scan")
                    .formatted(Formatting.GRAY));
            chat.addMessage(warn);
        }
    }
}
