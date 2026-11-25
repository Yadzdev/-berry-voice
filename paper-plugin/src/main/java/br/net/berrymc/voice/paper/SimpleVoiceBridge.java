package br.net.berrymc.voice.paper;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiolistener.PlayerAudioListener;
import de.maxhenkel.voicechat.api.audiosender.AudioSender;
import de.maxhenkel.voicechat.api.packets.SoundPacket;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Official Simple Voice Chat API adapter.
 * Java users keep using the SVC mod in Minecraft. Bedrock users use the Berry
 * website. PlayerAudioListener forwards the sound packets the Bedrock player
 * should hear; AudioSender injects the Bedrock browser microphone as that player.
 */
final class SimpleVoiceBridge implements VoicechatPlugin {
    private static final int MAX_QUEUED_FRAMES = 8;

    private static final class Attachment {
        private final VoicechatConnection connection;
        private final AudioSender sender;
        private final PlayerAudioListener listener;
        private final ArrayDeque<byte[]> queuedFrames = new ArrayDeque<>();
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private boolean streamActive;
        private long lastFrameSentNanos;

        private Attachment(VoicechatConnection connection, AudioSender sender, PlayerAudioListener listener) {
            this.connection = connection;
            this.sender = sender;
            this.listener = listener;
        }

        synchronized void enqueue(byte[] frame) {
            if (closed.get()) return;
            while (queuedFrames.size() >= MAX_QUEUED_FRAMES) queuedFrames.removeFirst();
            queuedFrames.addLast(frame);
        }

        synchronized byte[] pollFrame() {
            return closed.get() ? null : queuedFrames.pollFirst();
        }

        synchronized boolean clearFramesAndTakeActive() {
            queuedFrames.clear();
            boolean wasActive = streamActive;
            streamActive = false;
            return wasActive;
        }

        synchronized void markFrameSent(long nowNanos) {
            if (!closed.get()) {
                streamActive = true;
                lastFrameSentNanos = nowNanos;
            }
        }

        synchronized boolean expireStreamIfIdle(long nowNanos, long idleNanos) {
            if (!streamActive || nowNanos - lastFrameSentNanos < idleNanos) return false;
            streamActive = false;
            queuedFrames.clear();
            return true;
        }

        void close() { closed.set(true); synchronized (this) { queuedFrames.clear(); streamActive = false; } }
        boolean isClosed() { return closed.get(); }
    }

    private final BerryVoicePaper plugin;
    private final Map<UUID, Attachment> attachments = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> muted = new ConcurrentHashMap<>();
    private final ScheduledExecutorService audioPacer;
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private volatile VoicechatServerApi api;

