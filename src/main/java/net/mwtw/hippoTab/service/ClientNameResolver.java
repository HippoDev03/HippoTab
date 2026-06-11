package net.mwtw.hippoTab.service;

import org.bukkit.entity.Player;

import java.util.function.Function;

/**
 * Resolves the name the CLIENT uses for a player on scoreboards (teams, below-name).
 * With the nick subsystem enabled this is the (possibly nicked) packet profile name;
 * otherwise it is the login name. Mutable so the nick subsystem can install its
 * resolver after the tab subsystem (which is constructed first) is already running.
 */
public final class ClientNameResolver {
    private volatile Function<Player, String> resolver = Player::getName;

    public void set(Function<Player, String> resolver) {
        this.resolver = resolver != null ? resolver : Player::getName;
    }

    public String resolve(Player player) {
        String name = resolver.apply(player);
        return (name == null || name.isBlank()) ? player.getName() : name;
    }
}
