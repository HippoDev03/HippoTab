package net.mwtw.hippoNick.gui;

import net.kyori.adventure.text.format.TextDecoration;
import net.mwtw.hippoNick.Core;
import net.mwtw.hippoNick.message.MessagePlaceholder;
import net.mwtw.hippoNick.message.Messages;
import net.mwtw.hippoNick.service.NickManager;
import net.mwtw.hippoNick.domain.DisplayState;
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

import java.util.List;

public final class RealNameGuiService implements Listener {
    private final Core plugin;
    private final NickManager nickManager;
    private final Messages messages;

    public RealNameGuiService(Core plugin, NickManager nickManager, Messages messages) {
        this.plugin = plugin;
        this.nickManager = nickManager;
        this.messages = messages;
    }

    public void open(Player player) {
        int rows = Math.max(1, Math.min(6, plugin.getConfig().getInt("nick.gui.realname.rows", 6)));
        int size = rows * 9;
        RealNameHolder holder = new RealNameHolder();
        Inventory inventory = Bukkit.createInventory(holder, size, messages.plain("gui.realname.title"));
        holder.inventory = inventory;

        int slot = 0;
        List<Player> nickedPlayers = nickManager.onlineNickedPlayers();
        for (Player nicked : nickedPlayers) {
            if (slot >= inventory.getSize()) {
                break;
            }
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(nicked);
            DisplayState display = nickManager.resolveDisplay(nicked);
            String nick = display.effectiveName();
            meta.displayName(messages.component("gui.realname.item", MessagePlaceholder.of("nickname", nick))
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    messages.component("gui.realname.real", MessagePlaceholder.of("real", nicked.getName()))
                            .decoration(TextDecoration.ITALIC, false),
                    messages.component("gui.realname.rank", MessagePlaceholder.of("rank", display.effectiveRank()))
                            .decoration(TextDecoration.ITALIC, false)
            ));
            head.setItemMeta(meta);
            inventory.setItem(slot++, head);
        }

        if (slot == 0) {
            Material emptyMaterial = material(plugin.getConfig().getString("nick.gui.realname.empty-material", "BARRIER"), Material.BARRIER);
            ItemStack empty = new ItemStack(emptyMaterial);
            ItemMeta emptyMeta = empty.getItemMeta();
            emptyMeta.displayName(messages.component("gui.realname.empty").decoration(TextDecoration.ITALIC, false));
            empty.setItemMeta(emptyMeta);
            inventory.setItem(Math.min(size / 2, size - 1), empty);
        }

        player.openInventory(inventory);
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
        if (!(event.getInventory().getHolder() instanceof RealNameHolder)) {
            return;
        }
        event.setCancelled(true);
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir() || clicked.getType() == Material.BARRIER) {
            return;
        }
        if (!(clicked.getItemMeta() instanceof SkullMeta skullMeta)) {
            return;
        }
        if (skullMeta.getOwningPlayer() == null) {
            return;
        }
        Player target = skullMeta.getOwningPlayer().getPlayer();
        if (target == null || !target.isOnline() || !nickManager.isNicked(target)) {
            String query = skullMeta.getOwningPlayer().getName();
            messages.send(player, "realname.not-found", MessagePlaceholder.of("query", query == null ? "unknown" : query));
            return;
        }
        String effective = nickManager.resolveDisplay(target).effectiveName();
        messages.send(player, "realname.result",
                MessagePlaceholder.of("display", effective),
                MessagePlaceholder.of("real", target.getName()));
    }

    private static final class RealNameHolder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
