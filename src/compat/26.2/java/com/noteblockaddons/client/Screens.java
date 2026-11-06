package com.noteblockaddons.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** Minecraft 26.2 and later: the open screen moved from Minecraft to its Gui. */
final class Screens {
    private Screens() {}

    static Screen current() {
        return Minecraft.getInstance().gui.screen();
    }

    static void open(Screen screen) {
        Minecraft.getInstance().gui.setScreen(screen);
    }
}
