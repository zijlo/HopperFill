package com.zijlo.hopperfill;

import com.zijlo.hopperfill.command.HopperFillCommand;
import com.zijlo.hopperfill.data.TemplateStorage;
import com.zijlo.hopperfill.fill.FillSession;
import com.zijlo.hopperfill.gui.TemplateScreenHandler;
import com.zijlo.hopperfill.network.SettingsNetworking;
import com.zijlo.hopperfill.tool.HoeToolHandler;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HopperFillMod implements ModInitializer {
    public static final String MOD_ID = "hopperfill";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** 模板设置界面（上 5 槽 = 模板，界面内 16/64 切换按钮） */
    public static final MenuType<TemplateScreenHandler> TEMPLATE_SCREEN_HANDLER =
            Registry.register(BuiltInRegistries.MENU, Identifier.fromNamespaceAndPath(MOD_ID, "template"),
                    new MenuType<>(TemplateScreenHandler::new, FeatureFlags.VANILLA_SET));

    @Override
    public void onInitialize() {
        LOGGER.info("[HopperFill] 模组已加载！");

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            TemplateStorage.loadPlayer(server, handler.getPlayer());
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            HoeToolHandler.clearState(handler.getPlayer().getUUID());
            FillSession.cleanup(handler.getPlayer().getUUID());
            TemplateStorage.savePlayer(server, handler.getPlayer());
            TemplateStorage.removePlayer(handler.getPlayer().getUUID());
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            HopperFillCommand.register(dispatcher);
        });

        SettingsNetworking.registerServer();
        HoeToolHandler.register();
        FillSession.registerTick();
    }
}