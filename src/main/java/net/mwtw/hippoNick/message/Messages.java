package net.mwtw.hippoNick.message;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.mwtw.hippoNick.Core;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Every player-facing text of the plugin, from messages.yml (MiniMessage). Keys missing
 * from the server's file fall back to the messages.yml bundled in the jar, so new
 * messages work without regenerating the file.
 */
public final class Messages {
    // Texts that used to live in config.yml. A server that customised one there keeps
    // it until the key is written into its messages.yml.
    private static final Map<String, String> LEGACY_CONFIG_PATHS = Map.ofEntries(
            Map.entry("connmsg.toggled", "join-quit.messages.toggled"),
            Map.entry("connmsg.status", "join-quit.messages.status"),
            Map.entry("connmsg.state-on", "join-quit.messages.state-on"),
            Map.entry("connmsg.state-off", "join-quit.messages.state-off"),
            Map.entry("connmsg.usage", "join-quit.messages.usage"),
            Map.entry("togglechat.hidden", "chat.messages.chat-hidden"),
            Map.entry("togglechat.shown", "chat.messages.chat-shown"),
            Map.entry("togglechat.disabled", "chat.messages.toggle-disabled"),
            Map.entry("chatfilter.rate-limited", "chat-filter.repeat-limit.message"),
            Map.entry("chatfilter.duplicate", "chat-filter.duplicate-limit.message"));

    private final Core plugin;
    private final MiniMessage miniMessage;
    private YamlConfiguration yaml;

    public Messages(Core plugin) {
        this.plugin = plugin;
        this.miniMessage = MiniMessage.miniMessage();
        load();
    }

    public void load() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        this.yaml = YamlConfiguration.loadConfiguration(file);
        try (InputStream bundled = plugin.getResource("messages.yml")) {
            if (bundled != null) {
                yaml.setDefaults(YamlConfiguration.loadConfiguration(
                        new InputStreamReader(bundled, StandardCharsets.UTF_8)));
            }
        } catch (Exception exception) {
            plugin.getLogger().warning("Could not read the bundled messages.yml: " + exception.getMessage());
        }
    }

    public void send(CommandSender sender, String key, MessagePlaceholder... placeholders) {
        sender.sendMessage(component(key, placeholders));
    }

    /** Sends a list-valued message (one chat line per list entry). Falls back to a single line. */
    public void sendList(CommandSender sender, String key, MessagePlaceholder... placeholders) {
        List<String> lines = yaml.getStringList(key);
        if (lines.isEmpty()) {
            send(sender, key, placeholders);
            return;
        }
        TagResolver resolver = resolver(placeholders);
        for (String line : lines) {
            sender.sendMessage(miniMessage.deserialize(line, resolver));
        }
    }

    public Component component(String key, MessagePlaceholder... placeholders) {
        return miniMessage.deserialize(template(key), resolver(placeholders));
    }

    /** Like {@link #component(String, MessagePlaceholder...)} with extra tags (e.g. a component placeholder). */
    public Component component(String key, TagResolver extra, MessagePlaceholder... placeholders) {
        return miniMessage.deserialize(template(key), TagResolver.resolver(extra, resolver(placeholders)));
    }

    public String plain(String key, MessagePlaceholder... placeholders) {
        return PlainTextComponentSerializer.plainText().serialize(component(key, placeholders));
    }

    /** True when the message is set to an empty string, i.e. the server turned it off. */
    public boolean isBlank(String key) {
        return template(key).isBlank();
    }

    private String template(String key) {
        if (!yaml.isSet(key)) {
            String legacyPath = LEGACY_CONFIG_PATHS.get(key);
            if (legacyPath != null && plugin.getConfig().isSet(legacyPath)) {
                String legacy = plugin.getConfig().getString(legacyPath);
                if (legacy != null) {
                    return legacy;
                }
            }
        }
        return yaml.getString(key, "<red>Missing message key:</red> <gray>" + key + "</gray>");
    }

    // Unparsed: values are player names, nicknames and typed text, never markup.
    private static TagResolver resolver(MessagePlaceholder... placeholders) {
        List<TagResolver> resolvers = new ArrayList<>(placeholders.length);
        for (MessagePlaceholder placeholder : placeholders) {
            resolvers.add(Placeholder.unparsed(placeholder.key(), placeholder.value()));
        }
        return TagResolver.resolver(resolvers);
    }
}
