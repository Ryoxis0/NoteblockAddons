package com.noteblockaddons.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.noteblockaddons.NoteblockAddons;
import net.minecraft.client.Minecraft;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Settings saved to config/noteblockaddons.json. */
public final class Settings {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE = "noteblockaddons.json";
    private static final String LEGACY_FILE = "mcspotify.json";

    public boolean hudEnabled = true;
    public boolean lyricsEnabled = true;
    public float hudX = 24f;
    public float hudY = 24f;
    public float hudScale = 1.0f;

    public static Settings load() {
        Path path = configDir().resolve(FILE);
        boolean legacy = false;
        if (Files.notExists(path) && Files.exists(configDir().resolve(LEGACY_FILE))) {
            path = configDir().resolve(LEGACY_FILE); // keep HUD position from before the rename
            legacy = true;
        }
        Settings settings = null;
        try {
            if (Files.exists(path)) {
                try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    settings = GSON.fromJson(reader, Settings.class);
                }
            }
        } catch (Exception e) {
            NoteblockAddons.LOGGER.error("Failed to load {}", path.getFileName(), e);
        }
        if (settings == null) settings = new Settings();
        settings.save(); // rewrites the file without fields that no longer exist (old Spotify tokens)
        if (legacy) {
            try {
                Files.deleteIfExists(configDir().resolve(LEGACY_FILE)); // it held old Spotify login tokens
            } catch (Exception ignored) {
            }
        }
        return settings;
    }

    public synchronized void save() {
        Path path = configDir().resolve(FILE);
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            }
        } catch (Exception e) {
            NoteblockAddons.LOGGER.error("Failed to save {}", FILE, e);
        }
    }

    private static Path configDir() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config");
    }
}
