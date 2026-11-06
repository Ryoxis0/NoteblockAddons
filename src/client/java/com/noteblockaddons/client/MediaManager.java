package com.noteblockaddons.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.noteblockaddons.NoteblockAddons;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Holds what's playing, its cover art and colors, lyrics, and the player's settings. */
public final class MediaManager {
    private static final MediaManager INSTANCE = new MediaManager();

    private final ExecutorService io = Executors.newVirtualThreadPerTaskExecutor();
    private final LyricsService lyrics = new LyricsService(io);

    private Settings settings;
    private volatile NowPlaying nowPlaying = NowPlaying.IDLE;

    // Cover art and its colors. Written on the render thread only.
    private volatile Identifier coverId;
    private volatile String coverKey = "";
    private volatile int primaryColor = AlbumColors.DEFAULT_PRIMARY;
    private volatile int secondaryColor = AlbumColors.DEFAULT_SECONDARY;

    private MediaManager() {}

    public static MediaManager get() {
        return INSTANCE;
    }

    public void init() {
        settings = Settings.load();
        if (WindowsMediaSource.isSupported()) {
            new WindowsMediaSource(this::onTrack, this::onArt).start();
        } else {
            nowPlaying = NowPlaying.UNSUPPORTED;
        }
    }

    public Settings settings() { return settings; }
    public NowPlaying nowPlaying() { return nowPlaying; }
    public int primaryColor() { return primaryColor; }
    public int secondaryColor() { return secondaryColor; }

    /** Cover for the current track, or null while it's loading or the track has none. */
    public Identifier cover() {
        return coverKey.equals(nowPlaying.artKey()) ? coverId : null;
    }

    public Lyrics lyrics() {
        return settings.lyricsEnabled ? lyrics.current() : Lyrics.EMPTY;
    }

    public boolean lyricsLoading() {
        return settings.lyricsEnabled && lyrics.isLoading();
    }

    private void onTrack(NowPlaying track) {
        nowPlaying = track;
        if (settings.lyricsEnabled) lyrics.update(track);
    }

    private void onArt(String key, byte[] bytes) {
        Minecraft mc = Minecraft.getInstance();
        if (bytes == null) {
            mc.execute(() -> applyArtwork(key, null, new int[]{AlbumColors.DEFAULT_PRIMARY, AlbumColors.DEFAULT_SECONDARY}));
            return;
        }
        io.submit(() -> {
            try {
                NativeImage image = decodeArtwork(bytes, appIdOf(key));
                int[] colors = AlbumColors.extract(image);
                mc.execute(() -> applyArtwork(key, image, colors));
            } catch (Exception e) {
                NoteblockAddons.LOGGER.warn("Couldn't decode cover art ({} bytes): {}", bytes.length, e.toString());
            }
        });
    }

    // NativeImage only eats PNG. Apps hand Windows JPEGs, PNGs, whatever they feel like (the
    // first version just crashed on Spotify's JPEGs). So everything goes through ImageIO, gets
    // cropped into a clean square (see CoverCrop) and is re-encoded as PNG for Minecraft.
    private static NativeImage decodeArtwork(byte[] bytes, String appId) throws IOException {
        BufferedImage buffered = ImageIO.read(new ByteArrayInputStream(bytes));
        if (buffered == null) return NativeImage.read(bytes);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(CoverCrop.crop(buffered, appId), "png", png);
        return NativeImage.read(png.toByteArray());
    }

    /** Art keys look like "Spotify.exe|title|artist|album"; the app id is the first part. */
    private static String appIdOf(String artKey) {
        int bar = artKey.indexOf('|');
        return bar < 0 ? artKey : artKey.substring(0, bar);
    }

    private void applyArtwork(String key, NativeImage image, int[] colors) {
        Minecraft mc = Minecraft.getInstance();
        Identifier previous = coverId;
        try {
            Identifier next = null;
            if (image != null) {
                next = Identifier.fromNamespaceAndPath(NoteblockAddons.MOD_ID, "cover_" + UUID.randomUUID().toString().replace('-', '_'));
                mc.getTextureManager().register(next, new DynamicTexture(() -> "noteblockaddons-cover", image));
            }
            coverId = next;
            coverKey = key;
            primaryColor = colors[0];
            secondaryColor = colors[1];
            if (previous != null) mc.getTextureManager().release(previous);
        } catch (Exception e) {
            if (image != null) image.close();
            NoteblockAddons.LOGGER.error("Failed to apply cover art", e);
        }
    }

    public void toggleHud() {
        settings.hudEnabled = !settings.hudEnabled;
        settings.save();
        notifyPlayer("HUD " + (settings.hudEnabled ? "shown" : "hidden") + ".");
    }

    public void toggleLyrics() {
        settings.lyricsEnabled = !settings.lyricsEnabled;
        settings.save();
        if (settings.lyricsEnabled) lyrics.update(nowPlaying);
        else lyrics.clear();
        notifyPlayer("Lyrics " + (settings.lyricsEnabled ? "on" : "off") + ".");
    }

    public void resetHud() {
        settings.hudX = 24f;
        settings.hudY = 24f;
        settings.hudScale = 1.0f;
        settings.save();
        notifyPlayer("HUD position and size reset.");
    }

    public void status() {
        NowPlaying np = nowPlaying;
        if (!np.hasMedia()) {
            notifyPlayer(np.title() + (np.artist().isEmpty() ? "" : " (" + np.artist() + ")"));
            return;
        }
        String by = np.artist().isEmpty() ? "" : " by " + np.artist();
        notifyPlayer((np.playing() ? "Playing " : "Paused: ") + np.title() + by + " in " + np.source());
    }

    public void notifyPlayer(String text) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) mc.player.sendSystemMessage(Component.literal("[NoteblockAddons] " + text));
        });
    }
}
