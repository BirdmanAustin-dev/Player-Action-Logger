# PlayerActionLogger Paper 26.2 Moderation Fork

A clean-room, Paper-targeted rebuild of the uploaded PlayerActionLogger plugin.

This version is aimed at your moderation use case: readable username logs, separate UUID tracking, quieter fluid logging, and a local dashboard for searches.


## Author / Maintainer

Plugin author: **BirdmanAustin-dev**

This name is also set in `plugin.yml` so Paper will identify the plugin author correctly when the plugin is loaded.

## Paper target

This project now targets **Paper 26.2**, not Spigot.

Important build settings:

```xml
<groupId>io.papermc.paper</groupId>
<artifactId>paper-api</artifactId>
<version>26.2.build.48-alpha</version>
```

```yml
api-version: '26.2'
```

It uses JDK 25:

```xml
<maven.compiler.release>25</maven.compiler.release>
```

## Build

Use JDK 25 and Maven:

```bash
mvn clean package
```

The built plugin should appear at:

```text
target/PlayerActionLogger-Paper-0.4.3.jar
```

Copy that jar into your Paper server's `plugins/` folder and restart the server.

## Why this is Paper-specific now

- The Maven repository is PaperMC only.
- The API dependency is `io.papermc.paper:paper-api`.
- `plugin.yml` uses `api-version: '26.2'`.
- Chat logging now uses Paper's `AsyncChatEvent` instead of the old Bukkit/Spigot `AsyncPlayerChatEvent`.

This is still a normal `plugin.yml` plugin because Paper's own project setup currently says the newer Paper manifest/plugin system is not recommended for ordinary plugins yet.

## Quieter water/lava flow logging

Raw `BlockFromToEvent` logging is noisy because one water or lava source can generate many flow events.

This fork defaults to:

```yml
logging:
  fluid-flow:
    enabled: false
```

Player-caused liquid use is still logged through:

- `BUCKET_EMPTY`
- `BUCKET_FILL`
- `BUCKET_USE`

If you really want flow logs, turn them on and keep aggregation enabled:

```yml
logging:
  fluid-flow:
    enabled: true
    aggregate: true
    aggregate-interval-seconds: 30
```

That writes summaries like:

```text
[2026-06-22 16:30:00] FLOW_SUMMARY LAVA in world chunk(12,-4) count:213
```

instead of hundreds of individual flow lines.

## Username logs plus separate UUID index

Player logs remain easy to read by username:

```text
plugins/PlayerActionLogger/logs/players/Austin.log
plugins/PlayerActionLogger/logs/players/Sniperkid.log
```

UUIDs are stored separately here:

```text
plugins/PlayerActionLogger/logs/players/_uuid-index.csv
```

CSV columns:

```text
timestamp,username,uuid
```

This preserves the practical moderation workflow of searching usernames while still giving you the UUID trail if a player changes names.

## Dashboard

Default local dashboard:

```text
http://127.0.0.1:8080
```

Keep the bind address on `127.0.0.1` unless you have firewall or reverse-proxy protection.

Optional simple token:

```yml
web-interface:
  access-token: "put-a-long-random-token-here"
```

Then open the dashboard, click **Set Token**, and enter the token.

## Log locations

```text
plugins/PlayerActionLogger/logs/_server.log
plugins/PlayerActionLogger/logs/players/<username>.log
plugins/PlayerActionLogger/logs/players/_uuid-index.csv
```

## Good searches for moderation

Useful terms:

- `ELYTRA` + `PICKUP`
- `ELYTRA` + `DROP`
- `BUCKET_EMPTY` + `LAVA`
- `BUCKET_EMPTY` + `WATER`
- `IGNITE`
- `FIRE_TOOL`
- `EXPLOSION`
- `INV_CLICK` + item name
- `DEATH`
- `KILL`

## Manual test checklist

1. Start a Paper 26.2 server with JDK 25 (experimental/alpha channel as appropriate).
2. Drop the built jar into `plugins/`.
3. Join as a player and verify `LOGIN` appears in `logs/players/<username>.log`.
4. Verify `_uuid-index.csv` contains the username and UUID.
5. Place and break blocks.
6. Empty water and lava buckets.
7. Confirm bucket actions are logged but raw flow spam is not logged with `fluid-flow.enabled: false`.
8. Pick up/drop a valuable item such as Elytra; confirm `PICKUP` and `DROP` include item names.
9. Open the dashboard and search for `ELYTRA`, `BUCKET_EMPTY`, and `IGNITE`.

## Note

I could not compile the final jar inside this environment because Maven and the Paper dependency cache are not available here. This ZIP is the source project ready to build on your machine or Raspberry Pi with internet access and JDK 25 installed.
