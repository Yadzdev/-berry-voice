package br.net.berrymc.voice.velocity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

final class VoiceConfig {
    private final Properties values = new Properties();

    VoiceConfig(Path folder) throws IOException {
        Files.createDirectories(folder);
        Path file = folder.resolve("config.properties");
        if (Files.notExists(file)) {
            String defaults = "# BerryVoice Velocity 2.1.1\n"
                    + "api.bind=0.0.0.0\napi.port=9876\n"
                    + "ws.bind=0.0.0.0\nws.port=8766\n"
                    + "api.secret=CHANGE_ME_TO_A_RANDOM_SECRET_AT_LEAST_32_CHARS\n"
                    + "cors.origin=https://voice.berrymc.net.br\n"
                    + "pair.ttl-seconds=180\n"
                    + "session.ttl-hours=12\n"
                    + "ws.max-message-chars=32768\n";
            Files.writeString(file, defaults);
        }
        try (var in = Files.newInputStream(file)) { values.load(in); }
    }

    String get(String key, String fallback) { return values.getProperty(key, fallback).trim(); }
    int getInt(String key, int fallback) {
        try { return Integer.parseInt(get(key, String.valueOf(fallback))); }
        catch (NumberFormatException ignored) { return fallback; }
    }
}
