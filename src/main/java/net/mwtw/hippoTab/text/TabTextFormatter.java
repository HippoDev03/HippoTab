package net.mwtw.hippoTab.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.mwtw.hippoTab.service.PlaceholderService;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.stream.Collectors;

public final class TabTextFormatter {
    private final PlaceholderService placeholderService;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public TabTextFormatter(PlaceholderService placeholderService) {
        this.placeholderService = placeholderService;
    }

    public Component toComponent(Player player, String text) {
        String withPlaceholders = placeholderService.apply(player, text);
        return miniMessage.deserialize(LegacyColorTranslator.toMiniMessage(withPlaceholders));
    }

    public Component linesToComponent(Player player, List<String> lines) {
        String joined = lines.stream()
            .map(line -> placeholderService.apply(player, line))
            .collect(Collectors.joining("\n"));
        return miniMessage.deserialize(LegacyColorTranslator.toMiniMessage(joined));
    }
}
