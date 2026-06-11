package net.mwtw.hippoNick.storage;

import java.util.Locale;

public enum StorageType {
    FILE,
    MARIADB;

    public static StorageType from(String raw) {
        if (raw == null) {
            return FILE;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if ("MARIADB".equals(normalized) || "MYSQL".equals(normalized)) {
            return MARIADB;
        }
        return FILE;
    }
}
