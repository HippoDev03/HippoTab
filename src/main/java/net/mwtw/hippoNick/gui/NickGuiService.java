package net.mwtw.hippoNick.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.text.format.TextDecoration;
import net.mwtw.hippoNick.Core;
import net.mwtw.hippoNick.domain.RankInfo;
import net.mwtw.hippoNick.message.MessagePlaceholder;
import net.mwtw.hippoNick.message.Messages;
import net.mwtw.hippoNick.service.NickManager;
import net.mwtw.hippoNick.service.RankService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class NickGuiService implements Listener {
    private final Core plugin;
    private final NamespacedKey rankKey;
    private final NickManager nickManager;
    private final RankService rankService;
    private final Messages messages;
    private final Map<UUID, PendingNickRequest> pendingNick = new HashMap<>();

    public NickGuiService(Core plugin, NickManager nickManager, RankService rankService, Messages messages) {
        this.plugin = plugin;
        this.rankKey = new NamespacedKey(plugin, "rank-id");
        this.nickManager = nickManager;
        this.rankService = rankService;
        this.messages = messages;
    }

    public void openRankSelector(Player player, String nickname, String skinName) {
        pendingNick.put(player.getUniqueId(), new PendingNickRequest(nickname, skinName));
        List<RankInfo> ranks = rankService.listRanks();
        String baseRank = rankService.resolveBaseRank(player);
        int ownWeight = rankService.resolveWeight(baseRank).orElse(0);

        int rows = Math.max(1, Math.min(6, plugin.getConfig().getInt("nick.gui.rank.rows", 6)));
        int size = rows * 9;
        Material itemMaterial = material(plugin.getConfig().getString("nick.gui.rank.item-material", "NAME_TAG"), Material.NAME_TAG);
        boolean showClear = plugin.getConfig().getBoolean("nick.gui.rank.show-clear", true);
        Material clearMaterial = material(plugin.getConfig().getString("nick.gui.rank.clear-material", "BARRIER"), Material.BARRIER);
        boolean restrictByWeight = plugin.getConfig().getBoolean("nick.gui.rank.restrict-by-weight", true);
        int maxRankSlots = showClear ? size - 1 : size;

        RankSelectorHolder holder = new RankSelectorHolder();
        Inventory inventory = Bukkit.createInventory(holder, size, messages.plain("gui.rank.title"));
        holder.inventory = inventory;
        int slot = 0;
        for (RankInfo rank : ranks) {
            if (restrictByWeight && rank.weight() > ownWeight) {
                continue;
            }
            if (slot >= maxRankSlots) {
                break;
            }
            ItemStack item = new ItemStack(itemMaterial);
            ItemMeta meta = item.getItemMeta();
            meta.displayName(displayRank(rank).decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(messages.component("gui.rank.weight", MessagePlaceholder.of("weight", rank.weight()))
                    .decoration(TextDecoration.ITALIC, false)));
            meta.getPersistentDataContainer().set(rankKey, PersistentDataType.STRING, rank.name());
            item.setItemMeta(meta);
            inventory.setItem(slot++, item);
        }
        if (showClear) {
            ItemStack clear = new ItemStack(clearMaterial);
            ItemMeta clearMeta = clear.getItemMeta();
            clearMeta.displayName(messages.component("gui.rank.clear").decoration(TextDecoration.ITALIC, false));
            clear.setItemMeta(clearMeta);
            inventory.setItem(size - 1, clear);
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
    public void onRankClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!(event.getInventory().getHolder() instanceof RankSelectorHolder)) {
            return;
        }
        event.setCancelled(true);
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir()) {
            return;
        }
        if (clicked.getType() == Material.BARRIER) {
            pendingNick.remove(player.getUniqueId());
            nickManager.clear(player);
            player.closeInventory();
            messages.send(player, "nick.cleared");
            return;
        }

        ItemMeta meta = clicked.getItemMeta();
        if (meta == null || meta.displayName() == null) {
            return;
        }
        String rank = meta.getPersistentDataContainer().get(rankKey, PersistentDataType.STRING);
        if (rank == null || rank.isBlank()) {
            rank = strip(meta.displayName());
        }
        if (plugin.getConfig().getBoolean("nick.gui.rank.restrict-by-weight", true)) {
            String baseRank = rankService.resolveBaseRank(player);
            int ownWeight = rankService.resolveWeight(baseRank).orElse(0);
            int targetWeight = rankService.resolveWeight(rank).orElse(Integer.MAX_VALUE);
            if (targetWeight > ownWeight) {
                player.closeInventory();
                messages.send(player, "nick.rank-weight-blocked");
                return;
            }
        }
        PendingNickRequest request = pendingNick.remove(player.getUniqueId());
        if (request == null || request.nickname() == null || request.nickname().isBlank()) {
            player.closeInventory();
            messages.send(player, "nick.usage");
            return;
        }
        nickManager.setNicknameAndRank(player, request.nickname(), rank, request.skinName());
        player.closeInventory();
        messages.send(player, "nick.applied", MessagePlaceholder.of("nickname", request.nickname()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pendingNick.remove(event.getPlayer().getUniqueId());
    }

    private String strip(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private Component displayRank(RankInfo rank) {
        String prefix = rank.prefix();
        if (prefix == null || prefix.isBlank()) {
            return messages.component("gui.rank.item", MessagePlaceholder.of("rank", rank.name()));
        }
        return LegacyComponentSerializer.legacyAmpersand().deserialize(prefix + "&r " + rank.name());
    }

    private static final class RankSelectorHolder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private record PendingNickRequest(String nickname, String skinName) {
    }

}
