package com.zijlo.hopperfill.tool;

import com.zijlo.hopperfill.HopperFillMod;
import com.zijlo.hopperfill.command.HopperFillCommand;
import com.zijlo.hopperfill.data.TemplateStorage;
import com.zijlo.hopperfill.fill.FillSession;
import com.zijlo.hopperfill.fill.FillValidator;
import com.zijlo.hopperfill.fill.HopperFiller;
import com.zijlo.hopperfill.region.RegionScanner;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
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
 */
public class HoeToolHandler {
    private static final Map<UUID, SelectionState> states = new HashMap<>();

    /** 消息分批大小，与 HopperFillCommand 保持一致 */
    private static final int MESSAGE_BATCH_SIZE = 10;

    private static class SelectionState {
        BlockPos from, to, lineStart, lineEnd;
        int step = 0;
    }

    public static void clearState(UUID uuid) {
        states.remove(uuid);
    }

    public static void register() {
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!(world instanceof ServerWorld) || hand != Hand.MAIN_HAND) return ActionResult.PASS;
            if (!(player instanceof ServerPlayerEntity sp)) return ActionResult.PASS;
            if (!TemplateStorage.isHoeEnabled(sp)) return ActionResult.PASS;
            if (!player.getStackInHand(hand).isOf(Items.WOODEN_HOE)) return ActionResult.PASS;
            if (hitResult.getType() != HitResult.Type.BLOCK) return ActionResult.PASS;

            BlockPos pos = ((BlockHitResult) hitResult).getBlockPos();

            // 填充会话进行中：本次右键 = 结束（第 5 下）
            if (FillSession.isActive(sp)) {
                FillSession.stop(sp);
                clearState(sp.getUuid());
                return ActionResult.SUCCESS;
            }

            SelectionState state = states.computeIfAbsent(sp.getUuid(), k -> new SelectionState());
            ServerWorld sw = (ServerWorld) world;

            switch (state.step) {
                case 0 -> {
                    state.from = pos;
                    state.step = 1;
                    sp.sendMessage(Text.literal(
                            "§a[木锄] 区域起点 §7" + pos.toShortString() + " §a→ 请右键设置终点(scan)"), false);
                    return ActionResult.SUCCESS;
                }
                case 1 -> {
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
                    List<ItemStack> results = RegionScanner.scan(sw, state.from, state.to,
                            TemplateStorage.getBlockIds(sp), state.from, state.to);

                    Set<Item> nonStackableItems = HopperFillCommand.printScanResult(sp, results);

                    if (!nonStackableItems.isEmpty()) {
                        sp.sendMessage(Text.literal("§c[木锄] 检测到不可堆叠物品，操作已取消 → 请重新右键开始新的 scan"), false);
                        clearState(sp.getUuid());
                        return ActionResult.SUCCESS;
                    }

                    state.step = 2;
                    sp.sendMessage(Text.literal("§a[木锄] scan 完成 → 请右键设置漏斗线起点"), false);
                    return ActionResult.SUCCESS;
                }
                case 2 -> {
                    state.lineStart = pos;
                    state.step = 3;
                    sp.sendMessage(Text.literal(
                            "§a[木锄] 漏斗线起点 §7" + pos.toShortString() + " §a→ 请右键设置终点(fill)"), false);
                    return ActionResult.SUCCESS;
                }
                case 3 -> {
                    state.lineEnd = pos;
                    sp.sendMessage(Text.literal("§a[木锄] 执行 fill..."), false);
                    try {
                        FillValidator.Result result = FillValidator.validate(sp, sw, state.from, state.to, state.lineStart, state.lineEnd);
                        if (!result.success) {
                            sp.sendMessage(Text.literal(result.errorMessage), false);
                            clearState(sp.getUuid());
                            return ActionResult.SUCCESS;
                        }

                        if (sp.interactionManager.isCreative()) {
                            // 创造：一键直写，不消耗
                            HopperFiller.FillResult fr = HopperFiller.fill(sw, result.hoppers, result.results,
                                    result.template16, result.template64, sp);
                            if (!fr.success) {
                                sp.sendMessage(Text.literal(fr.message), false);
                                clearState(sp.getUuid());
                                return ActionResult.SUCCESS;
                            }
                            sp.sendMessage(Text.literal(
                                    "§a[木锄] fill 完成！已填充 " + result.hoppers.size() + " 个漏斗（其中 " + fr.filteredCount + " 个含过滤物品）"), false);
                            clearState(sp.getUuid());
                        } else {
                            // 生存：启动逐 tick 渐进填充（第 5 下右键结束）
                            FillSession.start(sp, sw, result);
                            clearState(sp.getUuid()); // 选区已转交给会话，清除残留 step 避免填充完成后误触发
                        }
                    } catch (IllegalStateException | IndexOutOfBoundsException e) {
                        HopperFillMod.LOGGER.error("[木锄] fill 执行出现逻辑错误", e);
                        sp.sendMessage(Text.literal("§c[木锄] fill 执行出错，请查看服务端日志"), false);
                        clearState(sp.getUuid());
                    } catch (Exception e) {
                        HopperFillMod.LOGGER.error("[木锄] fill 执行出现未知错误", e);
                        sp.sendMessage(Text.literal("§c[木锄] fill 执行出错，请查看服务端日志"), false);
                        clearState(sp.getUuid());
                    }
                    return ActionResult.SUCCESS;
                }
                default -> {
                    return ActionResult.PASS;
                }
            }
        });
    }
}