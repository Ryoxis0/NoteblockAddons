package com.noteblockaddons.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** Minecraft 26.1: the open screen lives on Minecraft itself. */
final class Screens {
    private Screens() {}

    static Screen current() {
        return Minecraft.getInstance().screen;
    }

    static void open(Screen screen) {
        Minecraft.getInstance().setScreen(screen);
    }
}
