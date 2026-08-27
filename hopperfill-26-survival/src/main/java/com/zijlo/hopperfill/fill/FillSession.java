package com.zijlo.hopperfill.fill;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 生存模式逐 tick 渐进填充会话。
 * 木锄第 4 下右键启动，玩家走到漏斗旁自动填入（从背包/潜影盒扣材料），第 5 下右键结束。
 * 仅在服务端主线程访问。
 */
public class FillSession {
    private static final Map<UUID, FillSession> SESSIONS = new HashMap<>();

    private static final double REACH = 5.0;
    private static final int HOPPER_SLOTS = 5;
    private static final int BLOCK_UPDATE_FLAGS = 3;
    private static final int SHULKER_SLOTS = 27;

    private final ServerPlayer player;
    private final ServerLevel world;
    private final List<BlockPos> hoppers;
    private final List<ItemStack> results;
    private final List<ItemStack> template16;
    private final List<ItemStack> template64;
    private final boolean[] done;
    private final boolean[] visited; // 玩家是否曾靠近过该漏斗（用于「走遍全程后自检」）
    private final Set<Item> missingFilters = new LinkedHashSet<>(); // 缺失过滤物的物品
    private int remaining;
    private int completeCount; // 完整填充（含过滤物）的漏斗数

    private FillSession(ServerPlayer player, ServerLevel world, FillValidator.Result r) {
        this.player = player;
        this.world = world;
        this.hoppers = r.hoppers;
        this.results = r.results;
        this.template16 = r.template16;
        this.template64 = r.template64;
        this.done = new boolean[r.hoppers.size()];
        this.visited = new boolean[r.hoppers.size()];
        this.remaining = r.hoppers.size();
        this.completeCount = 0;
    }

    public static boolean isActive(ServerPlayer p) {
        return SESSIONS.containsKey(p.getUUID());
    }

    public static void start(ServerPlayer p, ServerLevel w, FillValidator.Result r) {
        SESSIONS.put(p.getUUID(), new FillSession(p, w, r));
        p.sendSystemMessage(Component.literal("§a[木锄] 生存填充已启动：走到漏斗旁自动填入，再次右键木锄结束"), false);
    }

    /** 玩家主动结束（第 5 下右键） */
    public static void stop(ServerPlayer p) {
        FillSession s = SESSIONS.remove(p.getUUID());
        if (s == null) return;
        report(p, s);
    }

    /** 断线等静默清理（不发消息） */
    public static void cleanup(UUID uuid) {
        SESSIONS.remove(uuid);
    }

    public static void registerTick() {
        ServerTickEvents.END_SERVER_TICK.register(FillSession::tickAll);
    }

    // ---------- 材料统计 / 消耗（public static，供命令式填充复用） ----------

    /** 合并单个漏斗 5 槽中同类物品的需求量 */
    public static Map<Item, Integer> collectNeeds(ItemStack[] slots) {
        Map<Item, Integer> need = new HashMap<>();
        for (ItemStack s : slots) {
            if (s == null || s.isEmpty()) continue;
            need.merge(s.getItem(), s.getCount(), Integer::sum);
        }
        return need;
    }

    /** 统计背包 + 潜影盒中某物品的总数量 */
    public static int countAvailable(ServerPlayer p, Item target) {
        int total = 0;
        for (ItemStack stack : p.getInventory().getNonEquipmentItems()) {
            if (stack.isEmpty()) continue;
            if (stack.getItem() == target) {
                total += stack.getCount();
            }
            ItemContainerContents c = stack.get(DataComponents.CONTAINER);
            if (c != null) {
                for (ItemStack in : c.nonEmptyItemCopyStream().toList()) {
                    if (in.getItem() == target) total += in.getCount();
                }
            }
        }
        return total;
    }

