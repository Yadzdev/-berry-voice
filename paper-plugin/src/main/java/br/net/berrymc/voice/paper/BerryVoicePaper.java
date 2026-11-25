package br.net.berrymc.voice.paper;

import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import de.maxhenkel.voicechat.api.Group;
import java.util.concurrent.CompletionException;

public final class BerryVoicePaper extends JavaPlugin implements Listener, TabExecutor {
    private PaperApiClient api;
    private SimpleVoiceBridge voiceBridge;
    private BackendRelayClient relay;
    private String serverId;
    private org.bukkit.configuration.file.YamlConfiguration messages;
    private org.bukkit.configuration.file.YamlConfiguration menus;
    private String siteUrl;

    @Override public void onEnable() {
        saveDefaultConfig();
        saveIfMissing("messages.yml");
        saveIfMissing("menus.yml");
        reloadCustomFiles();
        siteUrl = getConfig().getString("site-url", "https://voice.berrymc.net.br");
        serverId = getConfig().getString("server-id", "lobby");
        String sharedSecret = getConfig().getString("api.secret", "CHANGE_ME_TO_A_RANDOM_SECRET_AT_LEAST_32_CHARS");
        if (sharedSecret == null || sharedSecret.startsWith("CHANGE_ME") || sharedSecret.length() < 32) {
            getLogger().severe("BerryVoice desativado: configure api.secret com a mesma chave de plugins/berryvoice/config.properties no Velocity (mínimo 32 caracteres).");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        if (serverId == null || !serverId.matches("[A-Za-z0-9_-]{1,32}")) {
            getLogger().severe("BerryVoice desativado: server-id deve conter apenas letras, números, _ ou - e ter até 32 caracteres.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        api = new PaperApiClient(getConfig().getString("api.base-url", "http://127.0.0.1:9876"), sharedSecret);
        voiceBridge = new SimpleVoiceBridge(this);
        BukkitVoicechatService voiceService = getServer().getServicesManager().load(BukkitVoicechatService.class);
        if (voiceService == null) {
            getLogger().severe("Simple Voice Chat não foi encontrado. Instale o plugin Simple Voice Chat 2.6.25+ antes do BerryVoice.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        voiceService.registerPlugin(voiceBridge);
        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("voz") != null) {
            getCommand("voz").setExecutor(this);
            getCommand("voz").setTabCompleter(this);
        }
        String relayUrl = getConfig().getString("relay.websocket-url", "ws://127.0.0.1:8766/backend");
        relay = new BackendRelayClient(this, relayUrl, serverId, sharedSecret);
        Bukkit.getScheduler().runTaskLaterAsynchronously(this, () -> { if (relay != null) relay.ensureConnected(); }, 1L);
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            if (relay != null && !relay.isAuthenticated()) relay.ensureConnected();
        }, 20L, 100L);
        getLogger().info("BerryVoice Paper habilitado. Simple Voice Chat API registrada. Floodgate: " + isFloodgateAvailable());
    }

    @Override public void onDisable() {
        Bukkit.getScheduler().cancelTasks(this);
        if (voiceBridge != null) voiceBridge.shutdown();
        if (relay != null) { try { relay.close(); } catch (Throwable ignored) { } }
    }

    void sendToRelay(JsonObject message) {
        BackendRelayClient current = relay;
        if (current != null) current.sendJson(message);
    }

    void handleBackendMessage(JsonObject message) {
        if (voiceBridge != null) voiceBridge.handleBackendMessage(message);
    }

    String serverId() { return serverId; }
    SimpleVoiceBridge voiceBridge() { return voiceBridge; }

    private void saveIfMissing(String name) {
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        if (!new java.io.File(getDataFolder(), name).exists()) saveResource(name, false);
    }

    private void reloadCustomFiles() {
        messages = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new java.io.File(getDataFolder(), "messages.yml"));
        menus = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new java.io.File(getDataFolder(), "menus.yml"));
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        if (!getConfig().getBoolean("welcome.enabled", true)) return;
        Bukkit.getScheduler().runTaskLater(this, () -> {
            Player player = event.getPlayer();
            String section = isFloodgatePlayer(player) ? "join-bedrock" : "join-java";
            List<String> lines = messages.getStringList(section);
            if (lines.isEmpty()) lines = messages.getStringList("join");
            for (String line : lines) player.sendMessage(color(line.replace("{site}", siteUrl).replace("{command}", "/voz")));
        }, 30L);
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        if (voiceBridge != null) voiceBridge.detach(event.getPlayer().getUniqueId());
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("Este comando só pode ser usado por um jogador."); return true; }
        if (args.length == 0) { openMainMenu(player); return true; }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "conectar", "connect" -> generateCode(player);
            case "mute", "mutar" -> setMute(player, true);
            case "desmutar", "unmute" -> setMute(player, false);
            case "alternar", "toggle" -> toggleMute(player);
            case "grupos", "groups" -> openGroups(player);
            case "criar" -> {
                if (args.length < 2) { send(player, "usage.create"); return true; }
                createGroup(player, String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)));
            }
            case "entrar", "join" -> {
                if (args.length < 2) { send(player, "usage.join"); return true; }
                joinGroup(player, args[1]);
            }
            case "sair", "leave" -> leaveGroup(player);
            case "reload" -> {
                if (!player.hasPermission("berryvoice.admin")) { send(player, "no-permission"); return true; }
                reloadConfig(); reloadCustomFiles(); siteUrl = getConfig().getString("site-url", siteUrl);
                send(player, "reloaded");
            }
            default -> openMainMenu(player);
        }
        return true;
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("conectar", "mute", "desmutar", "alternar", "grupos", "criar", "entrar", "sair", "reload").stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        return List.of();
    }

    private void openMainMenu(Player player) {
        if (isFloodgatePlayer(player)) { openBedrockMain(player); return; }
        int size = menus.getInt("main.size", 27); if (size < 9 || size > 54 || size % 9 != 0) size = 27;
        String title = color(menus.getString("main.title", "&8Berry Voice"));
        VoiceMenuHolder holder = new VoiceMenuHolder();
        Inventory inventory = Bukkit.createInventory(holder, size, title); holder.setInventory(inventory);
        ConfigurationSection items = menus.getConfigurationSection("main.items");
        if (items != null) for (String key : items.getKeys(false)) {
            ConfigurationSection item = items.getConfigurationSection(key); if (item == null) continue;
            int slot = item.getInt("slot", -1); if (slot < 0 || slot >= size) continue;
            Material material = Material.matchMaterial(item.getString("material", "PAPER")); if (material == null) material = Material.PAPER;
            ItemStack stack = new ItemStack(material); ItemMeta meta = stack.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(color(item.getString("name", key)));
                List<String> lore = new ArrayList<>(); for (String line : item.getStringList("lore")) lore.add(color(line)); meta.setLore(lore);
                stack.setItemMeta(meta);
            }
            inventory.setItem(slot, stack); holder.addAction(slot, item.getString("action", key));
        }
        player.openInventory(inventory);
    }

    private void openBedrockMain(Player player) {
        String title = color(menus.getString("bedrock.title", "&2Berry Voice"));
        String content = color(menus.getString("bedrock.content", "&7Gerencie sua voz e seus grupos."));
        SimpleForm form = SimpleForm.builder().title(title).content(content)
                .button(color(menus.getString("bedrock.buttons.connect", "&aGerar código para o site")))
                .button(color(menus.getString("bedrock.buttons.groups", "&bGrupos de voz")))
                .button(color(menus.getString("bedrock.buttons.mute", "&eMutar / desmutar")))
                .button(color(menus.getString("bedrock.buttons.leave", "&cSair do grupo")))
                .validResultHandler(response -> {
                    switch (response.clickedButtonId()) {
                        case 0 -> generateCode(player);
                        case 1 -> openBedrockGroups(player);
                        case 2 -> toggleMute(player);
                        case 3 -> leaveGroup(player);
                        default -> { }
                    }
                }).build();
        FloodgateApi.getInstance().sendForm(player.getUniqueId(), form);
    }

    private void openBedrockGroups(Player player) {
        var groups = voiceBridge.listGroups();
        SimpleForm.Builder builder = SimpleForm.builder().title(color(menus.getString("bedrock.groups-title", "&2Grupos de voz")))
                .content(color(menus.getString("bedrock.groups-content", "&7Escolha uma sala. Para criar: &f/voz criar <nome>")))
                .button(color(menus.getString("bedrock.groups-proximity", "&aProximidade")));
        List<String> ids = new ArrayList<>(); ids.add("global");
        for (int i = 0; i < groups.size(); i++) {
            var group = groups.get(i).getAsJsonObject();
            ids.add(group.get("id").getAsString());
            builder.button(color("&b" + group.get("name").getAsString()));
        }
        builder.validResultHandler(response -> {
            int index = response.clickedButtonId();
            if (index >= 0 && index < ids.size()) joinGroup(player, ids.get(index));
        });
        FloodgateApi.getInstance().sendForm(player.getUniqueId(), builder.build());
    }

    @EventHandler public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!(event.getView().getTopInventory().getHolder(false) instanceof VoiceMenuHolder holder)) return;
        event.setCancelled(true);
        // Só os slots do menu superior têm ações; cliques no inventário do jogador são apenas bloqueados.
        if (event.getRawSlot() < 0 || event.getRawSlot() >= event.getView().getTopInventory().getSize()) return;
        String action = holder.action(event.getRawSlot());
        if (action == null) return;
        String normalized = action.toLowerCase(Locale.ROOT);
        // Paper recomenda adiar closeInventory/openInventory para o próximo tick após InventoryClickEvent.
        Bukkit.getScheduler().runTask(this, () -> {
            if (!player.isOnline()) return;
            switch (normalized) {
                case "connect", "pair" -> { player.closeInventory(); generateCode(player); }
                case "mute", "toggle-mute" -> { player.closeInventory(); toggleMute(player); }
                case "groups" -> { player.closeInventory(); openGroups(player); }
                case "leave" -> { player.closeInventory(); leaveGroup(player); }
                case "close" -> player.closeInventory();
                case "java-help" -> { player.closeInventory(); send(player, "java-use-mod"); }
                default -> { if (normalized.startsWith("join:")) { player.closeInventory(); joinGroup(player, normalized.substring(5)); } }
            }
        });
    }

    @EventHandler public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof VoiceMenuHolder) event.setCancelled(true);
    }

    private void openGroups(Player player) {
        if (isFloodgatePlayer(player)) { openBedrockGroups(player); return; }
        var groups = voiceBridge.listGroups();
        int roomCount = Math.min(36, groups.size());
        int size = Math.max(27, ((roomCount + 9) / 9) * 9); size = Math.min(54, size);
        VoiceMenuHolder holder = new VoiceMenuHolder();
        Inventory inv = Bukkit.createInventory(holder, size, color(menus.getString("groups.title", "&8Berry Voice • Grupos")));
        holder.setInventory(inv);
        ItemStack proximity = new ItemStack(Material.COMPASS);
        ItemMeta proximityMeta = proximity.getItemMeta();
        if (proximityMeta != null) { proximityMeta.setDisplayName(color("&aProximidade")); proximityMeta.setLore(List.of(color("&7Voltar ao áudio por proximidade."))); proximity.setItemMeta(proximityMeta); }
        inv.setItem(0, proximity); holder.addAction(0, "join:global");
        for (int i = 0; i < roomCount; i++) {
            JsonObject group = groups.get(i).getAsJsonObject();
            String id = group.get("id").getAsString(); String name = group.get("name").getAsString();
            ItemStack item = new ItemStack(Material.NAME_TAG); ItemMeta meta = item.getItemMeta();
            if (meta != null) { meta.setDisplayName(color("&b" + name)); meta.setLore(List.of(color("&7Clique para entrar neste grupo."))); item.setItemMeta(meta); }
            int slot = i + 1; if (slot >= size) break;
            inv.setItem(slot, item); holder.addAction(slot, "join:" + id);
        }
        player.openInventory(inv);
    }

    private void generateCode(Player player) {
        if (!isFloodgatePlayer(player)) { send(player, "java-use-mod"); return; }
        JsonObject body = new JsonObject(); body.addProperty("uuid", player.getUniqueId().toString());
        body.addProperty("name", player.getName()); body.addProperty("serverId", serverId);
        api.post("/internal/pair/create", body).whenComplete((json, error) -> runMain(() -> {
            if (error != null) { sendError(player, error); return; }
            String code = json.get("code").getAsString(); int ttl = json.get("expiresIn").getAsInt();
            send(player, "connect.header");
            send(player, "connect.code", "{code}", code);
            send(player, "connect.site", "{site}", siteUrl);
            send(player, "connect.expiry", "{seconds}", String.valueOf(ttl));
        }));
    }

    private void toggleMute(Player player) {
        boolean muted = voiceBridge.toggleMute(player.getUniqueId());
        send(player, muted ? "mute.enabled" : "mute.disabled");
    }

    private void setMute(Player player, boolean muted) {
        voiceBridge.setMutedLocally(player.getUniqueId(), muted);
        send(player, muted ? "mute.enabled" : "mute.disabled");
    }

    private void createGroup(Player player, String name) {
        try {
            Group group = voiceBridge.createGroup(name);
            voiceBridge.joinGroup(player.getUniqueId(), group.getId().toString());
            send(player, "groups.created", "{name}", group.getName());
        } catch (Exception ex) {
            send(player, "api-error", "{error}", ex.getMessage() == null ? "não foi possível criar o grupo" : ex.getMessage());
        }
    }

    private void joinGroup(Player player, String groupId) {
        if (voiceBridge.joinGroup(player.getUniqueId(), groupId)) {
            if ("global".equalsIgnoreCase(groupId)) send(player, "groups.left");
            else send(player, "groups.joined", "{group}", groupId);
        } else send(player, "api-error", "{error}", "grupo não encontrado ou Simple Voice Chat indisponível");
    }

    private void leaveGroup(Player player) {
        if (voiceBridge.leaveGroup(player.getUniqueId())) send(player, "groups.left");
        else send(player, "api-error", "{error}", "não foi possível sair do grupo");
    }

    private boolean isFloodgateAvailable() { return Bukkit.getPluginManager().isPluginEnabled("floodgate"); }
    private boolean isFloodgatePlayer(Player player) {
        if (!isFloodgateAvailable()) return false;
        try { return FloodgateApi.getInstance().isFloodgatePlayer(player.getUniqueId()); }
        catch (Throwable ex) { getLogger().warning("Floodgate API indisponível: " + ex.getMessage()); return false; }
    }
    private void runMain(Runnable runnable) { if (!isEnabled()) return; Bukkit.getScheduler().runTask(this, runnable); }
    private String color(String text) { return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text); }
    private void send(Player player, String key, String... replacements) {
        String text = messages.getString(key, "&cMensagem não configurada: " + key);
        for (int i=0; i+1<replacements.length; i+=2) text = text.replace(replacements[i], replacements[i+1]);
        player.sendMessage(color(messages.getString("prefix", "&8[&aBerryVoice&8] &r") + text));
    }
    private void sendError(Player player, Throwable throwable) {
        Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null ? throwable.getCause() : throwable;
        send(player, "api-error", "{error}", cause.getMessage() == null ? "API indisponível" : cause.getMessage());
    }
}
