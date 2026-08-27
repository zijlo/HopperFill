package com.zijlo.hopperfill.fill;

import com.zijlo.hopperfill.data.TemplateStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class HopperFiller {

    /** 漏斗槽位总数 */
    private static final int HOPPER_SLOT_COUNT = 5;
    /** 兜底过滤槽位 */
    private static final int FILTER_SLOT = 0;
    /** 方块更新标志：通知邻居 + 客户端 */
    private static final int BLOCK_UPDATE_FLAGS = 3;

    /** 填充结果 */
    public static class FillResult {
        public final boolean success;
        public final String message;
        public final int filteredCount;

        public FillResult(boolean success, String message, int filteredCount) {
            this.success = success;
            this.message = message;
            this.filteredCount = filteredCount;
        }
    }

    /**
     * 填充漏斗线。
     * 创造模式：直接填充，不消耗。
     * 生存模式：检查背包材料（含潜影盒）够了则扣料并填充，不够返回提示。
     */
    public static FillResult fill(ServerLevel level, List<BlockPos> hoppers, List<ItemStack> results,
                                  List<ItemStack> template16, List<ItemStack> template64,
                                  ServerPlayer player) {
        List<ItemStack[]> placements = new ArrayList<>(hoppers.size());
        int filteredCount = 0;
        for (ItemStack r : results) {
            placements.add(computePlacement(template16, template64, r));
            if (!r.isEmpty()) filteredCount++;
        }

        if (!player.isCreative()) {
            // 生存：统计总需求 -> 检查 -> 扣料
            Map<Item, Integer> need = new HashMap<>();
            for (ItemStack[] slots : placements) {
                for (ItemStack s : slots) {
                    if (s == null || s.isEmpty()) continue;
                    need.merge(s.getItem(), s.getCount(), Integer::sum);
                }
            }
            for (Map.Entry<Item, Integer> e : need.entrySet()) {
                int have = FillSession.countAvailable(player, e.getKey());
                if (have < e.getValue()) {
                    return new FillResult(false, "§c材料不足：§e" + new ItemStack(e.getKey()).getDisplayName().getString()
                            + " §c需 " + e.getValue() + " 个（现有 " + have + " 个）", filteredCount);
                }
            }
            for (Map.Entry<Item, Integer> e : need.entrySet()) {
                FillSession.consume(player, e.getKey(), e.getValue());
            }
        }

        writeAll(level, hoppers, placements);
        return new FillResult(true, null, filteredCount);
    }

    /**
     * 计算单个漏斗的最终 5 槽布局（含过滤物）。
     */
    public static ItemStack[] computePlacement(List<ItemStack> template16, List<ItemStack> template64,
                                               ItemStack r) {
        return computePlacement(template16, template64, r, true);
    }

    /**
     * 计算单个漏斗的最终 5 槽布局。
     * @param includeFilter false 时过滤槽（首位）留空，仅填模板。
     */
    public static ItemStack[] computePlacement(List<ItemStack> template16, List<ItemStack> template64,
                                               ItemStack r, boolean includeFilter) {
        List<ItemStack> template;
        if (r.isEmpty()) {
            template = TemplateStorage.hasAnyItem(template64) ? template64 : template16;
        } else {
            template = r.getMaxStackSize() >= 64 ? template64 : template16;
        }

        ItemStack filterItem;
        if (!includeFilter) {
            filterItem = ItemStack.EMPTY;
        } else if (!r.isEmpty()) {
            filterItem = r.copy();
        } else {
            filterItem = ItemStack.EMPTY;
            if (template != null) {
                for (ItemStack t : template) {
                    if (t != null && !t.isEmpty()) {
                        filterItem = t.copy();
                        filterItem.setCount(1);
                        break;
                    }
                }
            }
        }

        ItemStack[] slots = new ItemStack[HOPPER_SLOT_COUNT];
        boolean filterPlaced = filterItem.isEmpty();
        for (int slot = 0; slot < HOPPER_SLOT_COUNT; slot++) {
            ItemStack t = (template != null && slot < template.size()) ? template.get(slot) : null;
            if (t != null && !t.isEmpty()) {
                slots[slot] = t.copy();
                continue;
            }
            if (!filterPlaced) {
                slots[slot] = filterItem.copy();
                filterPlaced = true;
                continue;
            }
            slots[slot] = ItemStack.EMPTY;
        }
        if (!filterPlaced) {
            slots[FILTER_SLOT] = filterItem.copy();
        }
        return slots;
    }

    /** 将计算好的布局写入所有漏斗 */
    private static void writeAll(ServerLevel level, List<BlockPos> hoppers, List<ItemStack[]> placements) {
        for (int i = 0; i < hoppers.size(); i++) {
            HopperBlockEntity hopper = (HopperBlockEntity) level.getBlockEntity(hoppers.get(i));
            if (hopper == null) continue;
            hopper.clearContent();
            ItemStack[] slots = placements.get(i);
            for (int s = 0; s < HOPPER_SLOT_COUNT; s++) {
                hopper.setItem(s, slots[s].copy());
            }
            hopper.setChanged();
            BlockState st = level.getBlockState(hoppers.get(i));
            level.sendBlockUpdated(hoppers.get(i), st, st, BLOCK_UPDATE_FLAGS);
        }
    }
}
