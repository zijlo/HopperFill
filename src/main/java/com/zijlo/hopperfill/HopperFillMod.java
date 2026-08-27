package com.zijlo.hopperfill;

import com.zijlo.hopperfill.command.HopperFillCommand;
import com.zijlo.hopperfill.data.TemplateStorage;
import com.zijlo.hopperfill.fill.FillSession;
import com.zijlo.hopperfill.gui.TemplateScreenHandler;
import com.zijlo.hopperfill.network.SettingsNetworking;
import com.zijlo.hopperfill.tool.HoeToolHandler;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.resource.featuretoggle.FeatureFlags;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HopperFillMod implements ModInitializer {
    public static final String MOD_ID = "hopperfill";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** 模板设置界面（上 5 槽 = 16 堆叠模板，下 5 槽 = 64 堆叠模板） */
    public static final ScreenHandlerType<TemplateScreenHandler> TEMPLATE_SCREEN_HANDLER =
            Registry.register(Registries.SCREEN_HANDLER, Identifier.of(MOD_ID, "template"),
                    new ScreenHandlerType<>(TemplateScreenHandler::new, FeatureFlags.VANILLA_FEATURES));

    @Override
    public void onInitialize() {
        LOGGER.info("[HopperFill] 模组已加载！");

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            TemplateStorage.loadPlayer(server, handler.getPlayer());
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            HoeToolHandler.clearState(handler.getPlayer().getUuid());
            FillSession.cleanup(handler.getPlayer().getUuid());
            TemplateStorage.savePlayer(server, handler.getPlayer());
            TemplateStorage.removePlayer(handler.getPlayer().getUuid());
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            HopperFillCommand.register(dispatcher);
        });

        SettingsNetworking.registerServer();
        HoeToolHandler.register();
        FillSession.registerTick();
    }
}