package com.konohaven.heads;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Stored at config/konoheads.json. Edit it by hand, or use /heads appuuid and /heads apikey in game.
 */
public class Config {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("konoheads.json");

    /** App UUID registered with minecraft-heads.com. Their API refuses requests without one. */
    public String appUuid = "";
    /** Optional minecraft-heads.com API key (Patreon license). Silver or above unlocks the full database. */
    public String apiKey = "";
    /** Re-download the head list automatically when the cache is older than this many hours. 0 = never. */
    public int cacheMaxAgeHours = 72;

    public static Config load() {
        try {
            if (Files.exists(PATH)) {
                Config c = GSON.fromJson(Files.readString(PATH), Config.class);
                if (c != null) return c;
            }
        } catch (Exception e) {
            KonoHeads.LOGGER.warn("Could not read {}, using defaults", PATH, e);
        }
        Config c = new Config();
        c.save();
        return c;
    }

    public void save() {
        try {
            Files.createDirectories(PATH.getParent());
            Files.writeString(PATH, GSON.toJson(this));
        } catch (IOException e) {
            KonoHeads.LOGGER.warn("Could not write {}", PATH, e);
        }
    }
}
