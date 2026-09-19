package com.zijlo.hopperfill.util;

import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.Set;

/**
 * 物品过滤规则的唯一出处。集中管理四件事：
 *  1) 刷怪蛋（spawn egg）
 *  2) 创造模式专用 / 生存模式无法获取的物品（/hf give 排除）
 *  3) 潜影盒自身（避免"盒中盒"）
 *  4) 设置界面（黑名单 / 满盒物品 / 跳过方块）的候选列表过滤
 * 后续增删排除项只需修改本类，不再散落在 HopperFillCommand / SettingsScreen 里。
 */
public final class ItemFilter {
    private ItemFilter() {}

    private static Identifier id(String path) {
        return Identifier.parse("minecraft:" + path);
    }

    /**
     * /hf give 的排除名单：创造模式专用 / 生存无法获取的物品。
     * 行为保持 4.0.0 原样，不随界面过滤规则变化。
     */
    private static final Set<Identifier> GIVE_EXCLUDED = Set.of(
            id("command_block"),
            id("chain_command_block"),
            id("repeating_command_block"),
            id("structure_block"),
            id("structure_void"),
            id("jigsaw"),
            id("barrier"),
            id("light"),
            id("debug_stick"),
            id("command_block_minecart"),
            id("knowledge_book"),
            id("bedrock"),
            id("end_portal_frame"),
            id("end_gateway"),
            id("spawner"),
            id("petrified_oak_slab")
    );

    /**
     * 生存模式下无法通过正常玩法获取的物品，用于设置界面「黑名单 / 满盒物品」列表过滤。
     * 在 {@link #GIVE_EXCLUDED} 的基础上补齐了自然生成但不可采集的方块
     * （紫水晶母岩、加固深板岩、试炼刷怪笼、宝库、虫蚀方块等）。
     */
    private static final Set<Identifier> SURVIVAL_UNOBTAINABLE = Set.of(
            // 命令 / 调试 / 结构类
            id("command_block"),
            id("chain_command_block"),
            id("repeating_command_block"),
            id("command_block_minecart"),
            id("structure_block"),
            id("structure_void"),
            id("jigsaw"),
            id("barrier"),
            id("light"),
            id("debug_stick"),
            id("knowledge_book"),
            // 世界生成 / 结构专用，生存无法采集
            id("bedrock"),
            id("spawner"),
            id("end_portal_frame"),
            id("end_gateway"),
            id("budding_amethyst"),
            id("reinforced_deepslate"),
            id("vault"),
            id("trial_spawner"),
            id("petrified_oak_slab"),
            id("frogspawn"),
            id("infested_stone"),
            id("infested_cobblestone"),
            id("infested_stone_bricks"),
            id("infested_mossy_stone_bricks"),
            id("infested_cracked_stone_bricks"),
            id("infested_chiseled_stone_bricks"),
            id("infested_deepslate")
    );

    /**
     * 「跳过方块」列表的排除名单：生存世界里不可能自然出现的创造专用方块。
     * 刻意<b>不</b>排除基岩 / 刷怪笼 / 试炼刷怪笼 / 宝库 / 虫蚀方块等——它们虽然生存无法获取，
     * 却会真实生成在存档里，恰恰是需要被跳过的对象。
     */
    private static final Set<Identifier> NON_WORLDGEN_BLOCKS = Set.of(
            id("command_block"),
            id("chain_command_block"),
            id("repeating_command_block"),
            id("structure_block"),
            id("structure_void"),
            id("jigsaw"),
            id("barrier"),
            id("light"),
            id("petrified_oak_slab")
    );

    // ---------- /hf give 过滤 ----------

    /** 判断物品是否应从 /hf give 列表中排除 */
    public static boolean isExcluded(Item item, Identifier id) {
        if (item == Items.AIR) return true;
        if (GIVE_EXCLUDED.contains(id)) return true;
        if (isSpawnEgg(id)) return true;   // 刷怪蛋
        if (isShulkerBox(id)) return true; // 潜影盒自身
        return false;
    }

    // ---------- 设置界面候选列表过滤 ----------

    /** 是否为「生存可获取」物品（设置界面「黑名单」「满盒物品」标签页用） */
    public static boolean isSurvivalObtainable(Item item, Identifier id) {
        if (item == Items.AIR) return false;
        if (SURVIVAL_UNOBTAINABLE.contains(id)) return false;
        if (isSpawnEgg(id)) return false; // 刷怪蛋只有创造模式能拿到
        return true;
    }

    /** 是否为「生存世界可能出现的方块」（设置界面「跳过方块」标签页用） */
    public static boolean isSurvivalBlockCandidate(Item item, Identifier id) {
        if (item == Items.AIR) return false;
        return !NON_WORLDGEN_BLOCKS.contains(id);
    }

    // ---------- 细粒度判定 ----------

    public static boolean isSpawnEgg(Identifier id) {
        return id.getPath().endsWith("spawn_egg");
    }

    public static boolean isShulkerBox(Identifier id) {
        return id.getPath().endsWith("shulker_box");
    }
}
