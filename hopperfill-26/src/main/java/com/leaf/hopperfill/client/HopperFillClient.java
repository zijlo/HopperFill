package com.leaf.hopperfill.client;

import net.fabricmc.api.ClientModInitializer;

/**
 * 客户端入口（框架版，26.x / Mojang 非混淆命名）。
 * 负责在客户端拦截 S2C 的「打开设置界面」包并弹出 SettingsScreen。
 */
public class HopperFillClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // TODO(26.x): ClientPlayNetworking.registerGlobalReceiver(OpenSettingsS2CPayload.ID, (payload, ctx) -> {
        //     Minecraft client = Minecraft.getInstance();
        //     client.execute(() -> client.setScreen(new SettingsScreen(
        //             payload.blacklist(), payload.skipBlocks())));
        // });
    }
}