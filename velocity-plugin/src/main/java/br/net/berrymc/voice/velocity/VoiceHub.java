package br.net.berrymc.voice.velocity;

import com.google.gson.JsonObject;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/** Pairing/session authority for all Paper backends connected to this proxy. */
final class VoiceHub {
    private static final String ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final SecureRandom RANDOM = new SecureRandom();

    record Pair(UUID uuid, String name, String serverId, long expiresAt) { }
    record WebSession(String token, UUID uuid, String name, String serverId, long expiresAt) { }

    private final Map<String, Pair> pairs = new ConcurrentHashMap<>();
    private final Map<String, WebSession> sessions = new ConcurrentHashMap<>();
    private final int pairTtlSeconds;
    private final int sessionTtlHours;
    private volatile VoiceSocketServer socketServer;

    VoiceHub(int pairTtlSeconds, int sessionTtlHours) {
        this.pairTtlSeconds = Math.max(30, pairTtlSeconds);
        this.sessionTtlHours = Math.max(1, sessionTtlHours);
    }

    void attachSocketServer(VoiceSocketServer server) { this.socketServer = server; }

    synchronized JsonObject createPair(UUID uuid, String name, String serverId) {
        cleanup();
        if (serverId == null || !serverId.matches("[A-Za-z0-9_-]{1,32}")) {
            throw new IllegalArgumentException("Identificador do backend inválido.");
        }
        VoiceSocketServer ws = socketServer;
        if (ws == null || !ws.hasBackend(serverId)) {
            throw new IllegalStateException("O backend '" + serverId + "' ainda não está conectado ao relay BerryVoice.");
        }
        pairs.entrySet().removeIf(entry -> entry.getValue().uuid().equals(uuid));
        String code;
        do { code = randomCode(); } while (pairs.containsKey(code));
        long expires = Instant.now().getEpochSecond() + pairTtlSeconds;
        pairs.put(code, new Pair(uuid, safeName(name), serverId, expires));
        JsonObject result = new JsonObject();
        result.addProperty("code", code);
        result.addProperty("expiresIn", pairTtlSeconds);
        result.addProperty("siteUrl", "https://voice.berrymc.net.br");
        result.addProperty("serverId", serverId);
        return result;
    }

    synchronized JsonObject consumePair(String rawCode) {
        cleanup();
        String code = rawCode == null ? "" : rawCode.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
        Pair pair = pairs.remove(code);
        if (pair == null || pair.expiresAt() < Instant.now().getEpochSecond()) return null;
        String token = randomToken();
        long expires = Instant.now().getEpochSecond() + sessionTtlHours * 3600L;
        WebSession session = new WebSession(token, pair.uuid(), pair.name(), pair.serverId(), expires);
        sessions.entrySet().removeIf(entry -> entry.getValue().uuid().equals(pair.uuid()));
        sessions.put(token, session);
        JsonObject result = new JsonObject();
        result.addProperty("token", token);
        result.addProperty("player", pair.name());
        result.addProperty("uuid", pair.uuid().toString());
        result.addProperty("serverId", pair.serverId());
        result.addProperty("expiresIn", sessionTtlHours * 3600L);
        return result;
    }

    WebSession authenticate(String token) {
        WebSession session = sessions.get(token);
        if (session == null || session.expiresAt() < Instant.now().getEpochSecond()) {
            if (session != null) sessions.remove(token);
            return null;
        }
        return session;
    }

    synchronized void movePlayer(UUID uuid, String serverId) {
        if (serverId == null || !serverId.matches("[A-Za-z0-9_-]{1,32}")) return;
        for (String code : pairs.keySet()) {
            pairs.computeIfPresent(code, (ignored, pair) -> pair.uuid().equals(uuid)
                    ? new Pair(pair.uuid(), pair.name(), serverId, pair.expiresAt()) : pair);
        }
        for (String token : sessions.keySet()) {
            WebSession moved = sessions.computeIfPresent(token, (ignored, session) -> {
                if (!session.uuid().equals(uuid) || session.serverId().equals(serverId)) return session;
                return new WebSession(session.token(), session.uuid(), session.name(), serverId, session.expiresAt());
            });
            if (moved != null && moved.uuid().equals(uuid) && moved.serverId().equals(serverId)) {
                VoiceSocketServer ws = socketServer;
                if (ws != null) ws.movePlayer(moved);
            }
        }
    }

    void disconnect(UUID uuid) {
        sessions.entrySet().removeIf(entry -> entry.getValue().uuid().equals(uuid));
        pairs.entrySet().removeIf(entry -> entry.getValue().uuid().equals(uuid));
        VoiceSocketServer ws = socketServer;
        if (ws != null) ws.disconnectPlayer(uuid);
    }

    private void cleanup() {
        long now = Instant.now().getEpochSecond();
        pairs.entrySet().removeIf(entry -> entry.getValue().expiresAt() < now);
        sessions.entrySet().removeIf(entry -> entry.getValue().expiresAt() < now);
    }

    private static String safeName(String name) {
        if (name == null || name.isBlank()) return "Jogador";
        String sanitized = name.replaceAll("[^A-Za-z0-9_ .-]", "");
        if (sanitized.isBlank()) return "Jogador";
        return sanitized.substring(0, Math.min(32, sanitized.length()));
    }

    private static String randomCode() {
        StringBuilder out = new StringBuilder(8);
        for (int i = 0; i < 8; i++) out.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return out.toString();
    }

    private static String randomToken() {
        byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
