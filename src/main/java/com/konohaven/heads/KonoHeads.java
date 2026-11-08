package com.konohaven.heads;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

public class KonoHeads implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("konoheads");
    public static Config CONFIG;
    public static final HeadDatabase DB = new HeadDatabase();

    private static KeyMapping openKey;
    /** Screen to open on the next tick (opening directly from a chat command gets overridden by chat closing). */
    private static String pendingQuery = null;

    @Override
    public void onInitializeClient() {
        CONFIG = Config.load();

        openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.konoheads.open", InputConstants.KEY_H, KeyMapping.Category.CREATIVE));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (openKey.consumeClick()) {
                if (mc.gui.screen() == null) mc.setScreenAndShow(new HeadSearchScreen(null));
            }
            if (pendingQuery != null) {
                String q = pendingQuery;
                pendingQuery = null;
                mc.setScreenAndShow(new HeadSearchScreen(q));
            }
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) -> dispatcher.register(literal("heads")
                .executes(c -> {
                    pendingQuery = "";
                    return 1;
                })
                .then(literal("search").then(argument("query", StringArgumentType.greedyString()).executes(c -> {
                    pendingQuery = StringArgumentType.getString(c, "query");
                    return 1;
                })))
                .then(literal("appuuid").then(argument("uuid", StringArgumentType.greedyString()).executes(c -> {
                    String v = StringArgumentType.getString(c, "uuid").trim();
                    if (!HeadDatabase.validAppUuid(v)) {
                        c.getSource().sendError(Component.literal("That isn't a valid UUID."));
                        return 0;
                    }
                    CONFIG.appUuid = v;
                    CONFIG.save();
                    c.getSource().sendFeedback(Component.literal("App UUID saved. Run /heads refresh to download."));
                    return 1;
                })))
                .then(literal("apikey").then(argument("key", StringArgumentType.greedyString()).executes(c -> {
                    String v = StringArgumentType.getString(c, "key").trim();
                    if (!HeadDatabase.validApiKey(v)) {
                        c.getSource().sendError(Component.literal("That API key has invalid characters."));
                        return 0;
                    }
                    CONFIG.apiKey = v;
                    CONFIG.save();
                    c.getSource().sendFeedback(Component.literal("API key saved. Run /heads refresh to re-download."));
                    return 1;
                })))
                .then(literal("refresh").executes(c -> {
                    DB.refresh().thenRun(() -> Minecraft.getInstance().execute(() -> {
                        if (Minecraft.getInstance().player != null) {
                            Minecraft.getInstance().player.sendSystemMessage(Component.literal("[Heads] " + DB.status()));
                        }
                    }));
                    c.getSource().sendFeedback(Component.literal("[Heads] " + DB.status()));
                    return 1;
                }))));
    }
}
