package br.net.berrymc.voice.velocity;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Public browser sockets and authenticated Paper-backend sockets share one listener. */
final class VoiceSocketServer extends WebSocketServer {
    private record Browser(VoiceHub.WebSession session) { }

    private final VoiceHub hub;
    private final Logger logger;
    private final int maxMessageChars;
    private final String allowedOrigin;
    private final String backendSecret;
    private final Gson gson = new Gson();
    private final Map<WebSocket, Browser> browsers = new ConcurrentHashMap<>();
    private final Set<WebSocket> pendingBrowsers = ConcurrentHashMap.newKeySet();
    private final Set<WebSocket> pendingBackends = ConcurrentHashMap.newKeySet();
    private final Map<UUID, WebSocket> browserByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, String> selectedGroups = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> mutedPlayers = new ConcurrentHashMap<>();
    private final Map<WebSocket, String> backendBySocket = new ConcurrentHashMap<>();
    private final Map<String, WebSocket> backendById = new ConcurrentHashMap<>();

    VoiceSocketServer(String bind, int port, VoiceHub hub, Logger logger,
                      int maxMessageChars, String allowedOrigin, String backendSecret) {
        super(new InetSocketAddress(bind, port));
        this.hub = hub;
        this.logger = logger;
        this.maxMessageChars = Math.max(4096, maxMessageChars);
        this.allowedOrigin = allowedOrigin;
        this.backendSecret = backendSecret;
        setReuseAddr(true);
        setConnectionLostTimeout(30);
    }

    @Override public void onOpen(WebSocket conn, ClientHandshake handshake) {
        String path = handshake.getResourceDescriptor();
        if ("/backend".equals(path)) {
            pendingBackends.add(conn);
            return;
        }
        String origin = handshake.getFieldValue("Origin");
        if ("/ws".equals(path) && (allowedOrigin.equals(origin) || "http://localhost:5500".equals(origin))) {
            pendingBrowsers.add(conn);
            return;
        }
        conn.close(1008, "Origem ou rota não permitida");
    }

    @Override public void onMessage(WebSocket conn, String raw) {
        if (raw == null || raw.length() > maxMessageChars) { conn.close(1009, "Mensagem muito grande"); return; }
        try {
            JsonObject json = JsonParser.parseString(raw).getAsJsonObject();
            String type = string(json, "type", "");

            String backendId = backendBySocket.get(conn);
            if (backendId != null) {
                handleBackendMessage(conn, backendId, json, type);
                return;
            }
            if (pendingBackends.contains(conn)) {
                if ("backend_auth".equals(type)) authenticateBackend(conn, json);
                else conn.close(1008, "Autenticação de backend necessária");
                return;
            }
            if (pendingBrowsers.contains(conn)) {
                if ("auth".equals(type)) authenticateBrowser(conn, json);
                else conn.close(1008, "Autenticação necessária");
                return;
            }
            Browser browser = browsers.get(conn);
            if (browser == null) { conn.close(1008, "Conexão não autenticada"); return; }
            if (hub.authenticate(browser.session().token()) == null) { conn.close(1008, "Sessão expirada"); return; }

            switch (type) {
                case "uplink" -> {
                    String opus = string(json, "opus", "");
                    if (opus.isBlank() || opus.length() > 16_384) return;
                    JsonObject forward = new JsonObject();
                    forward.addProperty("type", "uplink");
                    forward.addProperty("uuid", browser.session().uuid().toString());
                    forward.addProperty("opus", opus);
                    sendToBackend(browser.session().serverId(), forward);
                }
                case "mute" -> {
                    boolean muted = json.has("muted") && json.get("muted").getAsBoolean();
                    mutedPlayers.put(browser.session().uuid(), muted);
                    JsonObject forward = new JsonObject(); forward.addProperty("type", "mute");
                    forward.addProperty("uuid", browser.session().uuid().toString());
                    forward.addProperty("muted", muted);
                    sendToBackend(browser.session().serverId(), forward);
                }
                case "groups_list" -> {
                    JsonObject forward = new JsonObject(); forward.addProperty("type", "groups_list");
                    forward.addProperty("uuid", browser.session().uuid().toString());
                    sendToBackend(browser.session().serverId(), forward);
                }
                case "group_join" -> {
                    String groupId = string(json, "groupId", "global");
                    selectedGroups.put(browser.session().uuid(), groupId.isBlank() ? "global" : groupId);
                    JsonObject forward = new JsonObject(); forward.addProperty("type", "group_join");
                    forward.addProperty("uuid", browser.session().uuid().toString());
                    forward.addProperty("groupId", selectedGroups.get(browser.session().uuid()));
                    sendToBackend(browser.session().serverId(), forward);
                }
                case "ping" -> { JsonObject pong = new JsonObject(); pong.addProperty("type", "pong"); conn.send(gson.toJson(pong)); }
                default -> sendError(conn, "Comando de voz desconhecido.");
            }
        } catch (Exception ex) {
            sendError(conn, "Mensagem inválida.");
        }
    }

