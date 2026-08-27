package com.zijlo.hopperfill.client.gui;

import com.zijlo.hopperfill.network.SettingsPayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.block.Block;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 统一设置界面（1.21.9+ / Yarn 映射）。
 *
 * 布局：
 *   第 1 行：搜索框（缩小）+ 黑名单 tab + 满盒物品 tab + 跳过方块 tab（同一排）
 *   第 2 行：可添加 tab + 已添加 tab + 配置下拉选择器（最右）
 *   下方：REI 风格物品图标网格（纯平铺，无分组，滚轮上下翻页）
 *
 * 视图规则：
 *   「可添加」列出全部物品（含已添加项，置灰打勾）；已添加项点击移除，未添加项点击添加；
 *   「已添加」只列已添加项，点击移除。
 * 配置下拉选择器：点击展开下拉列表，从 5 个堆叠配置中单选。
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

    private TextFieldWidget searchField;
    private ButtonWidget blacklistButton;
    private ButtonWidget boxButton;
    private ButtonWidget skipBlockButton;
    private ButtonWidget addableViewButton;
    private ButtonWidget addedViewButton;
    private ButtonWidget configButton;

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
        super(Text.literal("HopperFill 设置"));
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

        this.searchField = new TextFieldWidget(this.textRenderer, 12, 24, searchW, 20, Text.literal("搜索"));
        this.searchField.setMaxLength(64);
        this.searchField.setChangedListener(s -> rebuildLists());
        this.addDrawableChild(this.searchField);
        this.setInitialFocus(this.searchField);

        this.blacklistButton = ButtonWidget.builder(Text.literal("黑名单"), b -> switchContent(ContentTab.BLACKLIST))
                .dimensions(12 + searchW + 8, 24, tabW, 20).build();
        this.boxButton = ButtonWidget.builder(Text.literal("满盒物品"), b -> switchContent(ContentTab.BOX))
                .dimensions(12 + searchW + 8 + tabW + 8, 24, tabW, 20).build();
        int skipX = creative ? (12 + searchW + 8 + tabW + 8 + tabW + 8) : (12 + searchW + 8);
        this.skipBlockButton = ButtonWidget.builder(Text.literal("跳过方块"), b -> switchContent(ContentTab.SKIP_BLOCK))
                .dimensions(skipX, 24, tabW, 20).build();
        this.addableViewButton = ButtonWidget.builder(Text.literal("可添加"), b -> switchView(ViewMode.ADDABLE))
                .dimensions(12, 48, tabW, 20).build();
        this.addedViewButton = ButtonWidget.builder(Text.literal("已添加"), b -> switchView(ViewMode.ADDED))
                .dimensions(12 + tabW + 8, 48, tabW, 20).build();

        this.dropdownX = this.width - 12 - 90;
        this.dropdownY = 48;
        this.dropdownW = 90;
        this.configButton = ButtonWidget.builder(Text.literal("配置 " + stackFilter.label), b -> toggleDropdown())
                .dimensions(dropdownX, dropdownY, dropdownW, 20).build();

        if (creative) {
            this.addDrawableChild(this.blacklistButton);
            this.addDrawableChild(this.boxButton);
        }
        this.addDrawableChild(this.skipBlockButton);
        this.addDrawableChild(this.addableViewButton);
        this.addDrawableChild(this.addedViewButton);
        this.addDrawableChild(this.configButton);

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
            for (Item item : Registries.ITEM) {
                Identifier id = Registries.ITEM.getId(item);
                ItemStack icon = new ItemStack(item);
                items.add(new Entry(id.toString(), icon.getName().getString(), icon));
            }
            items.sort(Comparator.comparingInt(e -> itemOrder.getOrDefault(e.id(), Integer.MAX_VALUE)));
            allItemEntries = items;
        }
        if (allBlockEntries == null) {
            List<Entry> blocks = new ArrayList<>();
            for (Block block : Registries.BLOCK) {
                Item item = block.asItem();
                if (item == Items.AIR) continue; // 去掉盆栽、空气、流体等无物品形式的方块
                Identifier id = Registries.BLOCK.getId(block);
                ItemStack icon = new ItemStack(item);
                blocks.add(new Entry(id.toString(), icon.getName().getString(), icon));
            }
            blocks.sort(Comparator.comparingInt(e -> itemOrder.getOrDefault(e.id(), Integer.MAX_VALUE)));
            allBlockEntries = blocks;
        }
    }

    /** 按创造模式物品组（ItemGroup）的顺序生成物品排序表，实现 REI 式分组排版 */
    private void ensureItemOrderBuilt() {
        if (itemOrder != null) return;
        itemOrder = new HashMap<>();
        int idx = 0;
        for (ItemGroup group : ItemGroups.getGroupsToDisplay()) {
            for (ItemStack stack : group.getDisplayStacks()) {
                Item item = stack.getItem();
                if (item == Items.AIR) continue;
                String id = Registries.ITEM.getId(item).toString();
                if (!itemOrder.containsKey(id)) {
                    itemOrder.put(id, idx++);
                }
            }
        }
        // 兜底：未出现在任何物品组的物品，按注册顺序追加到末尾
        for (Item item : Registries.ITEM) {
            String id = Registries.ITEM.getId(item).toString();
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
        return this.client != null && this.client.player != null && this.client.player.isCreative();
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
        this.blacklistButton.setMessage(Text.literal(
                (contentTab == ContentTab.BLACKLIST ? "▶ " : "") + "黑名单"));
        this.boxButton.setMessage(Text.literal(
                (contentTab == ContentTab.BOX ? "▶ " : "") + "满盒物品"));
        this.skipBlockButton.setMessage(Text.literal(
                (contentTab == ContentTab.SKIP_BLOCK ? "▶ " : "") + "跳过方块"));
        this.addableViewButton.setMessage(Text.literal(
                (viewMode == ViewMode.ADDABLE ? "▶ " : "") + "可添加"));
        this.addedViewButton.setMessage(Text.literal(
                (viewMode == ViewMode.ADDED ? "▶ " : "") + "已添加"));
        this.configButton.setMessage(Text.literal("配置 " + stackFilter.label));
    }

    private void rebuildLists() {
        String q = searchField == null ? "" : searchField.getText().trim().toLowerCase(Locale.ROOT);
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
        int max = e.icon().getMaxCount();
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
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        context.drawTextWithShadow(this.textRenderer, this.title, 12, 8, 0xFFFFFFFF);

        // 网格边框与底色
        context.fill(gridX, gridY, gridX + gridW, gridY + gridH, 0x88000000);
        context.fill(gridX, gridY, gridX + gridW, gridY + 1, 0xFF505050);
        context.fill(gridX, gridY + gridH - 1, gridX + gridW, gridY + gridH, 0xFF505050);
        context.fill(gridX, gridY, gridX + 1, gridY + gridH, 0xFF505050);
        context.fill(gridX + gridW - 1, gridY, gridX + gridW, gridY + gridH, 0xFF505050);

        int maxScroll = Math.max(0, contentHeight() - gridH);
        scrollY = Math.max(0, Math.min(scrollY, maxScroll));

        placedEntries.clear();
        placedX.clear();
        placedY.clear();

        List<Entry> entries = visibleEntries();
        int start = scrollY / CELL;
        int totalRows = (entries.size() + cols - 1) / cols;

        // 用 scissor 裁剪，避免图标画出网格框外
        context.enableScissor(gridX + 1, gridY + 1, gridX + gridW - 1, gridY + gridH - 1);

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
                context.fill(cx, cy, cx + CELL, cy + CELL, 0x33000000);
                if (!e.icon().isEmpty()) {
                    context.drawItem(e.icon(), cx + 1, cy + 1);
                }
                if (locked) {
                    context.fill(cx, cy, cx + CELL, cy + CELL, 0x99000000);
                    context.drawText(this.textRenderer, Text.literal("✓").formatted(Formatting.GREEN),
                            cx + 1, cy + 1, 0x00FF00, false);
                }
            }
        }

        context.disableScissor();

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
            List<Text> tip = new ArrayList<>();
            tip.add(Text.literal(hovered.name()));
            tip.add(Text.literal(hovered.id()).formatted(Formatting.GRAY));
            tip.add(Text.literal(locked ? "已添加（点击移除）" : "点击添加")
                    .formatted(locked ? Formatting.GREEN : Formatting.WHITE));
            context.drawTooltip(this.textRenderer, tip, mouseX, mouseY);
        }

        if (dropdownOpen) {
            renderDropdown(context, mouseX, mouseY);
        }
    }

    private void renderDropdown(DrawContext context, int mouseX, int mouseY) {
        StackFilter[] values = StackFilter.values();
        int x = dropdownX;
        int y = dropdownY + 20;
        int w = dropdownW;
        int h = values.length * DROPDOWN_ROW;
        context.fill(x, y, x + w, y + h, 0xF0202020);
        context.fill(x, y, x + w, y + 1, 0xFFFFFFFF);
        context.fill(x, y + h - 1, x + w, y + h, 0xFFFFFFFF);
        context.fill(x, y, x + 1, y + h, 0xFFFFFFFF);
        context.fill(x + w - 1, y, x + w, y + h, 0xFFFFFFFF);

        for (int i = 0; i < values.length; i++) {
            StackFilter f = values[i];
            int ry = y + i * DROPDOWN_ROW;
            boolean selected = f == stackFilter;
            boolean hover = mouseX >= x && mouseX < x + w && mouseY >= ry && mouseY < ry + DROPDOWN_ROW;
            if (hover || selected) {
                context.fill(x + 1, ry, x + w - 1, ry + DROPDOWN_ROW, 0xFF3D3D3D);
            }
            int color = selected ? 0xFF55FF55 : (hover ? 0xFFFFFF00 : 0xFFFFFFFF);
            context.drawText(this.textRenderer, Text.literal(f.label), x + 6, ry + 5, color, false);
        }
    }

    @Override
    public boolean mouseClicked(Click click, boolean handled) {
        if (super.mouseClicked(click, handled)) return true;
        if (click.button() != 0) return true;

        double mx = click.x();
        double my = click.y();

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
            if (changed) ClientPlayNetworking.send(new SettingsPayloads.UpdateBlacklistPayload(id, true));
        } else if (contentTab == ContentTab.BOX) {
            changed = boxItems.add(id);
            if (changed) ClientPlayNetworking.send(new SettingsPayloads.UpdateBoxPayload(id, true));
        } else {
            changed = skipBlocks.add(id);
            if (changed) ClientPlayNetworking.send(new SettingsPayloads.UpdateSkipBlockPayload(id, true));
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
            if (changed) ClientPlayNetworking.send(new SettingsPayloads.UpdateBlacklistPayload(id, false));
        } else if (contentTab == ContentTab.BOX) {
            changed = boxItems.remove(id);
            if (changed) ClientPlayNetworking.send(new SettingsPayloads.UpdateBoxPayload(id, false));
        } else {
            changed = skipBlocks.remove(id);
            if (changed) ClientPlayNetworking.send(new SettingsPayloads.UpdateSkipBlockPayload(id, false));
        }
        if (changed) {
            int saved = scrollY;
            rebuildLists();
            scrollY = saved;
        }
    }
}