package com.zijlo.hopperfill.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * HopperFill 设置界面的网络载荷（26.x / Mojang 非混淆命名）。
 * 使用 Fabric networking v1：CustomPacketPayload + StreamCodec。
 */
public final class SettingsPayloads {
    private SettingsPayloads() {}

    /** S2C：打开设置界面，携带当前黑名单、跳过方块与满盒物品集合 */
    public record OpenSettingsPayload(List<String> blacklist, List<String> skipBlocks, List<String> boxItems) implements CustomPacketPayload {
        public static final Type<OpenSettingsPayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath("hopperfill", "open_settings"));
        public static final StreamCodec<FriendlyByteBuf, OpenSettingsPayload> STREAM_CODEC =
                CustomPacketPayload.codec(OpenSettingsPayload::write, OpenSettingsPayload::new);

        private OpenSettingsPayload(FriendlyByteBuf buf) {
            this(readStringList(buf), readStringList(buf), readStringList(buf));
        }

        private void write(FriendlyByteBuf buf) {
            writeStringList(buf, blacklist);
            writeStringList(buf, skipBlocks);
            writeStringList(buf, boxItems);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S：黑名单（give 物品）增删，add=true 添加 / false 删除 */
    public record UpdateBlacklistPayload(String itemId, boolean add) implements CustomPacketPayload {
        public static final Type<UpdateBlacklistPayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath("hopperfill", "update_blacklist"));
        public static final StreamCodec<FriendlyByteBuf, UpdateBlacklistPayload> STREAM_CODEC =
                CustomPacketPayload.codec(UpdateBlacklistPayload::write, UpdateBlacklistPayload::new);

        private UpdateBlacklistPayload(FriendlyByteBuf buf) {
            this(buf.readUtf(), buf.readBoolean());
        }

        private void write(FriendlyByteBuf buf) {
            buf.writeUtf(itemId);
            buf.writeBoolean(add);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S：满盒物品增删，add=true 添加 / false 删除 */
    public record UpdateBoxPayload(String itemId, boolean add) implements CustomPacketPayload {
        public static final Type<UpdateBoxPayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath("hopperfill", "update_box"));
        public static final StreamCodec<FriendlyByteBuf, UpdateBoxPayload> STREAM_CODEC =
                CustomPacketPayload.codec(UpdateBoxPayload::write, UpdateBoxPayload::new);

        private UpdateBoxPayload(FriendlyByteBuf buf) {
            this(buf.readUtf(), buf.readBoolean());
        }

        private void write(FriendlyByteBuf buf) {
            buf.writeUtf(itemId);
            buf.writeBoolean(add);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S：跳过方块增删，add=true 添加 / false 删除 */
    public record UpdateSkipBlockPayload(String blockId, boolean add) implements CustomPacketPayload {
        public static final Type<UpdateSkipBlockPayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath("hopperfill", "update_skip_block"));
        public static final StreamCodec<FriendlyByteBuf, UpdateSkipBlockPayload> STREAM_CODEC =
                CustomPacketPayload.codec(UpdateSkipBlockPayload::write, UpdateSkipBlockPayload::new);

        private UpdateSkipBlockPayload(FriendlyByteBuf buf) {
            this(buf.readUtf(), buf.readBoolean());
        }

        private void write(FriendlyByteBuf buf) {
            buf.writeUtf(blockId);
            buf.writeBoolean(add);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** S2C：区域扫描统计结果。names 与 nonStackable 一一对应；nonStackableNames 为去重后的不可堆叠物品名。
     *  由客户端按真实字体宽度精确对齐多列排版，避免服务端无法测量客户端字体导致的错位。 */
    public record ScanResultPayload(List<String> names, List<Boolean> nonStackable,
                                    List<String> nonStackableNames, int totalPositions) implements CustomPacketPayload {
        public static final Type<ScanResultPayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath("hopperfill", "scan_result"));
        public static final StreamCodec<FriendlyByteBuf, ScanResultPayload> STREAM_CODEC =
                CustomPacketPayload.codec(ScanResultPayload::write, ScanResultPayload::new);

        private ScanResultPayload(FriendlyByteBuf buf) {
            this(readStringList(buf), readBooleanList(buf), readStringList(buf), buf.readVarInt());
        }

        private void write(FriendlyByteBuf buf) {
            writeStringList(buf, names);
            writeBooleanList(buf, nonStackable);
            writeStringList(buf, nonStackableNames);
            buf.writeVarInt(totalPositions);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 以 VarInt 前缀长度 + 逐条 writeUtf 的方式写字符串列表 */
    private static void writeStringList(FriendlyByteBuf buf, List<String> list) {
        buf.writeVarInt(list.size());
        for (String s : list) {
            buf.writeUtf(s);
        }
    }

    /** 以 VarInt 前缀长度 + 逐条 readUtf 的方式读字符串列表 */
    private static List<String> readStringList(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<String> list = new ArrayList<>(Math.min(n, 100000));
        for (int i = 0; i < n; i++) {
            list.add(buf.readUtf());
        }
        return list;
    }

    /** 以 VarInt 前缀长度 + 逐条 writeBoolean 的方式写布尔列表 */
    private static void writeBooleanList(FriendlyByteBuf buf, List<Boolean> list) {
        buf.writeVarInt(list.size());
        for (Boolean b : list) {
            buf.writeBoolean(b);
        }
    }

    /** 以 VarInt 前缀长度 + 逐条 readBoolean 的方式读布尔列表 */
    private static List<Boolean> readBooleanList(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<Boolean> list = new ArrayList<>(Math.min(n, 100000));
        for (int i = 0; i < n; i++) {
            list.add(buf.readBoolean());
        }
        return list;
    }
}
