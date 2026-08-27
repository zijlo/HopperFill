package com.zijlo.hopperfill.network;

import com.zijlo.hopperfill.data.TemplateStorage;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 设置界面的服务端网络层（1.21.11 / Yarn 映射）。
 * 1) registerServer()：注册 C2S 接收器（黑名单 / 跳过方块 增删）与载荷 codec。
 * 2) open(player)：把当前「黑名单 + 跳过方块」打包成 S2C 包发给客户端，令其打开设置界面。
 */
public final class SettingsNetworking {
    private SettingsNetworking() {}

    /** 在 ModInitializer 里调用一次 */
    public static void registerServer() {
        PayloadTypeRegistry.playS2C().register(SettingsPayloads.OpenSettingsPayload.ID, SettingsPayloads.OpenSettingsPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(SettingsPayloads.ScanResultPayload.ID, SettingsPayloads.ScanResultPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(SettingsPayloads.UpdateBlacklistPayload.ID, SettingsPayloads.UpdateBlacklistPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(SettingsPayloads.UpdateSkipBlockPayload.ID, SettingsPayloads.UpdateSkipBlockPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(SettingsPayloads.UpdateBoxPayload.ID, SettingsPayloads.UpdateBoxPayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(SettingsPayloads.UpdateBlacklistPayload.ID, (payload, ctx) -> {
            ServerPlayerEntity p = ctx.player();
            Identifier id = Identifier.of(payload.itemId());
            if (payload.add()) {
                TemplateStorage.addGiveBlacklist(p, id);
            } else {
                TemplateStorage.removeGiveBlacklist(p, id);
            }
        });

        ServerPlayNetworking.registerGlobalReceiver(SettingsPayloads.UpdateSkipBlockPayload.ID, (payload, ctx) -> {
            ServerPlayerEntity p = ctx.player();
            Identifier id = Identifier.of(payload.blockId());
            if (payload.add()) {
                TemplateStorage.addBlockId(p, id);
            } else {
                TemplateStorage.removeBlockId(p, id);
            }
        });

        ServerPlayNetworking.registerGlobalReceiver(SettingsPayloads.UpdateBoxPayload.ID, (payload, ctx) -> {
            ServerPlayerEntity p = ctx.player();
            Identifier id = Identifier.of(payload.itemId());
            if (payload.add()) {
                TemplateStorage.addBoxItem(p, id);
            } else {
                TemplateStorage.removeBoxItem(p, id);
            }
        });
    }

    /** /hf set gui 命令入口：发送打开界面的 S2C 包 */
    public static void open(ServerPlayerEntity player) {
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
