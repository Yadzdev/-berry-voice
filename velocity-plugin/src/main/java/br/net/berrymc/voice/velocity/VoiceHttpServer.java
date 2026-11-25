package br.net.berrymc.voice.velocity;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

final class VoiceHttpServer {
    private record RateBucket(long startedAt, int count) { }
    private final VoiceHub hub;
    private final String secret;
    private final String corsOrigin;
    private final Gson gson = new Gson();
    private final Map<String, RateBucket> attempts = new ConcurrentHashMap<>();
    private HttpServer server;

    VoiceHttpServer(VoiceHub hub, String secret, String corsOrigin) {
        this.hub = hub; this.secret = secret; this.corsOrigin = corsOrigin;
    }

    void start(String bind, int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(bind, port), 64);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newFixedThreadPool(8, r -> { Thread t = new Thread(r, "BerryVoice-HTTP"); t.setDaemon(true); return t; }));
        server.start();
    }

    void stop() { if (server != null) server.stop(1); }

    private void handle(HttpExchange exchange) throws IOException {
        applyCors(exchange.getResponseHeaders(), exchange.getRequestHeaders().getFirst("Origin"));
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        try {
            if ("OPTIONS".equalsIgnoreCase(method)) {
                exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
                exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, X-BerryVoice-Key");
                send(exchange, 204, ""); return;
            }
            if ("GET".equals(method) && "/health".equals(path)) {
                JsonObject result = new JsonObject(); result.addProperty("status", "ok"); result.addProperty("service", "BerryVoice SVC bridge");
                sendJson(exchange, 200, result); return;
            }
            if ("POST".equals(method) && "/api/pair/consume".equals(path)) {
                String ip = clientAddress(exchange);
                if (!allowAttempt(ip)) { sendError(exchange, 429, "Muitas tentativas. Aguarde e tente novamente."); return; }
                JsonObject body = readJson(exchange);
                JsonObject result = hub.consumePair(body.has("code") ? body.get("code").getAsString() : "");
                if (result == null) { sendError(exchange, 400, "Código inválido ou expirado. Gere outro com /voz no Minecraft."); return; }
                sendJson(exchange, 200, result); return;
            }
            if ("POST".equals(method) && "/internal/pair/create".equals(path)) {
                if (!authorized(exchange)) { sendError(exchange, 401, "Não autorizado."); return; }
                JsonObject body = readJson(exchange);
                JsonObject result = hub.createPair(
                        java.util.UUID.fromString(body.get("uuid").getAsString()),
                        body.has("name") ? body.get("name").getAsString() : "Jogador",
                        body.has("serverId") ? body.get("serverId").getAsString() : ""
                );
                sendJson(exchange, 200, result); return;
            }
            sendError(exchange, 404, "Rota não encontrada.");
        } catch (IllegalArgumentException | com.google.gson.JsonParseException ex) {
            sendError(exchange, 400, ex.getMessage() == null ? "Requisição inválida." : ex.getMessage());
        } catch (IllegalStateException ex) {
            sendError(exchange, 503, ex.getMessage() == null ? "Serviço indisponível." : ex.getMessage());
        } catch (Exception ex) {
            sendError(exchange, 500, "Erro interno no serviço BerryVoice.");
        } finally { exchange.close(); }
    }

    private boolean authorized(HttpExchange exchange) {
        String supplied = exchange.getRequestHeaders().getFirst("X-BerryVoice-Key");
        if (secret == null || secret.length() < 32 || secret.startsWith("CHANGE_ME") || supplied == null) return false;
        return MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }


    private String clientAddress(HttpExchange exchange) {
        // Only trust this header when the HTTP port is firewalled so that Caddy/Nginx
        // is the sole public entry point. The bundled reverse-proxy examples set it.
        String forwarded = exchange.getRequestHeaders().getFirst("X-Real-IP");
        if (forwarded != null && forwarded.matches("[0-9a-fA-F:.]{3,64}")) return forwarded;
        return exchange.getRemoteAddress().getAddress().getHostAddress();
    }

    private synchronized boolean allowAttempt(String ip) {
        long now = System.currentTimeMillis();
        if (attempts.size() > 4096) attempts.entrySet().removeIf(e -> now - e.getValue().startedAt() > 30_000);
        RateBucket bucket = attempts.get(ip);
        if (bucket == null && attempts.size() >= 8192) return false;
        if (bucket == null || now - bucket.startedAt() > 30_000) { attempts.put(ip, new RateBucket(now, 1)); return true; }
        if (bucket.count() >= 8) return false;
        attempts.put(ip, new RateBucket(bucket.startedAt(), bucket.count() + 1)); return true;
    }

    private JsonObject readJson(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readNBytes(8193);
        if (bytes.length > 8192) throw new IllegalArgumentException("Requisição muito grande.");
        if (bytes.length == 0) return new JsonObject();
        var parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
        if (!parsed.isJsonObject()) throw new IllegalArgumentException("O corpo da requisição precisa ser um objeto JSON.");
        return parsed.getAsJsonObject();
    }

    private void applyCors(Headers headers, String origin) {
        if (origin != null && (origin.equals(corsOrigin) || origin.equals("http://localhost:5500"))) {
            headers.set("Access-Control-Allow-Origin", origin); headers.set("Vary", "Origin");
        }
        headers.set("Access-Control-Allow-Headers", "Content-Type, X-BerryVoice-Key");
        headers.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
    }

    private void sendError(HttpExchange exchange, int code, String message) throws IOException {
        JsonObject result = new JsonObject(); result.addProperty("error", message); sendJson(exchange, code, result);
    }
    private void sendJson(HttpExchange exchange, int code, Object value) throws IOException { send(exchange, code, gson.toJson(value)); }
    private void send(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        if (code == 204 || bytes.length == 0) { exchange.sendResponseHeaders(code, -1); return; }
        exchange.sendResponseHeaders(code, bytes.length); exchange.getResponseBody().write(bytes);
    }
}
