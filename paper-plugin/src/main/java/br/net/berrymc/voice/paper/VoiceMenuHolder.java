package br.net.berrymc.voice.paper;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.HashMap;
import java.util.Map;

final class VoiceMenuHolder implements InventoryHolder {
    private Inventory inventory;
    private final Map<Integer, String> actions = new HashMap<>();
    void setInventory(Inventory inventory) { this.inventory = inventory; }
    void addAction(int slot, String action) { actions.put(slot, action); }
    String action(int slot) { return actions.get(slot); }
    @Override public Inventory getInventory() { return inventory; }
}
