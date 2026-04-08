package net.mwtw.hippoTab.config;

public record ConditionalPlaceholderRule(
    String operator,
    String expectedValue,
    String result
) {
    public static ConditionalPlaceholderRule parse(String raw) {
        if (raw == null) {
            return null;
        }

        String trimmed = raw.trim();
        int separatorIndex = trimmed.indexOf(';');

        if (separatorIndex < 0) {
            return new ConditionalPlaceholderRule(null, null, unquote(trimmed));
        }

        String condition = trimmed.substring(0, separatorIndex).trim();
        String result = unquote(trimmed.substring(separatorIndex + 1).trim());

        if (condition.startsWith("==")) {
            return new ConditionalPlaceholderRule("==", unquote(condition.substring(2).trim()), result);
        }
        if (condition.startsWith("!=")) {
            return new ConditionalPlaceholderRule("!=", unquote(condition.substring(2).trim()), result);
        }

        return new ConditionalPlaceholderRule(null, null, result);
    }

    public boolean isFallback() {
        return operator == null || operator.isBlank();
    }

    private static String unquote(String value) {
        if (value == null) {
            return "";
        }
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}
