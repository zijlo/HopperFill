package com.leaf.hopperfill.util;

import net.minecraft.resources.Identifier;

import java.util.Locale;

/**
 * 物品分类器（需求：按"材料 + 产物"分盒，让 give 不再全部塞一个盒子）。
 *
 * 分类策略（已按用户确认）：官方大类打底 + 材料族聚合，工具/武器/盔甲归工具大类。
 * 优先级即数组顺序，靠前的分类先匹配；未命中任何规则进入 OTHER。
 *
 * 匹配完全基于物品 ID 的 namespace:path（字符串），不依赖创造物品栏 API，
 * 因此 1.21（Yarn）与 26.x（Mojang）两套映射可共用同一套规则，仅 Identifier 包名不同。
 *
 * TODO(可调整)：当前为"框架版"，规则用前缀/包含子串近似匹配，个别边界物品归属可能不准，
 * 后续可按需增删前缀、子串，或改用精确 ID 白名单 / 官方 CreativeModeTab 动态分组。
 */
public final class ItemCategorizer {
    private ItemCategorizer() {}

    public static final String OTHER_KEY = "other";
    public static final String OTHER_LABEL = "其他";

    private static final Category[] CATEGORIES = {
            // 1) 工具/武器/盔甲优先于材料族，保证 iron_sword、diamond_chestplate 不被归入矿物
            new Category("tools", "工具·武器·盔甲",
                    new String[]{},
                    new String[]{
                            "sword", "pickaxe", "axe", "shovel", "hoe", "shears",
                            "flint_and_steel", "brush", "fishing_rod", "spyglass",
                            "compass", "clock", "map", "name_tag", "lead", "saddle",
                            "totem", "elytra", "bow", "crossbow", "arrow", "shield",
                            "mace", "trident", "helmet", "chestplate", "leggings",
                            "boots", "horse_armor", "wolf_armor"
                    }),
            // 2) 木材与木制品（树种前缀 + 通用木作子串）
            new Category("wood", "木材与木制品",
                    new String[]{
                            "oak_", "spruce_", "birch_", "jungle_", "acacia_",
                            "dark_oak_", "mangrove_", "cherry_", "pale_oak_",
                            "crimson_", "warped_", "bamboo_"
                    },
                    new String[]{
                            "log", "planks", "wood", "sapling", "leaves", "sign",
                            "boat", "charcoal", "stick", "ladder", "crafting_table",
                            "scaffolding", "bookshelf", "chest", "barrel", "composter",
                            "lectern", "smithing_table", "cartography_table",
                            "fletching_table", "loom"
                    }),
            // 3) 食物放在矿物之前，避免 golden_apple / golden_carrot 被 gold 误判为矿物
            new Category("food", "食物",
                    new String[]{},
                    new String[]{
                            "apple", "beef", "porkchop", "chicken", "mutton", "rabbit",
                            "cod", "salmon", "fish", "pufferfish", "potato", "carrot",
                            "beetroot", "bread", "cake", "cookie", "pie", "stew",
                            "soup", "berries", "melon", "pumpkin", "honey",
                            "rotten_flesh", "spider_eye", "chorus_fruit", "dried_kelp",
                            "egg", "mushroom"
                    }),
            // 4) 矿物与金属（原矿/粗矿/锭/块/粒/宝石）
            new Category("ore", "矿物与金属",
                    new String[]{
                            "raw_", "iron_", "gold_", "copper_", "diamond_",
                            "netherite_", "emerald_", "lapis_", "quartz_", "amethyst_"
                    },
                    new String[]{
                            "ore", "ingot", "nugget", "coal", "redstone", "crystal"
                    }),
            // 5) 石材与建材
            new Category("stone", "石材与建材",
                    new String[]{},
                    new String[]{
                            "stone", "cobblestone", "diorite", "andesite", "granite",
                            "deepslate", "tuff", "calcite", "dripstone", "sandstone",
                            "prismarine", "purpur", "end_stone", "netherrack",
                            "blackstone", "basalt", "bricks", "terracotta", "glass",
                            "obsidian", "concrete", "clay", "mossy", "cracked",
                            "polished", "smooth", "chiseled", "stairs", "slab", "wall"
                    }),
            // 6) 红石与机械
            new Category("redstone", "红石与机械",
                    new String[]{},
                    new String[]{
                            "piston", "observer", "dropper", "dispenser", "hopper",
                            "repeater", "comparator", "lever", "rail", "minecart",
                            "tnt", "daylight", "tripwire", "target", "note_block", "jukebox"
                    })
    };

    /** 返回物品分类 key；未命中返回 OTHER_KEY */
    public static String categorize(Identifier id) {
        String path = id.getPath().toLowerCase(Locale.ROOT);
        for (Category c : CATEGORIES) {
            if (c.matches(path)) return c.key;
        }
        return OTHER_KEY;
    }

    /** 分类 key -> 中文显示名（未知 key 直接返回原值） */
    public static String label(String key) {
        if (OTHER_KEY.equals(key)) return OTHER_LABEL;
        for (Category c : CATEGORIES) {
            if (c.key.equals(key)) return c.label;
        }
        return key;
    }

    /** 稳定有序的分类 key 列表（含 OTHER），用于保证盒子顺序一致 */
    public static String[] categoryOrder() {
        String[] order = new String[CATEGORIES.length + 1];
        for (int i = 0; i < CATEGORIES.length; i++) {
            order[i] = CATEGORIES[i].key;
        }
        order[CATEGORIES.length] = OTHER_KEY;
        return order;
    }

    private static final class Category {
        final String key;
        final String label;
        final String[] prefixes;
        final String[] contains;

        Category(String key, String label, String[] prefixes, String[] contains) {
            this.key = key;
            this.label = label;
            this.prefixes = prefixes;
            this.contains = contains;
        }

        boolean matches(String path) {
            for (String pre : prefixes) {
                if (path.startsWith(pre)) return true;
            }
            for (String sub : contains) {
                if (path.contains(sub)) return true;
            }
            return false;
        }
    }
}