package com.zijlo.hopperfill.command;

import com.zijlo.hopperfill.data.TemplateStorage;
import com.zijlo.hopperfill.fill.FillValidator;
import com.zijlo.hopperfill.fill.HopperFiller;
import com.zijlo.hopperfill.geometry.LineBresenham3D;
import com.zijlo.hopperfill.gui.TemplateScreenHandler;
import com.zijlo.hopperfill.region.RegionScanner;
import com.zijlo.hopperfill.network.SettingsNetworking;
import com.zijlo.hopperfill.network.SettingsPayloads;
import com.zijlo.hopperfill.util.GiveAmount;
import com.zijlo.hopperfill.util.ItemFilter;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
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
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

public class HopperFillCommand {
    private static final int MAX_VOLUME = 32768;
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
                .requires(source -> source.getPlayer() != null)
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "§e===== HopperFill 用法 =====\n" +
                                    "§7/hf set template §f- 设置模板（16/64 界面内切换）\n" +
                                    "§7/hf set gui §f- 打开设置界面（添加 / 查看 / 删除 黑名单与跳过方块）\n" +
                                    "§7/hf scan <from> <to> §f- 扫描区域统计\n" +
                                    "§7/hf fill <from> <to> <lineStart> <lineEnd> §f- 填充漏斗线（生存消耗材料）\n" +
                                    "§7/hf clear <lineStart> <lineEnd> §f- 清除漏斗内容\n" +
                                    "§7/hf givebox <from> <to> §f- §c[创造] §7把区域内物品装入纯净潜影盒\n" +
                                    "§7/hf hoe on/off §f- 开启/关闭木锄工具\n" +
                                    "§7/hf give all [数量] §f- §c[创造] §7给予可获取物品（装入潜影盒）\n" +
                                    "§7/hf give stackable [数量] §f- §c[创造] §7仅给予可堆叠物品\n" +
                                    "§7/hf give nonstackable [数量] §f- §c[创造] §7仅给予不可堆叠物品\n" +
                                    "§7/hf give box §f- §c[创造] §7按「满盒物品」清单给予纯净满潜影盒\n" +
                                    "§7  数量可为 1-64(64堆叠)/1-16(16堆叠)、all(64/16)、all-1(63/15)"
                    ), false);
                    return 1;
                })
                .then(Commands.literal("set")
                        .then(Commands.literal("template")
                                .executes(ctx -> {
                                    openTemplateGui(ctx.getSource().getPlayer());
                                    return 1;
                                })
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
                        .requires(source -> source.getPlayer() != null)
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
                .then(Commands.literal("clear")
                        .then(Commands.argument("lineStart", BlockPosArgument.blockPos())
                                .then(Commands.argument("lineEnd", BlockPosArgument.blockPos())
                                        .executes(ctx -> {
                                            ServerPlayer p = ctx.getSource().getPlayer();
                                            BlockPos ls = BlockPosArgument.getBlockPos(ctx, "lineStart");
                                            BlockPos le = BlockPosArgument.getBlockPos(ctx, "lineEnd");
                                            return executeClear(p, ctx.getSource().getLevel(), ls, le);
                                        })
                                )
                        )
                )
                .then(Commands.literal("givebox")
                        .requires(source -> source.getPlayer() != null && source.getPlayer().isCreative())
                        .then(Commands.argument("from", BlockPosArgument.blockPos())
                                .then(Commands.argument("to", BlockPosArgument.blockPos())
                                        .executes(ctx -> {
                                            ServerPlayer p = ctx.getSource().getPlayer();
                                            BlockPos from = BlockPosArgument.getBlockPos(ctx, "from");
                                            BlockPos to = BlockPosArgument.getBlockPos(ctx, "to");
                                            if (!checkVolume(p, from, to)) return 0;
                                            return executeGiveBox(p, ctx.getSource().getLevel(), from, to);
                                        })
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
                        .then(Commands.literal("box")
                                .executes(ctx -> executeGiveBoxItems(ctx.getSource().getPlayer())))
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

    private static void openTemplateGui(ServerPlayer player) {
        SimpleContainer inventory = new SimpleContainer(TemplateScreenHandler.TEMPLATE_SLOTS);
        TemplateStorage.loadTemplateInto(player, inventory, 16);
        player.openMenu(new SimpleMenuProvider(
                (syncId, playerInv, p) -> new TemplateScreenHandler(syncId, playerInv, inventory, player, 16),
                Component.literal("物品填充模板")
        ));
    }

    public static int executeScan(ServerPlayer player, ServerLevel level, BlockPos from, BlockPos to) {
        Set<Identifier> skipIds = TemplateStorage.getBlockIds(player);
        if (skipIds.isEmpty()) {
            player.sendSystemMessage(Component.literal("§c请先使用 /hf set gui 添加要跳过的方块"), false);
            return 0;
        }

        List<ItemStack> results = RegionScanner.scan(level, from, to, skipIds, from, to);
        printScanResult(player, results);
        return 1;
    }

    /** 发送区域内容统计到客户端（/hf scan 与木锄 scan 共用）。
     *  对齐排版全部在客户端按真实字体宽度完成，服务端只负责收集数据。
     *  返回不可堆叠物品集合，供调用方决定是否取消后续 fill 流程。 */
    public static Set<Item> printScanResult(ServerPlayer player, List<ItemStack> results) {
        Set<Item> nonStackableItems = new LinkedHashSet<>();
        List<String> names = new ArrayList<>();
        List<Boolean> nonStackable = new ArrayList<>();
        for (ItemStack r : results) {
            if (r.isEmpty()) continue;
            boolean ns = r.getMaxStackSize() == 1;
            if (ns) nonStackableItems.add(r.getItem());
            names.add(r.getDisplayName().getString());
            nonStackable.add(ns);
        }

        List<String> nsNames = new ArrayList<>(nonStackableItems.size());
        for (Item it : nonStackableItems) {
            nsNames.add(new ItemStack(it).getDisplayName().getString());
        }

        ServerPlayNetworking.send(player, new SettingsPayloads.ScanResultPayload(
                names, nonStackable, nsNames, results.size()));
        return nonStackableItems;
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

    /** 清除漏斗线内容（/hf clear <lineStart> <lineEnd>）。
     *  生存模式：先返还漏斗内物品（先背包，满则掉落），再清空；创造模式：直接清空不返还。 */
    private static int executeClear(ServerPlayer player, ServerLevel level, BlockPos lineStart, BlockPos lineEnd) {
        List<BlockPos> hoppers = LineBresenham3D.getPositions(lineStart, lineEnd);
        Optional<String> continuityError = LineBresenham3D.checkContinuity(hoppers);
        if (continuityError.isPresent()) {
            player.sendSystemMessage(Component.literal("§c错误：漏斗线不连续，" + continuityError.get()), false);
            return 0;
        }

        boolean survival = !player.isCreative();

        int cleared = 0;
        for (BlockPos pos : hoppers) {
            if (!(level.getBlockState(pos).getBlock() instanceof HopperBlock)) {
                player.sendSystemMessage(Component.literal("§c错误：" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + " 不是漏斗"), false);
                return 0;
            }
            HopperBlockEntity hopper = (HopperBlockEntity) level.getBlockEntity(pos);
            if (hopper == null) continue;
            if (survival) {
                // 生存模式：把漏斗内物品返还给玩家
                for (int i = 0; i < hopper.getContainerSize(); i++) {
                    ItemStack stack = hopper.getItem(i);
                    if (!stack.isEmpty()) {
                        ItemStack copy = stack.copy();
                        if (!player.getInventory().add(copy)) {
                            player.drop(copy, false);
                        }
                    }
                }
            }
            hopper.clearContent();
            hopper.setChanged();
            BlockState st = level.getBlockState(pos);
            level.sendBlockUpdated(pos, st, st, 3);
            cleared++;
        }
        player.sendSystemMessage(Component.literal("§a已清除 " + cleared + " 个漏斗的内容" + (survival ? "（物品已返还）" : "")), false);
        return 1;
    }

    /** 把区域内扫描到的每种物品装满一个纯净（未染色）潜影盒给予玩家（/hf givebox <from> <to>）
     *  每个潜影盒只装一种物品，装满 27 格（64堆叠=1728，16堆叠=432，不可堆叠=27）。 */
    private static int executeGiveBox(ServerPlayer player, ServerLevel level, BlockPos from, BlockPos to) {
        Set<Identifier> skipIds = TemplateStorage.getBlockIds(player);
        if (skipIds.isEmpty()) {
            player.sendSystemMessage(Component.literal("§c请先使用 /hf set gui 添加要跳过的方块"), false);
            return 0;
        }

        List<ItemStack> results = RegionScanner.scan(level, from, to, skipIds, from, to);
        Set<Item> uniqueItems = new LinkedHashSet<>();
        for (ItemStack r : results) {
            if (!r.isEmpty()) uniqueItems.add(r.getItem());
        }
        if (uniqueItems.isEmpty()) {
            player.sendSystemMessage(Component.literal("§c区域内没有检测到可给予的物品"), false);
            return 0;
        }

        int totalTypes = uniqueItems.size();
        player.sendSystemMessage(Component.literal(
                "§a正在生成 §e" + totalTypes + "§a 种物品的纯净满潜影盒（每种一个整盒）..."), false);

        int boxesGiven = 0;
        for (Item item : uniqueItems) {
            int maxStack = new ItemStack(item).getMaxStackSize();
            List<ItemStack> contents = new ArrayList<>(SHULKER_SLOTS);
            for (int slot = 0; slot < SHULKER_SLOTS; slot++) {
                ItemStack stack = new ItemStack(item);
                stack.setCount(maxStack);
                contents.add(stack);
            }
            ItemStack shulker = new ItemStack(Items.SHULKER_BOX);
            shulker.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(contents));
            if (!player.getInventory().add(shulker)) {
                player.drop(shulker, false);
            }
            boxesGiven++;
        }
        player.sendSystemMessage(Component.literal(
                "§a完成！已给予 §e" + boxesGiven + "§a 个纯净满潜影盒（§e" + totalTypes + "§a 种物品）"), false);
        return 1;
    }

    /** 按玩家「满盒物品」清单（/hf set gui → 满盒物品 标签页）给予纯净满潜影盒（/hf give box）。
     *  每种物品装满一个整盒：27 格 × 最大堆叠（64堆叠=1728，16堆叠=432，不可堆叠=27）。 */
    private static int executeGiveBoxItems(ServerPlayer player) {
        Set<Identifier> boxItems = TemplateStorage.getBoxItems(player);
        if (boxItems.isEmpty()) {
            player.sendSystemMessage(Component.literal(
                    "§c「满盒物品」清单为空，请先使用 /hf set gui 在「满盒物品」标签页添加物品"), false);
            return 0;
        }

        // 按注册表解析 ID，过滤无效 ID 与空气
        List<Item> items = new ArrayList<>();
        for (Identifier id : boxItems) {
            Item item = BuiltInRegistries.ITEM.getValue(id);
            if (item != null && item != Items.AIR && !items.contains(item)) {
                items.add(item);
            }
        }
        if (items.isEmpty()) {
            player.sendSystemMessage(Component.literal("§c清单中的物品 ID 均无法解析"), false);
            return 0;
        }

        int totalTypes = items.size();
        player.sendSystemMessage(Component.literal(
                "§a正在按「满盒物品」清单生成 §e" + totalTypes + "§a 种物品的纯净满潜影盒（每种一个整盒）..."), false);

        int boxesGiven = 0;
        for (Item item : items) {
            int maxStack = new ItemStack(item).getMaxStackSize();
            List<ItemStack> contents = new ArrayList<>(SHULKER_SLOTS);
            for (int slot = 0; slot < SHULKER_SLOTS; slot++) {
                ItemStack stack = new ItemStack(item);
                stack.setCount(maxStack);
                contents.add(stack);
            }
            ItemStack shulker = new ItemStack(Items.SHULKER_BOX);
            shulker.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(contents));
            if (!player.getInventory().add(shulker)) {
                player.drop(shulker, false);
            }
            boxesGiven++;
        }
        player.sendSystemMessage(Component.literal(
                "§a完成！已给予 §e" + boxesGiven + "§a 个纯净满潜影盒（§e" + totalTypes + "§a 种物品）"), false);
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

    /** 把物品按 27 槽一组装入染色潜影盒给予玩家 */
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