package com.zijlo.hopperfill.tool;

import com.zijlo.hopperfill.HopperFillMod;
import com.zijlo.hopperfill.command.HopperFillCommand;
import com.zijlo.hopperfill.data.TemplateStorage;
import com.zijlo.hopperfill.fill.FillService;
import com.zijlo.hopperfill.region.RegionScanner;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 木锄工具状态处理器。仅在服务端主线程访问。
 *
 * <p>选区只有 4 步（scan 两下 + fill 两下）：
 * <ol>
 *   <li>右键区域起点</li>
 *   <li>右键区域终点 → scan（发现「一片多物品」或不可堆叠物品则报错并重置）</li>
 *   <li>右键漏斗线起点</li>
 *   <li>右键漏斗线终点 → fill（生存转为逐 tick 渐进填充，再右键可手动结束）</li>
 * </ol>
 *
 * <p><b>scan 完成后</b>（第 2 步结束）会在聊天栏给出可点击的快捷操作：
 * 选区里全是容器时给「清空漏斗」，创造模式给「满盒物品」。点击后除执行对应操作外，
 * 还会重置木锄状态，避免残留选区导致下一次右键直接跑 fill。
 */
public final class HoeToolHandler {
    private HoeToolHandler() {}

    private static final Map<UUID, SelectionState> STATES = new HashMap<>();

    private static final class SelectionState {
        BlockPos from, to, lineStart, lineEnd;
        int step = 0;
    }

    // ---------- 状态管理 ----------

    /** 清空选区步骤，回到第 0 步 */
    public static void clearState(UUID uuid) {
        SelectionState state = STATES.remove(uuid);
        if (state == null) return;
        state.from = null;
        state.to = null;
        state.lineStart = null;
        state.lineEnd = null;
        state.step = 0;
    }

    /**
     * 把木锄彻底重置到第 0 步：结束可能存在的生存填充会话，并清空选区步骤。
     * 供「清空漏斗 / 满盒物品」快捷操作与 /hf clear、/hf givebox 使用。
     */
    public static void reset(ServerPlayer player) {
        if (FillService.isSessionActive(player)) {
            FillService.stopSession(player);
        }
        clearState(player.getUUID());
    }

    /** @return 当前已完成圈选的区域 {@code [起点, 终点]}，还没圈完则返回 {@code null} */
    public static BlockPos[] getCurrentRegion(UUID uuid) {
        SelectionState state = STATES.get(uuid);
        if (state == null || state.from == null || state.to == null) return null;
        return new BlockPos[]{state.from, state.to};
    }

    // ---------- 事件注册 ----------

