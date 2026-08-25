package com.leaf.hopperfill.tool;

import com.leaf.hopperfill.HopperFillMod;
import com.leaf.hopperfill.command.HopperFillCommand;
import com.leaf.hopperfill.data.TemplateStorage;
import com.leaf.hopperfill.fill.FillValidator;
import com.leaf.hopperfill.fill.HopperFiller;
import com.leaf.hopperfill.region.RegionScanner;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
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
import java.util.LinkedHashSet;
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
        SelectionState state = states.remove(uuid);
        if (state != null) {
            state.from = null;
            state.to = null;
            state.lineStart = null;
            state.lineEnd = null;
        }
    }

    public static void register() {
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            if (!(level instanceof ServerLevel) || hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
            if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
            if (!TemplateStorage.isHoeEnabled(sp)) return InteractionResult.PASS;
            if (!sp.getItemInHand(hand).is(Items.WOODEN_HOE)) return InteractionResult.PASS;
            if (hitResult.getType() != HitResult.Type.BLOCK) return InteractionResult.PASS;

            BlockPos pos = ((BlockHitResult) hitResult).getBlockPos();
            SelectionState state = states.computeIfAbsent(sp.getUUID(), k -> new SelectionState());
            ServerLevel sl = (ServerLevel) level;

            switch (state.step) {
                case 0 -> {
                    state.from = pos;
                    state.step = 1;
                    sp.sendSystemMessage(Component.literal(
                            "§a[木锄] 区域起点 §7" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + " §a→ 请右键设置终点(scan)"), false);
                    return InteractionResult.SUCCESS;
                }
                case 1 -> {
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
                    List<ItemStack> results = RegionScanner.scan(sl, state.from, state.to,
                            TemplateStorage.getBlockIds(sp), state.from, state.to);

                    sp.sendSystemMessage(Component.literal("§6===== 区域内容统计 ====="), false);
                    int itemCount = 0;
                    Set<Item> nonStackableItems = new LinkedHashSet<>();

                    StringBuilder batch = new StringBuilder(512);
                    int linesInBatch = 0;

                    for (ItemStack r : results) {
                        if (r.isEmpty()) {
                            batch.append(" §7• [跳过/空]\n");
                        } else {
                            boolean nonStackable = r.getMaxStackSize() == 1;
                            if (nonStackable) nonStackableItems.add(r.getItem());
                            batch.append(" §7• ").append(r.getDisplayName().getString())
                                    .append(" §8(").append(BuiltInRegistries.ITEM.getKey(r.getItem()))
                                    .append(") x1").append(nonStackable ? " §c[不可堆叠]" : "").append("\n");
                            itemCount++;
                        }
                        linesInBatch++;
                        if (linesInBatch >= MESSAGE_BATCH_SIZE) {
                            sp.sendSystemMessage(Component.literal(batch.toString()), false);
                            batch.setLength(0);
                            linesInBatch = 0;
                        }
                    }
                    if (linesInBatch > 0) {
                        sp.sendSystemMessage(Component.literal(batch.toString()), false);
                    }

                    sp.sendSystemMessage(Component.literal("§6共 " + results.size() + " 个位置（" + itemCount + " 个有效物品）"), false);

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
                        sp.sendSystemMessage(Component.literal(warn.toString()), false);
                        sp.sendSystemMessage(Component.literal("§c[木锄] 检测到不可堆叠物品，操作已取消 → 请重新右键开始新的 scan"), false);
                        clearState(sp.getUUID());
                        return InteractionResult.SUCCESS;
                    }

                    state.step = 2;
                    sp.sendSystemMessage(Component.literal("§a[木锄] scan 完成 → 请右键设置漏斗线起点"), false);
                    return InteractionResult.SUCCESS;
                }
                case 2 -> {
                    state.lineStart = pos;
                    state.step = 3;
                    sp.sendSystemMessage(Component.literal(
                            "§a[木锄] 漏斗线起点 §7" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + " §a→ 请右键设置终点(fill)"), false);
                    return InteractionResult.SUCCESS;
                }
                case 3 -> {
                    state.lineEnd = pos;
                    if (!sp.isCreative()) {
                        sp.sendSystemMessage(Component.literal("§c[木锄] 木锄填充仅创造模式可用"), false);
                        clearState(sp.getUUID());
                        return InteractionResult.SUCCESS;
                    }
                    sp.sendSystemMessage(Component.literal("§a[木锄] 执行 fill..."), false);
                    try {
                        FillValidator.Result result = FillValidator.validate(sp, sl, state.from, state.to, state.lineStart, state.lineEnd);
                        if (!result.success) {
                            sp.sendSystemMessage(Component.literal(result.errorMessage), false);
                            clearState(sp.getUUID());
                            return InteractionResult.SUCCESS;
                        }

                        int filteredCount = HopperFiller.fill(sl, result.hoppers, result.results,
                                result.template16, result.template64, sp).filteredCount;
                        sp.sendSystemMessage(Component.literal(
                                "§a[木锄] fill 完成！已填充 " + result.hoppers.size() + " 个漏斗（其中 " + filteredCount + " 个含过滤物品）"), false);
                    } catch (IllegalStateException | IndexOutOfBoundsException e) {
                        HopperFillMod.LOGGER.error("[木锄] fill 执行出现逻辑错误", e);
                        sp.sendSystemMessage(Component.literal("§c[木锄] fill 执行出错，请查看服务端日志"), false);
                    } catch (Exception e) {
                        HopperFillMod.LOGGER.error("[木锄] fill 执行出现未知错误", e);
                        sp.sendSystemMessage(Component.literal("§c[木锄] fill 执行出错，请查看服务端日志"), false);
                    }
                    clearState(sp.getUUID());
                    return InteractionResult.SUCCESS;
                }
                default -> {
                    return InteractionResult.PASS;
                }
            }
        });
    }
}