package com.zijlo.hopperfill.gui;

import com.zijlo.hopperfill.HopperFillMod;
import com.zijlo.hopperfill.data.TemplateStorage;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 模板设置 ScreenHandler（26.x / Mojang 非混淆命名）。
 * 采用原版漏斗界面布局：5 个模板槽 + 玩家背包，上方通过按钮切换「16 堆叠 / 64 堆叠」模板。
 * 客户端按下切换按钮 → clickMenuButton → 服务端切换模板并同步。
 */
public class TemplateScreenHandler extends AbstractContainerMenu {
    public static final int TEMPLATE_SLOTS = 5;

    private final SimpleContainer templateInventory;
    private final ServerPlayer player;
    private int templateType;

    /** 客户端构造（由 MenuType 工厂调用） */
    public TemplateScreenHandler(int containerId, Inventory playerInventory) {
        this(containerId, playerInventory, new SimpleContainer(TEMPLATE_SLOTS), null, 16);
    }

    /** 服务器构造 */
    public TemplateScreenHandler(int containerId, Inventory playerInventory,
                                 SimpleContainer templateInventory, ServerPlayer player, int templateType) {
        super(HopperFillMod.TEMPLATE_SCREEN_HANDLER, containerId);
        this.templateInventory = templateInventory;
        this.player = player;
        this.templateType = templateType;

        // 原版漏斗 5 槽布局：x = 44 + i*18, y = 20
        for (int i = 0; i < TEMPLATE_SLOTS; i++) {
            this.addSlot(new Slot(templateInventory, i, 44 + i * 18, 20));
        }
        // 玩家背包（原版漏斗布局）
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 51 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInventory, col, 8 + col * 18, 109));
        }
    }

    public int getTemplateType() {
        return templateType;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        ItemStack result = ItemStack.EMPTY;
        Slot s = this.slots.get(slotIndex);
        if (s != null && s.hasItem()) {
            ItemStack stack = s.getItem();
            result = stack.copy();
            if (slotIndex < TEMPLATE_SLOTS) {
                if (!this.moveItemStackTo(stack, TEMPLATE_SLOTS, TEMPLATE_SLOTS + 36, true)) {
                    return ItemStack.EMPTY;
                }
            } else if (!this.moveItemStackTo(stack, 0, TEMPLATE_SLOTS, false)) {
                return ItemStack.EMPTY;
            }
            if (stack.isEmpty()) {
                s.set(ItemStack.EMPTY);
            } else {
                s.setChanged();
            }
        }
        return result;
    }

    /** 客户端切换按钮（id = 0）：保存当前模板 → 载入另一套模板 → 同步 */
    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (id == 0 && player instanceof ServerPlayer sp) {
            switchTemplate(sp);
            return true;
        }
        return super.clickMenuButton(player, id);
    }

    private void switchTemplate(ServerPlayer sp) {
        boolean changed = templateChanged(sp);
        saveCurrentToStorage(sp);
        templateType = (templateType == 16) ? 64 : 16;

        List<ItemStack> template = (templateType == 16)
                ? TemplateStorage.getTemplate16(sp) : TemplateStorage.getTemplate64(sp);
        for (int i = 0; i < TEMPLATE_SLOTS; i++) {
            ItemStack s = (template != null && i < template.size()) ? template.get(i) : null;
            this.slots.get(i).set((s != null && !s.isEmpty()) ? s.copy() : ItemStack.EMPTY);
        }
        this.broadcastChanges();
        if (changed) {
            sendSavedMessage(sp);
        }
    }

    /** 比较当前 5 槽与已存储模板是否一致（含物品、数量与组件），仅在确有修改时才提示 */
    private boolean templateChanged(ServerPlayer sp) {
        List<ItemStack> stored = (templateType == 16)
                ? TemplateStorage.getTemplate16(sp) : TemplateStorage.getTemplate64(sp);

        List<ItemStack> current = new ArrayList<>(TEMPLATE_SLOTS);
        for (int i = 0; i < TEMPLATE_SLOTS; i++) {
            current.add(this.templateInventory.getItem(i).copy());
        }
        List<ItemStack> old = new ArrayList<>(TEMPLATE_SLOTS);
        for (int i = 0; i < TEMPLATE_SLOTS; i++) {
            ItemStack s = (stored != null && i < stored.size()) ? stored.get(i) : null;
            old.add((s != null && !s.isEmpty()) ? s : ItemStack.EMPTY);
        }
        return !ItemStack.listMatches(current, old);
    }

    private void saveCurrentToStorage(ServerPlayer sp) {
        SimpleContainer copy = new SimpleContainer(TEMPLATE_SLOTS);
        for (int i = 0; i < TEMPLATE_SLOTS; i++) {
            copy.setItem(i, this.templateInventory.getItem(i).copy());
        }
        TemplateStorage.saveTemplate(sp, copy, templateType);
    }

    private int countFilled() {
        int n = 0;
        for (int i = 0; i < TEMPLATE_SLOTS; i++) {
            if (!this.templateInventory.getItem(i).isEmpty()) n++;
        }
        return n;
    }

    private void sendSavedMessage(ServerPlayer sp) {
        int n = countFilled();
        sp.sendSystemMessage(Component.literal(
                "§a模板已保存 | " + (templateType == 16 ? "§c16堆叠" : "§b64堆叠")
                        + "§f：" + (n == 0 ? "已清空" : n + " 个物品")), false);
    }

    @Override
    public void removed(Player player) {
        if (player instanceof ServerPlayer sp) {
            boolean changed = templateChanged(sp);
            saveCurrentToStorage(sp);
            if (changed) {
                sendSavedMessage(sp);
            }
        }
        super.removed(player);
    }
}
