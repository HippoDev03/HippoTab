package net.mwtw.hippoNick.listener;

import net.mwtw.hippoNick.service.NickManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Lets commands find a nicked player by the nick when the server itself would not
 * (the profile is not renamed, e.g. packet-only nicks): a command argument that is a
 * nickname is swapped for the player's real name. Free text is never touched, so a
 * nick written inside a private message or /me never turns into the real name.
 */
public final class CommandNicknameBridgeListener implements Listener {
    // Commands whose arguments are text, not player names.
    private static final Set<String> TEXT_COMMANDS = Set.of("say", "me", "teammsg", "tm", "broadcast", "bc");

    private final Plugin plugin;
    private final NickManager nickManager;

    public CommandNicknameBridgeListener(Plugin plugin, NickManager nickManager) {
        this.plugin = plugin;
        this.nickManager = nickManager;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPreprocess(PlayerCommandPreprocessEvent event) {
        String raw = event.getMessage();
        if (raw == null || raw.length() < 2 || raw.charAt(0) != '/') {
            return;
        }
        int labelEnd = nextSpace(raw, 1);
        String label = raw.substring(1, labelEnd).toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) {
            label = label.substring(colon + 1);
        }
        int maxArgs = playerArgumentLimit(label);
        if (maxArgs == 0) {
            return;
        }

        // Replace tokens in place so spacing, quotes and the rest of the line stay as typed.
        StringBuilder out = new StringBuilder(raw.length());
        out.append(raw, 0, labelEnd);
        int index = labelEnd;
        int argument = 0;
        boolean changed = false;
        while (index < raw.length()) {
            if (raw.charAt(index) == ' ') {
                out.append(' ');
                index++;
                continue;
            }
            int end = nextSpace(raw, index);
            String token = raw.substring(index, end);
            argument++;
            String replacement = argument <= maxArgs ? realNameFor(token) : null;
            if (replacement != null) {
                out.append(replacement);
                changed = true;
            } else {
                out.append(token);
            }
            index = end;
        }
        if (changed) {
            event.setMessage(out.toString());
        }
    }

    /** How many leading arguments may be player names: 0 = none, MAX_VALUE = any. */
    private int playerArgumentLimit(String label) {
        if (TEXT_COMMANDS.contains(label) || configured("private-messages.reply-commands", label)) {
            return 0;
        }
        // "/tell <player> <message>": only the target.
        if (configured("private-messages.commands", label)) {
            return 1;
        }
        return Integer.MAX_VALUE;
    }

    private boolean configured(String path, String label) {
        List<String> labels = plugin.getConfig().getStringList(path);
        for (String entry : labels) {
            if (entry != null && entry.trim().replace("/", "").equalsIgnoreCase(label)) {
                return true;
            }
        }
        return false;
    }

    /** The real name to put in place of this token, or null to leave it alone. */
    private String realNameFor(String token) {
        if (token.isEmpty() || token.startsWith("@") || token.contains(":") || token.contains("=")
                || token.contains("\"")) {
            return null;
        }
        Optional<Player> resolved = nickManager.resolveRealPlayerExact(token);
        if (resolved.isEmpty()) {
            return null;
        }
        Player player = resolved.get();
        // The server already knows the player by this name (the profile is renamed to the
        // nick, or it is the real name): rewriting would only leak the real name.
        if (player.getName().equalsIgnoreCase(token)) {
            return null;
        }
        String real = nickManager.realName(player);
        return real.equalsIgnoreCase(token) ? null : real;
    }

    private static int nextSpace(String text, int from) {
        int space = text.indexOf(' ', from);
        return space < 0 ? text.length() : space;
    }
}
