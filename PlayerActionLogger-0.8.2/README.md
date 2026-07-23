# PlayerActionLogger 0.8.2

PlayerActionLogger is a lightweight Paper Minecraft moderation-evidence plugin by **BirdmanAustin-dev**. It preserves readable username-based text logs while adding compact PvP incident reconstruction, cautious moderation review flags, rolling activity classification, player profiles, statistics, an activity feed, alerts, and a polished local dashboard.

## Target

- Paper 26.2 build #65 beta
- Paper API `26.2.build.65-beta`
- Java 25
- Maven
- Paper only; not Spigot

## Build

```bash
mvn clean package
```

Expected output:

```text
target/PlayerActionLogger-Paper-0.8.2.jar
```

## Evidence folders

```text
plugins/PlayerActionLogger/
├── config.yml
├── web/
└── logs/
    ├── _server.log
    ├── players/
    │   ├── <username>.log
    │   └── _uuid-index.csv
    └── combat/
        └── <PvP incident ID>.log
```

There is no database, activity-summary folder, movement-log folder, or generated map data.

## Core logging

The existing username logs remain the primary evidence record. They include supported joins, quits, kicks, chat, commands, deaths, block breaks/placements, inventory activity, item pickup/drop, crafting, furnace extraction, buckets, signs, portals, explosions, pistons, fire, combat, and selected server/world events.

UUID history remains in `logs/players/_uuid-index.csv`. Raw water/lava flow remains quiet by default while player bucket actions and environmental combat context remain logged.

## PvP incidents

PvP creates compact `PVP_HIT` entries in both players' normal logs and one detailed report under `logs/combat/`. The combat report begins with an LLM-friendly summary and then preserves the raw hit timeline.

The combat system includes:

- probable initiator, confidence, and recorded-hit reason;
- possible unprovoked-attack review;
- repeated encounters and repeated kills;
- spawn-attack context that distinguishes remaining near respawn from leaving and returning to the prior fight area;
- combat logout;
- attacks shortly after login/teleport or while sleeping/in an inventory;
- environmental water/lava/fire context;
- post-death pickup, owner recovery, possible return, and unreturned-loot summaries.

These are evidence prompts, not automatic guilt findings. Protected regions and prohibited-weapon rules remain intentionally excluded.

## Activity classification

The dashboard uses an in-memory rolling window, 60 minutes by default. The underlying actions remain in normal text logs.

Supported classifications include:

- Building
- Possible demolition
- Mining
- Likely strip mining
- Woodcutting
- Excavating / landscaping
- Farming
- Animal care
- Fishing
- Travel / exploration
- Crafting / utility
- Items / storage
- Redstone engineering
- Environmental work
- Chat / commands
- PvE combat
- PvP combat
- Idle / AFK
- General activity

Exploration is sampled by both time and chunk distance. Chunk travel does not reset AFK status, is capped in the activity breakdown, and is only selected as the player's main activity when the player has done very little meaningful work or combat. Movement is not written as movement-event spam.

```yml
activity:
  exploration:
    enabled: true
    minimum-seconds-between-events: 45
    minimum-chunk-distance: 4
    classification-minimum-events: 4
    breakdown-weight-cap: 8
```

## Dashboard

Version 0.8.2 includes the dashboard with day-by-day historical moderation intelligence in addition to the live activity, combat, alerts, and insights views.

### Overview

Shows a live server picture, current activity cards, recent grouped activity, review alerts, current/recent combat, and the most active areas.

### Player profiles

Each active player profile includes:

- current likely activity;
- rolling action totals;
- activity breakdown bars;
- current summary details;
- recent grouped activity;
- top active chunks;
- linked combat incidents;
- profile-specific review alerts;
- direct access to the raw username log.


### Player history by day

The Player History area reads the complete retained username log on demand and groups activity by calendar day. Moderators can choose a player, sort through available dates, and review:

- the dominant activity for that day;
- first and last recorded times;
- totals by activity category;
- notable evidence such as PvP, deaths, Elytra activity, lava, fire, teleporting, and valuable ores;
- grouped examples for building, mining, storage, chat, commands, combat, travel, and other behavior;
- every raw player-log line from the selected day.

Historical summaries are file-backed and cached by file size and modification time. No database or additional history folder is created.

### Activity feed

Recent events are grouped by player, category, chunk, and configurable short time bucket. This avoids displaying every block action independently while retaining a direct raw-log link.

### Alerts

The dashboard combines combat flags and activity-pattern alerts. Alerts are sorted as HIGH, MEDIUM, or LOW and link directly to the relevant combat incident or username log. Open alerts remain available until an administrator marks them closed. Closing an alert stores only its OPEN/CLOSED state; no reviewer name, note, or closure time is retained.

Activity alerts can include:

- possible demolition patterns;
- low crop replant rate;
- high ore-event concentration;
- frequent fire/bucket activity;
- sustained PvP activity.

### Lightweight heatmap substitute

The dashboard does not render a world map. It ranks frequently active chunks based on visits, work, block changes, combat, involved players, and an activity score.

### Statistics

The Insights page shows rolling totals for:

- online, active, idle, and tracked players;
- total events;
- blocks placed/broken;
- PvP and PvE events;
- farming, crafting, inventory, and chunk-transition activity;
- activity classification mix;
- frequently active areas.

### Raw evidence links

Activity feed entries, player profiles, alerts, combat rows, and search results all lead back to the underlying player, server, or combat evidence.

## Optional player-log retention

Player-log retention is disabled by default. When enabled, the plugin removes timestamped lines older than the configured number of days from username log files. The UUID index, server log, and combat incident files are not deleted by this setting, and malformed or legacy lines are preserved rather than deleted.

```yml
logging:
  retention:
    enabled: false
    days: 7
    run-interval-hours: 24
```

Use `days: 3`, `days: 7`, or another positive value after enabling the feature. Historical dashboard results reflect whatever evidence remains in the retained username log.

## Web dashboard address and port

The dashboard port is configurable. Set `web-interface.port` to any unused TCP port from 1 through 65535, then restart the server.

```yml
web-interface:
  enabled: true
  port: 8080
  bind-address: "127.0.0.1"
  access-token: ""
```

Examples include `8080`, `8081`, `8888`, or `9090`. If the selected port is invalid, the plugin warns and falls back to `8080`. If the port is already occupied, the plugin remains enabled but reports that the dashboard could not start so the administrator can select another port.

Keep `bind-address` set to `127.0.0.1` for server-local access. Using `0.0.0.0` permits network access and should be combined with a dashboard token and firewall protection.

## Dashboard configuration

```yml
dashboard:
  feed-bucket-minutes: 5
  feed-limit: 60
  profile-feed-limit: 20
  alert-limit: 40
```

## Lightweight architecture

```text
Paper event
  -> capture essential values on the event thread
  -> queue text-log writes on the background writer
  -> update small in-memory rolling summaries
  -> dashboard reads summaries and links to raw text evidence
```

No database is used. Dashboard calculations reset after a plugin restart; the text logs and completed combat files do not.
