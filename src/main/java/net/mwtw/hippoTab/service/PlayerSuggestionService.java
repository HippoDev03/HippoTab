package net.mwtw.hippoTab.service;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.chat.Node;
import com.github.retrooper.packetevents.protocol.chat.Parsers;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.resources.ResourceLocation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDeclareCommands;
import org.bukkit.plugin.Plugin;

import java.util.Optional;

/**
 * Player-name arguments of vanilla/brigadier commands (/tell, /tp, /give ...) are
 * normally completed by the client from its own tab list, which also holds the players
 * synced from other servers (redis-sync), unlisted entries and NPCs. This marks those
 * arguments "ask the server" in the command tree the client receives, so suggestions
 * come from the players actually on this server (and visible to the asker).
 */
public final class PlayerSuggestionService {
    private static final ResourceLocation ASK_SERVER = new ResourceLocation("minecraft", "ask_server");

    private final Plugin plugin;
    private PacketListenerAbstract listener;

    public PlayerSuggestionService(Plugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (PacketEvents.getAPI() == null || PacketEvents.getAPI().getEventManager() == null) {
            return;
        }
        listener = new PacketListenerAbstract(PacketListenerPriority.NORMAL) {
            @Override
            public void onPacketSend(PacketSendEvent event) {
                if (event.getPacketType() != PacketType.Play.Server.DECLARE_COMMANDS
                        || !plugin.getConfig().getBoolean("tab.local-player-suggestions", true)) {
                    return;
                }
                WrapperPlayServerDeclareCommands packet = new WrapperPlayServerDeclareCommands(event);
                boolean changed = false;
                for (Node node : packet.getNodes()) {
                    if (isClientCompletedPlayerArgument(node)) {
                        node.setFlags((byte) (node.getFlags() | Node.FLAG_CUSTOM_SUGGESTIONS));
                        node.setSuggestionsType(Optional.of(ASK_SERVER));
                        changed = true;
                    }
                }
                if (changed) {
                    event.markForReEncode(true);
                }
            }
        };
        PacketEvents.getAPI().getEventManager().registerListener(listener);
    }

    public void stop() {
        if (listener != null && PacketEvents.getAPI() != null && PacketEvents.getAPI().getEventManager() != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(listener);
        }
        listener = null;
    }

    private static boolean isClientCompletedPlayerArgument(Node node) {
        if ((node.getFlags() & Node.TYPE_MASK) != Node.TYPE_ARGUMENT
                || (node.getFlags() & Node.FLAG_CUSTOM_SUGGESTIONS) != 0
                || node.getParser().isEmpty()) {
            return false;
        }
        var parser = node.getParser().get();
        return parser.getName().equals(Parsers.ENTITY.getName())
                || parser.getName().equals(Parsers.GAME_PROFILE.getName());
    }
}
