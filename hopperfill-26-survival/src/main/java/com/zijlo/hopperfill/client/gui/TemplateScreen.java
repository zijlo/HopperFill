package com.zijlo.hopperfill.client.gui;

import com.zijlo.hopperfill.gui.TemplateScreenHandler;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 模板设置界面（26.x / Mojang 非混淆命名）。
 * 复用原版漏斗界面纹理与布局：5 个模板槽，左上角一个「16 / 64」切换按钮。
 * 按下按钮 → gameMode.handleInventoryButtonClick(containerId, 0) → 服务端切换模板并同步。
 *
 * <p>跨版本要点：26.3 把渲染管线从 {@code com.mojang.blaze3d} 迁到了 {@code com.mojang.renderpearl}，
 * 于是 {@code RenderPipelines.GUI_TEXTURED} 的<b>字段类型</b>变了。若直接书写
 * {@code gui.blit(RenderPipelines.GUI_TEXTURED, ...)}，编译期会把旧包名写进常量池的字段/方法描述符，
 * 在 26.3 上就会抛 {@code NoSuchFieldError} / {@code NoSuchMethodError}。
 * 因此这里改为按<b>名字</b>反射解析「管线对象」与「blit 方法」，让同一个 jar 在 26.1 ～ 26.3 上都能用。
 */
public class TemplateScreen extends AbstractContainerScreen<TemplateScreenHandler> {
    private static final Identifier TEXTURE = Identifier.withDefaultNamespace("textures/gui/container/hopper.png");

    /** GUI_TEXTURED 管线实例（类型随版本而变，故按 Object 持有）。 */
    private static final Object GUI_TEXTURED;
    /** 匹配到的 {@code blit(RenderPipeline, Identifier, int, int, float, float, int, int, int, int)}。 */
    private static final Method BLIT;

    static {
        Object pipeline = null;
        Method blit = null;
        try {
            Field field = RenderPipelines.class.getField("GUI_TEXTURED");
            pipeline = field.get(null);
            for (Method candidate : GuiGraphicsExtractor.class.getMethods()) {
                Class<?>[] p = candidate.getParameterTypes();
                if ("blit".equals(candidate.getName())
                        && p.length == 10
                        && p[0].isInstance(pipeline)
                        && p[1] == Identifier.class) {
                    blit = candidate;
                    break;
                }
            }
        } catch (Throwable ignored) {
            // 解析失败则退化为不绘制背景：界面仍可正常使用，不会崩
        }
        GUI_TEXTURED = pipeline;
        BLIT = blit;
    }

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
        drawBackgroundTexture(gui);
    }

    /** 以整张纹理绘制界面背景（跨 26.x 版本，走反射，见类注释）。 */
    private void drawBackgroundTexture(GuiGraphicsExtractor gui) {
        if (BLIT == null || GUI_TEXTURED == null) {
            return;
        }
        int[] nums = {this.leftPos, this.topPos, 0, 0, this.imageWidth, this.imageHeight, 256, 256};
        Class<?>[] types = BLIT.getParameterTypes();
        Object[] args = new Object[10];
        args[0] = GUI_TEXTURED;
        args[1] = TEXTURE;
        for (int i = 0; i < nums.length; i++) {
            Class<?> type = types[i + 2];
            if (type == float.class) {
                args[i + 2] = (float) nums[i];
            } else if (type == double.class) {
                args[i + 2] = (double) nums[i];
            } else {
                args[i + 2] = nums[i];
            }
        }
        try {
            BLIT.invoke(gui, args);
        } catch (Throwable ignored) {
            // 绘制失败不影响界面可用性
        }
    }
}
