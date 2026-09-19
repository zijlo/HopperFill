package com.zijlo.hopperfill.region;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.FlowerPotBlock;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 区域扫描器。
 *
 * <p>扫描沿「主轴」把选区切成若干层（切片），每个切片对应漏斗线上的一个漏斗。
 * 取物规则：优先展示框（含框内物品），否则取该切片中自下而上的第一个非跳过方块。
 *
 * <p>由于一个漏斗只能放一种物品，若同一切片内出现多种物品，填充结果就是不确定的。
 * 因此扫描结果同时记录每个切片内的<b>全部</b>候选物品，供上层做「单片多物品」报错。
 */
public final class RegionScanner {
    private RegionScanner() {}

    /** 主轴：选区在哪个方向上被切成切片 */
    private enum Axis { X, Y, Z }

    /** 选区包围盒（已归一化） */
    private record Bounds(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        static Bounds of(BlockPos a, BlockPos b) {
            return new Bounds(
                    Math.min(a.getX(), b.getX()), Math.max(a.getX(), b.getX()),
                    Math.min(a.getY(), b.getY()), Math.max(a.getY(), b.getY()),
                    Math.min(a.getZ(), b.getZ()), Math.max(a.getZ(), b.getZ()));
        }
    }

    /**
     * 单个切片的扫描结果。
     *
     * @param picked        按取物规则选中的物品（空切片为 {@link ItemStack#EMPTY}）
     * @param distinctItems 该切片内出现的所有互不相同的物品（按发现顺序，已去重）
     * @param sourcePos     选中物品来自的<b>方块</b>坐标；若来自展示框或该片为空则为 {@code null}
     */
    public record Slice(ItemStack picked, List<Item> distinctItems, BlockPos sourcePos) {
        /** 该切片内是否出现多种物品 */
        public boolean isAmbiguous() {
            return distinctItems.size() > 1;
        }
    }

    /** 兼容旧调用：只取每个切片的「选中物品」 */
    public static List<ItemStack> scan(ServerWorld world, BlockPos from, BlockPos to,
                                       Set<Identifier> skipIds, BlockPos axisStart, BlockPos axisEnd) {
        List<Slice> slices = scanSlices(world, from, to, skipIds, axisStart, axisEnd);
        List<ItemStack> results = new ArrayList<>(slices.size());
        for (Slice s : slices) {
            results.add(s.picked());
        }
        return results;
    }

    /** 扫描整个选区，返回每个切片的完整信息（含「单片多物品」检测所需的候选集合） */
    public static List<Slice> scanSlices(ServerWorld world, BlockPos from, BlockPos to,
                                         Set<Identifier> skipIds, BlockPos axisStart, BlockPos axisEnd) {
        Bounds bounds = Bounds.of(from, to);
        Axis axis = dominantAxis(axisStart, axisEnd);
        int base = switch (axis) {
            case X -> axisStart.getX();
            case Y -> axisStart.getY();
            case Z -> axisStart.getZ();
        };
        int delta = switch (axis) {
            case X -> axisEnd.getX() - base;
            case Y -> axisEnd.getY() - base;
            case Z -> axisEnd.getZ() - base;
        };

        int steps = Math.max(Math.abs(axisEnd.getX() - axisStart.getX()),
                Math.max(Math.abs(axisEnd.getY() - axisStart.getY()),
                        Math.abs(axisEnd.getZ() - axisStart.getZ()))) + 1;

        List<Slice> slices = new ArrayList<>(steps);
        for (int i = 0; i < steps; i++) {
            int coord = base + (delta >= 0 ? i : -i);
            slices.add(scanSlice(world, bounds, axis, coord, skipIds));
        }
        return slices;
    }

    /**
     * 生成「一片多物品」的错误描述；若无冲突返回 {@code null}。
     * 文案已包含解决办法（加入跳过方块后重试），调用方只需补前缀与前缀色。
     */
    public static String describeAmbiguities(List<Slice> slices) {
        List<String> details = new ArrayList<>();
        int total = 0;
        for (int i = 0; i < slices.size(); i++) {
            Slice slice = slices.get(i);
            if (!slice.isAmbiguous()) continue;
            total++;
            if (details.size() >= MAX_REPORTED_SLICES) continue;
            List<String> names = new ArrayList<>(slice.distinctItems().size());
            for (Item item : slice.distinctItems()) {
                names.add(new ItemStack(item).getName().getString());
            }
            details.add("第 " + (i + 1) + " 片(" + String.join("、", names) + ")");
        }
        if (total == 0) return null;

        StringBuilder sb = new StringBuilder();
        sb.append("检测到 ").append(total).append(" 处「一片多物品」：");
        sb.append(String.join("；", details));
        if (total > details.size()) sb.append(" …… 等");
        sb.append("\n§7请把这些物品加入跳过方块（§f/hf set gui§7）后再重试");
        return sb.toString();
    }

    /** 错误提示中最多列出的冲突切片数 */
    private static final int MAX_REPORTED_SLICES = 5;

    // ---------- 内部实现 ----------

    /** 判断主轴，与旧版「谁最大谁优先，同长时 X > Z > Y」的顺序保持一致 */
    private static Axis dominantAxis(BlockPos start, BlockPos end) {
        int absDx = Math.abs(end.getX() - start.getX());
        int absDy = Math.abs(end.getY() - start.getY());
        int absDz = Math.abs(end.getZ() - start.getZ());
        if (absDx >= absDz && absDx >= absDy) return Axis.X;
        if (absDz >= absDy) return Axis.Z;
        return Axis.Y;
    }

