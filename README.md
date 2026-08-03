# PlayerActionLogger 0.8.3

PlayerActionLogger is a lightweight Paper moderation and evidence plugin by **BirdmanAustin-dev**. It keeps readable username-based logs while adding PvP incident reconstruction, activity summaries, player history, durable alerts, dashboard moderation controls, and optional server backups.

## Target

- Paper 26.2 build #92 stable
- Paper API `26.2.build.92-stable`
- Java 25
- Maven
- Paper only; not Spigot

## Build

```bash
mvn clean package
```

Expected output:

```text
target/PlayerActionLogger-Paper-0.8.3.jar
```

## Main data

```text
plugins/PlayerActionLogger/
├── config.yml
├── moderation-alerts.json
├── temporary-bans.json
├── web/
└── logs/
    ├── _server.log
    ├── players/
    │   ├── <username>.log
    │   └── _uuid-index.csv
    └── combat/
        └── <PvP incident ID>.log
```

There is no database.

## Core logging and evidence

The username logs remain the primary evidence record. Supported events include joins, quits, kicks, locations, chat, commands, deaths, block changes, meaningful storage-container movement, item pickups and drops, crafting, furnace extraction, buckets, signs, portals, explosions, pistons, fire, PvE, and PvP.

PvP incidents create compact player-log entries and a detailed report under `logs/combat/`. Reports may include probable initiator, health, deaths, combat logout, spawn context, repeated encounters, environmental context, and post-death item pickups. These are review prompts rather than automatic guilt findings.

## Dashboard

The local dashboard includes:

- overview and grouped activity feed;
- player profiles and raw logs;
- day-by-day player history;
- Combat Center;
- durable moderation alerts;
- rolling statistics and active-area rankings;
- evidence search;
- kick, ban, temporary-ban, and pardon actions.

Dashboard actions require the configured token when `moderation-actions.require-access-token` is enabled.

## 0.8.3 moderation-signal fixes

The 0.8.3 maintenance update makes activity alerts intentionally conservative. High raw block-break volume by itself is no longer suspicious: mining stone, chopping trees, harvesting crops, and clearing terrain do not trigger demolition alerts merely because many blocks were broken. Demolition review now requires concentrated removal of structure-like blocks together with multiple storage, utility, or redstone infrastructure breaks.

Clicking an activity alert opens a focused evidence excerpt from the rolling window that produced the alert. A separate **Open full player log** button is available when the complete timeline is needed.

Container evidence remains complete in the username log, but repeated chest movements are sampled and capped in the live activity model. Historical pickup/drop events are classified as **Item movement**, and repetitive storage clicks are discounted when deciding the day's dominant activity.

## Improved moderation controls

Version 0.8.3 replaces the plain moderation form with action cards and a clearer temporary-ban scheduler.

Temporary bans may be set by:

- minutes;
- hours;
- days;
- quick presets such as 1 hour, 1 day, or 7 days; or
- an exact future unban date and time.

The dashboard previews the expiration time before applying the ban. Temporary bans use Paper's native expiring profile-ban entries, so the Paper ban list—not `temporary-bans.json`—is authoritative across restarts. `temporary-bans.json` is retained only as dashboard metadata and can be reconstructed for plugin-owned native temporary bans.

```yml
moderation-actions:
  enabled: true
  require-access-token: true
  default-temporary-ban-hours: 1
  maximum-temporary-ban-days: 30
```


## 0.8.3 reliability hardening

Multi-player combat segments now retain a short-lived encounter-root identifier across death/disconnect splits, allowing related group-fight evidence files to be correlated without forcing one giant incident object.

The build #92 patch includes a second moderation-correctness pass:

- combat logout uses a dedicated PvP timestamp rather than unrelated environmental damage;
- first-retaliation timing is preserved correctly;
- PvP player deaths do not appear as PvE kills in history;
- offline players cannot be classified as AFK or active PvP;
- UUID-prefixed player-log lines remain fully classifiable by Player History;
- mining review uses localized evidence rather than combining unrelated work from different areas;
- moving toward a suspected fire/lava/water source is mitigating rather than incriminating;
- hazard attribution weighs recency as well as distance;
- unprovoked-attack alerts are limited to 1-v-1 incidents;
- native ban/pardon operations are verified from Paper ban state, and temporary-ban metadata cannot override unrelated bans;
- returned death-loot remains provenance-tracked and the original pickup evidence is retained;
- API requests authenticate before expensive reads, URL query tokens are not accepted, and the web server uses a bounded worker pool;
- successful bed entry is distinguished from failed attempts;
- structured combat signals persist in combat files and reload after restart.


## Automatic server backups

Automatic backups are disabled by default.

```yml
backups:
  enabled: false
  interval-hours: 12
  retention-days: 3
  folder: "backups/PlayerActionLogger"
```

When enabled, the plugin:

1. saves each loaded world;
2. creates a ZIP of the server folder on a dedicated background thread;
3. excludes the configured backup folder so archives cannot contain themselves;
4. writes a small backup-information file inside the ZIP; and
5. deletes only PlayerActionLogger backup ZIPs older than `retention-days`.

The first scheduled backup runs after the configured interval. A relative `folder` is resolved from the Minecraft server folder. Set `retention-days: 0` to keep backups indefinitely.

These are live backups. The worlds are saved immediately before archiving, but players may remain online while files are copied. For critical or very large production servers, also maintain an external offline backup process.

## Player-log retention

Player-log retention is separate from server backups and is disabled by default. When enabled, it removes old timestamped lines only from username log files. It does not delete the UUID index, server log, combat reports, or uncertain legacy lines.

```yml
logging:
  retention:
    enabled: false
    days: 7
    run-interval-hours: 24
```

## Dashboard address

```yml
web-interface:
  enabled: true
  port: 8080
  bind-address: "127.0.0.1"
  access-token: ""
```

Keep `127.0.0.1` for local-only access. Network or internet exposure should use HTTPS, a strong token, and appropriate firewall or private-network protection.

## Lightweight architecture

```text
Paper event
  -> capture essential values
  -> queue text-log writes on one background writer
  -> update small rolling summaries
  -> dashboard links summaries back to raw evidence
```

Backup compression uses its own single background worker. No external database or backup library is required.

## License

See `LICENSE.txt` for the Birdman No-Sale Software License.
