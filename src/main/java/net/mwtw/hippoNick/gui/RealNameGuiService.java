package net.mwtw.hippoNick.gui;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.mwtw.hippoNick.Core;
import net.mwtw.hippoNick.domain.DisplayState;
import net.mwtw.hippoNick.message.MessagePlaceholder;
import net.mwtw.hippoNick.message.Messages;
import net.mwtw.hippoNick.service.NetworkNickDirectory;
import net.mwtw.hippoNick.service.NickManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * The /realname menu: one head per nicked player — this server's, followed by the other
 * servers' when the network directory is active. Clicking a head prints who it is.
 */
public final class RealNameGuiService implements Listener {
    /** What a head in the menu stands for. {@code server} is null for a player on this server. */
    private record Listed(String realName, String nickname, String rank, String server) {
    }

    private final Core plugin;
    private final NickManager nickManager;
    private final Messages messages;
    private final NetworkNickDirectory directory;

    public RealNameGuiService(Core plugin, NickManager nickManager, Messages messages, NetworkNickDirectory directory) {
        this.plugin = plugin;
        this.nickManager = nickManager;
        this.messages = messages;
        this.directory = directory;
    }

    public void open(Player player) {
        int rows = Math.max(1, Math.min(6, plugin.getConfig().getInt("nick.gui.realname.rows", 6)));
        int size = rows * 9;
        RealNameHolder holder = new RealNameHolder();
        Inventory inventory = Bukkit.createInventory(holder, size, messages.component("gui.realname.title"));
        holder.inventory = inventory;

        for (Player nicked : nickManager.onlineNickedPlayers()) {
            if (holder.listed.size() >= size) {
                break;
            }
            DisplayState display = nickManager.resolveDisplay(nicked);
            Listed listed = new Listed(nickManager.realName(nicked), display.effectiveName(), display.effectiveRank(), null);
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(nicked);
            describe(meta, listed);
            head.setItemMeta(meta);
            inventory.setItem(holder.listed.size(), head);
            holder.listed.add(listed);
        }

        for (NetworkNickDirectory.Entry entry : directory.remoteEntries()) {
            if (holder.listed.size() >= size) {
                break;
            }
            Listed listed = new Listed(entry.realName(), entry.nickname(), entry.rank(), entry.server());
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            // Not on this server: build the head from the skin the other server published.
            if (!entry.textureValue().isBlank()) {
                PlayerProfile profile = Bukkit.createProfile(entry.uuid(), null);
                profile.setProperty(new ProfileProperty("textures", entry.textureValue(),
                        entry.textureSignature().isBlank() ? null : entry.textureSignature()));
                meta.setPlayerProfile(profile);
            }
            describe(meta, listed);
            head.setItemMeta(meta);
            inventory.setItem(holder.listed.size(), head);
            holder.listed.add(listed);
        }

        if (holder.listed.isEmpty()) {
            Material emptyMaterial = material(plugin.getConfig().getString("nick.gui.realname.empty-material", "BARRIER"), Material.BARRIER);
            ItemStack empty = new ItemStack(emptyMaterial);
            ItemMeta emptyMeta = empty.getItemMeta();
            emptyMeta.displayName(messages.component("gui.realname.empty").decoration(TextDecoration.ITALIC, false));
            empty.setItemMeta(emptyMeta);
            // Middle column of the middle row (the upper of the two when the row count is even).
            inventory.setItem(((rows - 1) / 2) * 9 + 4, empty);
        }

        player.openInventory(inventory);
    }

    private void describe(SkullMeta meta, Listed listed) {
        meta.displayName(messages.component("gui.realname.item", MessagePlaceholder.of("nickname", listed.nickname()))
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(messages.component("gui.realname.real", MessagePlaceholder.of("real", listed.realName()))
                .decoration(TextDecoration.ITALIC, false));
        lore.add(messages.component("gui.realname.rank", MessagePlaceholder.of("rank", listed.rank()))
                .decoration(TextDecoration.ITALIC, false));
        if (listed.server() != null) {
            lore.add(messages.component("gui.realname.server", MessagePlaceholder.of("server", listed.server()))
                    .decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
    }

    private Material material(String name, Material fallback) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        Material parsed = Material.matchMaterial(name.trim());
        return parsed != null ? parsed : fallback;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!(event.getInventory().getHolder() instanceof RealNameHolder holder)) {
            return;
        }
        event.setCancelled(true);
        // Only the menu itself, not the player's own inventory below it.
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= holder.listed.size()) {
            return;
        }
        Listed listed = holder.listed.get(slot);
        if (listed.server() == null) {
            messages.send(player, "realname.result",
                    MessagePlaceholder.of("display", listed.nickname()),
                    MessagePlaceholder.of("real", listed.realName()));
        } else {
            messages.send(player, "realname.result-network",
                    MessagePlaceholder.of("display", listed.nickname()),
                    MessagePlaceholder.of("real", listed.realName()),
                    MessagePlaceholder.of("server", listed.server()));
        }
    }

    private static final class RealNameHolder implements InventoryHolder {
        // Index = slot.
        private final List<Listed> listed = new ArrayList<>();
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
