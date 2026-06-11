package net.mwtw.hippoNick.service;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.TextureProperty;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfo;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTabComplete;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateScore;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerResetScore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.mwtw.hippoNick.domain.DisplayState;
import net.mwtw.hippoNick.Core;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

public final class TabPacketNameService {
    private final Core plugin;
    private final NickManager nickManager;
    private final RankService rankService;
    private PacketListenerAbstract listener;

    public TabPacketNameService(Core plugin, NickManager nickManager, RankService rankService) {
        this.plugin = plugin;
        this.nickManager = nickManager;
        this.rankService = rankService;
    }

    public void startIfAvailable() {
        if (!plugin.getConfig().getBoolean("nick.tab-list.enabled", true)) {
            plugin.getLogger().info("nick.tab-list.enabled=false. Tab packet rewrite disabled (HippoTab/other plugin owns the tab list).");
            return;
        }
        if (!Bukkit.getPluginManager().isPluginEnabled("packetevents")) {
            plugin.getLogger().warning("PacketEvents not found. Tab packet rewrite disabled.");
            return;
        }
        if (PacketEvents.getAPI() == null || PacketEvents.getAPI().getEventManager() == null) {
            plugin.getLogger().warning("PacketEvents API unavailable. Tab packet rewrite disabled.");
            return;
        }
        this.listener = new PacketListenerAbstract(PacketListenerPriority.HIGHEST) {
            @Override
            public void onPacketSend(PacketSendEvent event) {
                handle(event);
            }
        };
        PacketEvents.getAPI().getEventManager().registerListener(listener);
        plugin.getLogger().info("PacketEvents tab packet rewrite enabled.");
    }

    public void stop() {
        if (listener != null && PacketEvents.getAPI() != null && PacketEvents.getAPI().getEventManager() != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(listener);
        }
        listener = null;
    }

    private void handle(PacketSendEvent event) {
        try {
            if (event.getPacketType() == PacketType.Play.Server.PLAYER_INFO_UPDATE) {
                rewriteInfoUpdate(event);
                return;
            }
            if (event.getPacketType() == PacketType.Play.Server.PLAYER_INFO) {
                rewriteLegacyInfo(event);
                return;
            }
            if (event.getPacketType() == PacketType.Play.Server.TAB_COMPLETE) {
                rewriteTabComplete(event);
                return;
            }
            if (event.getPacketType() == PacketType.Play.Server.TEAMS) {
                rewriteTeamsMembership(event);
                return;
            }
            if (event.getPacketType() == PacketType.Play.Server.UPDATE_SCORE) {
                rewriteUpdateScore(event);
                return;
            }
            if (event.getPacketType() == PacketType.Play.Server.RESET_SCORE) {
                rewriteResetScore(event);
            }
        } catch (Exception exception) {
            plugin.getLogger().log(Level.WARNING, "Failed to rewrite tab packet", exception);
        }
    }

    private void rewriteInfoUpdate(PacketSendEvent event) {
        WrapperPlayServerPlayerInfoUpdate wrapper = new WrapperPlayServerPlayerInfoUpdate(event);
        boolean changed = false;
        for (WrapperPlayServerPlayerInfoUpdate.PlayerInfo info : wrapper.getEntries()) {
            Player target = Bukkit.getPlayer(info.getProfileId());
            if (target == null || !nickManager.isNicked(target)) {
                continue;
            }
            String packetName = nickManager.resolvePacketProfileName(target);
            UserProfile rewritten = rewriteProfileName(target, info.getGameProfile(), packetName);
            if (rewritten != null) {
                info.setGameProfile(rewritten);
            }
            Component displayName = info.getDisplayName();
            if (displayName != null) {
                info.setDisplayName(replaceNameInComponent(displayName, target.getName(), packetName));
            }
            changed = true;
        }
        if (changed) {
            event.markForReEncode(true);
        }
    }

    private void rewriteLegacyInfo(PacketSendEvent event) {
        WrapperPlayServerPlayerInfo wrapper = new WrapperPlayServerPlayerInfo(event);
        boolean changed = false;
        for (WrapperPlayServerPlayerInfo.PlayerData data : wrapper.getPlayerDataList()) {
            UUID uuid = data.getUserProfile().getUUID();
            Player target = Bukkit.getPlayer(uuid);
            if (target == null || !nickManager.isNicked(target)) {
                continue;
            }
            String packetName = nickManager.resolvePacketProfileName(target);
            UserProfile rewritten = rewriteProfileName(target, data.getUserProfile(), packetName);
            if (rewritten != null) {
                data.setUserProfile(rewritten);
            }
            Component displayName = data.getDisplayName();
            if (displayName != null) {
                data.setDisplayName(replaceNameInComponent(displayName, target.getName(), packetName));
            }
            changed = true;
        }
        if (changed) {
            event.markForReEncode(true);
        }
    }

