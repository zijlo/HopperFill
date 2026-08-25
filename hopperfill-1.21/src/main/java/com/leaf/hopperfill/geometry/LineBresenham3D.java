package com.leaf.hopperfill.geometry;

import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class LineBresenham3D {

    public static List<BlockPos> getPositions(BlockPos a, BlockPos b) {
        List<BlockPos> list = new ArrayList<>();
        if (a.equals(b)) {
            list.add(a);
            return list;
        }

        int x1 = a.getX(), y1 = a.getY(), z1 = a.getZ();
        int x2 = b.getX(), y2 = b.getY(), z2 = b.getZ();

        int dx = Math.abs(x2 - x1);
        int dy = Math.abs(y2 - y1);
        int dz = Math.abs(z2 - z1);

        int xs = Integer.compare(x2, x1);
        int ys = Integer.compare(y2, y1);
        int zs = Integer.compare(z2, z1);

        if (dx >= dy && dx >= dz) {
            int p1 = 2 * dy - dx;
            int p2 = 2 * dz - dx;
            int y = y1, z = z1;
            for (int x = x1; x != x2 + xs; x += xs) {
                list.add(new BlockPos(x, y, z));
                if (p1 >= 0) {
                    y += ys;
                    p1 -= 2 * dx;
                }
                if (p2 >= 0) {
                    z += zs;
                    p2 -= 2 * dx;
                }
                p1 += 2 * dy;
                p2 += 2 * dz;
            }
        } else if (dy >= dx && dy >= dz) {
            int p1 = 2 * dx - dy;
            int p2 = 2 * dz - dy;
            int x = x1, z = z1;
            for (int y = y1; y != y2 + ys; y += ys) {
                list.add(new BlockPos(x, y, z));
                if (p1 >= 0) {
                    x += xs;
                    p1 -= 2 * dy;
                }
                if (p2 >= 0) {
                    z += zs;
                    p2 -= 2 * dy;
                }
                p1 += 2 * dx;
                p2 += 2 * dz;
            }
        } else {
            int p1 = 2 * dx - dz;
            int p2 = 2 * dy - dz;
            int x = x1, y = y1;
            for (int z = z1; z != z2 + zs; z += zs) {
                list.add(new BlockPos(x, y, z));
                if (p1 >= 0) {
                    x += xs;
                    p1 -= 2 * dz;
                }
                if (p2 >= 0) {
                    y += ys;
                    p2 -= 2 * dz;
                }
                p1 += 2 * dx;
                p2 += 2 * dy;
            }
        }
        return list;
    }

    /**
     * 检查漏斗线是否连续（切比雪夫距离为1）。
     * @return 若连续返回 Optional.empty()，否则返回 Optional.of(错误描述)
     */
    public static Optional<String> checkContinuity(List<BlockPos> positions) {
        for (int i = 1; i < positions.size(); i++) {
            BlockPos prev = positions.get(i - 1);
            BlockPos curr = positions.get(i);
            int distX = Math.abs(prev.getX() - curr.getX());
            int distY = Math.abs(prev.getY() - curr.getY());
            int distZ = Math.abs(prev.getZ() - curr.getZ());
            int chebyshevDist = Math.max(distX, Math.max(distY, distZ));
            if (chebyshevDist != 1) {
                return Optional.of(prev.toShortString() + " 与 " + curr.toShortString() + " 不相邻");
            }
        }
        return Optional.empty();
    }
}