package com.leaf.hopperfill.util;

import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.Set;

/**
 * give 命令物品过滤规则（需求②）。
 * 集中管理三件事：
 *  1) 刷怪蛋（spawn egg）
 *  2) 创造模式专用 / 生存模式无法获取的物品
 *  3) 潜影盒自身（避免"盒中盒"）
 * 后续增删排除项只需修改本类，不再散落在 HopperFillCommand 里。
 */
public final class ItemFilter {
    private ItemFilter() {}

    /**
     * 创造模式专用 / 生存无法获取的原版物品黑名单。
     * TODO(可调整): 若期望更精确的"生存可获取"判定，可改为按创造物品栏 / 物品组动态过滤；
     * 目前先采用显式黑名单，覆盖命令方块、结构方块、屏障、光源方块、调试棒等。
     */
    private static final Set<Identifier> CREATIVE_ONLY = Set.of(
            Identifier.parse("minecraft:command_block"),
            Identifier.parse("minecraft:chain_command_block"),
            Identifier.parse("minecraft:repeating_command_block"),
            Identifier.parse("minecraft:structure_block"),
            Identifier.parse("minecraft:structure_void"),
            Identifier.parse("minecraft:jigsaw"),
            Identifier.parse("minecraft:barrier"),
            Identifier.parse("minecraft:light"),
            Identifier.parse("minecraft:debug_stick"),
            Identifier.parse("minecraft:command_block_minecart"),
            Identifier.parse("minecraft:knowledge_book"),
            Identifier.parse("minecraft:bedrock"),
            Identifier.parse("minecraft:end_portal_frame"),
            Identifier.parse("minecraft:end_gateway"),
            Identifier.parse("minecraft:spawner"),
            Identifier.parse("minecraft:petrified_oak_slab")
    );

    /** 判断物品是否应从 give 列表中排除 */
    public static boolean isExcluded(Item item, Identifier id) {
        if (item == Items.AIR) return true;
        if (CREATIVE_ONLY.contains(id)) return true;
        if (isSpawnEgg(id)) return true;   // 刷怪蛋
        if (isShulkerBox(id)) return true; // 潜影盒自身
        return false;
    }

    public static boolean isSpawnEgg(Identifier id) {
        return id.getPath().endsWith("spawn_egg");
    }

    public static boolean isShulkerBox(Identifier id) {
        return id.getPath().endsWith("shulker_box");
    }
}