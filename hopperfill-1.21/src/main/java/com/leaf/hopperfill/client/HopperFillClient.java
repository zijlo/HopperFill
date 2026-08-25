package com.leaf.hopperfill.client;

import com.leaf.hopperfill.HopperFillMod;
import com.leaf.hopperfill.client.gui.SettingsScreen;
import com.leaf.hopperfill.client.gui.TemplateScreen;
import com.leaf.hopperfill.network.SettingsPayloads;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreens;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 客户端入口（1.21.11 / Yarn 映射）。
 * 1) 注册模板界面（复用原版漏斗样式 + 16/64 切换按钮）。
 * 2) 注册设置界面的 S2C 接收器，收到 open_settings 时打开 SettingsScreen。
 * 注意：载荷 codec 已在公共入口（SettingsNetworking.registerServer）注册一次，
 *       此处不可重复注册，否则集成环境下同一 ID 会重复注册导致崩溃。
 */
public class HopperFillClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        HandledScreens.register(HopperFillMod.TEMPLATE_SCREEN_HANDLER, TemplateScreen::new);

        ClientPlayNetworking.registerGlobalReceiver(SettingsPayloads.OpenSettingsPayload.ID, (payload, ctx) -> {
            MinecraftClient client = ctx.client();
            Set<String> blacklist = new LinkedHashSet<>(payload.blacklist());
            Set<String> skipBlocks = new LinkedHashSet<>(payload.skipBlocks());
            client.execute(() -> client.setScreen(new SettingsScreen(blacklist, skipBlocks)));
        });
    }
}
