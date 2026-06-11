package net.mwtw.hippoNick.message;

public record MessagePlaceholder(String key, String value) {
    public static MessagePlaceholder of(String key, Object value) {
        return new MessagePlaceholder(key, String.valueOf(value));
    }
}
