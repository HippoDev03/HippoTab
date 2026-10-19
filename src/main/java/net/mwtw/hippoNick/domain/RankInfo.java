package net.mwtw.hippoNick.domain;

import java.util.Map;

/** A LuckPerms group. {@code meta} holds the group's meta values (keys lower-cased). */
public record RankInfo(String name, int weight, String prefix, Map<String, String> meta) {
}
