package net.mwtw.hippoTab.service;

import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ConditionParser {
    private static final Pattern WORLD_PATTERN = Pattern.compile("world=([^;]+)");
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("([^=;]+)=\\[([^]]+)]");

    private final PlaceholderService placeholderService;

    public ConditionParser(PlaceholderService placeholderService) {
        this.placeholderService = placeholderService;
    }

    public boolean evaluate(Player player, String conditions) {
        if (conditions == null || conditions.isBlank()) {
            return true;
        }

        String[] parts = conditions.split(";");
        for (String part : parts) {
            part = part.trim();
            if (part.isEmpty()) {
                continue;
            }

            if (!evaluateCondition(player, part)) {
                return false;
            }
        }

        return true;
    }

    private boolean evaluateCondition(Player player, String condition) {
        Matcher worldMatcher = WORLD_PATTERN.matcher(condition);
        if (worldMatcher.matches()) {
            String requiredWorld = worldMatcher.group(1).trim();
            return player.getWorld().getName().equalsIgnoreCase(requiredWorld);
        }

        Matcher placeholderMatcher = PLACEHOLDER_PATTERN.matcher(condition);
        if (placeholderMatcher.matches()) {
            String placeholder = placeholderMatcher.group(1).trim();
            String valuesStr = placeholderMatcher.group(2);
            
            List<String> allowedValues = parseValueList(valuesStr);
            String actualValue = placeholderService.apply(player, placeholder);

            for (String allowed : allowedValues) {
                if (actualValue.equalsIgnoreCase(allowed.trim())) {
                    return true;
                }
            }
            return false;
        }

        return true;
    }

    private List<String> parseValueList(String valuesStr) {
        List<String> values = new ArrayList<>();
        String[] parts = valuesStr.split(",");
        for (String part : parts) {
            String cleaned = part.trim();
            if (cleaned.startsWith("'") && cleaned.endsWith("'")) {
                cleaned = cleaned.substring(1, cleaned.length() - 1);
            } else if (cleaned.startsWith("\"") && cleaned.endsWith("\"")) {
                cleaned = cleaned.substring(1, cleaned.length() - 1);
            }
            values.add(cleaned);
        }
        return values;
    }
}
