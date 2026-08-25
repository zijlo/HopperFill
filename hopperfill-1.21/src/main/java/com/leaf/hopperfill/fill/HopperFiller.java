package com.leaf.hopperfill.fill;

import com.leaf.hopperfill.data.TemplateStorage;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

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
     * 生存模式：检查背包是否有足够数量的所有材料，够了则消耗并填充，不够则返回提示（不填充）。
     */
    public static FillResult fill(ServerWorld world, List<BlockPos> hoppers, List<ItemStack> results,
                                  List<ItemStack> template16, List<ItemStack> template64,
                                  ServerPlayerEntity player) {
        // 1. 预先计算每个漏斗最终槽位布局
        List<ItemStack[]> placements = new ArrayList<>(hoppers.size());
        int filteredCount = 0;
        for (ItemStack r : results) {
            placements.add(computePlacement(template16, template64, r));
            if (!r.isEmpty()) filteredCount++;
        }

        if (!player.interactionManager.isCreative()) {
            return new FillResult(false, "§c该功能仅创造模式可用", filteredCount);
        }

        writeAll(world, hoppers, placements);
        return new FillResult(true, null, filteredCount);
    }

    /**
     * 计算单个漏斗的最终 5 槽布局。
     */
    public static ItemStack[] computePlacement(List<ItemStack> template16, List<ItemStack> template64,
                                               ItemStack r) {
        List<ItemStack> template;
        if (r.isEmpty()) {
            template = TemplateStorage.hasAnyItem(template64) ? template64 : template16;
        } else {
            template = r.getMaxCount() >= 64 ? template64 : template16;
        }

        ItemStack filterItem;
        if (!r.isEmpty()) {
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
    private static void writeAll(ServerWorld world, List<BlockPos> hoppers, List<ItemStack[]> placements) {
        for (int i = 0; i < hoppers.size(); i++) {
            HopperBlockEntity hopper = (HopperBlockEntity) world.getBlockEntity(hoppers.get(i));
            if (hopper == null) continue;
            Inventory inv = hopper;
            inv.clear();
            ItemStack[] slots = placements.get(i);
            for (int s = 0; s < HOPPER_SLOT_COUNT; s++) {
                inv.setStack(s, slots[s].copy());
            }
            hopper.markDirty();
            BlockState st = world.getBlockState(hoppers.get(i));
            world.updateListeners(hoppers.get(i), st, st, BLOCK_UPDATE_FLAGS);
        }
    }
}