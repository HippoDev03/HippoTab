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
