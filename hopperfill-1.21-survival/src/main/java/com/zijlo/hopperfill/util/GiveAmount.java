package com.zijlo.hopperfill.util;

import java.util.Locale;

/**
 * give 命令"每个物品一组"的数量策略（需求①）。
 * 原逻辑固定给满堆叠（64堆叠=64，16堆叠=16），现改为可选：
 *   - count(n)   : 精确数量，按物品最大堆叠钳制（64堆叠 1-64，16堆叠 1-16）
 *   - all        : 满堆叠（64堆叠=64，16堆叠=16）
 *   - all-1      : 满堆叠减一（64堆叠=63，16堆叠=15）
 * 即"all"的两个可选项为 63 / 64（16 堆叠对应 15 / 16）。
 */
public final class GiveAmount {
    public enum Mode { COUNT, ALL, ALL_MINUS_ONE }

    private final Mode mode;
    private final int count;

    private GiveAmount(Mode mode, int count) {
        this.mode = mode;
        this.count = count;
    }

    /** 精确数量（自动按最大堆叠钳制） */
    public static GiveAmount count(int n) {
        return new GiveAmount(Mode.COUNT, Math.max(1, n));
    }

    /** 满堆叠：all（64堆叠=64，16堆叠=16） */
    public static GiveAmount all() {
        return new GiveAmount(Mode.ALL, 0);
    }

    /** 满堆叠减一：all-1（64堆叠=63，16堆叠=15） */
    public static GiveAmount allMinusOne() {
        return new GiveAmount(Mode.ALL_MINUS_ONE, 0);
    }

    /** 默认策略：满堆叠（保持旧版行为） */
    public static GiveAmount defaultAmount() {
        return all();
    }

    /**
     * 解析命令参数字符串（可为 null）。
     * 支持整数、"all"、"all-1"；null / 空白 / 非法输入回退默认（满堆叠）。
     */
    public static GiveAmount parse(String raw) {
        if (raw == null || raw.isBlank()) return defaultAmount();
        String s = raw.trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "all" -> all();
            case "all-1" -> allMinusOne();
            default -> {
                int n;
                try {
                    n = Integer.parseInt(s);
                } catch (NumberFormatException e) {
                    yield defaultAmount();
                }
                yield count(n);
            }
        };
    }

    /** 依据物品最大堆叠数计算最终给予数量；不可堆叠物品恒为 1。
     *  精确数量（COUNT）时：64 堆叠按给定数量，16 堆叠按「64 堆叠比例」换算
     *  （即 16堆叠数量 = 数量 * maxStack / 64，例如 63 → 64堆叠给 63、16堆叠给 15）。 */
    public int resolve(int maxStack) {
        if (maxStack <= 1) return 1;
        return switch (mode) {
            case ALL -> maxStack;
            case ALL_MINUS_ONE -> Math.max(1, maxStack - 1);
            case COUNT -> {
                if (maxStack >= 64) {
                    yield Math.max(1, Math.min(count, 64));
                }
                yield Math.max(1, Math.min(maxStack, count * maxStack / 64));
            }
        };
    }
}