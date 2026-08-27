package com.zijlo.hopperfill.fill;

import com.zijlo.hopperfill.data.TemplateStorage;
import com.zijlo.hopperfill.geometry.LineBresenham3D;
import com.zijlo.hopperfill.region.RegionScanner;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.HopperBlock;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class FillValidator {

    public static Result validate(ServerPlayer player, ServerLevel level,
                                  BlockPos from, BlockPos to,
                                  BlockPos lineStart, BlockPos lineEnd) {
        if (!TemplateStorage.hasTemplate16(player) && !TemplateStorage.hasTemplate64(player)) {
            return Result.fail("§c请先使用 /hf set template 16/64 设置模板");
        }

        Set<net.minecraft.resources.Identifier> skipIds = TemplateStorage.getBlockIds(player);
        if (skipIds.isEmpty()) {
            return Result.fail("§c请先使用 /hf set gui 添加要跳过的方块");
        }

        List<BlockPos> hoppers = LineBresenham3D.getPositions(lineStart, lineEnd);

        Optional<String> continuityError = LineBresenham3D.checkContinuity(hoppers);
        if (continuityError.isPresent()) {
            return Result.fail("§c错误：漏斗线不连续，" + continuityError.get());
        }

        for (BlockPos hopperPos : hoppers) {
            if (!(level.getBlockState(hopperPos).getBlock() instanceof HopperBlock)) {
                return Result.fail("§c错误：" + hopperPos.getX() + ", " + hopperPos.getY() + ", " + hopperPos.getZ() + " 不是漏斗");
            }
        }

        List<ItemStack> results = RegionScanner.scan(level, from, to, skipIds, lineStart, lineEnd);

        if (results.size() != hoppers.size()) {
            int diff = Math.abs(results.size() - hoppers.size());
            return Result.fail("§c错误：区域切片数 " + results.size() + " 与漏斗数 " + hoppers.size() + " 不匹配（差值 " + diff + "），请检查圈选区域");
        }

        for (ItemStack r : results) {
            if (r.isEmpty()) continue;
            if (r.getMaxStackSize() == 1) {
                return Result.fail("§c错误：扫描到不可堆叠物品 \"" + r.getDisplayName().getString() + "\"");
            }
        }

        List<ItemStack> t16 = TemplateStorage.getTemplate16(player);
        List<ItemStack> t64 = TemplateStorage.getTemplate64(player);
        if (t16 == null) t16 = Collections.emptyList();
        if (t64 == null) t64 = Collections.emptyList();

        for (ItemStack r : results) {
            if (r.isEmpty()) continue;
            int mc = r.getMaxStackSize();
            List<ItemStack> needed = (mc >= 64) ? t64 : t16;
            if (!TemplateStorage.hasAnyItem(needed)) {
                String typeName = (mc >= 64) ? "64" : "16";
                return Result.fail("§c错误：扫描到 " + typeName + " 堆叠物品 \"" + r.getDisplayName().getString() +
                        "\"，但未设置 " + typeName + " 堆叠模板。请使用 /hf set template " + typeName);
            }
        }

        return Result.success(hoppers, results, t16, t64);
    }

    public static class Result {
        public final boolean success;
        public final String errorMessage;
        public final List<BlockPos> hoppers;
        public final List<ItemStack> results;
        public final List<ItemStack> template16;
        public final List<ItemStack> template64;

        private Result(boolean success, String errorMessage,
                       List<BlockPos> hoppers, List<ItemStack> results,
                       List<ItemStack> template16, List<ItemStack> template64) {
            this.success = success;
            this.errorMessage = errorMessage;
            this.hoppers = hoppers;
            this.results = results;
            this.template16 = template16;
            this.template64 = template64;
        }

        static Result fail(String message) {
            return new Result(false, message, null, null, null, null);
        }

        static Result success(List<BlockPos> hoppers, List<ItemStack> results,
                              List<ItemStack> template16, List<ItemStack> template64) {
            return new Result(true, null, hoppers, results, template16, template64);
        }
    }
}