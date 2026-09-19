package com.zijlo.hopperfill.fill;

import com.zijlo.hopperfill.data.TemplateStorage;
import com.zijlo.hopperfill.region.RegionScanner;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.HopperBlock;
import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 漏斗填充的完整实现，合并了原先的 LineBresenham3D / FillValidator / HopperFiller / FillSession。
 * 仅在服务端主线程访问。
 *
 * <p>分四块：
 * <ol>
 *   <li>漏斗线几何：{@link #linePositions} / {@link #checkContinuity}</li>
 *   <li>填充前校验：{@link #validate}</li>
 *   <li>一次性填充（创造直写 / 生存先扣料）：{@link #fill}</li>
 *   <li>生存逐 tick 渐进填充会话：{@link #startSession} 等</li>
 * </ol>
 */
public final class FillService {
    private FillService() {}

    /** 漏斗槽位总数 */
    private static final int HOPPER_SLOTS = 5;
    /** 兜底过滤槽位 */
    private static final int FILTER_SLOT = 0;
    /** 方块更新标志：通知邻居 + 客户端 */
    private static final int BLOCK_UPDATE_FLAGS = 3;
    /** 潜影盒槽位数 */
    private static final int SHULKER_SLOTS = 27;
    /** 生存会话的自动填充半径 */
    private static final double REACH = 5.0;

    // ==================================================================
    // 1. 漏斗线几何（原 LineBresenham3D）
    // ==================================================================

    /** 3D 布雷森汉姆：列出 a 到 b 之间经过的每个方块坐标（含两端） */
    public static List<BlockPos> linePositions(BlockPos a, BlockPos b) {
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
     * 检查漏斗线是否连续（切比雪夫距离为 1）。
     *
     * @return 若连续返回 {@code Optional.empty()}，否则返回 {@code Optional.of(错误描述)}
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

    // ==================================================================
    // 2. 填充前校验（原 FillValidator）
    // ==================================================================

    /** 校验结果：成功时携带漏斗线坐标、每片扫描结果与两套模板 */
    public static final class ValidationResult {
        public final boolean success;
        public final String errorMessage;
        public final List<BlockPos> hoppers;
        public final List<ItemStack> results;
        public final List<ItemStack> template16;
        public final List<ItemStack> template64;

        private ValidationResult(boolean success, String errorMessage,
                                 List<BlockPos> hoppers, List<ItemStack> results,
                                 List<ItemStack> template16, List<ItemStack> template64) {
            this.success = success;
            this.errorMessage = errorMessage;
            this.hoppers = hoppers;
            this.results = results;
            this.template16 = template16;
            this.template64 = template64;
        }

        static ValidationResult fail(String message) {
            return new ValidationResult(false, message, null, null, null, null);
        }

        static ValidationResult success(List<BlockPos> hoppers, List<ItemStack> results,
                                        List<ItemStack> template16, List<ItemStack> template64) {
            return new ValidationResult(true, null, hoppers, results, template16, template64);
        }
    }

    /** 校验填充条件：模板、跳过方块、漏斗线连续性、切片数匹配、一片多物品、不可堆叠、堆叠模板 */
    public static ValidationResult validate(ServerPlayerEntity player, ServerWorld world,
                                            BlockPos from, BlockPos to,
                                            BlockPos lineStart, BlockPos lineEnd) {
        if (!TemplateStorage.hasTemplate16(player) && !TemplateStorage.hasTemplate64(player)) {
            return ValidationResult.fail("§c请先使用 /hf set template 16/64 设置模板");
        }

        Set<Identifier> skipIds = TemplateStorage.getBlockIds(player);
        if (skipIds.isEmpty()) {
            return ValidationResult.fail("§c请先使用 /hf set gui 添加要跳过的方块");
        }

        List<BlockPos> hoppers = linePositions(lineStart, lineEnd);

        Optional<String> continuityError = checkContinuity(hoppers);
        if (continuityError.isPresent()) {
            return ValidationResult.fail("§c错误：漏斗线不连续，" + continuityError.get());
        }

        for (BlockPos hopperPos : hoppers) {
            if (!(world.getBlockState(hopperPos).getBlock() instanceof HopperBlock)) {
                return ValidationResult.fail("§c错误：" + hopperPos.toShortString() + " 不是漏斗");
            }
        }

        List<RegionScanner.Slice> slices = RegionScanner.scanSlices(world, from, to, skipIds, lineStart, lineEnd);

        if (slices.size() != hoppers.size()) {
            int diff = Math.abs(slices.size() - hoppers.size());
            return ValidationResult.fail("§c错误：区域切片数 " + slices.size() + " 与漏斗数 " + hoppers.size()
                    + " 不匹配（差值 " + diff + "），请检查圈选区域");
        }

        // 一个漏斗只能放一种物品：同一切片出现多种物品时结果不确定，直接拦下并提示补救方式
        String ambiguity = RegionScanner.describeAmbiguities(slices);
        if (ambiguity != null) {
            return ValidationResult.fail("§c" + ambiguity);
        }

        List<ItemStack> results = new ArrayList<>(slices.size());
        for (RegionScanner.Slice slice : slices) {
            results.add(slice.picked());
        }

        for (ItemStack r : results) {
            if (r.isEmpty()) continue;
            if (r.getMaxCount() == 1) {
                return ValidationResult.fail("§c错误：扫描到不可堆叠物品 \"" + r.getName().getString() + "\"");
            }
        }

        List<ItemStack> t16 = TemplateStorage.getTemplate16(player);
        List<ItemStack> t64 = TemplateStorage.getTemplate64(player);
        if (t16 == null) t16 = Collections.emptyList();
        if (t64 == null) t64 = Collections.emptyList();

        for (ItemStack r : results) {
            if (r.isEmpty()) continue;
            int mc = r.getMaxCount();
            List<ItemStack> needed = (mc >= 64) ? t64 : t16;
            if (!TemplateStorage.hasAnyItem(needed)) {
                String typeName = (mc >= 64) ? "64" : "16";
                return ValidationResult.fail("§c错误：扫描到 " + typeName + " 堆叠物品 \"" + r.getName().getString()
                        + "\"，但未设置 " + typeName + " 堆叠模板。请使用 /hf set template " + typeName);
            }
        }

        return ValidationResult.success(hoppers, results, t16, t64);
    }

    // ==================================================================
    // 3. 一次性填充（原 HopperFiller）
    // ==================================================================

    /** 一次性填充结果 */
    public static final class FillOutcome {
        public final boolean success;
        public final String message;
        public final int filteredCount;

        FillOutcome(boolean success, String message, int filteredCount) {
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
    public static FillOutcome fill(ServerWorld world, List<BlockPos> hoppers, List<ItemStack> results,
                                   List<ItemStack> template16, List<ItemStack> template64,
                                   ServerPlayerEntity player) {
        List<ItemStack[]> placements = new ArrayList<>(hoppers.size());
        int filteredCount = 0;
        for (ItemStack r : results) {
            placements.add(computePlacement(template16, template64, r));
            if (!r.isEmpty()) filteredCount++;
        }

        if (!player.interactionManager.isCreative()) {
            // 生存：统计总需求 -> 检查 -> 扣料
            Map<Item, Integer> need = new HashMap<>();
            for (ItemStack[] slots : placements) {
                for (ItemStack s : slots) {
                    if (s == null || s.isEmpty()) continue;
                    need.merge(s.getItem(), s.getCount(), Integer::sum);
                }
            }
            for (Map.Entry<Item, Integer> e : need.entrySet()) {
                int have = countAvailable(player, e.getKey());
                if (have < e.getValue()) {
                    return new FillOutcome(false, "§c材料不足：§e" + new ItemStack(e.getKey()).getName().getString()
                            + " §c需 " + e.getValue() + " 个（现有 " + have + " 个）", filteredCount);
                }
            }
            for (Map.Entry<Item, Integer> e : need.entrySet()) {
                consume(player, e.getKey(), e.getValue());
            }
        }

        writeAll(world, hoppers, placements);
        return new FillOutcome(true, null, filteredCount);
    }

    /** 计算单个漏斗的最终 5 槽布局（含过滤物） */
    public static ItemStack[] computePlacement(List<ItemStack> template16, List<ItemStack> template64,
                                               ItemStack r) {
        return computePlacement(template16, template64, r, true);
    }

    /**
     * 计算单个漏斗的最终 5 槽布局。
     *
     * @param includeFilter false 时过滤槽（首位）留空，仅填模板
     */
    public static ItemStack[] computePlacement(List<ItemStack> template16, List<ItemStack> template64,
                                               ItemStack r, boolean includeFilter) {
        List<ItemStack> template;
        if (r.isEmpty()) {
            template = TemplateStorage.hasAnyItem(template64) ? template64 : template16;
        } else {
            template = r.getMaxCount() >= 64 ? template64 : template16;
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

        ItemStack[] slots = new ItemStack[HOPPER_SLOTS];
        boolean filterPlaced = filterItem.isEmpty();
        for (int slot = 0; slot < HOPPER_SLOTS; slot++) {
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
            for (int s = 0; s < HOPPER_SLOTS; s++) {
                inv.setStack(s, slots[s].copy());
            }
            hopper.markDirty();
            BlockState st = world.getBlockState(hoppers.get(i));
            world.updateListeners(hoppers.get(i), st, st, BLOCK_UPDATE_FLAGS);
        }
    }

    // ==================================================================
    // 4. 生存逐 tick 渐进填充会话（原 FillSession）
    // ==================================================================

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    /**
     * 生存模式逐 tick 渐进填充会话：木锄第 4 下右键启动，玩家走到漏斗旁自动填入，
     * 走遍全程仍缺料则自动结束，也可再右键木锄手动结束。
     */
    private static final class Session {
        private final ServerPlayerEntity player;
        private final ServerWorld world;
        private final List<BlockPos> hoppers;
        private final List<ItemStack> results;
        private final List<ItemStack> template16;
        private final List<ItemStack> template64;
        private final boolean[] done;
        private final boolean[] visited;
        private final Set<Item> missingFilters = new LinkedHashSet<>();
        private int remaining;
        private int completeCount;

        private Session(ServerPlayerEntity player, ServerWorld world, ValidationResult r) {
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

        /**
         * 每个 tick 填充玩家够得着的一个漏斗；缺料的跳过，继续尝试下一个。
         * 返回 true 表示「玩家已走遍所有未填漏斗，但仍有缺料填不了」，应自动结束会话。
         */
        private boolean tickOne() {
            Vec3d eye = player.getEyePos();
            boolean allVisited = true;
            boolean madeProgress = false;
            for (int i = 0; i < hoppers.size(); i++) {
                if (done[i]) continue;
                BlockPos pos = hoppers.get(i);
                boolean inReach = eye.distanceTo(Vec3d.ofCenter(pos)) <= REACH;
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
            ItemStack[] full = computePlacement(template16, template64, r);
            ItemStack[] noFilter = computePlacement(template16, template64, r, false);

            // 1) 完全正确
            if (isAlreadyCorrect(hopper, full)) return Outcome.COMPLETE;

            // 2) 模板正确但过滤槽空缺：只补首位过滤物
            if (isAlreadyCorrect(hopper, noFilter)) {
                int filterSlot = findFilterSlot(full, noFilter);
                ItemStack filter = full[filterSlot].copy();
                if (!filter.isEmpty() && countAvailable(player, filter.getItem()) >= 1) {
                    consume(player, filter.getItem(), 1);
                    hopper.setStack(filterSlot, filter);
                    hopper.markDirty();
                    BlockState st = world.getBlockState(pos);
                    world.updateListeners(pos, st, st, BLOCK_UPDATE_FLAGS);
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
            Inventory inv = hopper;
            inv.clear();
            for (int i = 0; i < HOPPER_SLOTS; i++) {
                inv.setStack(i, slots[i].copy());
            }
            hopper.markDirty();
            BlockState st = world.getBlockState(pos);
            world.updateListeners(pos, st, st, BLOCK_UPDATE_FLAGS);
        }

        /** 判断漏斗当前 5 槽是否已与目标布局一致（物品、数量、组件均相同） */
        private boolean isAlreadyCorrect(HopperBlockEntity hopper, ItemStack[] slots) {
            for (int i = 0; i < HOPPER_SLOTS; i++) {
                ItemStack cur = hopper.getStack(i);
                ItemStack tgt = slots[i];
                boolean curEmpty = cur.isEmpty();
                boolean tgtEmpty = tgt.isEmpty();
                if (curEmpty != tgtEmpty) return false;
                if (curEmpty) continue;
                if (cur.getCount() != tgt.getCount()) return false;
                if (!ItemStack.areItemsAndComponentsEqual(cur, tgt)) return false;
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

    private enum Outcome {
        COMPLETE,      // 完整填充（含过滤物）或已完整正确
        TEMPLATE_ONLY, // 只填模板（缺过滤物）
        FAILED         // 未填（缺模板材料）
    }

    public static boolean isSessionActive(ServerPlayerEntity p) {
        return SESSIONS.containsKey(p.getUuid());
    }

    /** 启动生存逐 tick 填充会话 */
    public static void startSession(ServerPlayerEntity p, ServerWorld w, ValidationResult r) {
        SESSIONS.put(p.getUuid(), new Session(p, w, r));
        p.sendMessage(Text.literal("§a[木锄] 生存填充已启动：走到漏斗旁自动填入，再次右键木锄结束"), false);
    }

    /** 玩家主动结束（第 5 下右键） */
    public static void stopSession(ServerPlayerEntity p) {
        Session s = SESSIONS.remove(p.getUuid());
        if (s == null) return;
        report(p, s);
    }

    /** 断线等静默清理（不发消息） */
    public static void clearSession(UUID uuid) {
        SESSIONS.remove(uuid);
    }

    public static void registerTick() {
        ServerTickEvents.END_SERVER_TICK.register(FillService::tickAll);
    }

    private static void tickAll(MinecraftServer server) {
        if (SESSIONS.isEmpty()) return;
        for (Session s : new ArrayList<>(SESSIONS.values())) {
            if (s.player.isDisconnected() || s.player.isRemoved()) {
                SESSIONS.remove(s.player.getUuid());
                continue;
            }
            // 注：跨 1.21.x 兼容，不显式比对玩家所在世界（getWorld/getEntityWorld 在不同小版本间改名）。
            // tickOne 以「玩家够得着的漏斗」为准，跨维度时无漏斗可达、自然不推进，回到原世界即可继续。
            boolean autoFinish = s.tickOne();
            if (s.remaining <= 0 || autoFinish) {
                SESSIONS.remove(s.player.getUuid());
                report(s.player, s);
            }
        }
    }

    private static void report(ServerPlayerEntity p, Session s) {
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
                names.add(new ItemStack(it).getName().getString());
            }
            sb.append("\n§e缺少过滤物：").append(String.join("、", names));
        }
        p.sendMessage(Text.literal(sb.toString()), false);
    }

    // ==================================================================
    // 5. 材料统计 / 消耗（命令式填充与逐 tick 会话共用）
    // ==================================================================

    /** 合并单个漏斗 5 槽中同类物品的需求量 */
    public static Map<Item, Integer> collectNeeds(ItemStack[] slots) {
        Map<Item, Integer> need = new HashMap<>();
        for (ItemStack s : slots) {
            if (s == null || s.isEmpty()) continue;
            need.merge(s.getItem(), s.getCount(), Integer::sum);
        }
        return need;
    }

    /** 跨 1.21.x 兼容地获取玩家主物品栏（36 格）。1.21.0 用 main 字段、1.21.11 用 getMainStacks()，
     *  此处统一走稳定的 Inventory.getStack(int) + MAIN_SIZE 常量。 */
    private static List<ItemStack> mainStacks(ServerPlayerEntity p) {
        PlayerInventory inv = p.getInventory();
        List<ItemStack> out = new ArrayList<>(PlayerInventory.MAIN_SIZE);
        for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
            out.add(inv.getStack(i));
        }
        return out;
    }

    /** 统计背包 + 潜影盒中某物品的总数量 */
    public static int countAvailable(ServerPlayerEntity p, Item target) {
        int total = 0;
        for (ItemStack stack : mainStacks(p)) {
            if (stack.isEmpty()) continue;
            if (stack.isOf(target)) {
                total += stack.getCount();
            }
            ContainerComponent c = stack.get(DataComponentTypes.CONTAINER);
            if (c != null) {
                for (ItemStack in : c.iterateNonEmpty()) {
                    if (in.isOf(target)) total += in.getCount();
                }
            }
        }
        return total;
    }

    /** 从背包与潜影盒中扣除指定物品指定数量（先背包，后潜影盒） */
    public static void consume(ServerPlayerEntity p, Item target, int count) {
        int remaining = count;
        for (ItemStack stack : mainStacks(p)) {
            if (remaining == 0) return;
            if (stack.isEmpty() || !stack.isOf(target)) continue;
            int take = Math.min(stack.getCount(), remaining);
            stack.decrement(take);
            remaining -= take;
        }
        if (remaining == 0) return;

        for (ItemStack box : mainStacks(p)) {
            if (remaining == 0) return;
            if (box.isEmpty()) continue;
            ContainerComponent c = box.get(DataComponentTypes.CONTAINER);
            if (c == null) continue;

            DefaultedList<ItemStack> items = DefaultedList.ofSize(SHULKER_SLOTS, ItemStack.EMPTY);
            c.copyTo(items);
            boolean changed = false;
            for (int i = 0; i < items.size() && remaining > 0; i++) {
                ItemStack in = items.get(i);
                if (in.isEmpty() || !in.isOf(target)) continue;
                int take = Math.min(in.getCount(), remaining);
                in.decrement(take);
                remaining -= take;
                if (in.isEmpty()) items.set(i, ItemStack.EMPTY);
                changed = true;
            }
            if (changed) {
                box.set(DataComponentTypes.CONTAINER, ContainerComponent.fromStacks(items));
            }
        }
    }
}