    private void rewriteTabComplete(PacketSendEvent event) {
        WrapperPlayServerTabComplete wrapper = new WrapperPlayServerTabComplete(event);
        List<WrapperPlayServerTabComplete.CommandMatch> matches = wrapper.getCommandMatches();
        if (matches == null || matches.isEmpty()) {
            return;
        }
        boolean changed = false;
        for (WrapperPlayServerTabComplete.CommandMatch match : matches) {
            if (match == null) {
                continue;
            }
            String mapped = mapTabSuggestionName(match.getText());
            if (mapped.equals(match.getText())) {
                continue;
            }
            match.setText(mapped);
            changed = true;
        }
        if (changed) {
            event.markForReEncode(true);
        }
    }

    private void rewriteUpdateScore(PacketSendEvent event) {
        WrapperPlayServerUpdateScore wrapper = new WrapperPlayServerUpdateScore(event);
        String mapped = mapScoreTargetName(wrapper.getEntityName());
        if (mapped.equals(wrapper.getEntityName())) {
            return;
        }
        wrapper.setEntityName(mapped);
        event.markForReEncode(true);
    }

    private void rewriteResetScore(PacketSendEvent event) {
        WrapperPlayServerResetScore wrapper = new WrapperPlayServerResetScore(event);
        String mapped = mapScoreTargetName(wrapper.getTargetName());
        if (mapped.equals(wrapper.getTargetName())) {
            return;
        }
        wrapper.setTargetName(mapped);
        event.markForReEncode(true);
    }

    private void rewriteTeamsMembership(PacketSendEvent event) {
        WrapperPlayServerTeams wrapper = new WrapperPlayServerTeams(event);
        Collection<String> members = wrapper.getPlayers();
        if (members == null || members.isEmpty()) {
            return;
        }
        ArrayList<String> remapped = new ArrayList<>(members.size());
        boolean changed = false;
        for (String member : members) {
            String replacement = member;
            // Try real name lookup first, then scan nicked players by real name
            Player online = Bukkit.getPlayerExact(member);
            if (online == null) {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getName().equalsIgnoreCase(member) && nickManager.isNicked(p)) {
                        online = p;
                        break;
                    }
                }
            }
            if (online != null && nickManager.isNicked(online)) {
                replacement = nickManager.resolvePacketProfileName(online);
            }
            if (!replacement.equals(member)) {
                changed = true;
            }
            remapped.add(replacement);
        }
        if (!changed) {
            return;
        }
        wrapper.setPlayers(remapped);
        event.markForReEncode(true);
    }

    private UserProfile rewriteProfileName(Player target, UserProfile source, String packetName) {
        if (source == null || source.getUUID() == null) {
            return source;
        }
        String safeName = (packetName == null || packetName.isBlank()) ? source.getName() : packetName;
        if (safeName == null || safeName.isBlank()) {
            return source;
        }
        List<TextureProperty> liveTextures = extractLiveTextureProperties(target);
        if (liveTextures.isEmpty()) {
            UserProfile copy = new UserProfile(source.getUUID(), source.getName(), source.getTextureProperties());
            copy.setName(safeName);
            return copy;
        }
        return new UserProfile(source.getUUID(), safeName, liveTextures);
    }

    private List<TextureProperty> extractLiveTextureProperties(Player target) {
        List<TextureProperty> out = new ArrayList<>();
        for (com.destroystokyo.paper.profile.ProfileProperty property : target.getPlayerProfile().getProperties()) {
            if (property.getName() == null || property.getValue() == null) {
                continue;
            }
            out.add(new TextureProperty(property.getName(), property.getValue(), property.getSignature()));
        }
        return out;
    }

    private Component replaceNameInComponent(Component component, String realName, String nickName) {
        return component.replaceText(
            TextReplacementConfig.builder()
                .matchLiteral(realName)
                .replacement(nickName)
                .build()
        );
    }

    private String mapScoreTargetName(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        Player player = Bukkit.getPlayerExact(raw);
        if (player == null || !nickManager.isNicked(player)) {
            return raw;
        }
        String packetName = nickManager.resolvePacketProfileName(player);
        return packetName == null || packetName.isBlank() ? raw : packetName;
    }

    private String mapTabSuggestionName(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        Player player = Bukkit.getPlayerExact(raw);
        if (player == null || !nickManager.isNicked(player)) {
            return raw;
        }
        DisplayState display = nickManager.resolveDisplay(player);
        String effective = display.effectiveName();
        return effective == null || effective.isBlank() ? raw : effective;
    }
}
