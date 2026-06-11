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
import java.util.ArrayList;
import java.util.List;

public final class Messages {
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
        List<TagResolver> resolvers = new ArrayList<>();
        for (MessagePlaceholder placeholder : placeholders) {
            resolvers.add(Placeholder.unparsed(placeholder.key(), placeholder.value()));
        }
        TagResolver resolver = TagResolver.resolver(resolvers);
        for (String line : lines) {
            sender.sendMessage(miniMessage.deserialize(line, resolver));
        }
    }

    public Component component(String key, MessagePlaceholder... placeholders) {
        String template = yaml.getString(key, "<red>Missing message key:</red> <gray>" + key + "</gray>");
        List<TagResolver> resolvers = new ArrayList<>();
        for (MessagePlaceholder placeholder : placeholders) {
            resolvers.add(Placeholder.unparsed(placeholder.key(), placeholder.value()));
        }
        return miniMessage.deserialize(template, TagResolver.resolver(resolvers));
    }

    public String plain(String key, MessagePlaceholder... placeholders) {
        return PlainTextComponentSerializer.plainText().serialize(component(key, placeholders));
    }
}
