package com.leaf.hopperfill.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * HopperFill 设置界面的网络载荷（1.21.11 / Yarn 映射）。
 * 使用 Fabric networking v1：CustomPayload + PacketCodec。
 */
public final class SettingsPayloads {
    private SettingsPayloads() {}

    /** S2C：打开设置界面，携带当前黑名单与跳过方块集合 */
    public record OpenSettingsPayload(List<String> blacklist, List<String> skipBlocks) implements CustomPayload {
        public static final Id<OpenSettingsPayload> ID = new Id<>(Identifier.of("hopperfill", "open_settings"));
        public static final PacketCodec<RegistryByteBuf, OpenSettingsPayload> CODEC = PacketCodec.of(
                (payload, buf) -> {
                    buf.writeCollection(payload.blacklist, PacketByteBuf::writeString);
                    buf.writeCollection(payload.skipBlocks, PacketByteBuf::writeString);
                },
                buf -> new OpenSettingsPayload(
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
}
