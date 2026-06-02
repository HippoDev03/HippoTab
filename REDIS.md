# Redis tab sync

HippoTab can optionally sync player tab entries between multiple servers through Redis.

## What it does

- Publishes each server's current online players to Redis.
- Reads other servers' player snapshots.
- Injects remote players into the tab list so all connected servers share one tab list.

## Config

Use the `redis-sync` section in `config.yml`:

- `enabled`: turn sync on/off.
- `host`, `port`, `username`, `password`, `database`: Redis connection.
- `key-prefix`: namespace for Redis keys.
- `server-id`: unique id per server instance (auto defaults to `server-<port>` when blank).
- `publish-interval-ticks`: how often this server publishes/reads.
- `entry-ttl-seconds`: expiration window for stale server entries.

## Notes

- Keep `server-id` unique for every server sharing the same Redis.
- PacketEvents is required (already a plugin dependency).

## Velocity Registry Support

You can optionally require Redis-synced tab entries to be validated against a Velocity-managed registry:

- `redis-sync.velocity-registry.enabled`: enable proxy registry checks.
- `online-set-key`: Redis `SET` of currently online proxy UUIDs.
- `profiles-hash-key`: Redis `HASH` where field is UUID and value is:
  - `base64(name)|base64(prefixMiniMessage)|base64(weight)|base64(perm1,perm2,...)`
- `require-profile`: when true, player must exist in the profiles hash.
- `required-permission`: optional permission gate; player is hidden unless that permission exists in the profile.
- `use-profile-prefix-for-team`: overrides synced nametag team prefix from Velocity profile.
- `use-profile-weight-for-sorting`: overrides synced tab sort order from Velocity profile weight.

When enabled, players missing from the proxy online set are never injected into tab.

## Team Sync Safety

If clients see `Network Protocol Error` related to scoreboard/team packets:

- Set `redis-sync.team-sync.enabled: false` to fully ignore remote team syncing.
- Keep `redis-sync.team-sync.ignore-packet-errors: true` so team packet failures do not break Redis tab syncing.