    private void authenticateBackend(WebSocket conn, JsonObject json) {
        if (backendBySocket.containsKey(conn)) return;
        String supplied = string(json, "secret", "");
        String serverId = string(json, "serverId", "");
        if (!constantTimeEquals(backendSecret, supplied) || backendSecret == null || backendSecret.length() < 32
                || !serverId.matches("[A-Za-z0-9_-]{1,32}")) {
            conn.close(1008, "Credencial de backend inválida");
            return;
        }
        WebSocket previous = backendById.put(serverId, conn);
        if (previous != null && previous != conn) previous.close(4004, "Backend substituído por nova conexão");
        pendingBackends.remove(conn);
        backendBySocket.put(conn, serverId);
        JsonObject ready = new JsonObject(); ready.addProperty("type", "backend_ready"); ready.addProperty("serverId", serverId);
        conn.send(gson.toJson(ready));
        logger.info("BerryVoice: backend Paper autenticado: " + serverId);
        // Reattach browser sessions that were waiting while this backend reconnected.
        for (Map.Entry<UUID, WebSocket> entry : browserByPlayer.entrySet()) {
            WebSocket browserSocket = entry.getValue();
            Browser active = browsers.get(browserSocket);
            if (active != null && active.session().serverId().equals(serverId)) {
                sendAttach(conn, active.session());
            }
        }
    }

    private void authenticateBrowser(WebSocket conn, JsonObject json) {
        if (browsers.containsKey(conn)) return;
        String token = string(json, "token", "");
        VoiceHub.WebSession session = hub.authenticate(token);
        if (session == null) { sendError(conn, "Sessão inválida ou expirada. Gere um novo código com /voz."); conn.close(1008, "Sessão inválida"); return; }
        WebSocket backend = backendById.get(session.serverId());
        if (backend == null || !backend.isOpen()) {
            sendError(conn, "O servidor de jogo ainda não está conectado ao relay Berry Voice.");
            conn.close(1013, "Backend indisponível");
            return;
        }
        WebSocket old = browserByPlayer.put(session.uuid(), conn);
        if (old != null && old != conn) old.close(4001, "Nova conexão iniciada");
        pendingBrowsers.remove(conn);
        browsers.put(conn, new Browser(session));
        selectedGroups.putIfAbsent(session.uuid(), "global");
        mutedPlayers.putIfAbsent(session.uuid(), false);
        sendAttach(backend, session);
    }

    private void sendAttach(WebSocket backend, VoiceHub.WebSession session) {
        if (backend == null || !backend.isOpen()) return;
        JsonObject attach = new JsonObject();
        attach.addProperty("type", "attach");
        attach.addProperty("uuid", session.uuid().toString());
        attach.addProperty("name", session.name());
        try { backend.send(gson.toJson(attach)); }
        catch (Exception ex) { logger.warn("Não foi possível vincular sessão Bedrock ao backend: " + ex.getMessage()); }
    }

