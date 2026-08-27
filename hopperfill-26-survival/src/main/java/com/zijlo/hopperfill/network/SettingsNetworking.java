package com.zijlo.hopperfill.network;

import com.zijlo.hopperfill.data.TemplateStorage;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 设置界面的服务端网络层（26.x / Mojang 非混淆命名）。
 * 1) registerServer()：注册 C2S/S2C 载荷 codec 与 C2S 接收器（黑名单 / 跳过方块 增删）。
 * 2) open(player)：把当前「黑名单 + 跳过方块」打包成 S2C 包发给客户端，令其打开设置界面。
 */
public final class SettingsNetworking {
    private SettingsNetworking() {}

    /** 在 ModInitializer 里调用一次（onInitialize 同时跑在客户端与服务端，codec 两侧都注册到） */
    public static void registerServer() {
        PayloadTypeRegistry.clientboundPlay().register(SettingsPayloads.OpenSettingsPayload.TYPE, SettingsPayloads.OpenSettingsPayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(SettingsPayloads.ScanResultPayload.TYPE, SettingsPayloads.ScanResultPayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SettingsPayloads.UpdateBlacklistPayload.TYPE, SettingsPayloads.UpdateBlacklistPayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SettingsPayloads.UpdateSkipBlockPayload.TYPE, SettingsPayloads.UpdateSkipBlockPayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SettingsPayloads.UpdateBoxPayload.TYPE, SettingsPayloads.UpdateBoxPayload.STREAM_CODEC);

        ServerPlayNetworking.registerGlobalReceiver(SettingsPayloads.UpdateBlacklistPayload.TYPE, (payload, ctx) -> {
            ServerPlayer p = ctx.player();
            Identifier id = Identifier.parse(payload.itemId());
            if (payload.add()) {
                TemplateStorage.addGiveBlacklist(p, id);
            } else {
                TemplateStorage.removeGiveBlacklist(p, id);
            }
        });

        ServerPlayNetworking.registerGlobalReceiver(SettingsPayloads.UpdateSkipBlockPayload.TYPE, (payload, ctx) -> {
            ServerPlayer p = ctx.player();
            Identifier id = Identifier.parse(payload.blockId());
            if (payload.add()) {
                TemplateStorage.addBlockId(p, id);
            } else {
                TemplateStorage.removeBlockId(p, id);
            }
        });

        ServerPlayNetworking.registerGlobalReceiver(SettingsPayloads.UpdateBoxPayload.TYPE, (payload, ctx) -> {
            ServerPlayer p = ctx.player();
            Identifier id = Identifier.parse(payload.itemId());
            if (payload.add()) {
                TemplateStorage.addBoxItem(p, id);
            } else {
                TemplateStorage.removeBoxItem(p, id);
            }
        });
    }

    /** /hf set gui 命令入口：发送打开界面的 S2C 包 */
    public static void open(ServerPlayer player) {
        Set<Identifier> blacklist = TemplateStorage.getGiveBlacklist(player);
        Set<Identifier> skipBlocks = TemplateStorage.getBlockIds(player);
        Set<Identifier> boxItems = TemplateStorage.getBoxItems(player);

        List<String> bl = new ArrayList<>(blacklist.size());
        for (Identifier id : blacklist) bl.add(id.toString());
        List<String> sb = new ArrayList<>(skipBlocks.size());
        for (Identifier id : skipBlocks) sb.add(id.toString());
        List<String> bx = new ArrayList<>(boxItems.size());
        for (Identifier id : boxItems) bx.add(id.toString());

        ServerPlayNetworking.send(player, new SettingsPayloads.OpenSettingsPayload(bl, sb, bx));
    }
}
