package net.mwtw.hippoTab.chat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.mwtw.hippoTab.text.LegacyColorTranslator;
import net.mwtw.hippoTab.text.TabTextFormatter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Custom chat formatter plus a per-player "hide public chat" toggle. Templates are
 * read live from config (so {@code /htab reload} takes effect without a rebuild) and
 * rendered with MiniMessage + PlaceholderAPI. The player's typed text is inserted
 * through the {@code <message>} tag as a safe component: plain text by default, with
 * the formatting kinds the server allows ({@code chat.player-formatting}) parsed for
 * players holding the matching permission. The per-kind settings only cover visual
 * tags; click/hover/insertion and the rest are parsed only for {@code all} (or for OPs
 * while {@code op-bypass} is on).
 */
public final class ChatService {
    private static final int COLOR = 1;
    private static final int DECORATION = 2;
    private static final int GRADIENT = 4;
    // Every standard tag, interactive ones included (chat.player-formatting.all, or OPs).
    private static final int ALL = 8;

    private final Plugin plugin;
    private final TabTextFormatter formatter;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    // One parser per combination of allowed formatting kinds, built on first use.
    private final MiniMessage[] playerParsers = new MiniMessage[8];
    private final Set<UUID> chatHidden = ConcurrentHashMap.newKeySet();

    public ChatService(Plugin plugin, TabTextFormatter formatter) {
        this.plugin = plugin;
        this.formatter = formatter;
    }

    public boolean isFormatterEnabled() {
        return plugin.getConfig().getBoolean("chat.enabled", true);
    }

    public boolean isToggleEnabled() {
        return plugin.getConfig().getBoolean("chat.toggle.enabled", true);
    }

    public String togglePermission() {
        return plugin.getConfig().getString("chat.toggle.permission", "hippotab.chat.toggle");
    }

    // --- personal chat visibility -------------------------------------------------

    public boolean isChatHidden(UUID uuid) {
        return chatHidden.contains(uuid);
    }

    /** Flips the player's chat visibility and returns {@code true} if chat is now hidden. */
    public boolean toggleHidden(UUID uuid) {
        if (chatHidden.contains(uuid)) {
            chatHidden.remove(uuid);
            return false;
        }
        chatHidden.add(uuid);
        return true;
    }

    public void clear(UUID uuid) {
        chatHidden.remove(uuid);
    }

    // --- formatting ---------------------------------------------------------------

    /** Renders the final chat line for a message from {@code source}. */
    public Component format(Player source, Component typedMessage) {
        String template = resolveFormat(source);
        if (template == null || template.isEmpty()) {
            // No template configured: fall back to "<name> message" so nothing is lost.
            return Component.text(source.getName() + " ")
                    .append(buildMessageComponent(source, typedMessage));
        }
        // Resolve PlaceholderAPI + legacy/hex colours, leaving the <message> tag intact
        // for the safe component insertion below.
        String resolved = formatter.toMiniMessageText(source, template);
        return miniMessage.deserialize(resolved,
                Placeholder.component("message", buildMessageComponent(source, typedMessage)));
    }

    private Component buildMessageComponent(Player source, Component typedMessage) {
        String raw = PlainTextComponentSerializer.plainText().serialize(typedMessage);
        int kinds = allowedKinds(source);
        if (kinds == 0) {
            return Component.text(raw);
        }
        // Legacy '&' codes of the allowed kinds become MiniMessage tags first; then only
        // the allowed tags are parsed. Everything else stays as the literal text typed.
        String withTags = LegacyColorTranslator.toMiniMessage(raw, (kinds & COLOR) != 0, (kinds & DECORATION) != 0);
        return playerParser(kinds).deserialize(withTags);
    }

    private int allowedKinds(Player source) {
        if (source.isOp() && plugin.getConfig().getBoolean("chat.player-formatting.op-bypass", true)) {
            return COLOR | DECORATION | GRADIENT | ALL;
        }
        // Before chat.player-formatting existed, the single chat.color.permission
        // granted everything; a config that still only has that key keeps doing so.
        if (!plugin.getConfig().isSet("chat.player-formatting") && plugin.getConfig().isSet("chat.color.permission")) {
            String legacy = plugin.getConfig().getString("chat.color.permission", "");
            return legacy != null && !legacy.isBlank() && source.hasPermission(legacy)
                    ? COLOR | DECORATION | GRADIENT : 0;
        }
        int kinds = 0;
        if (allows(source, "color", "hippotab.chat.color")) {
            kinds |= COLOR;
        }
        if (allows(source, "decoration", "hippotab.chat.decoration")) {
            kinds |= DECORATION;
        }
        if (allows(source, "gradient", "hippotab.chat.gradient")) {
            kinds |= GRADIENT;
        }
        if (plugin.getConfig().isSet("chat.player-formatting.all")
                && allows(source, "all", "hippotab.chat.minimessage")) {
            kinds |= COLOR | DECORATION | GRADIENT | ALL;
        }
        return kinds;
    }

    private boolean allows(Player source, String kind, String defaultPermission) {
        String base = "chat.player-formatting." + kind;
        if (!plugin.getConfig().getBoolean(base + ".enabled", true)) {
            return false;
        }
        String permission = plugin.getConfig().getString(base + ".permission", defaultPermission);
        // A blank permission opens this kind to everyone.
        return permission == null || permission.isBlank() || source.hasPermission(permission);
    }

    private MiniMessage playerParser(int kinds) {
        if ((kinds & ALL) != 0) {
            return miniMessage;
        }
        MiniMessage parser = playerParsers[kinds];
        if (parser == null) {
            TagResolver.Builder tags = TagResolver.builder().resolver(StandardTags.reset());
            if ((kinds & COLOR) != 0) {
                tags.resolver(StandardTags.color());
            }
            if ((kinds & DECORATION) != 0) {
                tags.resolver(StandardTags.decorations());
            }
            if ((kinds & GRADIENT) != 0) {
                tags.resolver(StandardTags.gradient()).resolver(StandardTags.rainbow());
            }
            parser = MiniMessage.builder().tags(tags.build()).build();
            playerParsers[kinds] = parser;
        }
        return parser;
    }

    /**
     * Returns the format of the first custom entry whose permission the player has,
     * falling back to {@code chat.default}.
     */
    private String resolveFormat(Player source) {
        List<Map<?, ?>> formats = plugin.getConfig().getMapList("chat.formats");
        for (Map<?, ?> entry : formats) {
            Object permission = entry.get("permission");
            if (permission == null || !source.hasPermission(String.valueOf(permission))) {
                continue;
            }
            Object value = entry.get("format");
            if (value != null) {
                return String.valueOf(value);
            }
        }
        return plugin.getConfig().getString("chat.default");
    }
}
