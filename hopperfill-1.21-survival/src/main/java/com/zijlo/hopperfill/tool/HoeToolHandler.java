package com.zijlo.hopperfill.tool;

import com.zijlo.hopperfill.HopperFillMod;
import com.zijlo.hopperfill.command.HopperFillCommand;
import com.zijlo.hopperfill.data.TemplateStorage;
import com.zijlo.hopperfill.fill.FillService;
import com.zijlo.hopperfill.region.RegionScanner;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

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
    public static void reset(ServerPlayerEntity player) {
        if (FillService.isSessionActive(player)) {
            FillService.stopSession(player);
        }
        clearState(player.getUuid());
    }

    /** @return 当前已完成圈选的区域 {@code [起点, 终点]}，还没圈完则返回 {@code null} */
    public static BlockPos[] getCurrentRegion(UUID uuid) {
        SelectionState state = STATES.get(uuid);
        if (state == null || state.from == null || state.to == null) return null;
        return new BlockPos[]{state.from, state.to};
    }

    // ---------- 事件注册 ----------

    public static void register() {
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!(world instanceof ServerWorld sw) || hand != Hand.MAIN_HAND) return ActionResult.PASS;
            if (!(player instanceof ServerPlayerEntity sp)) return ActionResult.PASS;
            if (!TemplateStorage.isHoeEnabled(sp)) return ActionResult.PASS;
            if (!player.getStackInHand(hand).isOf(Items.WOODEN_HOE)) return ActionResult.PASS;
            if (hitResult.getType() != HitResult.Type.BLOCK) return ActionResult.PASS;

            BlockPos pos = ((BlockHitResult) hitResult).getBlockPos();

            // 生存填充会话进行中：本次右键 = 结束
            if (FillService.isSessionActive(sp)) {
                FillService.stopSession(sp);
                clearState(sp.getUuid());
                return ActionResult.SUCCESS;
            }

            SelectionState state = STATES.computeIfAbsent(sp.getUuid(), k -> new SelectionState());

            return switch (state.step) {
                case 0 -> {
                    state.from = pos;
                    state.step = 1;
                    sp.sendMessage(Text.literal(
                            "§a[木锄] 区域起点 §7" + pos.toShortString() + " §a→ 请右键设置终点(scan)"), false);
                    yield ActionResult.SUCCESS;
                }
                case 1 -> scan(sp, sw, state, pos);
                case 2 -> {
                    state.lineStart = pos;
                    state.step = 3;
                    sp.sendMessage(Text.literal(
                            "§a[木锄] 漏斗线起点 §7" + pos.toShortString() + " §a→ 请右键设置终点(fill)"), false);
                    yield ActionResult.SUCCESS;
                }
                case 3 -> fill(sp, sw, state, pos);
                default -> ActionResult.PASS;
            };
        });
    }

    // ---------- 第 2 步：scan ----------

    private static ActionResult scan(ServerPlayerEntity sp, ServerWorld sw, SelectionState state, BlockPos pos) {
        state.to = pos;
        if (!HopperFillCommand.checkVolume(sp, state.from, state.to)) {
            clearState(sp.getUuid());
            return ActionResult.FAIL;
        }
        if (TemplateStorage.getBlockIds(sp).isEmpty()) {
            sp.sendMessage(Text.literal("§c[木锄] 请先使用 /hf set gui 添加要跳过的方块"), false);
            clearState(sp.getUuid());
            return ActionResult.FAIL;
        }

        sp.sendMessage(Text.literal("§a[木锄] 执行 scan..."), false);

        List<RegionScanner.Slice> slices = RegionScanner.scanSlices(sw, state.from, state.to,
                TemplateStorage.getBlockIds(sp), state.from, state.to);

        // ① 一片多物品：一个漏斗塞不下两种东西，先让用户补跳过方块
        String ambiguity = RegionScanner.describeAmbiguities(slices);
        if (ambiguity != null) {
            sp.sendMessage(Text.literal("§c[木锄] " + ambiguity), false);
            reset(sp);
            return ActionResult.SUCCESS;
        }

        List<ItemStack> results = slices.stream().map(RegionScanner.Slice::picked).toList();
        Set<Item> nonStackableItems = HopperFillCommand.printScanResult(sp, results);

        // ② 不可堆叠物品：漏斗同样放不下
        if (!nonStackableItems.isEmpty()) {
            sp.sendMessage(Text.literal("§c[木锄] 检测到不可堆叠物品，操作已取消 → 请重新右键开始新的 scan"), false);
            reset(sp);
            return ActionResult.SUCCESS;
        }

        state.step = 2;
        sp.sendMessage(Text.literal("§a[木锄] scan 完成 → 请右键设置漏斗线起点"), false);
        sendQuickActions(sp, sw, slices, state.from, state.to);
        return ActionResult.SUCCESS;
    }

    // ---------- 第 4 步：fill ----------

    private static ActionResult fill(ServerPlayerEntity sp, ServerWorld sw, SelectionState state, BlockPos pos) {
        state.lineEnd = pos;
        sp.sendMessage(Text.literal("§a[木锄] 执行 fill..."), false);
        try {
            FillService.ValidationResult result = FillService.validate(sp, sw, state.from, state.to,
                    state.lineStart, state.lineEnd);
            if (!result.success) {
                sp.sendMessage(Text.literal(result.errorMessage), false);
                reset(sp);
                return ActionResult.SUCCESS;
            }

            if (sp.interactionManager.isCreative()) {
                // 创造：一键直写，不消耗
                FillService.FillOutcome fr = FillService.fill(sw, result.hoppers, result.results,
                        result.template16, result.template64, sp);
                if (!fr.success) {
                    sp.sendMessage(Text.literal(fr.message), false);
                    reset(sp);
                    return ActionResult.SUCCESS;
                }
                sp.sendMessage(Text.literal(
                        "§a[木锄] fill 完成！已填充 " + result.hoppers.size() + " 个漏斗（其中 "
                                + fr.filteredCount + " 个含过滤物品）"), false);
                clearState(sp.getUuid());
            } else {
                // 生存：启动逐 tick 渐进填充（再右键木锄可手动结束）
                FillService.startSession(sp, sw, result);
                clearState(sp.getUuid()); // 选区已转交给会话，清掉步骤避免填充完成后误触发
            }
        } catch (IllegalStateException | IndexOutOfBoundsException e) {
            HopperFillMod.LOGGER.error("[木锄] fill 执行出现逻辑错误", e);
            sp.sendMessage(Text.literal("§c[木锄] fill 执行出错，请查看服务端日志"), false);
            reset(sp);
        } catch (Exception e) {
            HopperFillMod.LOGGER.error("[木锄] fill 执行出现未知错误", e);
            sp.sendMessage(Text.literal("§c[木锄] fill 执行出错，请查看服务端日志"), false);
            reset(sp);
        }
        return ActionResult.SUCCESS;
    }

    // ---------- scan 完成后的可点击快捷操作 ----------

    /**
     * 按选区内容给出可点击的快捷操作：
     * 「清空漏斗」只在选区里全是容器时出现；「满盒物品」只在创造模式出现。
     */
    private static void sendQuickActions(ServerPlayerEntity sp, ServerWorld world,
                                         List<RegionScanner.Slice> slices, BlockPos from, BlockPos to) {
        boolean canClear = RegionScanner.isAllContainers(world, slices);
        boolean canBox = sp.interactionManager.isCreative();
        if (!canClear && !canBox) return;

        MutableText line = Text.literal("§7[木锄] 快捷操作：");
        if (canClear) {
            line.append(actionButton("[清空漏斗]", "/hf clear",
                    Text.literal("§e清空选区内所有容器的内容\n§7生存返还物品、创造直接清空\n§8点击后木锄状态会重置")));
        }
        if (canBox) {
            if (canClear) line.append(Text.literal("  "));
            line.append(actionButton("[满盒物品]", giveBoxCommand(from, to),
                    Text.literal("§e把区域内扫描到的每种物品装满一个纯净潜影盒\n§c仅创造模式可用\n§8点击后木锄状态会重置")));
        }
        sp.sendMessage(line, false);
    }

    /** 拼出带真实坐标的 /hf givebox 命令，点击即可直接执行 */
    private static String giveBoxCommand(BlockPos from, BlockPos to) {
        return "/hf givebox " + from.getX() + " " + from.getY() + " " + from.getZ()
                + " " + to.getX() + " " + to.getY() + " " + to.getZ();
    }

    private static MutableText actionButton(String label, String command, Text hoverText) {
        Style style = Style.EMPTY
                .withColor(Formatting.YELLOW)
                .withClickEvent(new ClickEvent.RunCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(hoverText));
        return Text.literal(label).setStyle(style);
    }
}
