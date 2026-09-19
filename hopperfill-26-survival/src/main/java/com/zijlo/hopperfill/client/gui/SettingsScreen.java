package com.zijlo.hopperfill.client.gui;

import com.zijlo.hopperfill.network.SettingsNetwork;
import com.zijlo.hopperfill.util.ItemFilter;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 统一设置界面（26.x / Mojang 非混淆命名）。
 *
 * 一个 GUI 同时承担「添加 / 查看 / 删除」三类操作，两个 tab 用顶部按钮切换，
 * 二者共享同一个搜索框。每个 tab 内自上而下分为两区：
 *   Tab[0] = 黑名单（物品）：
 *       搜索框（两个 tab 共用一个）
 *       [可添加候选区] —— 遍历 BuiltInRegistries.ITEM，按搜索词过滤；未加入的条目点击 = 添加
 *       [已添加区]     —— 当前黑名单条目，点击 = 删除（查看与删除合一）
 *   Tab[1] = 跳过方块（方块）：
 *       结构同上，遍历 BuiltInRegistries.BLOCK，操作 skip block 集合
 *
 * 搜索（两个 tab 共用一个搜索框，切换 tab 不清空搜索词，仅重算列表）同时匹配两个维度：
 *   1) ID path（如 minecraft:diamond -> "diamond")
 *   2) 客户端显示名（ItemStack.getDisplayName().getString()，已按客户端语言翻译）
 * 因此中文搜索直接比对显示名，无需语言文件反查。
 *
 * 数据流（点击即增量同步）：
 *   点候选区条目 → 发 C2S UpdateBlacklistPayload / UpdateSkipBlockPayload(add=true)
 *   点已添加区条目 → 发对应 C2S 包(add=false)
 *   → 服务端 TemplateStorage 增删 → 后续 /hf give 自动读取并剔除，填充自动跳过。
 */
public class SettingsScreen extends Screen {
    private enum ContentTab { BLACKLIST, BOX, SKIP_BLOCK }
    private enum ViewMode { ADDABLE, ADDED }

    private enum StackFilter {
        ALL("全部"), STACKABLE("可堆叠"), STACK_64("64堆叠"), STACK_16("16堆叠"), NON_STACKABLE("不可堆叠");
        final String label;
        StackFilter(String label) { this.label = label; }
    }

    private record Entry(String id, String name, ItemStack icon) {}

    private static final int CELL = 18;
    private static final int GRID_TOP = 72;
    private static final int DROPDOWN_ROW = 18;

    private ContentTab contentTab = ContentTab.BLACKLIST;
    private ViewMode viewMode = ViewMode.ADDABLE;
    private StackFilter stackFilter = StackFilter.ALL;

    private final Set<String> blacklist;
    private final Set<String> skipBlocks;
    private final Set<String> boxItems;

    private EditBox searchField;
    private Button blacklistButton;
    private Button boxButton;
    private Button skipBlockButton;
    private Button addableViewButton;
    private Button addedViewButton;
    private Button configButton;

    private boolean dropdownOpen;
    private int dropdownX, dropdownY, dropdownW;

    private final List<Entry> filtered = new ArrayList<>();
    private final List<Entry> addedFiltered = new ArrayList<>();
    private final List<Entry> placedEntries = new ArrayList<>();
    private final List<Integer> placedX = new ArrayList<>();
    private final List<Integer> placedY = new ArrayList<>();
    private int scrollY;

    private int gridX, gridY, gridW, gridH;
    private int cols;

    private List<Entry> allItemEntries;
    private List<Entry> allBlockEntries;

    /** 物品 ID -> 全局排序序号（按创造模式物品组顺序，即 REI 排版） */
    private Map<String, Integer> itemOrder;

    private Entry hovered;

    public SettingsScreen(Set<String> blacklist, Set<String> skipBlocks, Set<String> boxItems) {
        super(Component.literal("HopperFill 设置"));
        this.blacklist = new LinkedHashSet<>(blacklist);
        this.skipBlocks = new LinkedHashSet<>(skipBlocks);
        this.boxItems = new LinkedHashSet<>(boxItems);
    }