    SimpleVoiceBridge(BerryVoicePaper plugin) {
        this.plugin = plugin;
        this.audioPacer = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "BerryVoice-Opus-Pacer");
            thread.setDaemon(true);
            return thread;
        });
        // SVC AudioSender expects paced microphone frames. Browser encoders may
        // deliver frames in short bursts, so send at a fixed 20 ms cadence.
        this.audioPacer.scheduleAtFixedRate(this::flushAudioFrames, 20, 20, TimeUnit.MILLISECONDS);
    }

    @Override public String getPluginId() { return "berryvoice"; }

    @Override public void registerEvents(EventRegistration registration) {
        // Server-side microphone mute for Java clients. Bedrock browser frames
        // are additionally stopped before AudioSender.send() in receiveUplink().
        registration.registerEvent(MicrophonePacketEvent.class, event -> {
            try {
                var senderConnection = event.getSenderConnection();
                if (senderConnection == null || senderConnection.getPlayer() == null) return;
                UUID senderId = senderConnection.getPlayer().getUuid();
                if (Boolean.TRUE.equals(muted.get(senderId)) && event.isCancellable()) {
                    event.cancel();
                }
            } catch (Throwable ex) {
                plugin.getLogger().warning("Falha ao aplicar mute do microfone: " + ex.getMessage());
            }
        });
    }

    @Override public void initialize(VoicechatApi voicechatApi) {
        if (voicechatApi instanceof VoicechatServerApi serverApi) {
            this.api = serverApi;
            plugin.getLogger().info("API oficial do Simple Voice Chat 2.6.x conectada à ponte BerryVoice.");
        } else {
            plugin.getLogger().severe("Simple Voice Chat não forneceu VoicechatServerApi. A ponte não será ativada.");
        }
    }

    VoicechatServerApi api() { return api; }

    void handleBackendMessage(JsonObject message) {
        String type = message.has("type") ? message.get("type").getAsString() : "";
        UUID uuid;
        try { uuid = UUID.fromString(message.has("uuid") ? message.get("uuid").getAsString() : ""); }
        catch (IllegalArgumentException ex) { return; }

        switch (type) {
            case "attach" -> runMain(() -> attach(uuid));
            case "detach" -> runMain(() -> detach(uuid));
            case "uplink" -> receiveUplink(uuid, message.has("opus") ? message.get("opus").getAsString() : "");
            case "mute" -> runMain(() -> setMuted(uuid, message.has("muted") && message.get("muted").getAsBoolean()));
            case "groups_list" -> runMain(() -> sendGroups(uuid));
            case "group_join" -> runMain(() -> joinGroupForBrowser(uuid, message.has("groupId") ? message.get("groupId").getAsString() : ""));
            default -> { }
        }
    }

    private void attach(UUID uuid) {
        // Preserve Bedrock mute preference across a browser reconnect/backend reattach.
        boolean keepMuted = Boolean.TRUE.equals(muted.get(uuid));
        detach(uuid);
        muted.put(uuid, keepMuted);
        VoicechatServerApi voiceApi = api;
        Player player = Bukkit.getPlayer(uuid);
        if (shuttingDown.get() || voiceApi == null || player == null || !player.isOnline()) {
            sendAttachError(uuid, "O jogador não está online neste servidor ou o Simple Voice Chat ainda não iniciou.");
            return;
        }
        if (!isBedrock(uuid)) {
            sendAttachError(uuid, "O portal é destinado ao Bedrock. Jogadores Java usam o mod Simple Voice Chat dentro do Minecraft.");
            return;
        }

        VoicechatConnection connection = null;
        AudioSender sender = null;
        PlayerAudioListener listener = null;
        boolean senderRegistered = false;
        boolean listenerRegistered = false;
        try {
            connection = voiceApi.getConnectionOf(uuid);
            if (connection == null) {
                sendAttachError(uuid, "O Simple Voice Chat ainda não criou a conexão deste jogador. Tente novamente em alguns segundos.");
                return;
            }
            if (connection.isInstalled()) {
                sendAttachError(uuid, "Este jogador tem um cliente Simple Voice Chat instalado. Use o áudio dentro do Minecraft.");
                return;
            }

            // Marks the Bedrock player as connected to SVC even though they do
            // not run the Java client mod. This is supported for no-mod players.
            connection.setConnected(true);
            sender = voiceApi.createAudioSender(connection);
            senderRegistered = voiceApi.registerAudioSender(sender);
            if (!senderRegistered || !sender.canSend()) {
                sendAttachError(uuid, "O Simple Voice Chat não permitiu registrar o microfone Bedrock.");
                return;
            }

            listener = voiceApi.playerAudioListenerBuilder()
                    .setPlayer(uuid)
                    .setPacketListener(packet -> forwardAudioToBrowser(uuid, packet))
                    .build();
            listenerRegistered = voiceApi.registerAudioListener(listener);
            if (!listenerRegistered) {
                sendAttachError(uuid, "Não foi possível registrar o receptor de áudio Bedrock no Simple Voice Chat.");
                return;
            }

            Attachment attachment = new Attachment(connection, sender, listener);
            attachments.put(uuid, attachment);
            muted.putIfAbsent(uuid, false);

            JsonObject attached = new JsonObject();
            attached.addProperty("type", "attached");
            attached.addProperty("uuid", uuid.toString());
            plugin.sendToRelay(attached);

            JsonObject muteState = new JsonObject();
            muteState.addProperty("type", "muted");
            muteState.addProperty("uuid", uuid.toString());
            muteState.addProperty("muted", Boolean.TRUE.equals(muted.get(uuid)));
            plugin.sendToRelay(muteState);
            plugin.getLogger().info("Ponte Simple Voice Chat ativa para Bedrock: " + player.getName());
            return;
        } catch (Throwable ex) {
            plugin.getLogger().warning("Falha ao conectar Bedrock ao Simple Voice Chat: " + ex.getMessage());
            sendAttachError(uuid, "Falha ao preparar a conexão de voz: " + safeMessage(ex));
        } finally {
            // Roll back partially registered API objects on every failed path.
            if (!attachments.containsKey(uuid)) {
                try { if (listenerRegistered && listener != null) voiceApi.unregisterAudioListener(listener); }
                catch (Throwable ignored) { }
                try {
                    if (senderRegistered && sender != null) {
                        sender.reset();
                        voiceApi.unregisterAudioSender(sender);
                    }
                } catch (Throwable ignored) { }
                try { if (connection != null) connection.setConnected(false); }
                catch (Throwable ignored) { }
            }
        }
    }

    private void forwardAudioToBrowser(UUID receiver, SoundPacket packet) {
        if (packet == null || shuttingDown.get()) return;
        Attachment attachment = attachments.get(receiver);
        if (attachment == null || attachment.isClosed()) return;
        try {
            byte[] encoded = packet.getOpusEncodedData();
            if (encoded == null || encoded.length == 0 || encoded.length > 12_000) return;
            JsonObject message = new JsonObject();
            message.addProperty("type", "downlink");
            message.addProperty("uuid", receiver.toString());
            message.addProperty("speaker", packet.getSender() == null ? "unknown" : packet.getSender().toString());
            message.addProperty("sequence", packet.getSequenceNumber());
            message.addProperty("opus", Base64.getEncoder().encodeToString(encoded));
            plugin.sendToRelay(message);
        } catch (Throwable ex) {
            plugin.getLogger().warning("Falha ao encaminhar áudio SVC para Bedrock: " + ex.getMessage());
        }
    }

    private void receiveUplink(UUID uuid, String encoded) {
        if (encoded == null || encoded.isBlank() || encoded.length() > 16_384 || Boolean.TRUE.equals(muted.get(uuid))) return;
        Attachment attachment = attachments.get(uuid);
        if (attachment == null || attachment.isClosed()) return;
        try {
            byte[] opus = Base64.getDecoder().decode(encoded);
            if (opus.length == 0 || opus.length > 12_000) return;
            attachment.enqueue(opus);
        } catch (IllegalArgumentException ignored) {
            plugin.getLogger().warning("Frame Opus inválido recebido para " + uuid);
        }
    }

    private void flushAudioFrames() {
        if (shuttingDown.get() || !plugin.isEnabled()) return;
        for (Map.Entry<UUID, Attachment> entry : attachments.entrySet()) {
            UUID uuid = entry.getKey();
            Attachment attachment = entry.getValue();
            if (attachment.isClosed()) continue;
            try {
                if (Boolean.TRUE.equals(muted.get(uuid))) {
                    if (attachment.clearFramesAndTakeActive()) attachment.sender.reset();
                    continue;
                }
                byte[] frame = attachment.pollFrame();
                if (frame == null) {
                    // Browser/WebSocket scheduling can jitter by one or two ticks.
                    // Keep the SVC stream alive briefly instead of resetting at every gap.
                    if (attachment.expireStreamIfIdle(System.nanoTime(), TimeUnit.MILLISECONDS.toNanos(120))) {
                        attachment.sender.reset();
                    }
                    continue;
                }
                if (!attachment.sender.canSend() || !attachment.sender.send(frame)) {
                    if (attachment.clearFramesAndTakeActive()) attachment.sender.reset();
                    continue;
                }
                attachment.markFrameSent(System.nanoTime());
            } catch (Throwable ex) {
                plugin.getLogger().warning("Falha ao enviar áudio Bedrock ao Simple Voice Chat: " + ex.getMessage());
            }
        }
    }

    private void setMuted(UUID uuid, boolean value) {
        muted.put(uuid, value);
        Attachment attachment = attachments.get(uuid);
        if (value && attachment != null && attachment.clearFramesAndTakeActive()) {
            try { attachment.sender.reset(); } catch (Throwable ignored) { }
        }
        JsonObject response = new JsonObject();
        response.addProperty("type", "muted");
        response.addProperty("uuid", uuid.toString());
        response.addProperty("muted", value);
        plugin.sendToRelay(response);
    }

    private void sendGroups(UUID uuid) {
        VoicechatServerApi voiceApi = api;
        if (voiceApi == null) return;
        JsonArray items = new JsonArray();
        Collection<Group> groups = voiceApi.getGroups();
        if (groups != null) {
            for (Group group : groups) {
                if (group.hasPassword() || group.isHidden()) continue;
                JsonObject item = new JsonObject();
                item.addProperty("id", group.getId().toString());
                item.addProperty("name", group.getName());
                item.addProperty("persistent", group.isPersistent());
                item.addProperty("password", group.hasPassword());
                items.add(item);
            }
        }
        JsonObject response = new JsonObject();
        response.addProperty("type", "groups");
        response.addProperty("uuid", uuid.toString());
        response.add("items", items);
        plugin.sendToRelay(response);
    }

    private void joinGroupForBrowser(UUID uuid, String groupId) {
        Attachment attachment = attachments.get(uuid);
        VoicechatServerApi voiceApi = api;
        if (attachment == null || attachment.isClosed() || voiceApi == null) return;
        Group group = null;
        if (groupId != null && !groupId.isBlank() && !"global".equalsIgnoreCase(groupId)) {
            try { group = voiceApi.getGroup(UUID.fromString(groupId)); }
            catch (IllegalArgumentException ignored) { }
            if (group == null || group.hasPassword() || group.isHidden()) {
                JsonObject error = new JsonObject();
                error.addProperty("type", "group_error");
                error.addProperty("uuid", uuid.toString());
                error.addProperty("message", "Grupo de voz não encontrado.");
                plugin.sendToRelay(error);
                return;
            }
        }
        attachment.connection.setGroup(group);
        JsonObject response = new JsonObject();
        response.addProperty("type", "group_joined");
        response.addProperty("uuid", uuid.toString());
        response.addProperty("groupId", group == null ? "global" : group.getId().toString());
        response.addProperty("groupName", group == null ? "Proximidade" : group.getName());
        plugin.sendToRelay(response);
    }

    Group createGroup(String name) {
        VoicechatServerApi voiceApi = api;
        if (voiceApi == null) throw new IllegalStateException("Simple Voice Chat ainda não está pronto.");
        String clean = name == null ? "" : name.trim();
        if (clean.length() < 3 || clean.length() > 24) throw new IllegalArgumentException("O nome do grupo deve ter de 3 a 24 caracteres.");
        return voiceApi.groupBuilder().setName(clean).setPassword(null).setPersistent(true).setHidden(false)
                .setType(Group.Type.ISOLATED).build();
    }

    boolean joinGroup(UUID uuid, String id) {
        VoicechatServerApi voiceApi = api;
        if (voiceApi == null) return false;
        VoicechatConnection connection = voiceApi.getConnectionOf(uuid);
        if (connection == null) return false;
        if (id == null || id.equalsIgnoreCase("global")) {
            connection.setGroup(null);
            notifyGroupChanged(uuid, "global", "Proximidade");
            return true;
        }
        try {
            Group group = voiceApi.getGroup(UUID.fromString(id));
            if (group == null || group.hasPassword() || group.isHidden()) return false;
            connection.setGroup(group);
            notifyGroupChanged(uuid, group.getId().toString(), group.getName());
            return true;
        } catch (IllegalArgumentException ex) { return false; }
    }

    boolean leaveGroup(UUID uuid) {
        VoicechatServerApi voiceApi = api;
        if (voiceApi == null) return false;
        VoicechatConnection connection = voiceApi.getConnectionOf(uuid);
        if (connection == null) return false;
        connection.setGroup(null);
        notifyGroupChanged(uuid, "global", "Proximidade");
        return true;
    }

    private void notifyGroupChanged(UUID uuid, String groupId, String groupName) {
        JsonObject response = new JsonObject();
        response.addProperty("type", "group_joined");
        response.addProperty("uuid", uuid.toString());
        response.addProperty("groupId", groupId);
        response.addProperty("groupName", groupName);
        plugin.sendToRelay(response);
    }

    JsonArray listGroups() {
        JsonArray items = new JsonArray();
        VoicechatServerApi voiceApi = api;
        if (voiceApi == null) return items;
        Collection<Group> groups = voiceApi.getGroups();
        if (groups != null) for (Group group : groups) {
            if (group.hasPassword() || group.isHidden()) continue;
            JsonObject item = new JsonObject();
            item.addProperty("id", group.getId().toString());
            item.addProperty("name", group.getName());
            items.add(item);
        }
        return items;
    }

    boolean toggleMute(UUID uuid) {
        boolean value = !Boolean.TRUE.equals(muted.get(uuid));
        setMuted(uuid, value);
        return value;
    }

    boolean setMutedLocally(UUID uuid, boolean value) { setMuted(uuid, value); return value; }

    void detach(UUID uuid) {
        Attachment attachment = attachments.remove(uuid);
        muted.remove(uuid);
        if (attachment == null) return;
        attachment.close();
        VoicechatServerApi voiceApi = api;
        try { attachment.sender.reset(); }
        catch (Throwable ex) { plugin.getLogger().fine("Reset de áudio ao encerrar " + uuid + ": " + ex.getMessage()); }
        if (voiceApi != null) {
            try { voiceApi.unregisterAudioListener(attachment.listener); }
            catch (Throwable ex) { plugin.getLogger().warning("Falha ao remover listener de " + uuid + ": " + ex.getMessage()); }
            try { voiceApi.unregisterAudioSender(attachment.sender); }
            catch (Throwable ex) { plugin.getLogger().warning("Falha ao remover AudioSender de " + uuid + ": " + ex.getMessage()); }
        }
        try { attachment.connection.setConnected(false); }
        catch (Throwable ex) { plugin.getLogger().warning("Falha ao atualizar conexão SVC de " + uuid + ": " + ex.getMessage()); }
    }

    void shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) return;
        audioPacer.shutdownNow();
        for (UUID uuid : attachments.keySet()) detach(uuid);
    }

    private void sendAttachError(UUID uuid, String text) {
        JsonObject error = new JsonObject();
        error.addProperty("type", "attach_error");
        error.addProperty("uuid", uuid.toString());
        error.addProperty("message", text);
        plugin.sendToRelay(error);
    }

    private boolean isBedrock(UUID uuid) {
        if (!Bukkit.getPluginManager().isPluginEnabled("floodgate")) return false;
        try { return FloodgateApi.getInstance().isFloodgatePlayer(uuid); }
        catch (Throwable ex) { return false; }
    }

    private void runMain(Runnable runnable) {
        if (!plugin.isEnabled() || shuttingDown.get()) return;
        Bukkit.getScheduler().runTask(plugin, runnable);
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }
}
