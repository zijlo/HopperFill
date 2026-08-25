package com.leaf.hopperfill.command;

import com.leaf.hopperfill.data.TemplateStorage;
import com.leaf.hopperfill.fill.FillValidator;
import com.leaf.hopperfill.fill.HopperFiller;
import com.leaf.hopperfill.gui.TemplateScreenHandler;
import com.leaf.hopperfill.region.RegionScanner;
import com.leaf.hopperfill.network.SettingsNetworking;
import com.leaf.hopperfill.util.GiveAmount;
import com.leaf.hopperfill.util.ItemCategorizer;
import com.leaf.hopperfill.util.ItemFilter;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.DyeColor;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class HopperFillCommand {
    private static final int MAX_VOLUME = 32768;
    private static final int MESSAGE_BATCH_SIZE = 10;
    private static final int SHULKER_SLOTS = 27;

    /** 16 色潜影盒颜色，按给予顺序循环 */
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

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("hf")
                .requires(source -> source.getPlayer() != null && source.getPlayer().interactionManager.isCreative())
                .executes(ctx -> {
                    ctx.getSource().sendFeedback(() -> Text.literal(
                            "§e===== HopperFill 用法 =====\n" +
                                    "§7/hf set template §f- 设置模板（原版漏斗界面，16/64 切换）\n" +
                                    "§7/hf set gui §f- 打开设置界面（添加 / 查看 / 删除 黑名单与跳过方块）\n" +
                                    "§7/hf scan <from> <to> §f- 扫描区域统计\n" +
                                    "§7/hf fill <from> <to> <lineStart> <lineEnd> §f- §c[创造] §f填充漏斗线\n" +
                                    "§7/hf hoe on/off §f- 开启/关闭木锄工具\n" +
                                    "§7/hf give all [数量] §f- §c[创造] §7给予可获取物品（装入潜影盒）\n" +
                                    "§7/hf give stackable [数量] §f- §c[创造] §7仅给予可堆叠物品\n" +
                                    "§7/hf give nonstackable [数量] §f- §c[创造] §7仅给予不可堆叠物品\n" +
                                    "§7/hf give category [数量] §f- §c[创造] §7按分类分盒给予（材料/工具/食物等）\n" +
                                    "§7  数量可为 all(64/16)、all-1(63/15)、或具体数量(64堆叠按给定数，16堆叠按比例换算)"
                    ), false);
                    return 1;
                })
                .then(literal("set")
                        .then(literal("template")
                                .executes(ctx -> {
                                    openTemplateGui(ctx.getSource().getPlayer());
                                    return 1;
                                })
                        )
                        .then(literal("gui")
                                .requires(source -> source.getPlayer() != null)
                                .executes(ctx -> {
                                    SettingsNetworking.open(ctx.getSource().getPlayer());
                                    return 1;
                                })
                        )
                )
                .then(literal("hoe")
                        .then(literal("on").executes(ctx -> {
                            TemplateStorage.setHoeEnabled(ctx.getSource().getPlayer(), true);
                            ctx.getSource().sendFeedback(() -> Text.literal("§a木锄工具已开启"), false);
                            return 1;
                        }))
                        .then(literal("off").executes(ctx -> {
                            TemplateStorage.setHoeEnabled(ctx.getSource().getPlayer(), false);
                            ctx.getSource().sendFeedback(() -> Text.literal("§c木锄工具已关闭"), false);
                            return 1;
                        }))
                        .executes(ctx -> {
                            boolean enabled = TemplateStorage.isHoeEnabled(ctx.getSource().getPlayer());
                            ctx.getSource().sendFeedback(() -> Text.literal(
                                    enabled ? "§a木锄工具当前状态：开启" : "§c木锄工具当前状态：关闭（默认）"
                            ), false);
                            return 1;
                        })
                )
                .then(literal("scan")
                        .then(argument("from", BlockPosArgumentType.blockPos())
                                .then(argument("to", BlockPosArgumentType.blockPos())
                                        .executes(ctx -> {
                                            ServerPlayerEntity p = ctx.getSource().getPlayer();
                                            BlockPos from = BlockPosArgumentType.getBlockPos(ctx, "from");
                                            BlockPos to = BlockPosArgumentType.getBlockPos(ctx, "to");
                                            if (!checkVolume(p, from, to)) return 0;
                                            return executeScan(p, ctx.getSource().getWorld(), from, to);
                                        })
                                )
                        )
                )
                .then(literal("fill")
                        .requires(source -> source.getPlayer() != null && source.getPlayer().interactionManager.isCreative())
                        .then(argument("from", BlockPosArgumentType.blockPos())
                                .then(argument("to", BlockPosArgumentType.blockPos())
                                        .then(argument("lineStart", BlockPosArgumentType.blockPos())
                                                .then(argument("lineEnd", BlockPosArgumentType.blockPos())
                                                        .executes(ctx -> {
                                                            ServerPlayerEntity p = ctx.getSource().getPlayer();
                                                            BlockPos from = BlockPosArgumentType.getBlockPos(ctx, "from");
                                                            BlockPos to = BlockPosArgumentType.getBlockPos(ctx, "to");
                                                            if (!checkVolume(p, from, to)) return 0;
                                                            BlockPos ls = BlockPosArgumentType.getBlockPos(ctx, "lineStart");
                                                            BlockPos le = BlockPosArgumentType.getBlockPos(ctx, "lineEnd");
                                                            return executeFill(p, ctx.getSource().getWorld(), from, to, ls, le);
                                                        })
                                                )
                                        )
                                )
                        )
                )
                .then(literal("give")
                        .requires(source -> source.getPlayer() != null && source.getPlayer().interactionManager.isCreative())
                        .then(literal("all")
                                .executes(ctx -> executeGiveFiltered(ctx.getSource().getPlayer(), FilterMode.ALL, GiveAmount.defaultAmount()))
                                .then(argument("amount", StringArgumentType.word())
                                        .executes(ctx -> executeGiveFiltered(ctx.getSource().getPlayer(), FilterMode.ALL,
                                                GiveAmount.parse(StringArgumentType.getString(ctx, "amount"))))))
                        .then(literal("stackable")
                                .executes(ctx -> executeGiveFiltered(ctx.getSource().getPlayer(), FilterMode.STACKABLE, GiveAmount.defaultAmount()))
                                .then(argument("amount", StringArgumentType.word())
                                        .executes(ctx -> executeGiveFiltered(ctx.getSource().getPlayer(), FilterMode.STACKABLE,
                                                GiveAmount.parse(StringArgumentType.getString(ctx, "amount"))))))
                        .then(literal("nonstackable")
                                .executes(ctx -> executeGiveFiltered(ctx.getSource().getPlayer(), FilterMode.NON_STACKABLE, GiveAmount.defaultAmount()))
                                .then(argument("amount", StringArgumentType.word())
                                        .executes(ctx -> executeGiveFiltered(ctx.getSource().getPlayer(), FilterMode.NON_STACKABLE,
                                                GiveAmount.parse(StringArgumentType.getString(ctx, "amount"))))))
                        .then(literal("category")
                                .executes(ctx -> executeGiveCategorized(ctx.getSource().getPlayer(), GiveAmount.defaultAmount()))
                                .then(argument("amount", StringArgumentType.word())
                                        .executes(ctx -> executeGiveCategorized(ctx.getSource().getPlayer(),
                                                GiveAmount.parse(StringArgumentType.getString(ctx, "amount"))))))
                )
        );
    }

    private enum FilterMode {
        ALL, STACKABLE, NON_STACKABLE
    }

    public static boolean checkVolume(ServerPlayerEntity player, BlockPos from, BlockPos to) {
        long volume = (long) (Math.abs(to.getX() - from.getX()) + 1)
                * (long) (Math.abs(to.getY() - from.getY()) + 1)
                * (long) (Math.abs(to.getZ() - from.getZ()) + 1);
        if (volume > MAX_VOLUME) {
            player.sendMessage(Text.literal("§c错误：区域过大（" + volume + " 格），最大允许 " + MAX_VOLUME + " 格"), false);
            return false;
        }
        return true;
    }

    private static void openTemplateGui(ServerPlayerEntity player) {
        SimpleInventory inventory = new SimpleInventory(TemplateScreenHandler.TEMPLATE_SLOTS);
        TemplateStorage.loadTemplateInto(player, inventory, 16);
        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, playerInv, p) -> new TemplateScreenHandler(syncId, playerInv, inventory, player, 16),
                Text.literal("物品填充模板")
        ));
    }

    public static int executeScan(ServerPlayerEntity player, ServerWorld world, BlockPos from, BlockPos to) {
        Set<Identifier> skipIds = TemplateStorage.getBlockIds(player);
        if (skipIds.isEmpty()) {
            player.sendMessage(Text.literal("§c请先使用 /hf set gui 添加要跳过的方块"), false);
            return 0;
        }

        List<ItemStack> results = RegionScanner.scan(world, from, to, skipIds, from, to);

        player.sendMessage(Text.literal("§6===== 区域内容统计 ====="), false);
        int itemCount = 0;

        StringBuilder batch = new StringBuilder(512);
        int linesInBatch = 0;
        Set<Item> nonStackableItems = new LinkedHashSet<>();

        for (ItemStack r : results) {
            String line;
            if (r.isEmpty()) {
                line = " §7• [跳过/空]\n";
            } else {
                boolean nonStackable = r.getMaxCount() == 1;
                if (nonStackable) nonStackableItems.add(r.getItem());
                line = " §7• " + r.getName().getString() + " §8(" + Registries.ITEM.getId(r.getItem()) +
                        ") x1" + (nonStackable ? " §c[不可堆叠]" : "") + "\n";
                itemCount++;
            }

            batch.append(line);
            linesInBatch++;

            if (linesInBatch >= MESSAGE_BATCH_SIZE) {
                player.sendMessage(Text.literal(batch.toString()), false);
                batch.setLength(0);
                linesInBatch = 0;
            }
        }

        if (linesInBatch > 0) {
            player.sendMessage(Text.literal(batch.toString()), false);
        }

        player.sendMessage(Text.literal("§6共 " + results.size() + " 个位置（" + itemCount + " 个有效物品）"), false);

        if (!nonStackableItems.isEmpty()) {
            StringBuilder warn = new StringBuilder("§c⚠ 检测到 " + nonStackableItems.size() + " 种不可堆叠物品，无法填充漏斗：\n");
            int listed = 0;
            for (Item it : nonStackableItems) {
                if (listed >= 8) {
                    warn.append("§7   ……等共 ").append(nonStackableItems.size()).append(" 种\n");
                    break;
                }
                warn.append("   §c• ").append(new ItemStack(it).getName().getString())
                        .append(" §8(").append(Registries.ITEM.getId(it)).append(")\n");
                listed++;
            }
            warn.append("§7请将这些物品加入跳过方块（/hf set gui）后重新 scan");
            player.sendMessage(Text.literal(warn.toString()), false);
        }
        return 1;
    }

    public static int executeFill(ServerPlayerEntity player, ServerWorld world, BlockPos from, BlockPos to,
                                  BlockPos lineStart, BlockPos lineEnd) {
        FillValidator.Result result = FillValidator.validate(player, world, from, to, lineStart, lineEnd);
        if (!result.success) {
            player.sendMessage(Text.literal(result.errorMessage), false);
            return 0;
        }
        HopperFiller.FillResult fr = HopperFiller.fill(world, result.hoppers, result.results,
                result.template16, result.template64, player);

        if (!fr.success) {
            player.sendMessage(Text.literal(fr.message), false);
            return 0;
        }

        player.sendMessage(Text.literal("§a已填充 " + result.hoppers.size() + " 个漏斗（其中 " + fr.filteredCount + " 个含过滤物品）"), false);
        return 1;
    }

    private static int executeGiveFiltered(ServerPlayerEntity player, FilterMode mode, GiveAmount amount) {
        if (amount == null) amount = GiveAmount.defaultAmount();
        List<ItemStack> items = new ArrayList<>();

        for (Item item : Registries.ITEM) {
            Identifier id = Registries.ITEM.getId(item);
            // ② 统一过滤：刷怪蛋、生存无法获取物品、潜影盒自身（避免盒中盒）
            if (ItemFilter.isExcluded(item, id)) continue;
            // ③ 个人黑名单：用户手动添加的物品
            if (TemplateStorage.isGiveBlacklisted(player, id)) continue;

            ItemStack stack = new ItemStack(item);
            int maxStack = stack.getMaxCount();

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
    private static int executeGiveCategorized(ServerPlayerEntity player, GiveAmount amount) {
        if (amount == null) amount = GiveAmount.defaultAmount();

        Map<String, List<ItemStack>> groups = new LinkedHashMap<>();
        for (String key : ItemCategorizer.categoryOrder()) {
            groups.put(key, new ArrayList<>());
        }

        for (Item item : Registries.ITEM) {
            Identifier id = Registries.ITEM.getId(item);
            if (ItemFilter.isExcluded(item, id)) continue;
            if (TemplateStorage.isGiveBlacklisted(player, id)) continue;

            ItemStack stack = new ItemStack(item);
            stack.setCount(amount.resolve(stack.getMaxCount()));
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

    private static int giveItemsInShulkers(ServerPlayerEntity player, List<ItemStack> items, String label) {
        return giveItemsInShulkers(player, items, label, true, false);
    }

    private static int giveItemsInShulkers(ServerPlayerEntity player, List<ItemStack> items, String label, boolean nameBox) {
        return giveItemsInShulkers(player, items, label, true, nameBox);
    }

    private static int giveItemsInShulkers(ServerPlayerEntity player, List<ItemStack> items, String label, boolean verbose, boolean nameBox) {
        int totalTypes = items.size();
        int boxCount = (totalTypes + SHULKER_SLOTS - 1) / SHULKER_SLOTS;

        if (verbose) {
            player.sendMessage(Text.literal(
                    "§a正在生成 §e" + label + "§a，共 §e" + totalTypes + "§a 种物品，§e" + boxCount + "§a 个潜影盒..."), false);
        }

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

            ItemStack shulker = createColoredShulker(color);
            shulker.set(DataComponentTypes.CONTAINER, ContainerComponent.fromStacks(contents));
            if (nameBox) {
                shulker.set(DataComponentTypes.CUSTOM_NAME, Text.literal(label).formatted(Formatting.RESET));
            }

            player.getInventory().offerOrDrop(shulker);
            boxesGiven++;
        }

        player.sendMessage(Text.literal(
                "§a完成！已给予 §e" + boxesGiven + "§a 个潜影盒（§e" + totalTypes + "§a 种物品）"), false);
        return boxesGiven;
    }

    private static ItemStack createColoredShulker(DyeColor color) {
        Item item = switch (color) {
            case WHITE -> Items.WHITE_SHULKER_BOX;
            case ORANGE -> Items.ORANGE_SHULKER_BOX;
            case MAGENTA -> Items.MAGENTA_SHULKER_BOX;
            case LIGHT_BLUE -> Items.LIGHT_BLUE_SHULKER_BOX;
            case YELLOW -> Items.YELLOW_SHULKER_BOX;
            case LIME -> Items.LIME_SHULKER_BOX;
            case PINK -> Items.PINK_SHULKER_BOX;
            case GRAY -> Items.GRAY_SHULKER_BOX;
            case LIGHT_GRAY -> Items.LIGHT_GRAY_SHULKER_BOX;
            case CYAN -> Items.CYAN_SHULKER_BOX;
            case PURPLE -> Items.PURPLE_SHULKER_BOX;
            case BLUE -> Items.BLUE_SHULKER_BOX;
            case BROWN -> Items.BROWN_SHULKER_BOX;
            case GREEN -> Items.GREEN_SHULKER_BOX;
            case RED -> Items.RED_SHULKER_BOX;
            case BLACK -> Items.BLACK_SHULKER_BOX;
        };
        return new ItemStack(item);
    }
}