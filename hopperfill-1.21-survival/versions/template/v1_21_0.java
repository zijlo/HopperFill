package com.zijlo.hopperfill.client.gui;

import com.zijlo.hopperfill.gui.TemplateScreenHandler;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * 模板设置界面（1.21.0 / 1.21.1 / Yarn 映射）。
 * 复用原版漏斗界面纹理与布局：5 个模板槽，左上角一个「16 / 64」切换按钮。
 * 此版本 DrawContext.drawTexture 使用旧式 7 参签名（无 RenderLayer/RenderPipeline）。
 */
public class TemplateScreen extends HandledScreen<TemplateScreenHandler> {
    private static final Identifier TEXTURE = Identifier.ofVanilla("textures/gui/container/hopper.png");

    private ButtonWidget toggleButton;
    private int currentType;

    public TemplateScreen(TemplateScreenHandler handler, PlayerInventory inventory, Text title) {
        super(handler, inventory, title);
        this.backgroundHeight = 133;
        this.playerInventoryTitleY = this.backgroundHeight - 94; // 原版漏斗：39
        this.currentType = handler.getTemplateType();
    }

    @Override
    protected void init() {
        super.init();
        // 按钮放在漏斗界面左侧、与槽位行对齐，避开右上角的一键整理等快捷键位置
        this.toggleButton = ButtonWidget.builder(Text.literal(label()), b -> {
            if (this.client != null && this.client.interactionManager != null) {
                this.client.interactionManager.clickButton(this.handler.syncId, 0);
            }
            this.currentType = (this.currentType == 16) ? 64 : 16;
            b.setMessage(Text.literal(label()));
        }).dimensions(this.x + 2, this.y + 19, 40, 18).build();
        this.addDrawableChild(this.toggleButton);
    }

    private String label() {
        return this.currentType == 16 ? "16堆叠" : "64堆叠";
    }

    @Override
    protected void drawBackground(DrawContext context, float delta, int mouseX, int mouseY) {
        int x = (this.width - this.backgroundWidth) / 2;
        int y = (this.height - this.backgroundHeight) / 2;
        context.drawTexture(TEXTURE, x, y, 0, 0, this.backgroundWidth, this.backgroundHeight);
    }
}
