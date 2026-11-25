package br.net.berrymc.voice.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;

@Plugin(id = "berryvoice", name = "BerryVoice", version = "2.1.1", authors = {"Yadzdev"})
public final class BerryVoiceVelocity {
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private VoiceHub hub;
    private VoiceHttpServer http;
    private VoiceSocketServer websocket;

    @Inject
    public BerryVoiceVelocity(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy; this.logger = logger; this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        try {
            VoiceConfig config = new VoiceConfig(dataDirectory);
            String secret = config.get("api.secret", "CHANGE_ME_TO_A_RANDOM_SECRET_AT_LEAST_32_CHARS");
            if (secret.startsWith("CHANGE_ME") || secret.length() < 32) {
                logger.error("BerryVoice desativado: configure api.secret (mínimo 32 caracteres) em plugins/berryvoice/config.properties.");
                return;
            }
            hub = new VoiceHub(config.getInt("pair.ttl-seconds", 180), config.getInt("session.ttl-hours", 12));
            http = new VoiceHttpServer(hub, secret, config.get("cors.origin", "https://voice.berrymc.net.br"));
            http.start(config.get("api.bind", "0.0.0.0"), config.getInt("api.port", 9876));
            websocket = new VoiceSocketServer(
                    config.get("ws.bind", "0.0.0.0"), config.getInt("ws.port", 8766), hub, logger,
                    config.getInt("ws.max-message-chars", 32_768),
                    config.get("cors.origin", "https://voice.berrymc.net.br"), secret
            );
            hub.attachSocketServer(websocket);
            websocket.start();
            logger.info("BerryVoice 2.1.1 iniciado. HTTP {} | WebSocket {} | domínio {}",
                    config.getInt("api.port", 9876), config.getInt("ws.port", 8766), config.get("cors.origin", "https://voice.berrymc.net.br"));
        } catch (IOException ex) {
            logger.error("Não foi possível iniciar o BerryVoice.", ex);
        }
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        if (hub == null) return;
        // Configure Paper server-id to exactly match the name in velocity.toml.
        String serverId = event.getServer().getServerInfo().getName();
        hub.movePlayer(event.getPlayer().getUniqueId(), serverId);
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        if (hub != null) hub.disconnect(event.getPlayer().getUniqueId());
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (websocket != null) {
            try { websocket.stop(1000); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        }
        if (http != null) http.stop();
    }
}
