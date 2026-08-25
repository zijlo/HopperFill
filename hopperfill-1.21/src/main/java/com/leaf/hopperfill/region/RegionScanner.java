package com.leaf.hopperfill.region;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.FlowerPotBlock;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class RegionScanner {

    public static List<ItemStack> scan(ServerWorld world, BlockPos from, BlockPos to,
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
                results.add(scanSliceX(world, x, minY, maxY, minZ, maxZ, skipIds));
            }
        } else if (absDz >= absDy) {
            for (int i = 0; i < steps; i++) {
                int z = axisStart.getZ() + (dz >= 0 ? i : -i);
                results.add(scanSliceZ(world, minX, maxX, minY, maxY, z, skipIds));
            }
        } else {
            for (int i = 0; i < steps; i++) {
                int y = axisStart.getY() + (dy >= 0 ? i : -i);
                results.add(scanSliceY(world, minX, maxX, y, minZ, maxZ, skipIds));
            }
        }
        return results;
    }

    private static ItemStack scanSliceX(ServerWorld world, int x, int minY, int maxY, int minZ, int maxZ, Set<Identifier> skipIds) {
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                ItemStack frame = detectItemFrame(world, new BlockPos(x, y, z));
                if (!frame.isEmpty()) return frame;
            }
        }
        for (int y = maxY; y >= minY; y--) {
            for (int z = minZ; z <= maxZ; z++) {
                ItemStack block = resolveBlockItem(world, new BlockPos(x, y, z), skipIds);
                if (!block.isEmpty()) return block;
            }
        }
        return ItemStack.EMPTY;
    }

    private static ItemStack scanSliceZ(ServerWorld world, int minX, int maxX, int minY, int maxY, int z, Set<Identifier> skipIds) {
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                ItemStack frame = detectItemFrame(world, new BlockPos(x, y, z));
                if (!frame.isEmpty()) return frame;
            }
        }
        for (int y = maxY; y >= minY; y--) {
            for (int x = minX; x <= maxX; x++) {
                ItemStack block = resolveBlockItem(world, new BlockPos(x, y, z), skipIds);
                if (!block.isEmpty()) return block;
            }
        }
        return ItemStack.EMPTY;
    }

    private static ItemStack scanSliceY(ServerWorld world, int minX, int maxX, int y, int minZ, int maxZ, Set<Identifier> skipIds) {
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                ItemStack frame = detectItemFrame(world, new BlockPos(x, y, z));
                if (!frame.isEmpty()) return frame;
            }
        }
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                ItemStack block = resolveBlockItem(world, new BlockPos(x, y, z), skipIds);
                if (!block.isEmpty()) return block;
            }
        }
        return ItemStack.EMPTY;
    }

    private static ItemStack detectItemFrame(ServerWorld world, BlockPos pos) {
        List<ItemFrameEntity> frames = world.getEntitiesByClass(ItemFrameEntity.class,
                new Box(pos).expand(0.1),
                f -> f.getAttachedBlockPos().equals(pos));
        if (!frames.isEmpty()) {
            ItemStack held = frames.get(0).getHeldItemStack();
            if (!held.isEmpty()) {
                ItemStack stack = held.copy();
                stack.setCount(1);
                return stack;
            } else {
                // 展示框为空，返回展示框本身
                ItemStack stack = new ItemStack(Items.ITEM_FRAME);
                stack.setCount(1);
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    private static ItemStack resolveBlockItem(ServerWorld world, BlockPos pos, Set<Identifier> skipIds) {
        Block block = world.getBlockState(pos).getBlock();
        if (block == Blocks.AIR) return ItemStack.EMPTY;

        if (block instanceof FlowerPotBlock fp) {
            Block plant = fp.getContent();
            if (plant != Blocks.AIR) {
                ItemStack stack = new ItemStack(plant.asItem());
                if (!stack.isEmpty() && stack.getItem() != Items.AIR) {
                    stack.setCount(1);
                    return stack;
                }
            }
            // 空花盆，返回花盆本身
            ItemStack stack = new ItemStack(block.asItem());
            if (!stack.isEmpty() && stack.getItem() != Items.AIR) {
                stack.setCount(1);
                return stack;
            }
            return ItemStack.EMPTY;
        }

        if (skipIds.contains(Registries.BLOCK.getId(block))) {
            return ItemStack.EMPTY;
        }

        ItemStack stack = new ItemStack(block.asItem());
        if (!stack.isEmpty() && stack.getItem() != Items.AIR) {
            stack.setCount(1);
            return stack;
        }
        return ItemStack.EMPTY;
    }
}