    public static void register() {
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            if (!(level instanceof ServerLevel sl) || hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
            if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
            if (!TemplateStorage.isHoeEnabled(sp)) return InteractionResult.PASS;
            if (!sp.getItemInHand(hand).is(Items.WOODEN_HOE)) return InteractionResult.PASS;
            if (hitResult.getType() != HitResult.Type.BLOCK) return InteractionResult.PASS;

            BlockPos pos = ((BlockHitResult) hitResult).getBlockPos();

            // 生存填充会话进行中：本次右键 = 结束
            if (FillService.isSessionActive(sp)) {
                FillService.stopSession(sp);
                clearState(sp.getUUID());
                return InteractionResult.SUCCESS;
            }

            SelectionState state = STATES.computeIfAbsent(sp.getUUID(), k -> new SelectionState());

            return switch (state.step) {
                case 0 -> {
                    state.from = pos;
                    state.step = 1;
                    sp.sendSystemMessage(Component.literal(
                            "§a[木锄] 区域起点 §7" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()
                                    + " §a→ 请右键设置终点(scan)"), false);
                    yield InteractionResult.SUCCESS;
                }
                case 1 -> scan(sp, sl, state, pos);
                case 2 -> {
                    state.lineStart = pos;
                    state.step = 3;
                    sp.sendSystemMessage(Component.literal(
                            "§a[木锄] 漏斗线起点 §7" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()
                                    + " §a→ 请右键设置终点(fill)"), false);
                    yield InteractionResult.SUCCESS;
                }
                case 3 -> fill(sp, sl, state, pos);
                default -> InteractionResult.PASS;
            };
        });
    }

    // ---------- 第 2 步：scan ----------

    private static InteractionResult scan(ServerPlayer sp, ServerLevel sl, SelectionState state, BlockPos pos) {
        state.to = pos;
        if (!HopperFillCommand.checkVolume(sp, state.from, state.to)) {
            clearState(sp.getUUID());
            return InteractionResult.FAIL;
        }
        if (TemplateStorage.getBlockIds(sp).isEmpty()) {
            sp.sendSystemMessage(Component.literal("§c[木锄] 请先使用 /hf set gui 添加要跳过的方块"), false);
            clearState(sp.getUUID());
            return InteractionResult.FAIL;
        }

        sp.sendSystemMessage(Component.literal("§a[木锄] 执行 scan..."), false);

        List<RegionScanner.Slice> slices = RegionScanner.scanSlices(sl, state.from, state.to,
                TemplateStorage.getBlockIds(sp), state.from, state.to);

        // ① 一片多物品：一个漏斗塞不下两种东西，先让用户补跳过方块
        String ambiguity = RegionScanner.describeAmbiguities(slices);
        if (ambiguity != null) {
            sp.sendSystemMessage(Component.literal("§c[木锄] " + ambiguity), false);
            reset(sp);
            return InteractionResult.SUCCESS;
        }

        List<ItemStack> results = slices.stream().map(RegionScanner.Slice::picked).toList();
        Set<Item> nonStackableItems = HopperFillCommand.printScanResult(sp, results);

        // ② 不可堆叠物品：漏斗同样放不下
        if (!nonStackableItems.isEmpty()) {
            sp.sendSystemMessage(Component.literal("§c[木锄] 检测到不可堆叠物品，操作已取消 → 请重新右键开始新的 scan"), false);
            reset(sp);
            return InteractionResult.SUCCESS;
        }

        state.step = 2;
        sp.sendSystemMessage(Component.literal("§a[木锄] scan 完成 → 请右键设置漏斗线起点"), false);
        sendQuickActions(sp, sl, slices, state.from, state.to);
        return InteractionResult.SUCCESS;
    }

    // ---------- 第 4 步：fill ----------

    private static InteractionResult fill(ServerPlayer sp, ServerLevel sl, SelectionState state, BlockPos pos) {
        state.lineEnd = pos;
        sp.sendSystemMessage(Component.literal("§a[木锄] 执行 fill..."), false);
        try {
            FillService.ValidationResult result = FillService.validate(sp, sl, state.from, state.to,
                    state.lineStart, state.lineEnd);
            if (!result.success) {
                sp.sendSystemMessage(Component.literal(result.errorMessage), false);
                reset(sp);
                return InteractionResult.SUCCESS;
            }

            if (sp.isCreative()) {
                // 创造：一键直写，不消耗
                FillService.FillOutcome fr = FillService.fill(sl, result.hoppers, result.results,
                        result.template16, result.template64, sp);
                if (!fr.success) {
                    sp.sendSystemMessage(Component.literal(fr.message), false);
                    reset(sp);
                    return InteractionResult.SUCCESS;
                }
                sp.sendSystemMessage(Component.literal(
                        "§a[木锄] fill 完成！已填充 " + result.hoppers.size() + " 个漏斗（其中 "
                                + fr.filteredCount + " 个含过滤物品）"), false);
                clearState(sp.getUUID());
            } else {
                // 生存：启动逐 tick 渐进填充（再右键木锄可手动结束）
                FillService.startSession(sp, sl, result);
                clearState(sp.getUUID()); // 选区已转交给会话，清掉步骤避免填充完成后误触发
            }
        } catch (IllegalStateException | IndexOutOfBoundsException e) {
            HopperFillMod.LOGGER.error("[木锄] fill 执行出现逻辑错误", e);
            sp.sendSystemMessage(Component.literal("§c[木锄] fill 执行出错，请查看服务端日志"), false);
            reset(sp);
        } catch (Exception e) {
            HopperFillMod.LOGGER.error("[木锄] fill 执行出现未知错误", e);
            sp.sendSystemMessage(Component.literal("§c[木锄] fill 执行出错，请查看服务端日志"), false);
            reset(sp);
        }
        return InteractionResult.SUCCESS;
    }

    // ---------- scan 完成后的可点击快捷操作 ----------

    /**
     * 按选区内容给出可点击的快捷操作：
     * 「清空漏斗」只在选区里全是容器时出现；「满盒物品」只在创造模式出现。
     */
    private static void sendQuickActions(ServerPlayer sp, ServerLevel level,
                                         List<RegionScanner.Slice> slices, BlockPos from, BlockPos to) {
        boolean canClear = RegionScanner.isAllContainers(level, slices);
        boolean canBox = sp.isCreative();
        if (!canClear && !canBox) return;

        MutableComponent line = Component.literal("§7[木锄] 快捷操作：");
        if (canClear) {
            line.append(actionButton("[清空漏斗]", "/hf clear",
                    Component.literal("§e清空选区内所有容器的内容\n§7生存返还物品、创造直接清空\n§8点击后木锄状态会重置")));
        }
        if (canBox) {
            if (canClear) line.append(Component.literal("  "));
            line.append(actionButton("[满盒物品]", giveBoxCommand(from, to),
                    Component.literal("§e把区域内扫描到的每种物品装满一个纯净潜影盒\n§c仅创造模式可用\n§8点击后木锄状态会重置")));
        }
        sp.sendSystemMessage(line, false);
    }

    /** 拼出带真实坐标的 /hf givebox 命令，点击即可直接执行 */
    private static String giveBoxCommand(BlockPos from, BlockPos to) {
        return "/hf givebox " + from.getX() + " " + from.getY() + " " + from.getZ()
                + " " + to.getX() + " " + to.getY() + " " + to.getZ();
    }

    private static MutableComponent actionButton(String label, String command, Component hoverText) {
        Style style = Style.EMPTY
                .withColor(ChatFormatting.YELLOW)
                .withClickEvent(new ClickEvent.RunCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(hoverText));
        return Component.literal(label).setStyle(style);
    }
}
