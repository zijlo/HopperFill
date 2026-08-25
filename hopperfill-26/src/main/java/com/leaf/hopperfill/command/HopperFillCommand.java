package com.leaf.hopperfill.command;

import com.leaf.hopperfill.data.TemplateStorage;
import com.leaf.hopperfill.fill.FillValidator;
import com.leaf.hopperfill.fill.HopperFiller;
import com.leaf.hopperfill.gui.CustomHopperScreenHandler;
import com.leaf.hopperfill.region.RegionScanner;
import com.leaf.hopperfill.network.SettingsNetworking;
import com.leaf.hopperfill.util.GiveAmount;
import com.leaf.hopperfill.util.ItemCategorizer;
import com.leaf.hopperfill.util.ItemFilter;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class HopperFillCommand {
    private static final int MAX_VOLUME = 32768;
    private static final int MESSAGE_BATCH_SIZE = 10;
    private static final int SHULKER_SLOTS = 27;

    /** 16 色潜影盒颜色，按给予顺序循环（26.2 起独立颜色 item 被合并为单一 SHULKER_BOX + DyeColor） */
    private static final DyeColor[] SHULKER_COLORS = {
            DyeColor.WHITE,
            DyeColor.ORANGE,
            DyeColor.MAGENTA,
            DyeColor.LIGHT_BLUE,
            DyeColor.YELLOW,
            DyeColor.LIME,
            DyeColor.PINK,
            DyeColor.GRAY,
            DyeColor.LIGHT_GRAY,
            DyeColor.CYAN,
            DyeColor.PURPLE,
            DyeColor.BLUE,
            DyeColor.BROWN,
            DyeColor.GREEN,
            DyeColor.RED,
            DyeColor.BLACK
    };

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("hf")
                .requires(source -> source.getPlayer() != null && source.getPlayer().isCreative())
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "§e===== HopperFill 用法 =====\n" +
                                    "§7/hf set template 16/64 §f- 设置模板\n" +
                                    "§7/hf set gui §f- 打开设置界面（添加 / 查看 / 删除 黑名单与跳过方块）\n" +
                                    "§7/hf scan <from> <to> §f- 扫描区域统计\n" +
                                    "§7/hf fill <from> <to> <lineStart> <lineEnd> §f- §c[创造] §f填充漏斗线\n" +
                                    "§7/hf hoe on/off §f- 开启/关闭木锄工具\n" +
                                    "§7/hf give all [数量] §f- §c[创造] §7给予可获取物品（装入潜影盒）\n" +
                                    "§7/hf give stackable [数量] §f- §c[创造] §7仅给予可堆叠物品\n" +
                                    "§7/hf give nonstackable [数量] §f- §c[创造] §7仅给予不可堆叠物品\n" +
                                    "§7/hf give category [数量] §f- §c[创造] §7按分类分盒给予（材料/工具/食物等）\n" +
                                    "§7  数量可为 1-64(64堆叠)/1-16(16堆叠)、all(64/16)、all-1(63/15)"
                    ), false);
                    return 1;
                })
                .then(Commands.literal("set")
                        .then(Commands.literal("template")
                                .then(Commands.literal("16").executes(ctx -> {
                                    openTemplateGui(ctx.getSource().getPlayer(), 16);
                                    return 1;
                                }))
                                .then(Commands.literal("64").executes(ctx -> {
                                    openTemplateGui(ctx.getSource().getPlayer(), 64);
                                    return 1;
                                }))
                        )
                        .then(Commands.literal("gui")
                                .requires(source -> source.getPlayer() != null)
                                .executes(ctx -> {
                                    SettingsNetworking.open(ctx.getSource().getPlayer());
                                    return 1;
                                })
                        )
                )
                .then(Commands.literal("hoe")
                        .then(Commands.literal("on").executes(ctx -> {
                            TemplateStorage.setHoeEnabled(ctx.getSource().getPlayer(), true);
                            ctx.getSource().sendSuccess(() -> Component.literal("§a木锄工具已开启"), false);
                            return 1;
                        }))
                        .then(Commands.literal("off").executes(ctx -> {
                            TemplateStorage.setHoeEnabled(ctx.getSource().getPlayer(), false);
                            ctx.getSource().sendSuccess(() -> Component.literal("§c木锄工具已关闭"), false);
                            return 1;
                        }))
                        .executes(ctx -> {
                            boolean enabled = TemplateStorage.isHoeEnabled(ctx.getSource().getPlayer());
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    enabled ? "§a木锄工具当前状态：开启" : "§c木锄工具当前状态：关闭（默认）"
                            ), false);
                            return 1;
                        })
                )
                .then(Commands.literal("scan")
                        .then(Commands.argument("from", BlockPosArgument.blockPos())
                                .then(Commands.argument("to", BlockPosArgument.blockPos())
                                        .executes(ctx -> {
                                            ServerPlayer p = ctx.getSource().getPlayer();
                                            BlockPos from = BlockPosArgument.getBlockPos(ctx, "from");
                                            BlockPos to = BlockPosArgument.getBlockPos(ctx, "to");
                                            if (!checkVolume(p, from, to)) return 0;
                                            return executeScan(p, ctx.getSource().getLevel(), from, to);
                                        })
                                )
                        )
                )
                .then(Commands.literal("fill")
                        .requires(source -> source.getPlayer() != null && source.getPlayer().isCreative())
                        .then(Commands.argument("from", BlockPosArgument.blockPos())
                                .then(Commands.argument("to", BlockPosArgument.blockPos())
                                        .then(Commands.argument("lineStart", BlockPosArgument.blockPos())
                                                .then(Commands.argument("lineEnd", BlockPosArgument.blockPos())
                                                        .executes(ctx -> {
                                                            ServerPlayer p = ctx.getSource().getPlayer();
                                                            BlockPos from = BlockPosArgument.getBlockPos(ctx, "from");
                                                            BlockPos to = BlockPosArgument.getBlockPos(ctx, "to");
                                                            if (!checkVolume(p, from, to)) return 0;
                                                            BlockPos ls = BlockPosArgument.getBlockPos(ctx, "lineStart");
                                                            BlockPos le = BlockPosArgument.getBlockPos(ctx, "lineEnd");
                                                            return executeFill(p, ctx.getSource().getLevel(), from, to, ls, le);
                                                        })
                                                )
                                        )
                                )
                        )
                )
                .then(Commands.literal("give")
                        .requires(source -> source.getPlayer() != null && source.getPlayer().isCreative())
                        .then(Commands.literal("all")
                                .executes(ctx -> executeGiveFiltered(ctx.getSource().getPlayer(), FilterMode.ALL, GiveAmount.defaultAmount()))
                                .then(Commands.argument("amount", StringArgumentType.word())
                                        .executes(ctx -> executeGiveFiltered(ctx.getSource().getPlayer(), FilterMode.ALL,
                                                GiveAmount.parse(StringArgumentType.getString(ctx, "amount"))))))
                        .then(Commands.literal("stackable")
                                .executes(ctx -> executeGiveFiltered(ctx.getSource().getPlayer(), FilterMode.STACKABLE, GiveAmount.defaultAmount()))
                                .then(Commands.argument("amount", StringArgumentType.word())
                                        .executes(ctx -> executeGiveFiltered(ctx.getSource().getPlayer(), FilterMode.STACKABLE,
                                                GiveAmount.parse(StringArgumentType.getString(ctx, "amount"))))))
                        .then(Commands.literal("nonstackable")
                                .executes(ctx -> executeGiveFiltered(ctx.getSource().getPlayer(), FilterMode.NON_STACKABLE, GiveAmount.defaultAmount()))
                                .then(Commands.argument("amount", StringArgumentType.word())
                                        .executes(ctx -> executeGiveFiltered(ctx.getSource().getPlayer(), FilterMode.NON_STACKABLE,
                                                GiveAmount.parse(StringArgumentType.getString(ctx, "amount"))))))
                        .then(Commands.literal("category")
                                .executes(ctx -> executeGiveCategorized(ctx.getSource().getPlayer(), GiveAmount.defaultAmount()))
                                .then(Commands.argument("amount", StringArgumentType.word())
                                        .executes(ctx -> executeGiveCategorized(ctx.getSource().getPlayer(),
                                                GiveAmount.parse(StringArgumentType.getString(ctx, "amount"))))))
                )
        );
    }

    private enum FilterMode {
        ALL, STACKABLE, NON_STACKABLE
    }

    public static boolean checkVolume(ServerPlayer player, BlockPos from, BlockPos to) {
        long volume = (long) (Math.abs(to.getX() - from.getX()) + 1)
                * (long) (Math.abs(to.getY() - from.getY()) + 1)
                * (long) (Math.abs(to.getZ() - from.getZ()) + 1);
        if (volume > MAX_VOLUME) {
            player.sendSystemMessage(Component.literal("§c错误：区域过大（" + volume + " 格），最大允许 " + MAX_VOLUME + " 格"), false);
            return false;
        }
        return true;
    }

    private static void openTemplateGui(ServerPlayer player, int type) {
        SimpleContainer inventory = new SimpleContainer(5);
        TemplateStorage.loadTemplateInto(player, inventory, type);
        player.openMenu(new SimpleMenuProvider(
                (syncId, playerInv, p) -> new CustomHopperScreenHandler(syncId, playerInv, inventory, player, type),
                Component.literal("设置" + type + "堆叠模板")
        ));
    }

    public static int executeScan(ServerPlayer player, ServerLevel level, BlockPos from, BlockPos to) {
        Set<Identifier> skipIds = TemplateStorage.getBlockIds(player);
        if (skipIds.isEmpty()) {
            player.sendSystemMessage(Component.literal("§c请先使用 /hf set gui 添加要跳过的方块"), false);
            return 0;
        }

        List<ItemStack> results = RegionScanner.scan(level, from, to, skipIds, from, to);

        player.sendSystemMessage(Component.literal("§6===== 区域内容统计 ====="), false);
        int itemCount = 0;

        StringBuilder batch = new StringBuilder(512);
        int linesInBatch = 0;
        Set<Item> nonStackableItems = new LinkedHashSet<>();

        for (ItemStack r : results) {
            String line;
            if (r.isEmpty()) {
                line = " §7• [跳过/空]\n";
            } else {
                boolean nonStackable = r.getMaxStackSize() == 1;
                if (nonStackable) nonStackableItems.add(r.getItem());
                line = " §7• " + r.getDisplayName().getString() + " §8(" + BuiltInRegistries.ITEM.getKey(r.getItem()) +
                        ") x1" + (nonStackable ? " §c[不可堆叠]" : "") + "\n";
                itemCount++;
            }

            batch.append(line);
            linesInBatch++;

            if (linesInBatch >= MESSAGE_BATCH_SIZE) {
                player.sendSystemMessage(Component.literal(batch.toString()), false);
                batch.setLength(0);
                linesInBatch = 0;
            }
        }

        if (linesInBatch > 0) {
            player.sendSystemMessage(Component.literal(batch.toString()), false);
        }

        player.sendSystemMessage(Component.literal("§6共 " + results.size() + " 个位置（" + itemCount + " 个有效物品）"), false);

        if (!nonStackableItems.isEmpty()) {
            StringBuilder warn = new StringBuilder("§c⚠ 检测到 " + nonStackableItems.size() + " 种不可堆叠物品，无法填充漏斗：\n");
            int listed = 0;
            for (Item it : nonStackableItems) {
                if (listed >= 8) {
                    warn.append("§7   ……等共 ").append(nonStackableItems.size()).append(" 种\n");
                    break;
                }
                warn.append("   §c• ").append(new ItemStack(it).getDisplayName().getString())
                        .append(" §8(").append(BuiltInRegistries.ITEM.getKey(it)).append(")\n");
                listed++;
            }
            warn.append("§7请将这些物品加入跳过方块（/hf set gui）后重新 scan");
            player.sendSystemMessage(Component.literal(warn.toString()), false);
        }
        return 1;
    }

    public static int executeFill(ServerPlayer player, ServerLevel level, BlockPos from, BlockPos to,
                                  BlockPos lineStart, BlockPos lineEnd) {
        FillValidator.Result result = FillValidator.validate(player, level, from, to, lineStart, lineEnd);
        if (!result.success) {
            player.sendSystemMessage(Component.literal(result.errorMessage), false);
            return 0;
        }

        HopperFiller.FillResult fr = HopperFiller.fill(level, result.hoppers, result.results,
                result.template16, result.template64, player);

        if (!fr.success) {
            player.sendSystemMessage(Component.literal(fr.message), false);
            return 0;
        }

        player.sendSystemMessage(Component.literal("§a已填充 " + result.hoppers.size() + " 个漏斗（其中 " + fr.filteredCount + " 个含过滤物品）"), false);
        return 1;
    }

    private static int executeGiveFiltered(ServerPlayer player, FilterMode mode, GiveAmount amount) {
        if (amount == null) amount = GiveAmount.defaultAmount();
        List<ItemStack> items = new ArrayList<>();

        for (Item item : BuiltInRegistries.ITEM) {
            Identifier id = BuiltInRegistries.ITEM.getKey(item);
            // ② 统一过滤：刷怪蛋、生存无法获取物品、潜影盒自身（避免盒中盒）
            if (ItemFilter.isExcluded(item, id)) continue;
            // ③ 个人黑名单：用户手动添加的物品
            if (TemplateStorage.isGiveBlacklisted(player, id)) continue;

            ItemStack stack = new ItemStack(item);
            int maxStack = stack.getMaxStackSize();

            boolean matches = switch (mode) {
                case ALL -> true;
                case STACKABLE -> maxStack > 1;
                case NON_STACKABLE -> maxStack == 1;
            };

            if (!matches) continue;

            // ① 每个物品一组 → 数量可选（1-64 / 1-16 / all / all-1）
            stack.setCount(amount.resolve(maxStack));
            items.add(stack);
        }

        String label = switch (mode) {
            case ALL -> "全部物品";
            case STACKABLE -> "可堆叠物品";
            case NON_STACKABLE -> "不可堆叠物品";
        };

        return giveItemsInShulkers(player, items, label);
    }

    /** 按分类分盒给予：每个分类单独一组潜影盒，盒子顺序稳定 */
    private static int executeGiveCategorized(ServerPlayer player, GiveAmount amount) {
        if (amount == null) amount = GiveAmount.defaultAmount();

        Map<String, List<ItemStack>> groups = new LinkedHashMap<>();
        for (String key : ItemCategorizer.categoryOrder()) {
            groups.put(key, new ArrayList<>());
        }

        for (Item item : BuiltInRegistries.ITEM) {
            Identifier id = BuiltInRegistries.ITEM.getKey(item);
            if (ItemFilter.isExcluded(item, id)) continue;
            if (TemplateStorage.isGiveBlacklisted(player, id)) continue;

            ItemStack stack = new ItemStack(item);
            stack.setCount(amount.resolve(stack.getMaxStackSize()));
            groups.get(ItemCategorizer.categorize(id)).add(stack);
        }

        int totalBoxes = 0;
        for (Map.Entry<String, List<ItemStack>> entry : groups.entrySet()) {
            List<ItemStack> list = entry.getValue();
            if (list.isEmpty()) continue;
            totalBoxes += giveItemsInShulkers(player, list, ItemCategorizer.label(entry.getKey()), true);
        }
        return totalBoxes;
    }

    private static int giveItemsInShulkers(ServerPlayer player, List<ItemStack> items, String label) {
        return giveItemsInShulkers(player, items, label, false);
    }

    private static int giveItemsInShulkers(ServerPlayer player, List<ItemStack> items, String label, boolean nameBox) {
        int totalTypes = items.size();
        int boxCount = (totalTypes + SHULKER_SLOTS - 1) / SHULKER_SLOTS;

        player.sendSystemMessage(Component.literal(
                "§a正在生成 §e" + label + "§a，共 §e" + totalTypes + "§a 种物品，§e" + boxCount + "§a 个潜影盒..."), false);

        int itemIndex = 0;
        int boxesGiven = 0;

        while (itemIndex < items.size()) {
            DyeColor color = SHULKER_COLORS[boxesGiven % SHULKER_COLORS.length];
            List<ItemStack> contents = new ArrayList<>(SHULKER_SLOTS);

            for (int slot = 0; slot < SHULKER_SLOTS && itemIndex < items.size(); slot++) {
                contents.add(items.get(itemIndex++));
            }

            while (contents.size() < SHULKER_SLOTS) {
                contents.add(ItemStack.EMPTY);
            }

            // 获取染色潜影盒物品，再写入内容组件
            ItemStack shulker = createColoredShulker(color);
            shulker.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(contents));
            if (nameBox) {
                shulker.set(DataComponents.CUSTOM_NAME, Component.literal(label).withStyle(ChatFormatting.RESET));
            }

            if (!player.getInventory().add(shulker)) {
                player.drop(shulker, false);
            }
            boxesGiven++;
        }

        player.sendSystemMessage(Component.literal(
                "§a完成！已给予 §e" + boxesGiven + "§a 个潜影盒（§e" + totalTypes + "§a 种物品）"), false);
        return boxesGiven;
    }

    /**
     * 跨版本创建染色潜影盒物品。
     * 26.1 及更早：通过反射获取 Items.XXX_SHULKER_BOX 字段；
     * 26.2（合并染色方块后）：这些字段被移除，回退到注册表 ID 或未染色潜影盒。
     */
    private static ItemStack createColoredShulker(DyeColor color) {
        String upperName = color.name();
        String lowerName = upperName.toLowerCase(Locale.ROOT);

        // 1) 反射 Items 的染色字段（如 WHITE_SHULKER_BOX），兼容 26.1 及更早
        try {
            Field field = Items.class.getField(upperName + "_SHULKER_BOX");
            Object value = field.get(null);
            if (value instanceof Item item && item != Items.AIR) {
                return new ItemStack(item);
            }
        } catch (ReflectiveOperationException ignored) {
            // 26.2 已移除这些字段，继续走注册表兜底
        }

        // 2) 遍历注册表按 ID 兜底（若目标版本仍保留 minecraft:xxx_shulker_box ID）
        String targetId = "minecraft:" + lowerName + "_shulker_box";
        for (Item item : BuiltInRegistries.ITEM) {
            if (BuiltInRegistries.ITEM.getKey(item).toString().equals(targetId)) {
                return new ItemStack(item);
            }
        }

        // 3) 兜底：未染色潜影盒（26.2 若彻底移除染色 ID 时）
        return new ItemStack(Items.SHULKER_BOX);
    }
}