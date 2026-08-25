package com.leaf.hopperfill.gui;

import com.leaf.hopperfill.HopperFillMod;
import com.leaf.hopperfill.data.TemplateStorage;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.List;

/**
 * 模板设置 ScreenHandler（1.21.11 / Yarn 映射）。
 * 采用原版漏斗界面布局：5 个模板槽 + 玩家背包，上方通过按钮切换「16 堆叠 / 64 堆叠」模板。
 * 客户端按下切换按钮 → clickButton → 服务端 onButtonClick 保存当前模板、载入另一套模板并同步。
 */
public class TemplateScreenHandler extends ScreenHandler {
    public static final int TEMPLATE_SLOTS = 5;

    private final SimpleInventory templateInventory;
    private final ServerPlayerEntity player;
    private int templateType;

    /** 客户端构造（由 ScreenHandlerType 工厂调用） */
    public TemplateScreenHandler(int syncId, PlayerInventory playerInventory) {
        this(syncId, playerInventory, new SimpleInventory(TEMPLATE_SLOTS), null, 16);
    }

    /** 服务器构造 */
    public TemplateScreenHandler(int syncId, PlayerInventory playerInventory,
                                 SimpleInventory templateInventory, ServerPlayerEntity player, int templateType) {
        super(HopperFillMod.TEMPLATE_SCREEN_HANDLER, syncId);
        this.templateInventory = templateInventory;
        this.player = player;
        this.templateType = templateType;

        // 原版漏斗 5 槽布局：x = 44 + i*18, y = 20
        for (int i = 0; i < TEMPLATE_SLOTS; i++) {
            this.addSlot(new Slot(templateInventory, i, 44 + i * 18, 20));
        }
        // 玩家背包（原版漏斗布局，手写循环以兼容所有 1.21.x 版本）
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
    public boolean canUse(PlayerEntity player) {
        return true;
    }

    @Override
    public ItemStack quickMove(PlayerEntity player, int slot) {
        ItemStack result = ItemStack.EMPTY;
        Slot s = this.slots.get(slot);
        if (s != null && s.hasStack()) {
            ItemStack stack = s.getStack();
            result = stack.copy();
            if (slot < TEMPLATE_SLOTS) {
                if (!this.insertItem(stack, TEMPLATE_SLOTS, TEMPLATE_SLOTS + 36, true)) {
                    return ItemStack.EMPTY;
                }
            } else if (!this.insertItem(stack, 0, TEMPLATE_SLOTS, false)) {
                return ItemStack.EMPTY;
            }
            if (stack.isEmpty()) {
                s.setStack(ItemStack.EMPTY);
            } else {
                s.markDirty();
            }
        }
        return result;
    }

    /** 客户端切换按钮（id = 0）：保存当前模板 → 载入另一套模板 → 同步 */
    @Override
    public boolean onButtonClick(PlayerEntity player, int id) {
        if (id == 0 && player instanceof ServerPlayerEntity sp) {
            switchTemplate(sp);
            return true;
        }
        return super.onButtonClick(player, id);
    }

    private void switchTemplate(ServerPlayerEntity sp) {
        saveCurrentToStorage(sp);
        templateType = (templateType == 16) ? 64 : 16;

        List<ItemStack> template = (templateType == 16)
                ? TemplateStorage.getTemplate16(sp) : TemplateStorage.getTemplate64(sp);
        for (int i = 0; i < TEMPLATE_SLOTS; i++) {
            ItemStack s = (template != null && i < template.size()) ? template.get(i) : null;
            this.slots.get(i).setStack((s != null && !s.isEmpty()) ? s.copy() : ItemStack.EMPTY);
        }
        this.sendContentUpdates();
        sendSavedMessage(sp);
    }

    private void saveCurrentToStorage(ServerPlayerEntity sp) {
        SimpleInventory copy = new SimpleInventory(TEMPLATE_SLOTS);
        for (int i = 0; i < TEMPLATE_SLOTS; i++) {
            copy.setStack(i, this.templateInventory.getStack(i).copy());
        }
        TemplateStorage.saveTemplate(sp, copy, templateType);
    }

    private int countFilled() {
        int n = 0;
        for (int i = 0; i < TEMPLATE_SLOTS; i++) {
            if (!this.templateInventory.getStack(i).isEmpty()) n++;
        }
        return n;
    }

    private void sendSavedMessage(ServerPlayerEntity sp) {
        int n = countFilled();
        sp.sendMessage(Text.literal(
                "§a模板已保存 | " + (templateType == 16 ? "§c16堆叠" : "§b64堆叠")
                        + "§f：" + (n == 0 ? "已清空" : n + " 个物品")), false);
    }

    @Override
    public void onClosed(PlayerEntity player) {
        if (player instanceof ServerPlayerEntity sp) {
            saveCurrentToStorage(sp);
            sendSavedMessage(sp);
        }
        super.onClosed(player);
    }
}
