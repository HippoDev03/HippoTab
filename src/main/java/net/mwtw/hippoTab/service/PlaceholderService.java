package net.mwtw.hippoTab.service;

import me.clip.placeholderapi.PlaceholderAPI;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

public final class PlaceholderService {
    private final JavaPlugin plugin;
    private HippoTabExpansion expansion;

    public PlaceholderService(JavaPlugin plugin) {
        this.plugin = plugin;
        if (!Bukkit.getPluginManager().isPluginEnabled("VaultUnlocked")) {
            plugin.getLogger().info("VaultUnlocked not found. Rank sorting can still work with any numeric placeholder.");
        }
        
        // Register custom PlaceholderAPI expansion
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            expansion = new HippoTabExpansion(plugin);
            expansion.register();
            plugin.getLogger().info("Registered HippoTab PlaceholderAPI expansion");
        }
    }

    public String apply(Player player, String text) {
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            return PlaceholderAPI.setPlaceholders(player, text);
        }
        return text;
    }

    public void setNameTagService(NameTagService nameTagService) {
        if (expansion != null) {
            expansion.setNameTagService(nameTagService);
        }
    }

    public void unregister() {
        if (expansion != null) {
            expansion.unregister();
        }
    }

    private static class HippoTabExpansion extends PlaceholderExpansion {
        private final JavaPlugin plugin;
        private NameTagService nameTagService;

        public HippoTabExpansion(JavaPlugin plugin) {
            this.plugin = plugin;
        }

        public void setNameTagService(NameTagService nameTagService) {
            this.nameTagService = nameTagService;
        }

        @Override
        public @NotNull String getIdentifier() {
            return "hippotab";
        }

        @Override
        public @NotNull String getAuthor() {
            return plugin.getDescription().getAuthors().toString();
        }

        @Override
        public @NotNull String getVersion() {
            return plugin.getDescription().getVersion();
        }

        @Override
        public boolean persist() {
            return true;
        }

        @Override
        public String onPlaceholderRequest(Player player, @NotNull String identifier) {
            if (player == null) {
                return "";
            }

            // %hippotab_nametag% - Returns the formatted name tag (prefix + name + suffix)
            if (identifier.equals("nametag")) {
                if (nameTagService != null) {
                    return nameTagService.getFormattedName(player);
                }
                return player.getName();
            }

            // %hippotab_nametag_prefix% - Returns just the prefix
            if (identifier.equals("nametag_prefix")) {
                if (nameTagService != null) {
                    return nameTagService.getFormattedPrefix(player);
                }
                return "";
            }

            // %hippotab_nametag_suffix% - Returns just the suffix
            if (identifier.equals("nametag_suffix")) {
                if (nameTagService != null) {
                    return nameTagService.getFormattedSuffix(player);
                }
                return "";
            }

            return null;
        }
    }
}
