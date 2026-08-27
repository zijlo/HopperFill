package com.zijlo.hopperfill.region;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class RegionScanner {

    public static List<ItemStack> scan(ServerLevel level, BlockPos from, BlockPos to,
                                       Set<Identifier> skipIds, BlockPos axisStart, BlockPos axisEnd) {
        int minX = Math.min(from.getX(), to.getX());
        int maxX = Math.max(from.getX(), to.getX());
        int minY = Math.min(from.getY(), to.getY());
        int maxY = Math.max(from.getY(), to.getY());
        int minZ = Math.min(from.getZ(), to.getZ());
        int maxZ = Math.max(from.getZ(), to.getZ());

        int dx = axisEnd.getX() - axisStart.getX();
        int dy = axisEnd.getY() - axisStart.getY();
        int dz = axisEnd.getZ() - axisStart.getZ();
        int absDx = Math.abs(dx);
        int absDy = Math.abs(dy);
        int absDz = Math.abs(dz);

        int steps = Math.max(absDx, Math.max(absDy, absDz)) + 1;
        List<ItemStack> results = new ArrayList<>(steps);

        if (absDx >= absDz && absDx >= absDy) {
            for (int i = 0; i < steps; i++) {
                int x = axisStart.getX() + (dx >= 0 ? i : -i);
                results.add(scanSliceX(level, x, minY, maxY, minZ, maxZ, skipIds));
            }
        } else if (absDz >= absDy) {
            for (int i = 0; i < steps; i++) {
                int z = axisStart.getZ() + (dz >= 0 ? i : -i);
                results.add(scanSliceZ(level, minX, maxX, minY, maxY, z, skipIds));
            }
        } else {
            for (int i = 0; i < steps; i++) {
                int y = axisStart.getY() + (dy >= 0 ? i : -i);
                results.add(scanSliceY(level, minX, maxX, y, minZ, maxZ, skipIds));
            }
        }
        return results;
    }

    private static ItemStack scanSliceX(ServerLevel level, int x, int minY, int maxY, int minZ, int maxZ, Set<Identifier> skipIds) {
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                ItemStack frame = detectItemFrame(level, new BlockPos(x, y, z));
                if (!frame.isEmpty()) return frame;
            }
        }
        for (int y = maxY; y >= minY; y--) {
            for (int z = minZ; z <= maxZ; z++) {
                ItemStack block = resolveBlockItem(level, new BlockPos(x, y, z), skipIds);
                if (!block.isEmpty()) return block;
            }
        }
        return ItemStack.EMPTY;
    }

    private static ItemStack scanSliceZ(ServerLevel level, int minX, int maxX, int minY, int maxY, int z, Set<Identifier> skipIds) {
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                ItemStack frame = detectItemFrame(level, new BlockPos(x, y, z));
                if (!frame.isEmpty()) return frame;
            }
        }
        for (int y = maxY; y >= minY; y--) {
            for (int x = minX; x <= maxX; x++) {
                ItemStack block = resolveBlockItem(level, new BlockPos(x, y, z), skipIds);
                if (!block.isEmpty()) return block;
            }
        }
        return ItemStack.EMPTY;
    }

    private static ItemStack scanSliceY(ServerLevel level, int minX, int maxX, int y, int minZ, int maxZ, Set<Identifier> skipIds) {
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                ItemStack frame = detectItemFrame(level, new BlockPos(x, y, z));
                if (!frame.isEmpty()) return frame;
            }
        }
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                ItemStack block = resolveBlockItem(level, new BlockPos(x, y, z), skipIds);
                if (!block.isEmpty()) return block;
            }
        }
        return ItemStack.EMPTY;
    }

    private static ItemStack detectItemFrame(ServerLevel level, BlockPos pos) {
        List<ItemFrame> frames = level.getEntitiesOfClass(ItemFrame.class,
                new AABB(pos).inflate(1.0),
                f -> f.getPos().equals(pos));
        if (frames.isEmpty()) return ItemStack.EMPTY;

        ItemStack held = frames.get(0).getItem();
        if (!held.isEmpty()) {
            ItemStack stack = held.copy();
            stack.setCount(1);
            return stack;
        } else {
            ItemStack stack = new ItemStack(Items.ITEM_FRAME);
            stack.setCount(1);
            return stack;
        }
    }

    private static ItemStack resolveBlockItem(ServerLevel level, BlockPos pos, Set<Identifier> skipIds) {
        Block block = level.getBlockState(pos).getBlock();
        if (block == Blocks.AIR) return ItemStack.EMPTY;

        if (block instanceof FlowerPotBlock fp) {
            Block plant = fp.getPotted();
            if (plant != Blocks.AIR) {
                ItemStack stack = new ItemStack(plant.asItem());
                if (!stack.isEmpty()) {
                    stack.setCount(1);
                    return stack;
                }
            }
            ItemStack stack = new ItemStack(block.asItem());
            if (!stack.isEmpty()) {
                stack.setCount(1);
                return stack;
            }
            return ItemStack.EMPTY;
        }

        if (skipIds.contains(BuiltInRegistries.BLOCK.getKey(block))) {
            return ItemStack.EMPTY;
        }

        ItemStack stack = new ItemStack(block.asItem());
        if (!stack.isEmpty()) {
            stack.setCount(1);
            return stack;
        }
        return ItemStack.EMPTY;
    }
}