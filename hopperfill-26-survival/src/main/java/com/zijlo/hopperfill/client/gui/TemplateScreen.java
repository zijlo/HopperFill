package com.zijlo.hopperfill.client.gui;

import com.zijlo.hopperfill.gui.TemplateScreenHandler;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;

/**
 * 模板设置界面（26.x / Mojang 非混淆命名）。
 * 复用原版漏斗界面纹理与布局：5 个模板槽，左上角一个「16 / 64」切换按钮。
 * 按下按钮 → gameMode.handleInventoryButtonClick(containerId, 0) → 服务端切换模板并同步。
 */
public class TemplateScreen extends AbstractContainerScreen<TemplateScreenHandler> {
    private static final Identifier TEXTURE = Identifier.withDefaultNamespace("textures/gui/container/hopper.png");

    private Button toggleButton;
    private int currentType;

    public TemplateScreen(TemplateScreenHandler handler, Inventory inventory, Component title) {
        super(handler, inventory, title, 176, 133);
        this.currentType = handler.getTemplateType();
    }

    @Override
    protected void init() {
        super.init();
        this.toggleButton = Button.builder(Component.literal(label()), b -> {
            if (this.minecraft != null && this.minecraft.gameMode != null) {
                this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, 0);
            }
            this.currentType = (this.currentType == 16) ? 64 : 16;
            b.setMessage(Component.literal(label()));
        }).bounds(this.leftPos + 2, this.topPos + 19, 40, 18).build();
        this.addRenderableWidget(this.toggleButton);
    }

    private String label() {
        return this.currentType == 16 ? "16堆叠" : "64堆叠";
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor gui, int mouseX, int mouseY, float delta) {
        super.extractBackground(gui, mouseX, mouseY, delta);
        int x = this.leftPos;
        int y = this.topPos;
        gui.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, x, y, 0, 0, this.imageWidth, this.imageHeight, 256, 256);
    }
}
