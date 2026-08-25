package com.leaf.hopperfill.gui;

import com.leaf.hopperfill.data.TemplateStorage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public class CustomHopperScreenHandler extends AbstractContainerMenu {
    /** 模板槽位数量（与漏斗 5 槽一一对应，第 0 槽为过滤物占位） */
    private static final int TEMPLATE_SLOTS = 5;
    /** 模板槽起始 X 坐标 */
    private static final int TEMPLATE_START_X = 44;
    /** 模板槽 Y 坐标 */
    private static final int TEMPLATE_Y = 20;
    /** 槽位间距 */
    private static final int SLOT_SPACING = 18;
    /** 玩家背包起始 X */
    private static final int PLAYER_INV_X = 8;
    /** 玩家背包起始 Y */
    private static final int PLAYER_INV_Y = 51;
    /** 快捷栏 Y */
    private static final int HOTBAR_Y = 109;

    private final SimpleContainer container;
    private final ServerPlayer player;
    private final int stackType;

    public CustomHopperScreenHandler(int containerId, Inventory playerInventory, SimpleContainer container, ServerPlayer player, int stackType) {
        super(MenuType.HOPPER, containerId);
        this.container = container;
        this.player = player;
        this.stackType = stackType;

        // 4 个模板槽位（漏斗槽 1-4）
        for (int i = 0; i < TEMPLATE_SLOTS; i++) {
            addSlot(new Slot(container, i, TEMPLATE_START_X + i * SLOT_SPACING, TEMPLATE_Y));
        }

        // 玩家背包 3 行
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, col + row * 9 + 9, PLAYER_INV_X + col * SLOT_SPACING, PLAYER_INV_Y + row * SLOT_SPACING));
            }
        }

        // 玩家快捷栏
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInventory, col, PLAYER_INV_X + col * SLOT_SPACING, HOTBAR_Y));
        }
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        TemplateStorage.saveTemplate(this.player, container, stackType);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack newStack = ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (slot != null && slot.hasItem()) {
            ItemStack originalStack = slot.getItem();
            newStack = originalStack.copy();
            if (index < TEMPLATE_SLOTS) {
                if (!moveItemStackTo(originalStack, TEMPLATE_SLOTS, slots.size(), true)) {
                    return ItemStack.EMPTY;
                }
            } else if (!moveItemStackTo(originalStack, 0, TEMPLATE_SLOTS, false)) {
                return ItemStack.EMPTY;
            }

            if (originalStack.isEmpty()) {
                slot.set(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }
        }
        return newStack;
    }
}