    @Override
    protected void init() {
        boolean creative = isCreative();
        if (!creative) {
            this.contentTab = ContentTab.SKIP_BLOCK; // 生存模式锁定为「跳过方块」
        }

        int tabW = 80;
        int searchW = Math.max(90, this.width - 12 - tabW - 8 - tabW - 8 - tabW - 8 - 12);

        this.searchField = new EditBox(this.font, 12, 24, searchW, 20, Component.literal("搜索"));
        this.searchField.setMaxLength(64);
        this.searchField.setResponder(s -> rebuildLists());
        this.addRenderableWidget(this.searchField);
        this.setInitialFocus(this.searchField);

        this.blacklistButton = Button.builder(Component.literal("黑名单"), b -> switchContent(ContentTab.BLACKLIST))
                .bounds(12 + searchW + 8, 24, tabW, 20).build();
        this.boxButton = Button.builder(Component.literal("满盒物品"), b -> switchContent(ContentTab.BOX))
                .bounds(12 + searchW + 8 + tabW + 8, 24, tabW, 20).build();
        int skipX = creative ? (12 + searchW + 8 + tabW + 8 + tabW + 8) : (12 + searchW + 8);
        this.skipBlockButton = Button.builder(Component.literal("跳过方块"), b -> switchContent(ContentTab.SKIP_BLOCK))
                .bounds(skipX, 24, tabW, 20).build();
        this.addableViewButton = Button.builder(Component.literal("可添加"), b -> switchView(ViewMode.ADDABLE))
                .bounds(12, 48, tabW, 20).build();
        this.addedViewButton = Button.builder(Component.literal("已添加"), b -> switchView(ViewMode.ADDED))
                .bounds(12 + tabW + 8, 48, tabW, 20).build();

        this.dropdownX = this.width - 12 - 90;
        this.dropdownY = 48;
        this.dropdownW = 90;
        this.configButton = Button.builder(Component.literal("配置 " + stackFilter.label), b -> toggleDropdown())
                .bounds(dropdownX, dropdownY, dropdownW, 20).build();

        if (creative) {
            this.addRenderableWidget(this.blacklistButton);
            this.addRenderableWidget(this.boxButton);
        }
        this.addRenderableWidget(this.skipBlockButton);
        this.addRenderableWidget(this.addableViewButton);
        this.addRenderableWidget(this.addedViewButton);
        this.addRenderableWidget(this.configButton);

        this.gridX = 12;
        this.gridY = GRID_TOP;
        this.gridW = this.width - 24;
        this.gridH = this.height - this.gridY - 8;
        this.cols = Math.max(1, this.gridW / CELL);

        ensureEntriesBuilt();
        updateTabLabels();
        rebuildLists();
    }

    private void ensureEntriesBuilt() {
        ensureItemOrderBuilt();

        if (allItemEntries == null) {
            List<Entry> items = new ArrayList<>();
            for (Item item : BuiltInRegistries.ITEM) {
                Identifier id = BuiltInRegistries.ITEM.getKey(item);
                // 只列生存可获取物品：创造专用 / 生存拿不到的（命令方块、刷怪蛋、基岩……）一律不出现在列表里
                if (!ItemFilter.isSurvivalObtainable(item, id)) continue;
                ItemStack icon = new ItemStack(item);
                items.add(new Entry(id.toString(), icon.getDisplayName().getString(), icon));
            }
            items.sort(Comparator.comparingInt(e -> itemOrder.getOrDefault(e.id(), Integer.MAX_VALUE)));
            allItemEntries = items;
        }
        if (allBlockEntries == null) {
            List<Entry> blocks = new ArrayList<>();
            for (Block block : BuiltInRegistries.BLOCK) {
                Item item = block.asItem();
                if (item == Items.AIR) continue; // 去掉盆栽、空气、流体等无物品形式的方块
                Identifier id = BuiltInRegistries.BLOCK.getKey(block);
                if (!ItemFilter.isSurvivalBlockCandidate(item, id)) continue; // 去掉创造专用方块
                ItemStack icon = new ItemStack(item);
                blocks.add(new Entry(id.toString(), icon.getDisplayName().getString(), icon));
            }
            blocks.sort(Comparator.comparingInt(e -> itemOrder.getOrDefault(e.id(), Integer.MAX_VALUE)));
            allBlockEntries = blocks;
        }
    }

