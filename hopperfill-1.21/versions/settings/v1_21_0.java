package com.leaf.hopperfill.client.gui;

import com.leaf.hopperfill.network.SettingsPayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.block.Block;
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
 * 统一设置界面（1.21.0 - 1.21.8 / Yarn 映射）。
 * 与新版唯一区别：mouseClicked 使用旧式 (double, double, int) 签名（无 Click 记录）。
 */
public class SettingsScreen extends Screen {
    private enum ContentTab { BLACKLIST, SKIP_BLOCK }
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

    private TextFieldWidget searchField;
    private ButtonWidget blacklistButton;
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

    private Map<String, Integer> itemOrder;

    private Entry hovered;

    public SettingsScreen(Set<String> blacklist, Set<String> skipBlocks) {
        super(Text.literal("HopperFill 设置"));
        this.blacklist = new LinkedHashSet<>(blacklist);
        this.skipBlocks = new LinkedHashSet<>(skipBlocks);
    }

    @Override
    protected void init() {
        int tabW = 80;
        int searchW = Math.max(90, this.width - 12 - tabW - 8 - tabW - 8 - 12);

        this.searchField = new TextFieldWidget(this.textRenderer, 12, 24, searchW, 20, Text.literal("搜索"));
        this.searchField.setMaxLength(64);
        this.searchField.setChangedListener(s -> rebuildLists());
        this.addDrawableChild(this.searchField);
        this.setInitialFocus(this.searchField);

        this.blacklistButton = ButtonWidget.builder(Text.literal("黑名单"), b -> switchContent(ContentTab.BLACKLIST))
                .dimensions(12 + searchW + 8, 24, tabW, 20).build();
        this.skipBlockButton = ButtonWidget.builder(Text.literal("跳过方块"), b -> switchContent(ContentTab.SKIP_BLOCK))
                .dimensions(12 + searchW + 8 + tabW + 8, 24, tabW, 20).build();
        this.addableViewButton = ButtonWidget.builder(Text.literal("可添加"), b -> switchView(ViewMode.ADDABLE))
                .dimensions(12, 48, tabW, 20).build();
        this.addedViewButton = ButtonWidget.builder(Text.literal("已添加"), b -> switchView(ViewMode.ADDED))
                .dimensions(12 + tabW + 8, 48, tabW, 20).build();

        this.dropdownX = this.width - 12 - 90;
        this.dropdownY = 48;
        this.dropdownW = 90;
        this.configButton = ButtonWidget.builder(Text.literal("配置 " + stackFilter.label), b -> toggleDropdown())
                .dimensions(dropdownX, dropdownY, dropdownW, 20).build();

        this.addDrawableChild(this.blacklistButton);
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
                if (item == Items.AIR) continue;
                Identifier id = Registries.BLOCK.getId(block);
                ItemStack icon = new ItemStack(item);
                blocks.add(new Entry(id.toString(), icon.getName().getString(), icon));
            }
            blocks.sort(Comparator.comparingInt(e -> itemOrder.getOrDefault(e.id(), Integer.MAX_VALUE)));
            allBlockEntries = blocks;
        }
    }

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
        for (Item item : Registries.ITEM) {
            String id = Registries.ITEM.getId(item).toString();
            if (!itemOrder.containsKey(id)) {
                itemOrder.put(id, idx++);
            }
        }
    }

    private List<Entry> allEntries() {
        return contentTab == ContentTab.BLACKLIST ? allItemEntries : allBlockEntries;
    }

    private Set<String> currentSet() {
        return contentTab == ContentTab.BLACKLIST ? blacklist : skipBlocks;
    }

    private void switchContent(ContentTab tab) {
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
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        if (button != 0) return true;

        double mx = mouseX;
        double my = mouseY;

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
