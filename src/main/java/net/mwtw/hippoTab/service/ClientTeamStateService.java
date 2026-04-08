package net.mwtw.hippoTab.service;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.event.UserDisconnectEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ClientTeamStateService {
    private static final String HIPPO_TEAM_PREFIX = "ht_";

    private final JavaPlugin plugin;
    private final Map<UUID, Map<String, String>> observedTeamsByViewer = new ConcurrentHashMap<>();
    private PacketListenerAbstract listener;

    public ClientTeamStateService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isAvailable() {
        return plugin.getServer().getPluginManager().isPluginEnabled("packetevents");
    }

    private boolean isDebugEnabled() {
        return plugin.getConfig().getBoolean("debug.client-team-packets", false);
    }

    public void start() {
        if (!isAvailable() || listener != null) {
            return;
        }

        listener = new PacketListenerAbstract(PacketListenerPriority.MONITOR) {
            @Override
            public void onPacketSend(PacketSendEvent event) {
                if (event.getPacketType() != PacketType.Play.Server.TEAMS) {
                    return;
                }

                Object packetPlayer = event.getPlayer();
                if (!(packetPlayer instanceof Player viewer)) {
                    return;
                }

                WrapperPlayServerTeams packet = new WrapperPlayServerTeams(event);
                guardAndTrack(viewer, event, packet);
            }

            @Override
            public void onUserDisconnect(UserDisconnectEvent event) {
                if (event.getUser() == null || event.getUser().getUUID() == null) {
                    return;
                }
                observedTeamsByViewer.remove(event.getUser().getUUID());
            }
        };

        PacketEvents.getAPI().getEventManager().registerListener(listener);
        plugin.getLogger().info("Hooked into PacketEvents for client scoreboard team tracking.");
    }

    public void stop() {
        if (listener != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(listener);
            listener = null;
        }
        observedTeamsByViewer.clear();
    }

    public void clearAndPrepareReassign(Player target) {
        if (!isAvailable() || target == null || target.getName() == null) {
            return;
        }

        String entry = target.getName();
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer == null || viewer.getUniqueId() == null) {
                continue;
            }
            Map<String, String> observedTeams = observedTeamsByViewer.get(viewer.getUniqueId());
            if (observedTeams == null) {
                continue;
            }

            String observedTeam = observedTeams.get(entry);
            if (observedTeam == null || !observedTeam.startsWith(HIPPO_TEAM_PREFIX)) {
                continue;
            }

            WrapperPlayServerTeams removePacket = new WrapperPlayServerTeams(
                observedTeam,
                WrapperPlayServerTeams.TeamMode.REMOVE_ENTITIES,
                (WrapperPlayServerTeams.ScoreBoardTeamInfo) null,
                List.of(entry)
            );
            PacketEvents.getAPI().getPlayerManager().sendPacketSilently(viewer, removePacket);
            observedTeams.remove(entry, observedTeam);

            if (isDebugEnabled()) {
                plugin.getLogger().warning("ClientTeamDebug forced-reassign viewer="
                    + viewer.getName()
                    + " entry=" + entry
                    + " oldTeam=" + observedTeam);
            }
        }
    }

    private void guardAndTrack(Player viewer, PacketSendEvent event, WrapperPlayServerTeams packet) {
        String teamName = packet.getTeamName();
        WrapperPlayServerTeams.TeamMode mode = packet.getTeamMode();
        Collection<String> players = packet.getPlayers();
        if (viewer.getUniqueId() == null || teamName == null || mode == null || players == null) {
            return;
        }
        Map<String, String> observedTeams = observedTeamsByViewer.computeIfAbsent(viewer.getUniqueId(), ignored -> new ConcurrentHashMap<>());

        if (teamName.startsWith(HIPPO_TEAM_PREFIX) && isDebugEnabled()) {
            plugin.getLogger().info("ClientTeamDebug viewer="
                + viewer.getName()
                + " team=" + teamName
                + " mode=" + mode
                + " players=" + players
                + " cancelled=" + event.isCancelled());
        }

        if (mode == WrapperPlayServerTeams.TeamMode.REMOVE_ENTITIES) {
            Collection<String> validPlayers = new ArrayList<>();
            for (String entry : players) {
                if (entry == null) {
                    continue;
                }
                String observedTeam = observedTeams.get(entry);
                if (teamName.equals(observedTeam)) {
                    validPlayers.add(entry);
                } else if (isDebugEnabled()) {
                    plugin.getLogger().warning("ClientTeamDebug stripped viewer="
                        + viewer.getName()
                        + " team=" + teamName
                        + " entry=" + entry
                        + " observedTeam=" + observedTeam);
                }
            }

            if (validPlayers.isEmpty()) {
                event.setCancelled(true);
                if (isDebugEnabled()) {
                    plugin.getLogger().warning("ClientTeamDebug blocked viewer="
                        + viewer.getName()
                        + " team=" + teamName
                        + " mode=" + mode
                        + " players=" + players);
                }
                return;
            }

            if (validPlayers.size() != players.size()) {
                packet.setPlayers(validPlayers);
                packet.write();
                event.markForReEncode(true);
                players = validPlayers;
            }
        }

        if (teamName.startsWith(HIPPO_TEAM_PREFIX) && mode == WrapperPlayServerTeams.TeamMode.REMOVE) {
            if (isDebugEnabled()) {
                plugin.getLogger().warning("ClientTeamDebug blocked viewer="
                    + viewer.getName()
                    + " team=" + teamName
                    + " mode=" + mode
                    + " players=" + players);
            }
            event.setCancelled(true);
            return;
        }

        applyObservedState(mode, teamName, players, observedTeams);
    }

    private void applyObservedState(WrapperPlayServerTeams.TeamMode mode,
                                    String teamName,
                                    Collection<String> players,
                                    Map<String, String> observedTeams) {
        switch (mode) {
            case CREATE, ADD_ENTITIES -> {
                for (String entry : players) {
                    if (entry != null) {
                        observedTeams.put(entry, teamName);
                    }
                }
            }
            case REMOVE_ENTITIES -> {
                for (String entry : players) {
                    if (entry != null) {
                        observedTeams.remove(entry, teamName);
                    }
                }
            }
            case REMOVE -> observedTeams.entrySet().removeIf(entry -> teamName.equals(entry.getValue()));
            case UPDATE -> {
                // No membership change.
            }
        }
    }
}
