package net.mwtw.hippoNick.listener;

import net.mwtw.hippoNick.service.NickManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.ArrayList;
import java.util.List;

public final class CommandNicknameBridgeListener implements Listener {
    private final NickManager nickManager;

    public CommandNicknameBridgeListener(NickManager nickManager) {
        this.nickManager = nickManager;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPreprocess(PlayerCommandPreprocessEvent event) {
        String raw = event.getMessage();
        if (raw == null || raw.isBlank() || !raw.startsWith("/")) {
            return;
        }
        List<String> parts = splitPreservingQuoted(raw.substring(1));
        if (parts.size() <= 1) {
            return;
        }

        boolean changed = false;
        for (int i = 1; i < parts.size(); i++) {
            String token = parts.get(i);
            if (!isResolvablePlayerToken(token)) {
                continue;
            }
            var resolved = nickManager.resolveRealPlayerExact(token);
            if (resolved.isEmpty()) {
                continue;
            }
            String real = resolved.get().getName();
            if (real.equalsIgnoreCase(token)) {
                continue;
            }
            parts.set(i, real);
            changed = true;
        }
        if (!changed) {
            return;
        }

        String rebuilt = "/" + String.join(" ", parts);
        event.setMessage(rebuilt);
    }

    private boolean isResolvablePlayerToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        if (token.startsWith("@")) {
            return false;
        }
        if (token.contains(":")) {
            return false;
        }
        return !token.contains("=");
    }

    private List<String> splitPreservingQuoted(String input) {
        ArrayList<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '"') {
                quoted = !quoted;
                continue;
            }
            if (Character.isWhitespace(c) && !quoted) {
                if (!current.isEmpty()) {
                    out.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(c);
        }
        if (!current.isEmpty()) {
            out.add(current.toString());
        }
        return out;
    }
}