    /** 按创造模式物品组（CreativeModeTab）的顺序生成物品排序表，实现 REI 式分组排版 */
    private void ensureItemOrderBuilt() {
        if (itemOrder != null) return;
        itemOrder = new HashMap<>();
        int idx = 0;
        for (CreativeModeTab tab : CreativeModeTabs.tabs()) {
            for (ItemStack stack : tab.getDisplayItems()) {
                Item item = stack.getItem();
                if (item == Items.AIR) continue;
                String id = BuiltInRegistries.ITEM.getKey(item).toString();
                if (!itemOrder.containsKey(id)) {
                    itemOrder.put(id, idx++);
                }
            }
        }
        // 兜底：未出现在任何物品组的物品，按注册顺序追加到末尾
        for (Item item : BuiltInRegistries.ITEM) {
            String id = BuiltInRegistries.ITEM.getKey(item).toString();
            if (!itemOrder.containsKey(id)) {
                itemOrder.put(id, idx++);
            }
        }
    }

    private List<Entry> allEntries() {
        return contentTab == ContentTab.SKIP_BLOCK ? allBlockEntries : allItemEntries;
    }

    private Set<String> currentSet() {
        return switch (contentTab) {
            case BLACKLIST -> blacklist;
            case BOX -> boxItems;
            case SKIP_BLOCK -> skipBlocks;
        };
    }

    private boolean isCreative() {
        return this.minecraft != null && this.minecraft.player != null && this.minecraft.player.isCreative();
    }

    private void switchContent(ContentTab tab) {
        if (!isCreative() && (tab == ContentTab.BLACKLIST || tab == ContentTab.BOX)) return; // 生存锁定黑名单与满盒物品
        this.contentTab = tab;
        this.dropdownOpen = false;
        updateTabLabels();
        rebuildLists();
    }

    private void switchView(ViewMode mode) {
        this.viewMode = mode;
        this.dropdownOpen = false;
        updateTabLabels();
        rebuildLists();
    }

    private void toggleDropdown() {
        this.dropdownOpen = !this.dropdownOpen;
    }

    private void selectStackFilter(StackFilter f) {
        this.stackFilter = f;
        this.dropdownOpen = false;
        updateTabLabels();
        rebuildLists();
    }

    private void updateTabLabels() {
        this.blacklistButton.setMessage(Component.literal(
                (contentTab == ContentTab.BLACKLIST ? "▶ " : "") + "黑名单"));
        this.boxButton.setMessage(Component.literal(
                (contentTab == ContentTab.BOX ? "▶ " : "") + "满盒物品"));
        this.skipBlockButton.setMessage(Component.literal(
                (contentTab == ContentTab.SKIP_BLOCK ? "▶ " : "") + "跳过方块"));
        this.addableViewButton.setMessage(Component.literal(
                (viewMode == ViewMode.ADDABLE ? "▶ " : "") + "可添加"));
        this.addedViewButton.setMessage(Component.literal(
                (viewMode == ViewMode.ADDED ? "▶ " : "") + "已添加"));
        this.configButton.setMessage(Component.literal("配置 " + stackFilter.label));
    }

    private void rebuildLists() {
        String q = searchField == null ? "" : searchField.getValue().trim().toLowerCase(Locale.ROOT);
        Set<String> current = currentSet();

        filtered.clear();
        addedFiltered.clear();
        for (Entry e : allEntries()) {
            if (!matches(e, q)) continue;
            if (!matchesStackFilter(e)) continue;
            filtered.add(e);
            if (current.contains(e.id())) addedFiltered.add(e);
        }
        scrollY = 0;
    }

    private static boolean matches(Entry e, String q) {
        if (q.isEmpty()) return true;
        return e.name().toLowerCase(Locale.ROOT).contains(q) || e.id().contains(q);
    }

    private boolean matchesStackFilter(Entry e) {
        int max = e.icon().getMaxStackSize();
        return switch (stackFilter) {
            case ALL -> true;
            case STACKABLE -> max > 1;
            case STACK_64 -> max >= 64;
            case STACK_16 -> max == 16;
            case NON_STACKABLE -> max == 1;
        };
    }

