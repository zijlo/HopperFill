package com.zijlo.hopperfill.network;

import com.zijlo.hopperfill.data.TemplateStorage;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 设置界面的网络层（1.21.11 / Yarn 映射），合并了原先的 SettingsPayloads 与 SettingsNetworking。
 * 使用 Fabric networking v1：CustomPayload + PacketCodec。
 *
 * <p>{@link #registerServer()} 在 ModInitializer 里调用一次注册 codec 与 C2S 接收器；
 * {@link #open} 是 /hf set gui 的入口。
 */
public final class SettingsNetwork {
    private SettingsNetwork() {}

    // ---------------- 载荷定义 ----------------

    /** S2C：打开设置界面，携带当前黑名单、跳过方块与满盒物品集合 */
    public record OpenSettingsPayload(List<String> blacklist, List<String> skipBlocks,
                                      List<String> boxItems) implements CustomPayload {
        public static final Id<OpenSettingsPayload> ID = new Id<>(Identifier.of("hopperfill", "open_settings"));
        public static final PacketCodec<RegistryByteBuf, OpenSettingsPayload> CODEC = PacketCodec.of(
                (payload, buf) -> {
                    buf.writeCollection(payload.blacklist, PacketByteBuf::writeString);
                    buf.writeCollection(payload.skipBlocks, PacketByteBuf::writeString);
                    buf.writeCollection(payload.boxItems, PacketByteBuf::writeString);
                },
                buf -> new OpenSettingsPayload(
                        buf.readCollection(ArrayList::new, PacketByteBuf::readString),
                        buf.readCollection(ArrayList::new, PacketByteBuf::readString),
                        buf.readCollection(ArrayList::new, PacketByteBuf::readString)
                )
        );

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** C2S：黑名单（give 物品）增删，add=true 添加 / false 删除 */
    public record UpdateBlacklistPayload(String itemId, boolean add) implements CustomPayload {
        public static final Id<UpdateBlacklistPayload> ID = new Id<>(Identifier.of("hopperfill", "update_blacklist"));
        public static final PacketCodec<RegistryByteBuf, UpdateBlacklistPayload> CODEC = PacketCodec.of(
                (payload, buf) -> {
                    buf.writeString(payload.itemId);
                    buf.writeBoolean(payload.add);
                },
                buf -> new UpdateBlacklistPayload(buf.readString(), buf.readBoolean())
        );

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** C2S：满盒物品增删，add=true 添加 / false 删除 */
    public record UpdateBoxPayload(String itemId, boolean add) implements CustomPayload {
        public static final Id<UpdateBoxPayload> ID = new Id<>(Identifier.of("hopperfill", "update_box"));
        public static final PacketCodec<RegistryByteBuf, UpdateBoxPayload> CODEC = PacketCodec.of(
                (payload, buf) -> {
                    buf.writeString(payload.itemId);
                    buf.writeBoolean(payload.add);
                },
                buf -> new UpdateBoxPayload(buf.readString(), buf.readBoolean())
        );

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** C2S：跳过方块增删，add=true 添加 / false 删除 */
    public record UpdateSkipBlockPayload(String blockId, boolean add) implements CustomPayload {
        public static final Id<UpdateSkipBlockPayload> ID = new Id<>(Identifier.of("hopperfill", "update_skip_block"));
        public static final PacketCodec<RegistryByteBuf, UpdateSkipBlockPayload> CODEC = PacketCodec.of(
                (payload, buf) -> {
                    buf.writeString(payload.blockId);
                    buf.writeBoolean(payload.add);
                },
                buf -> new UpdateSkipBlockPayload(buf.readString(), buf.readBoolean())
        );

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** S2C：区域扫描统计结果。names 与 nonStackable 一一对应；nonStackableNames 为去重后的不可堆叠物品名。
     *  由客户端按真实字体宽度精确对齐多列排版，避免服务端无法测量客户端字体导致的错位。 */
    public record ScanResultPayload(List<String> names, List<Boolean> nonStackable,
                                    List<String> nonStackableNames, int totalPositions) implements CustomPayload {
        public static final Id<ScanResultPayload> ID = new Id<>(Identifier.of("hopperfill", "scan_result"));
        public static final PacketCodec<RegistryByteBuf, ScanResultPayload> CODEC = PacketCodec.of(
                (payload, buf) -> {
                    buf.writeCollection(payload.names, PacketByteBuf::writeString);
                    buf.writeCollection(payload.nonStackable, PacketByteBuf::writeBoolean);
                    buf.writeCollection(payload.nonStackableNames, PacketByteBuf::writeString);
                    buf.writeVarInt(payload.totalPositions);
                },
                buf -> new ScanResultPayload(
                        buf.readCollection(ArrayList::new, PacketByteBuf::readString),
                        buf.readCollection(ArrayList::new, PacketByteBuf::readBoolean),
                        buf.readCollection(ArrayList::new, PacketByteBuf::readString),
                        buf.readVarInt()
                )
        );

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    // ---------------- 服务端注册 ----------------

    /** 在 ModInitializer 里调用一次 */
    public static void registerServer() {
        PayloadTypeRegistry.playS2C().register(OpenSettingsPayload.ID, OpenSettingsPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ScanResultPayload.ID, ScanResultPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(UpdateBlacklistPayload.ID, UpdateBlacklistPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(UpdateSkipBlockPayload.ID, UpdateSkipBlockPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(UpdateBoxPayload.ID, UpdateBoxPayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(UpdateBlacklistPayload.ID, (payload, ctx) -> {
            ServerPlayerEntity p = ctx.player();
            Identifier id = Identifier.of(payload.itemId());
            if (payload.add()) {
                TemplateStorage.addGiveBlacklist(p, id);
            } else {
                TemplateStorage.removeGiveBlacklist(p, id);
            }
        });

        ServerPlayNetworking.registerGlobalReceiver(UpdateSkipBlockPayload.ID, (payload, ctx) -> {
            ServerPlayerEntity p = ctx.player();
            Identifier id = Identifier.of(payload.blockId());
            if (payload.add()) {
                TemplateStorage.addBlockId(p, id);
            } else {
                TemplateStorage.removeBlockId(p, id);
            }
        });

        ServerPlayNetworking.registerGlobalReceiver(UpdateBoxPayload.ID, (payload, ctx) -> {
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
        ServerPlayNetworking.send(player, new OpenSettingsPayload(
                toStrings(TemplateStorage.getGiveBlacklist(player)),
                toStrings(TemplateStorage.getBlockIds(player)),
                toStrings(TemplateStorage.getBoxItems(player))));
    }

    private static List<String> toStrings(Set<Identifier> ids) {
        List<String> out = new ArrayList<>(ids.size());
        for (Identifier id : ids) {
            out.add(id.toString());
        }
        return out;
    }
}