    /** 从背包与潜影盒中扣除指定物品指定数量（先背包，后潜影盒） */
    public static void consume(ServerPlayer p, Item target, int count) {
        int remaining = count;
        for (ItemStack stack : p.getInventory().getNonEquipmentItems()) {
            if (remaining == 0) return;
            if (stack.isEmpty() || stack.getItem() != target) continue;
            int take = Math.min(stack.getCount(), remaining);
            stack.shrink(take);
            remaining -= take;
        }
        if (remaining == 0) return;

        for (ItemStack box : p.getInventory().getNonEquipmentItems()) {
            if (remaining == 0) return;
            if (box.isEmpty()) continue;
            ItemContainerContents c = box.get(DataComponents.CONTAINER);
            if (c == null) continue;

            NonNullList<ItemStack> items = NonNullList.withSize(SHULKER_SLOTS, ItemStack.EMPTY);
            c.copyInto(items);
            boolean changed = false;
            for (int i = 0; i < items.size() && remaining > 0; i++) {
                ItemStack in = items.get(i);
                if (in.isEmpty() || in.getItem() != target) continue;
                int take = Math.min(in.getCount(), remaining);
                in.shrink(take);
                remaining -= take;
                if (in.isEmpty()) items.set(i, ItemStack.EMPTY);
                changed = true;
            }
            if (changed) {
                box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(items));
            }
        }
    }

    // ---------- 会话逐 tick 填充 ----------

    private static void tickAll(MinecraftServer server) {
        if (SESSIONS.isEmpty()) return;
        for (FillSession s : new ArrayList<>(SESSIONS.values())) {
            if (s.player.hasDisconnected() || s.player.isRemoved()) {
                SESSIONS.remove(s.player.getUUID());
                continue;
            }
            if (s.player.level() != s.world) continue; // 玩家不在会话世界
            boolean autoFinish = s.tickOne();
            if (s.remaining <= 0 || autoFinish) {
                SESSIONS.remove(s.player.getUUID());
                report(s.player, s);
            }
        }
    }

    private static void report(ServerPlayer p, FillSession s) {
        int done = s.hoppers.size() - s.remaining;
        int templateOnly = done - s.completeCount;

        StringBuilder sb = new StringBuilder("§a[木锄] 完整填充 " + s.completeCount + " 个漏斗");
        if (templateOnly > 0) {
            sb.append("，§e仅填模板 ").append(templateOnly).append(" 个");
        }
        if (s.remaining > 0) {
            sb.append("，§c未填 ").append(s.remaining).append(" 个");
        }
        if (!s.missingFilters.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (Item it : s.missingFilters) {
                names.add(new ItemStack(it).getDisplayName().getString());
            }
            sb.append("\n§e缺少过滤物：").append(String.join("、", names));
        }
        p.sendSystemMessage(Component.literal(sb.toString()), false);
    }

    /**
     * 每个 tick 填充玩家够得着的一个漏斗；缺料的跳过，继续尝试下一个。
     * 返回 true 表示「玩家已走遍所有未填漏斗，但仍有缺料填不了」，应自动结束会话。
     */
    private boolean tickOne() {
        Vec3 eye = player.getEyePosition();
        boolean allVisited = true;
        boolean madeProgress = false;
        for (int i = 0; i < hoppers.size(); i++) {
            if (done[i]) continue;
            BlockPos pos = hoppers.get(i);
            boolean inReach = eye.distanceTo(Vec3.atCenterOf(pos)) <= REACH;
            if (inReach) visited[i] = true;
            if (!visited[i]) allVisited = false;
            if (!inReach) continue;

            Outcome o = tryFill(i);
            if (o == Outcome.COMPLETE) {
                done[i] = true;
                remaining--;
                completeCount++;
                madeProgress = true;
                break;
            }
            if (o == Outcome.TEMPLATE_ONLY) {
                done[i] = true;
                remaining--;
                madeProgress = true;
                break;
            }
            // FAILED：缺模板材料，不 break，继续尝试下一个够得着的漏斗
        }
        // 自检：本 tick 未成功填充任何漏斗，且所有未填漏斗都已被玩家访问过（走遍全程）→ 自动结束
        return remaining > 0 && allVisited && !madeProgress;
    }

    private enum Outcome {
        COMPLETE,      // 完整填充（含过滤物）或已完整正确
        TEMPLATE_ONLY, // 只填模板（缺过滤物）
        FAILED         // 未填（缺模板材料）
    }

    /**
     * 填充单个漏斗：
     * 1) 完全正确则不重复填；
     * 2) 模板已正确但过滤槽空缺（上次缺过滤物）：只补首位过滤物；
     * 3) 否则完整填充；缺过滤物则退化为「首位不填、模板照填」。
     */
    private Outcome tryFill(int index) {
        BlockPos pos = hoppers.get(index);
        HopperBlockEntity hopper = (HopperBlockEntity) world.getBlockEntity(pos);
        if (hopper == null) return Outcome.COMPLETE;

        ItemStack r = results.get(index);
        ItemStack[] full = HopperFiller.computePlacement(template16, template64, r);
        ItemStack[] noFilter = HopperFiller.computePlacement(template16, template64, r, false);

        // 1) 完全正确
        if (isAlreadyCorrect(hopper, full)) return Outcome.COMPLETE;

        // 2) 模板正确但过滤槽空缺：只补首位过滤物
        if (isAlreadyCorrect(hopper, noFilter)) {
            int filterSlot = findFilterSlot(full, noFilter);
            ItemStack filter = full[filterSlot].copy();
            if (!filter.isEmpty() && countAvailable(player, filter.getItem()) >= 1) {
                consume(player, filter.getItem(), 1);
                hopper.setItem(filterSlot, filter);
                hopper.setChanged();
                BlockState st = world.getBlockState(pos);
                world.sendBlockUpdated(pos, st, st, BLOCK_UPDATE_FLAGS);
                return Outcome.COMPLETE;
            }
            if (!r.isEmpty()) missingFilters.add(r.getItem());
            return Outcome.FAILED; // 仍缺过滤物（模板已填）
        }

        // 3) 完整填充
        if (canAfford(full)) {
            consumeAll(full);
            writeSlots(hopper, pos, full);
            return Outcome.COMPLETE;
        }
        // 缺过滤物但模板够：首位（过滤槽）不填，模板照填
        if (canAfford(noFilter)) {
            consumeAll(noFilter);
            writeSlots(hopper, pos, noFilter);
            if (!r.isEmpty()) missingFilters.add(r.getItem());
            return Outcome.TEMPLATE_ONLY;
        }
        return Outcome.FAILED;
    }

    /** 找出过滤槽位：full 中有物品而 noFilter 中为空的槽 */
    private int findFilterSlot(ItemStack[] full, ItemStack[] noFilter) {
        for (int i = 0; i < HOPPER_SLOTS; i++) {
            if (!full[i].isEmpty() && noFilter[i].isEmpty()) return i;
        }
        return 0;
    }

    private void writeSlots(HopperBlockEntity hopper, BlockPos pos, ItemStack[] slots) {
        hopper.clearContent();
        for (int i = 0; i < HOPPER_SLOTS; i++) {
            hopper.setItem(i, slots[i].copy());
        }
        hopper.setChanged();
        BlockState st = world.getBlockState(pos);
        world.sendBlockUpdated(pos, st, st, BLOCK_UPDATE_FLAGS);
    }

    /** 判断漏斗当前 5 槽是否已与目标布局一致（物品、数量、组件均相同） */
    private boolean isAlreadyCorrect(HopperBlockEntity hopper, ItemStack[] slots) {
        for (int i = 0; i < HOPPER_SLOTS; i++) {
            ItemStack cur = hopper.getItem(i);
            ItemStack tgt = slots[i];
            boolean curEmpty = cur.isEmpty();
            boolean tgtEmpty = tgt.isEmpty();
            if (curEmpty != tgtEmpty) return false;
            if (curEmpty) continue;
            if (cur.getCount() != tgt.getCount()) return false;
            if (!ItemStack.isSameItemSameComponents(cur, tgt)) return false;
        }
        return true;
    }

    private boolean canAfford(ItemStack[] slots) {
        Map<Item, Integer> need = collectNeeds(slots);
        for (Map.Entry<Item, Integer> e : need.entrySet()) {
            if (countAvailable(player, e.getKey()) < e.getValue()) {
                return false;
            }
        }
        return true;
    }

    private void consumeAll(ItemStack[] slots) {
        Map<Item, Integer> need = collectNeeds(slots);
        for (Map.Entry<Item, Integer> e : need.entrySet()) {
            consume(player, e.getKey(), e.getValue());
        }
    }
}