    private List<Entry> visibleEntries() {
        return viewMode == ViewMode.ADDABLE ? filtered : addedFiltered;
    }

    private boolean isAdded(Entry e) {
        return currentSet().contains(e.id());
    }

    private int contentHeight() {
        int n = visibleEntries().size();
        return ((n + cols - 1) / cols) * CELL;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor gui, int mouseX, int mouseY, float delta) {
        super.extractRenderState(gui, mouseX, mouseY, delta);

        gui.text(this.font, this.title, 12, 8, 0xFFFFFFFF, true);

        // 网格边框与底色
        gui.fill(gridX, gridY, gridX + gridW, gridY + gridH, 0x88000000);
        gui.fill(gridX, gridY, gridX + gridW, gridY + 1, 0xFF505050);
        gui.fill(gridX, gridY + gridH - 1, gridX + gridW, gridY + gridH, 0xFF505050);
        gui.fill(gridX, gridY, gridX + 1, gridY + gridH, 0xFF505050);
        gui.fill(gridX + gridW - 1, gridY, gridX + gridW, gridY + gridH, 0xFF505050);

        int maxScroll = Math.max(0, contentHeight() - gridH);
        scrollY = Math.max(0, Math.min(scrollY, maxScroll));

        placedEntries.clear();
        placedX.clear();
        placedY.clear();

        List<Entry> entries = visibleEntries();
        int start = scrollY / CELL;
        int totalRows = (entries.size() + cols - 1) / cols;

        gui.enableScissor(gridX + 1, gridY + 1, gridX + gridW - 1, gridY + gridH - 1);

        for (int row = start; row < Math.min(totalRows, start + (gridH / CELL) + 1); row++) {
            for (int col = 0; col < cols; col++) {
                int idx = row * cols + col;
                if (idx >= entries.size()) break;
                Entry e = entries.get(idx);
                int cx = gridX + col * CELL;
                int cy = gridY + row * CELL - scrollY;

                placedEntries.add(e);
                placedX.add(cx);
                placedY.add(cy);

                boolean locked = viewMode == ViewMode.ADDABLE && isAdded(e);
                gui.fill(cx, cy, cx + CELL, cy + CELL, 0x33000000);
                if (!e.icon().isEmpty()) {
                    gui.item(e.icon(), cx + 1, cy + 1);
                }
                if (locked) {
                    gui.fill(cx, cy, cx + CELL, cy + CELL, 0x99000000);
                    gui.text(this.font, "✓", cx + 1, cy + 1, 0x00FF00, false);
                }
            }
        }

        gui.disableScissor();

        // 悬停检测
        this.hovered = null;
        for (int i = 0; i < placedEntries.size(); i++) {
            int cx = placedX.get(i);
            int cy = placedY.get(i);
            if (mouseX >= cx && mouseX < cx + CELL && mouseY >= cy && mouseY < cy + CELL) {
                this.hovered = placedEntries.get(i);
                break;
            }
        }

        if (this.hovered != null && !dropdownOpen) {
            boolean locked = viewMode == ViewMode.ADDABLE && isAdded(hovered);
            List<Component> tip = new ArrayList<>();
            tip.add(Component.literal(hovered.name()));
            tip.add(Component.literal(hovered.id()).withStyle(ChatFormatting.GRAY));
            tip.add(Component.literal(locked ? "已添加（点击移除）" : "点击添加")
                    .withStyle(locked ? ChatFormatting.GREEN : ChatFormatting.WHITE));
            gui.setComponentTooltipForNextFrame(this.font, tip, mouseX, mouseY);
        }

        if (dropdownOpen) {
            renderDropdown(gui, mouseX, mouseY);
        }
    }

