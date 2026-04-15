package net.mwtw.hippoTab.service;

import me.clip.placeholderapi.PlaceholderAPI;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.mwtw.hippoTab.config.ConditionalPlaceholderConfig;
import net.mwtw.hippoTab.config.ConditionalPlaceholderRule;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

public final class PlaceholderService {
    private final JavaPlugin plugin;
    private HippoTabExpansion expansion;
    private Map<String, ConditionalPlaceholderConfig> conditionalPlaceholders = Map.of();

    public PlaceholderService(JavaPlugin plugin) {
        this.plugin = plugin;
        if (!Bukkit.getPluginManager().isPluginEnabled("VaultUnlocked")) {
            plugin.getLogger().info("VaultUnlocked not found. Rank sorting can still work with any numeric placeholder.");
        }
        
        // Register custom PlaceholderAPI expansion
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            expansion = new HippoTabExpansion(plugin, this);
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

    public void setNametagService(NametagService nametagService) {
        if (expansion != null) {
            expansion.setNametagService(nametagService);
        }
    }

    public void setConditionalPlaceholders(Map<String, ConditionalPlaceholderConfig> conditionalPlaceholders) {
        this.conditionalPlaceholders = conditionalPlaceholders == null ? Map.of() : Map.copyOf(conditionalPlaceholders);
        if (expansion != null) {
            expansion.setConditionalPlaceholders(this.conditionalPlaceholders);
        }
    }

    public void unregister() {
        if (expansion != null) {
            expansion.unregister();
        }
    }

    private static class HippoTabExpansion extends PlaceholderExpansion {
        private final JavaPlugin plugin;
        private final PlaceholderService placeholderService;
        private NametagService nametagService;
        private Map<String, ConditionalPlaceholderConfig> conditionalPlaceholders = Map.of();

        public HippoTabExpansion(JavaPlugin plugin, PlaceholderService placeholderService) {
            this.plugin = plugin;
            this.placeholderService = placeholderService;
        }

        public void setNametagService(NametagService nametagService) {
            this.nametagService = nametagService;
        }

        public void setConditionalPlaceholders(Map<String, ConditionalPlaceholderConfig> conditionalPlaceholders) {
            this.conditionalPlaceholders = conditionalPlaceholders == null ? Map.of() : Map.copyOf(conditionalPlaceholders);
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
                if (nametagService != null) {
                    return nametagService.getFormattedName(player);
                }
                return player.getName();
            }

            // %hippotab_nametag_prefix% - Returns just the prefix
            if (identifier.equals("nametag_prefix")) {
                if (nametagService != null) {
                    return nametagService.getFormattedPrefix(player);
                }
                return "";
            }

            // %hippotab_nametag_suffix% - Returns just the suffix
            if (identifier.equals("nametag_suffix")) {
                if (nametagService != null) {
                    return nametagService.getFormattedSuffix(player);
                }
                return "";
            }

            ConditionalPlaceholderConfig conditionalPlaceholder = conditionalPlaceholders.get(identifier);
            if (conditionalPlaceholder != null) {
                return resolveConditionalPlaceholder(player, conditionalPlaceholder);
            }

            return null;
        }

        private String resolveConditionalPlaceholder(Player player, ConditionalPlaceholderConfig conditionalPlaceholder) {
            String sourceValue = placeholderService.apply(player, conditionalPlaceholder.sourcePlaceholder());
            String fallback = "";

            for (ConditionalPlaceholderRule rule : conditionalPlaceholder.rules()) {
                if (rule.isFallback()) {
                    fallback = placeholderService.apply(player, rule.result());
                    continue;
                }

                String expectedValue = placeholderService.apply(player, rule.expectedValue());
                boolean matched = switch (rule.operator()) {
                    case "==" -> sourceValue.equalsIgnoreCase(expectedValue);
                    case "!=" -> !sourceValue.equalsIgnoreCase(expectedValue);
                    default -> false;
                };

                if (matched) {
                    return placeholderService.apply(player, rule.result());
                }
            }

            return fallback;
        }
    }
}
