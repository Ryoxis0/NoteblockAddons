package com.noteblockaddons.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** Drag to move, scroll to resize. Doesn't pause the game, so you can keep walking. */
public final class HudEditorScreen extends Screen {
    private static final float MARGIN = 4f;
    private static final float MIN_SCALE = 0.35f;
    private static final float MAX_SCALE = 2.0f;

    private boolean dragging;
    private double dragOffsetX;
    private double dragOffsetY;

    public HudEditorScreen() {
        super(Component.literal("NoteblockAddons HUD Editor"));
    }

    private static Settings settings() {
        return MediaManager.get().settings();
    }

    @Override
    protected void init() {
        settings().hudEnabled = true;
        clampToScreen();
        Minecraft.getInstance().mouseHandler.releaseMouse();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
        super.tick();
        // belt and braces from the cursor saga (see NoteblockAddonsClient#openEditor)
        Minecraft.getInstance().mouseHandler.releaseMouse();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        Settings s = settings();
        HudRenderer.draw(graphics, s.hudX, s.hudY, s.hudScale, true);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (click.button() == 0 && insideHud(click.x(), click.y())) {
            dragging = true;
            dragOffsetX = click.x() - settings().hudX;
            dragOffsetY = click.y() - settings().hudY;
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        if (click.button() == 0) dragging = false;
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double dragX, double dragY) {
        if (!dragging || click.button() != 0) return super.mouseDragged(click, dragX, dragY);
        settings().hudX = (float) (click.x() - dragOffsetX);
        settings().hudY = (float) (click.y() - dragOffsetY);
        clampToScreen();
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (!insideHud(mouseX, mouseY)) return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        Settings s = settings();
        s.hudScale = Math.clamp(s.hudScale + (float) verticalAmount * 0.05f, MIN_SCALE, MAX_SCALE);
        clampToScreen();
        return true;
    }

    @Override
    public void onClose() {
        settings().save();
        Minecraft mc = Minecraft.getInstance();
        Screens.open(null);
        mc.mouseHandler.grabMouse();
    }

    private void clampToScreen() {
        Settings s = settings();
        float maxX = Math.max(MARGIN, width - HudRenderer.WIDTH * s.hudScale - MARGIN);
        float maxY = Math.max(MARGIN, height - HudRenderer.height() * s.hudScale - MARGIN);
        s.hudX = Math.clamp(s.hudX, MARGIN, maxX);
        s.hudY = Math.clamp(s.hudY, MARGIN, maxY);
    }

    private static boolean insideHud(double mouseX, double mouseY) {
        Settings s = settings();
        return mouseX >= s.hudX && mouseX <= s.hudX + HudRenderer.WIDTH * s.hudScale
                && mouseY >= s.hudY && mouseY <= s.hudY + HudRenderer.height() * s.hudScale;
    }
}
