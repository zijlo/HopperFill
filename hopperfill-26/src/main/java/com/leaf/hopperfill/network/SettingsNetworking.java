package com.leaf.hopperfill.network;

import com.leaf.hopperfill.data.TemplateStorage;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.util.Set;

/**
 * 设置界面的服务端网络层（框架版，26.x / Mojang 非混淆命名）。
 *
 * 职责：
 *  1) open(player)：把当前「黑名单 + 跳过方块」打包成 S2C 包发给客户端，令其打开设置界面
 *  2) 注册 C2S 接收器，处理客户端点击产生的增量更新（黑名单 / 跳过方块 增删）
 *
 * ⚠ 跨版本 API 敏感：26.x 非混淆后采用 CustomPacketPayload.Type + StreamCodec +
 * FriendlyByteBuf / RegistryFriendlyByteBuf。此处仅给结构 + TODO，逐版本构建时按
 * docs.fabricmc.net 对应版本 networking 文档校对。
 */
public final class SettingsNetworking {
    private SettingsNetworking() {}

    // ── 待定义 payload（每个版本按下述字段实现，codec 用手动 ByteBuf 读写） ──
    // OpenSettingsS2CPayload(List<String> blacklist, List<String> skipBlocks)  // 打开界面
    // UpdateBlacklistC2SPayload(String itemId, boolean add)                   // 黑名单增删
    // UpdateSkipBlockC2SPayload(String blockId, boolean add)                  // 跳过方块增删

    /** /hf set gui 命令入口：发送打开界面的 S2C 包 */
    public static void open(ServerPlayer player) {
        Set<Identifier> blacklist = TemplateStorage.getGiveBlacklist(player);
        Set<Identifier> skipBlocks = TemplateStorage.getBlockIds(player);
        // TODO(26.x): 构造 OpenSettingsS2CPayload（集合 -> List<String>），
        //  然后 ServerPlayNetworking.send(player, payload)。
    }

    /** 在 ModInitializer 里调用一次，注册 C2S 接收器 */
    public static void registerServer() {
        // TODO(26.x):
        // ServerPlayNetworking.registerGlobalReceiver(UpdateBlacklistC2SPayload.ID, (payload, ctx) -> {
        //     ServerPlayer p = ctx.player();
        //     Identifier id = Identifier.parse(payload.itemId());
        //     if (payload.add()) TemplateStorage.addGiveBlacklist(p, id);
        //     else TemplateStorage.removeGiveBlacklist(p, id);
        // });
        //
        // ServerPlayNetworking.registerGlobalReceiver(UpdateSkipBlockC2SPayload.ID, (payload, ctx) -> {
        //     ServerPlayer p = ctx.player();
        //     Identifier id = Identifier.parse(payload.blockId());
        //     if (payload.add()) TemplateStorage.addBlockId(p, id);
        //     else TemplateStorage.removeBlockId(p, id);
        // });
    }
}