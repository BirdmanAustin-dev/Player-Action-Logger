# PlayerActionLogger 0.7.0

PlayerActionLogger is a lightweight Paper Minecraft moderation-evidence plugin by **BirdmanAustin-dev**. It preserves readable username-based text logs while adding compact PvP incident reconstruction, cautious moderation review flags, rolling activity classification, player profiles, statistics, an activity feed, alerts, and a polished local dashboard.

## Target

- Paper 26.2 build #60 beta
- Paper API `26.2.build.60-beta`
- Java 25
- Maven
- Paper only; not Spigot

## Build

```bash
mvn clean package
```

Expected output:

```text
target/PlayerActionLogger-Paper-0.7.0.jar
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
- Farming
- Exploring
- Crafting / smelting
- Storage / inventory
- Redstone
- PvE combat
- PvP combat
- Idle / AFK
- General activity

Movement is used only for low-noise chunk-level context. It is not written as movement-event spam.

## Phase 4 dashboard

Version 0.7.0 completes the planned dashboard work.

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

### Activity feed

Recent events are grouped by player, category, chunk, and configurable short time bucket. This avoids displaying every block action independently while retaining a direct raw-log link.

### Alerts

The dashboard combines combat flags and activity-pattern alerts. Alerts are sorted as HIGH, MEDIUM, or LOW and link directly to the relevant combat incident or username log. They remain cautious review prompts.

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

## Dashboard configuration

```yml
dashboard:
  feed-bucket-minutes: 5
  feed-limit: 60
  profile-feed-limit: 20
  alert-limit: 40
```

The dashboard remains local by default at `127.0.0.1:8080`. Do not expose it publicly without authentication and network protection.

## Lightweight architecture

```text
Paper event
  -> capture essential values on the event thread
  -> queue text-log writes on the background writer
  -> update small in-memory rolling summaries
  -> dashboard reads summaries and links to raw text evidence
```

No database is used. Dashboard calculations reset after a plugin restart; the text logs and completed combat files do not.
