package net.mwtw.hippoTab.config;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;

public record ConditionalPlaceholderConfig(
    String sourcePlaceholder,
    List<ConditionalPlaceholderRule> rules
) {
    public static ConditionalPlaceholderConfig from(ConfigurationSection section) {
        String sourcePlaceholder = section.getString("placeholder", "");
        List<ConditionalPlaceholderRule> rules = new ArrayList<>();
        for (String ruleText : section.getStringList("rules")) {
            ConditionalPlaceholderRule rule = ConditionalPlaceholderRule.parse(ruleText);
            if (rule != null) {
                rules.add(rule);
            }
        }
        return new ConditionalPlaceholderConfig(sourcePlaceholder, List.copyOf(rules));
    }

    public boolean isValid() {
        return sourcePlaceholder != null
            && !sourcePlaceholder.isBlank()
            && rules != null
            && !rules.isEmpty();
    }
}
