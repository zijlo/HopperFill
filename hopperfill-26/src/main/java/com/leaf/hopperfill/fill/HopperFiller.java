package com.leaf.hopperfill.fill;

import com.leaf.hopperfill.data.TemplateStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

public class HopperFiller {

    /** 漏斗槽位总数 */
    private static final int HOPPER_SLOT_COUNT = 5;
    /** 第一个过滤槽位 */
    private static final int FILTER_SLOT = 0;
    /** 方块更新标志：通知邻居+客户端 */
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
     * 填充漏斗线。仅创造模式可用：直接填充，不消耗任何材料。
     */
    public static FillResult fill(ServerLevel level, List<BlockPos> hoppers, List<ItemStack> results,
                                  List<ItemStack> template16, List<ItemStack> template64,
                                  ServerPlayer player) {
        if (!player.isCreative()) {
            return new FillResult(false, "§c该功能仅创造模式可用", 0);
        }

        int filteredCount = 0;
        for (int i = 0; i < hoppers.size(); i++) {
            BlockPos pos = hoppers.get(i);
            HopperBlockEntity hopper = (HopperBlockEntity) level.getBlockEntity(pos);
            if (hopper == null) continue;

            hopper.clearContent();

            ItemStack r = results.get(i);
            // 模板是漏斗 5 槽的完整布局，空槽为过滤物占位
            List<ItemStack> template;
            if (r.isEmpty()) {
                template = TemplateStorage.hasAnyItem(template64) ? template64 : template16;
            } else {
                template = r.getMaxStackSize() >= 64 ? template64 : template16;
            }

            // 过滤物：有扫描物品用 r；无物品（切片内仅跳过方块）用模板首个非空物品作为默认过滤物
            ItemStack filterItem;
            if (!r.isEmpty()) {
                filterItem = r.copy();
            } else {
                filterItem = ItemStack.EMPTY;
                for (ItemStack t : template) {
                    if (t != null && !t.isEmpty()) {
                        filterItem = t.copy();
                        filterItem.setCount(1);
                        break;
                    }
                }
            }

            boolean filterPlaced = filterItem.isEmpty();
            for (int slot = 0; slot < HOPPER_SLOT_COUNT; slot++) {
                ItemStack t = (slot < template.size()) ? template.get(slot) : ItemStack.EMPTY;
                if (t != null && !t.isEmpty()) {
                    hopper.setItem(slot, t.copy());
                    continue;
                }
                if (!filterPlaced) {
                    // 过滤物（或默认过滤物）填入第一个空槽
                    hopper.setItem(slot, filterItem.copy());
                    filterPlaced = true;
                    if (!r.isEmpty()) filteredCount++;
                    continue;
                }
                hopper.setItem(slot, ItemStack.EMPTY);
            }
            // 兜底：模板无空槽时，过滤物强制放到第 0 槽
            if (!filterPlaced) {
                hopper.setItem(FILTER_SLOT, filterItem.copy());
                if (!r.isEmpty()) filteredCount++;
            }

            hopper.setChanged();
            BlockState state = level.getBlockState(pos);
            level.sendBlockUpdated(pos, state, state, BLOCK_UPDATE_FLAGS);
        }
        return new FillResult(true, null, filteredCount);
    }
}