    private void renderDropdown(GuiGraphicsExtractor gui, int mouseX, int mouseY) {
        StackFilter[] values = StackFilter.values();
        int x = dropdownX;
        int y = dropdownY + 20;
        int w = dropdownW;
        int h = values.length * DROPDOWN_ROW;
        gui.fill(x, y, x + w, y + h, 0xF0202020);
        gui.fill(x, y, x + w, y + 1, 0xFFFFFFFF);
        gui.fill(x, y + h - 1, x + w, y + h, 0xFFFFFFFF);
        gui.fill(x, y, x + 1, y + h, 0xFFFFFFFF);
        gui.fill(x + w - 1, y, x + w, y + h, 0xFFFFFFFF);

        for (int i = 0; i < values.length; i++) {
            StackFilter f = values[i];
            int ry = y + i * DROPDOWN_ROW;
            boolean selected = f == stackFilter;
            boolean hover = mouseX >= x && mouseX < x + w && mouseY >= ry && mouseY < ry + DROPDOWN_ROW;
            if (hover || selected) {
                gui.fill(x + 1, ry, x + w - 1, ry + DROPDOWN_ROW, 0xFF3D3D3D);
            }
            int color = selected ? 0xFF55FF55 : (hover ? 0xFFFFFF00 : 0xFFFFFFFF);
            gui.text(this.font, f.label, x + 6, ry + 5, color, false);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean handled) {
        if (super.mouseClicked(event, handled)) return true;
        if (event.button() != 0) return true;

        double mx = event.x();
        double my = event.y();

        // 下拉列表优先处理
        if (dropdownOpen) {
            StackFilter[] values = StackFilter.values();
            int x = dropdownX;
            int y = dropdownY + 20;
            int w = dropdownW;
            int h = values.length * DROPDOWN_ROW;
            if (mx >= x && mx < x + w && my >= y && my < y + h) {
                int idx = (int) ((my - y) / DROPDOWN_ROW);
                if (idx >= 0 && idx < values.length) {
                    selectStackFilter(values[idx]);
                    return true;
                }
            }
            // 点下拉外区域关闭
            dropdownOpen = false;
            return true;
        }

        for (int i = 0; i < placedEntries.size(); i++) {
            int cx = placedX.get(i);
            int cy = placedY.get(i);
            if (mx >= cx && mx < cx + CELL && my >= cy && my < cy + CELL) {
                Entry e = placedEntries.get(i);
                if (viewMode == ViewMode.ADDABLE) {
                    if (isAdded(e)) {
                        removeEntry(e.id());
                    } else {
                        addEntry(e.id());
                    }
                } else {
                    removeEntry(e.id());
                }
                return true;
            }
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (mouseX >= gridX && mouseX < gridX + gridW && mouseY >= gridY && mouseY < gridY + gridH) {
            int maxScroll = Math.max(0, contentHeight() - gridH);
            scrollY = Math.max(0, Math.min(scrollY - (int) verticalAmount * CELL, maxScroll));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private void addEntry(String id) {
        boolean changed;
        if (contentTab == ContentTab.BLACKLIST) {
            changed = blacklist.add(id);
            if (changed) ClientPlayNetworking.send(new SettingsNetwork.UpdateBlacklistPayload(id, true));
        } else if (contentTab == ContentTab.BOX) {
            changed = boxItems.add(id);
            if (changed) ClientPlayNetworking.send(new SettingsNetwork.UpdateBoxPayload(id, true));
        } else {
            changed = skipBlocks.add(id);
            if (changed) ClientPlayNetworking.send(new SettingsNetwork.UpdateSkipBlockPayload(id, true));
        }
        if (changed) {
            int saved = scrollY;
            rebuildLists();
            scrollY = saved;
        }
    }

    private void removeEntry(String id) {
        boolean changed;
        if (contentTab == ContentTab.BLACKLIST) {
            changed = blacklist.remove(id);
            if (changed) ClientPlayNetworking.send(new SettingsNetwork.UpdateBlacklistPayload(id, false));
        } else if (contentTab == ContentTab.BOX) {
            changed = boxItems.remove(id);
            if (changed) ClientPlayNetworking.send(new SettingsNetwork.UpdateBoxPayload(id, false));
        } else {
            changed = skipBlocks.remove(id);
            if (changed) ClientPlayNetworking.send(new SettingsNetwork.UpdateSkipBlockPayload(id, false));
        }
        if (changed) {
            int saved = scrollY;
            rebuildLists();
            scrollY = saved;
        }
    }
}
