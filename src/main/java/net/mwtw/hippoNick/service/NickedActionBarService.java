package net.mwtw.hippoNick.service;

import net.mwtw.hippoNick.Core;
import net.mwtw.hippoNick.message.MessagePlaceholder;
import net.mwtw.hippoNick.message.Messages;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public final class NickedActionBarService {
    private final Core plugin;
    private final NickManager nickManager;
    private final RankService rankService;
    private final Messages messages;
    private int taskId = -1;

    public NickedActionBarService(Core plugin, NickManager nickManager, RankService rankService, Messages messages) {
        this.plugin = plugin;
        this.nickManager = nickManager;
        this.rankService = rankService;
        this.messages = messages;
    }

    public void start() {
        stop();
        if (!plugin.getConfig().getBoolean("nick.actionbar.enabled", true)) {
            return;
        }
        long period = Math.max(2L, plugin.getConfig().getLong("nick.actionbar.interval-ticks", 10L));
        taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (!nickManager.isNicked(player)) {
                    continue;
                }
                var display = nickManager.resolveDisplay(player);
                String prefix = rankService.resolvePrefix(display.effectiveRank()).orElse("");
                player.sendActionBar(messages.component(
                        "nick.actionbar.message",
                        MessagePlaceholder.of("nickname", display.effectiveName()),
                        MessagePlaceholder.of("rank", display.effectiveRank()),
                        MessagePlaceholder.of("prefix", prefix)
                ));
            }
        }, 1L, period);
    }

    public void stop() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
    }
}
