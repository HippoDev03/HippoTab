package net.mwtw.hippoNick.service;

import net.mwtw.hippoNick.domain.DisplayState;
import org.bukkit.entity.Player;

public interface NametagService {
    void apply(Player player, DisplayState state);

    default void clear(Player player) {
    }

    default void shutdown() {
    }
}
