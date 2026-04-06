package net.mwtw.hippoTab.service;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class PlaceholderService {
    public PlaceholderService(JavaPlugin plugin) {
        if (!Bukkit.getPluginManager().isPluginEnabled("VaultUnlocked")) {
            plugin.getLogger().info("VaultUnlocked not found. Rank sorting can still work with any numeric placeholder.");
        }
    }

    public String apply(Player player, String text) {
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            return PlaceholderAPI.setPlaceholders(player, text);
        }
        return text;
    }
}
