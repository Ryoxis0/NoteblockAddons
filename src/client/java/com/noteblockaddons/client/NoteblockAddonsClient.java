package com.noteblockaddons.client;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.noteblockaddons.NoteblockAddons;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

public final class NoteblockAddonsClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        MediaManager.get().init();
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(NoteblockAddons.MOD_ID, "hud"), HudRenderer::render);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(commands()));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> commands() {
        MediaManager m = MediaManager.get();
        return ClientCommands.literal("note")
                .executes(c -> run(m::status))
                .then(ClientCommands.literal("toggle").executes(c -> run(m::toggleHud)))
                .then(ClientCommands.literal("lyrics").executes(c -> run(m::toggleLyrics)))
                .then(ClientCommands.literal("edit").executes(c -> run(NoteblockAddonsClient::openEditor)))
                .then(ClientCommands.literal("reset").executes(c -> run(m::resetHud)))
                .then(ClientCommands.literal("status").executes(c -> run(m::status)));
    }

    private static int run(Runnable action) {
        action.run();
        return 1;
    }

    // DO NOT call setScreen() straight from a command. I did. Four builds in a row.
    // The editor shows up, the cursor stays locked, the log says nothing. Tried releaseMouse(),
    // raw GLFW, blaming the pause screen. Turns out client commands don't run on the render
    // thread, and the cursor only unlocks when the screen is set from there. mc.execute() or suffer.
    private static void openEditor() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (!(Screens.current() instanceof HudEditorScreen)) Screens.open(new HudEditorScreen());
        });
    }
}
