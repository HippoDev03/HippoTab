package net.mwtw.hippoTab.joinquit;

import net.kyori.adventure.text.Component;
import net.mwtw.hippoTab.text.TabTextFormatter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;

/**
 * Owns the join/quit message logic: reads the templates live from config (so a
 * {@code /hippotab reload} takes effect without a rebuild) and combines them with the
 * per-player {@link JoinQuitPreferences}. Produces the {@link Component} to broadcast,
 * or {@code null} to suppress the message.
 */
public final class JoinQuitService {
    private final Plugin plugin;
    private final TabTextFormatter formatter;
    private final JoinQuitPreferences preferences;

    public JoinQuitService(Plugin plugin, TabTextFormatter formatter) {
        this.plugin = plugin;
        this.formatter = formatter;
        this.preferences = new JoinQuitPreferences(plugin);
    }

    public JoinQuitPreferences preferences() {
        return preferences;
    }

    public boolean isJoinFeatureEnabled() {
        return plugin.getConfig().getBoolean("join-quit.join.enabled", true);
    }

    public boolean isQuitFeatureEnabled() {
        return plugin.getConfig().getBoolean("join-quit.quit.enabled", true);
    }

    public String togglePermission() {
        return plugin.getConfig().getString("join-quit.toggle.permission", "hippotab.joinquit.toggle");
    }

    /** The join message to broadcast, or {@code null} to suppress it. */
    public Component joinMessage(Player player) {
        if (!isJoinFeatureEnabled() || !preferences.isJoinEnabled(player.getUniqueId())) {
            return null;
        }
        return render(player, resolveTemplate(player, "join", "join-quit.join.default"));
    }

    /** The quit message to broadcast, or {@code null} to suppress it. */
    public Component quitMessage(Player player) {
        if (!isQuitFeatureEnabled() || !preferences.isQuitEnabled(player.getUniqueId())) {
            return null;
        }
        return render(player, resolveTemplate(player, "quit", "join-quit.quit.default"));
    }

    private Component render(Player player, String template) {
        if (template == null || template.isEmpty()) {
            return null;
        }
        return formatter.toComponent(player, template);
    }

    /**
     * Returns the {@code field} (join/quit) template of the first custom entry whose
     * permission the player has, falling back to the default template at {@code defaultPath}.
     */
    private String resolveTemplate(Player player, String field, String defaultPath) {
        List<Map<?, ?>> custom = plugin.getConfig().getMapList("join-quit.custom");
        for (Map<?, ?> entry : custom) {
            Object permission = entry.get("permission");
            if (permission == null || !player.hasPermission(String.valueOf(permission))) {
                continue;
            }
            Object value = entry.get(field);
            if (value != null) {
                return String.valueOf(value);
            }
        }
        return plugin.getConfig().getString(defaultPath);
    }
}