    boolean hasBackend(String serverId) {
        WebSocket backend = backendById.get(serverId);
        return backend != null && backend.isOpen();
    }

    void movePlayer(VoiceHub.WebSession movedSession) {
        WebSocket browser = browserByPlayer.get(movedSession.uuid());
        if (browser == null || !browser.isOpen()) return;
        Browser oldBrowser = browsers.get(browser);
        if (oldBrowser == null) return;
        String oldServer = oldBrowser.session().serverId();
        if (oldServer.equals(movedSession.serverId())) return;
        browsers.put(browser, new Browser(movedSession));

        JsonObject detach = new JsonObject();
        detach.addProperty("type", "detach");
        detach.addProperty("uuid", movedSession.uuid().toString());
        sendToBackend(oldServer, detach);

        WebSocket newBackend = backendById.get(movedSession.serverId());
        if (newBackend != null && newBackend.isOpen()) {
            sendAttach(newBackend, movedSession);
        } else {
            JsonObject waiting = new JsonObject();
            waiting.addProperty("type", "status");
            waiting.addProperty("message", "Servidor alterado. Aguardando o backend de voz reconectar...");
            browser.send(gson.toJson(waiting));
        }
    }

    private void handleBackendMessage(WebSocket conn, String serverId, JsonObject json, String type) {
        if (serverId == null || !conn.isOpen()) return;
        UUID playerId;
        try { playerId = UUID.fromString(string(json, "uuid", "")); }
        catch (IllegalArgumentException ex) { return; }
        WebSocket browser = browserByPlayer.get(playerId);
        if (browser == null || !browser.isOpen()) return;
        Browser active = browsers.get(browser);
        if (active == null || !active.session().serverId().equals(serverId)) return;

        switch (type) {
            case "attached" -> {
                JsonObject ready = new JsonObject(); ready.addProperty("type", "ready"); ready.addProperty("player", active.session().name());
                browser.send(gson.toJson(ready));
                JsonObject groups = new JsonObject(); groups.addProperty("type", "groups_list"); groups.addProperty("uuid", playerId.toString());
                sendToBackend(serverId, groups);
                JsonObject group = new JsonObject(); group.addProperty("type", "group_join");
                group.addProperty("uuid", playerId.toString());
                group.addProperty("groupId", selectedGroups.getOrDefault(playerId, "global"));
                sendToBackend(serverId, group);
                JsonObject mute = new JsonObject(); mute.addProperty("type", "mute");
                mute.addProperty("uuid", playerId.toString());
                mute.addProperty("muted", mutedPlayers.getOrDefault(playerId, false));
                sendToBackend(serverId, mute);
            }
            case "attach_error" -> {
                sendError(browser, string(json, "message", "Não foi possível vincular o jogador ao Simple Voice Chat."));
                browser.close(1011, "Falha ao vincular jogador");
            }
            case "downlink" -> {
                JsonObject audio = new JsonObject(); audio.addProperty("type", "audio");
                audio.addProperty("speaker", string(json, "speaker", ""));
                audio.addProperty("sequence", json.has("sequence") ? json.get("sequence").getAsLong() : 0L);
                audio.addProperty("opus", string(json, "opus", ""));
                browser.send(gson.toJson(audio));
            }
            case "groups" -> {
                JsonObject groups = new JsonObject(); groups.addProperty("type", "groups");
                groups.add("items", json.has("items") ? json.getAsJsonArray("items") : new com.google.gson.JsonArray());
                browser.send(gson.toJson(groups));
            }
            case "group_joined" -> {
                String groupId = string(json, "groupId", "global");
                selectedGroups.put(playerId, groupId.isBlank() ? "global" : groupId);
                JsonObject state = new JsonObject(); state.addProperty("type", "group_joined");
                state.addProperty("groupId", selectedGroups.get(playerId));
                state.addProperty("groupName", string(json, "groupName", "Proximidade"));
                browser.send(gson.toJson(state));
            }
            case "muted" -> {
                boolean muted = json.has("muted") && json.get("muted").getAsBoolean();
                mutedPlayers.put(playerId, muted);
                JsonObject state = new JsonObject(); state.addProperty("type", "muted");
                state.addProperty("muted", muted);
                browser.send(gson.toJson(state));
            }
            case "group_error" -> sendError(browser, string(json, "message", "Não foi possível entrar no grupo."));
            default -> { }
        }
    }

