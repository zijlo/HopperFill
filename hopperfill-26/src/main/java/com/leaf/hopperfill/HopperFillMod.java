package com.leaf.hopperfill;

import com.leaf.hopperfill.command.HopperFillCommand;
import com.leaf.hopperfill.data.TemplateStorage;
import com.leaf.hopperfill.tool.HoeToolHandler;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HopperFillMod implements ModInitializer {
    public static final String MOD_ID = "hopperfill";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("[HopperFill] 模组已加载！");

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            TemplateStorage.loadPlayer(server, handler.getPlayer());
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            HoeToolHandler.clearState(handler.getPlayer().getUUID());
            TemplateStorage.savePlayer(server, handler.getPlayer());
            TemplateStorage.removePlayer(handler.getPlayer().getUUID());
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            HopperFillCommand.register(dispatcher);
        });

        HoeToolHandler.register();
    }
}