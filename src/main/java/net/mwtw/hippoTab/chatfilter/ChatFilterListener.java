package net.mwtw.hippoTab.chatfilter;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.mwtw.hippoNick.message.MessagePlaceholder;
import net.mwtw.hippoNick.message.Messages;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Chat filter (the former HippoChatFilter plugin): blocks URLs and flooding, hides
 * messages carrying IPs/domains, and censors listed words. Runs at HIGHEST, after the
 * chat formatter ({@code chat.ChatListener}, HIGH) has installed its renderer, so the
 * censoring wraps the final formatted line.
 */
public final class ChatFilterListener implements Listener {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final Pattern WEB_URL_PATTERN =
            Pattern.compile("(?i)\\b(?:https?://|www\\.)\\S+\\b");

    private final FilterManager filterManager;
    private final DiscordNotifier discordNotifier;
    private final SpamGuard spamGuard;
    // How a player is named in staff reports: the login name, plus the nick if any.
    private final Function<Player, String> realNameResolver;
    private final Messages messages;
    private final Logger logger;

    public ChatFilterListener(FilterManager filterManager, DiscordNotifier discordNotifier,
                              SpamGuard spamGuard, Function<Player, String> realNameResolver,
                              Messages messages, Logger logger) {
        this.messages = messages;
        this.logger = logger;
        this.filterManager = filterManager;
        this.discordNotifier = discordNotifier;
        this.spamGuard = spamGuard;
        this.realNameResolver = realNameResolver;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!filterManager.isEnabled()) {
            return;
        }
        Player player = event.getPlayer();
        String original = PLAIN.serialize(event.message());
        if (shouldBlockWebUrl(player, original) || isSpamBlocked(player, original)) {
            event.setCancelled(true);
            return;
        }
        if (handleAddressDetection(player, original)) {
            // Keep the sender as the only viewer; other players and console won't see it.
            event.viewers().removeIf(viewer -> !viewer.equals(player));
        }
        WordMatcher.Result scan = filterManager.scan(original);
        if (scan.matched()) {
            // Update the message itself (for plugins/logs that read event.message()).
            event.message(Component.text(scan.censored()));
            // Wrap whatever renderer is set (ours runs last at HIGHEST) so the final
            // line shown to players is filtered even if another plugin re-rendered
            // it from the original text. The other plugin's formatting is preserved.
            List<String> hits = scan.hits();
            ChatRenderer previous = event.renderer();
            event.renderer((source, sourceDisplayName, message, viewer) ->
                    censor(previous.render(source, sourceDisplayName, message, viewer), hits));
        }
    }

    /** Replaces each censored piece of the original message inside a rendered chat line, keeping styling. */
    private static Component censor(Component line, List<String> hits) {
        Component out = line;
        for (String hit : hits) {
            Component stars = Component.text("*".repeat(hit.length()));
            out = out.replaceText(builder -> builder.matchLiteral(hit).replacement(stars));
        }
        return out;
    }

    // The spam limits (and a spam mute) also cover the commands listed in
    // chat-filter.spam-commands, so they cannot be used to flood instead of chat.
    // LOW: before the private-message monitor and most command handling.
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!filterManager.isEnabled()) {
            return;
        }
        String raw = event.getMessage();
        if (raw == null || raw.length() < 2 || raw.charAt(0) != '/') {
            return;
        }
        String body = raw.substring(1).trim();
        int space = body.indexOf(' ');
        String label = (space < 0 ? body : body.substring(0, space)).toLowerCase(Locale.ROOT);
        int namespace = label.indexOf(':');
        if (namespace >= 0) {
            label = label.substring(namespace + 1);
        }
        if (filterManager.isSpamCommand(label) && isSpamBlocked(event.getPlayer(), body)) {
            event.setCancelled(true);
        }
    }

    // The spam state is deliberately NOT cleared here: reconnecting must not lift a
    // mute or reset the counters. SpamGuard forgets idle players on its own.
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        discordNotifier.clear(event.getPlayer().getUniqueId());
    }

    /** Applies the rate limit, the duplicate limit and any running spam mute; tells the player why. */
    private boolean isSpamBlocked(Player player, String text) {
        SpamGuard.Settings settings = filterManager.getSpamSettings();
        boolean rate = settings.rateEnabled() && !bypasses(player, filterManager.getRepeatBypassPermission());
        boolean duplicate = settings.duplicateEnabled() && !bypasses(player, filterManager.getDuplicateBypassPermission());
        if (!rate && !duplicate) {
            return false;
        }
        SpamGuard.Result result = spamGuard.check(player.getUniqueId(), WordMatcher.fold(text),
                settings.limitedTo(rate, duplicate));
        if (!result.blocked()) {
            return false;
        }
        String key = switch (result.verdict()) {
            case RATE -> "chatfilter.rate-limited";
            case DUPLICATE -> "chatfilter.duplicate";
            default -> null;
        };
        if (key != null && !messages.isBlank(key)) {
            messages.send(player, key);
        }
        if (result.muteSecondsLeft() > 0 && !messages.isBlank("chatfilter.muted")) {
            messages.send(player, "chatfilter.muted", MessagePlaceholder.of("seconds", result.muteSecondsLeft()));
        }
        return true;
    }

    private static boolean bypasses(Player player, String permission) {
        return permission != null && !permission.isBlank() && player.hasPermission(permission);
    }

    private boolean shouldBlockWebUrl(Player player, String message) {
        if (!filterManager.isWebUrlBlockEnabled()) {
            return false;
        }
        String bypassPermission = filterManager.getWebUrlBypassPermission();
        if (bypassPermission != null && !bypassPermission.isBlank() && player.hasPermission(bypassPermission)) {
            return false;
        }
        return WEB_URL_PATTERN.matcher(message).find();
    }

    /**
     * Runs IP and domain detection, and if either matches, hides the message and
     * sends a single merged Discord notification listing which kinds were hit.
     * Returns true when the message should be hidden from other players.
     */
    private boolean handleAddressDetection(Player player, String message) {
        boolean ipHit = detectIp(player, message);
        boolean domainHit = detectDomain(player, message);
        if (!ipHit && !domainHit) {
            return false;
        }

        List<String> kinds = new ArrayList<>(2);
        if (ipHit) {
            kinds.add("IP");
        }
        if (domainHit) {
            kinds.add("網域");
        }
        String label = String.join("、", kinds);

        // Resolve each feature's webhook, falling back to the other when blank.
        String ipUrl = orFallback(filterManager.getDiscordWebhookUrl(), filterManager.getDomainDiscordWebhookUrl());
        String domainUrl = orFallback(filterManager.getDomainDiscordWebhookUrl(), filterManager.getDiscordWebhookUrl());

        // De-duplicate so a shared channel only receives one merged message.
        Set<String> urls = new LinkedHashSet<>();
        if (ipHit && ipUrl != null && !ipUrl.isBlank()) {
            urls.add(ipUrl);
        }
        if (domainHit && domainUrl != null && !domainUrl.isBlank()) {
            urls.add(domainUrl);
        }
        String reportedName = reportedName(player);
        // The message is hidden from the console as well, so this line is the only
        // local record of it (and the only record at all without a webhook).
        logger.info("[ChatFilter] Hidden message (" + label + ") from " + reportedName + ": " + message);
        if (!urls.isEmpty()) {
            int skipped = discordNotifier.acquire(player.getUniqueId(), filterManager.getDiscordCooldownMillis());
            if (skipped >= 0) {
                String reported = skipped == 0 ? message : message + "\n(另有 " + skipped + " 則通知因冷卻而略過)";
                for (String url : urls) {
                    discordNotifier.notify(url, filterManager.getServerName(), label, reportedName, reported);
                }
            }
        }
        return true;
    }

    private String reportedName(Player player) {
        return realNameResolver.apply(player);
    }

    private boolean detectIp(Player player, String message) {
        boolean blockAll = filterManager.isIpBlockEnabled();
        List<Pattern> targets = filterManager.getIpTargets();
        if (!blockAll && targets.isEmpty()) {
            return false;
        }
        String bypassPermission = filterManager.getIpBypassPermission();
        if (bypassPermission != null && !bypassPermission.isBlank() && player.hasPermission(bypassPermission)) {
            return false;
        }
        boolean hit = blockAll && (filterManager.isIpDetectObfuscated()
                ? IpDetector.containsObfuscatedIp(message)
                : IpDetector.containsIp(message));
        // Targeted addresses are always watched, even when block-all is off.
        if (!hit) {
            hit = IpDetector.matchesAnyTarget(message, targets);
        }
        return hit;
    }

    private boolean detectDomain(Player player, String message) {
        if (!filterManager.isDomainBlockEnabled()) {
            return false;
        }
        String bypassPermission = filterManager.getDomainBypassPermission();
        if (bypassPermission != null && !bypassPermission.isBlank() && player.hasPermission(bypassPermission)) {
            return false;
        }
        Set<String> tlds = filterManager.getDomainTlds();
        return filterManager.isDomainDetectObfuscated()
                ? IpDetector.containsObfuscatedDomain(message, tlds)
                : IpDetector.containsDomain(message, tlds);
    }

    private static String orFallback(String primary, String fallback) {
        return (primary != null && !primary.isBlank()) ? primary : fallback;
    }
}