    private static Slice scanSlice(ServerWorld world, Bounds b, Axis axis, int coord, Set<Identifier> skipIds) {
        ItemStack picked = ItemStack.EMPTY;
        BlockPos sourcePos = null;
        Map<Item, ItemStack> distinct = new LinkedHashMap<>();

        // 第一遍：展示框（与旧版一致，框内物品优先）
        for (BlockPos pos : slicePositions(axis, coord, b, true)) {
            ItemStack frame = detectItemFrame(world, pos);
            if (frame.isEmpty()) continue;
            if (picked.isEmpty()) picked = frame;
            distinct.putIfAbsent(frame.getItem(), frame);
        }
        // 第二遍：方块（自下而上取第一个非跳过方块）
        for (BlockPos pos : slicePositions(axis, coord, b, false)) {
            ItemStack block = resolveBlockItem(world, pos, skipIds);
            if (block.isEmpty()) continue;
            if (picked.isEmpty()) {
                picked = block;
                sourcePos = pos;
            }
            distinct.putIfAbsent(block.getItem(), block);
        }
        return new Slice(picked, new ArrayList<>(distinct.keySet()), sourcePos);
    }

    /**
     * 判断选区里「扫描到的每个切片都是容器」——即每个非空切片选中的都是带物品栏的容器方块
     * （箱子 / 木桶 / 漏斗 / 潜影盒 / 熔炉 / 发射器 …）。用于决定是否提供「清空容器」入口。
     * 展示框取物、以及全部为空的选区都返回 {@code false}。
     */
    public static boolean isAllContainers(ServerWorld world, List<Slice> slices) {
        boolean any = false;
        for (Slice slice : slices) {
            if (slice.picked().isEmpty()) continue;
            any = true;
            if (slice.sourcePos() == null) return false;
            if (!(world.getBlockEntity(slice.sourcePos()) instanceof Inventory)) return false;
        }
        return any;
    }

    /**
     * 生成一个切片内需要访问的坐标序列。
     * {@code framePass=true} 为展示框遍历顺序，{@code false} 为方块遍历顺序；两者与旧版逐字对齐，
     * 以保证取物结果与原实现完全一致。
     */
    private static List<BlockPos> slicePositions(Axis axis, int coord, Bounds b, boolean framePass) {
        List<BlockPos> positions = new ArrayList<>();
        switch (axis) {
            case X -> {
                if (framePass) {
                    for (int y = b.minY(); y <= b.maxY(); y++)
                        for (int z = b.minZ(); z <= b.maxZ(); z++) positions.add(new BlockPos(coord, y, z));
                } else {
                    for (int y = b.maxY(); y >= b.minY(); y--)
                        for (int z = b.minZ(); z <= b.maxZ(); z++) positions.add(new BlockPos(coord, y, z));
                }
            }
            case Z -> {
                if (framePass) {
                    for (int x = b.minX(); x <= b.maxX(); x++)
                        for (int y = b.minY(); y <= b.maxY(); y++) positions.add(new BlockPos(x, y, coord));
                } else {
                    for (int y = b.maxY(); y >= b.minY(); y--)
                        for (int x = b.minX(); x <= b.maxX(); x++) positions.add(new BlockPos(x, y, coord));
                }
            }
            case Y -> {
                if (framePass) {
                    for (int x = b.minX(); x <= b.maxX(); x++)
                        for (int z = b.minZ(); z <= b.maxZ(); z++) positions.add(new BlockPos(x, coord, z));
                } else {
                    for (int z = b.minZ(); z <= b.maxZ(); z++)
                        for (int x = b.minX(); x <= b.maxX(); x++) positions.add(new BlockPos(x, coord, z));
                }
            }
        }
        return positions;
    }

    /** 读取挂在指定方块上的展示框；空框返回展示框本身 */
    private static ItemStack detectItemFrame(ServerWorld world, BlockPos pos) {
        List<ItemFrameEntity> frames = world.getEntitiesByClass(ItemFrameEntity.class,
                new Box(pos).expand(0.1),
                f -> f.getAttachedBlockPos().equals(pos));
        if (frames.isEmpty()) return ItemStack.EMPTY;

        ItemStack held = frames.get(0).getHeldItemStack();
        if (!held.isEmpty()) {
            return single(held);
        }
        return single(new ItemStack(Items.ITEM_FRAME));
    }

    /** 把方块解析成它对应的物品（花盆特殊处理：优先取盆内植物） */
    private static ItemStack resolveBlockItem(ServerWorld world, BlockPos pos, Set<Identifier> skipIds) {
        Block block = world.getBlockState(pos).getBlock();
        if (block == Blocks.AIR) return ItemStack.EMPTY;

        if (block instanceof FlowerPotBlock fp) {
            Block plant = fp.getContent();
            if (plant != Blocks.AIR) {
                ItemStack stack = new ItemStack(plant.asItem());
                if (!stack.isEmpty() && stack.getItem() != Items.AIR) {
                    return single(stack);
                }
            }
            // 空花盆，返回花盆本身
            ItemStack stack = new ItemStack(block.asItem());
            return (stack.isEmpty() || stack.getItem() == Items.AIR) ? ItemStack.EMPTY : single(stack);
        }

        if (skipIds.contains(Registries.BLOCK.getId(block))) {
            return ItemStack.EMPTY;
        }

        ItemStack stack = new ItemStack(block.asItem());
        return (stack.isEmpty() || stack.getItem() == Items.AIR) ? ItemStack.EMPTY : single(stack);
    }

    /** 扫描结果的物品一律按 1 个计（数量对填充无意义） */
    private static ItemStack single(ItemStack stack) {
        ItemStack copy = stack.copy();
        copy.setCount(1);
        return copy;
    }
}