    private void sendToBackend(String serverId, JsonObject message) {
        WebSocket backend = backendById.get(serverId);
        if (backend == null || !backend.isOpen()) return;
        try { backend.send(gson.toJson(message)); }
        catch (Exception ex) { logger.warn("Falha ao encaminhar evento para backend " + serverId + ": " + ex.getMessage()); }
    }

    void disconnectPlayer(UUID uuid) {
        WebSocket browser = browserByPlayer.remove(uuid);
        if (browser == null) return;
        Browser session = browsers.remove(browser);
        if (session != null) {
            JsonObject detach = new JsonObject();
            detach.addProperty("type", "detach");
            detach.addProperty("uuid", uuid.toString());
            sendToBackend(session.session().serverId(), detach);
        }
        selectedGroups.remove(uuid);
        mutedPlayers.remove(uuid);
        if (browser.isOpen()) browser.close(4002, "Jogador saiu do servidor");
    }

    private void sendError(WebSocket conn, String message) {
        if (conn == null || !conn.isOpen()) return;
        JsonObject error = new JsonObject(); error.addProperty("type", "error"); error.addProperty("message", message);
        conn.send(gson.toJson(error));
    }

    @Override public void onMessage(WebSocket conn, ByteBuffer message) {
        if (message != null && message.remaining() > maxMessageChars) conn.close(1009, "Pacote muito grande");
        else conn.close(1003, "Este relay utiliza mensagens Opus encapsuladas em JSON.");
    }

    @Override public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        pendingBrowsers.remove(conn);
        pendingBackends.remove(conn);

        Browser browser = browsers.remove(conn);
        if (browser != null) {
            // A replaced/old socket must never detach the newer active session.
            boolean wasActive = browserByPlayer.remove(browser.session().uuid(), conn);
            if (wasActive) {
                JsonObject detach = new JsonObject();
                detach.addProperty("type", "detach");
                detach.addProperty("uuid", browser.session().uuid().toString());
                sendToBackend(browser.session().serverId(), detach);
                selectedGroups.remove(browser.session().uuid());
                mutedPlayers.remove(browser.session().uuid());
            }
        }

        String serverId = backendBySocket.remove(conn);
        if (serverId != null) {
            // A replaced backend socket must not tear down sessions using its replacement.
            boolean wasCurrent = backendById.remove(serverId, conn);
            if (wasCurrent) {
                for (Map.Entry<UUID, WebSocket> entry : browserByPlayer.entrySet()) {
                    Browser active = browsers.get(entry.getValue());
                    if (active != null && active.session().serverId().equals(serverId)) {
                        JsonObject waiting = new JsonObject();
                        waiting.addProperty("type", "status");
                        waiting.addProperty("message", "Conexão do servidor interrompida. Aguardando reconexão...");
                        if (entry.getValue().isOpen()) entry.getValue().send(gson.toJson(waiting));
                    }
                }
                logger.warn("BerryVoice: backend desconectado: " + serverId);
            }
        }
    }

    @Override public void onError(WebSocket conn, Exception ex) {
        if (ex != null) logger.warn("BerryVoice WebSocket: " + ex.getMessage());
    }

    @Override public void onStart() { logger.info("BerryVoice WebSocket ativo."); }

    private static String string(JsonObject json, String key, String fallback) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : fallback;
    }

    private static boolean constantTimeEquals(String expected, String supplied) {
        if (expected == null || supplied == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }
}
