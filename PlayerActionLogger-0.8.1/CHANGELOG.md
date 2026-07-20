# Changelog

## 0.8.1

- Made the dashboard listening port explicitly configurable through `web-interface.port` in `config.yml`.
- Accepts any valid TCP port from 1 through 65535.
- Added clear configuration examples and restart instructions.
- Added validation for invalid port values with a warning and fallback to port 8080.
- Improved dashboard startup reporting so the plugin only announces a dashboard URL after a successful bind.
- Added a clear warning when the configured address or port is unavailable.

## 0.8.0

- Updated the Paper API target to `26.2.build.62-beta` and bumped the plugin version to 0.8.0.
- Removed unnecessary Paper build and rolling-window explanation text from the web dashboard.
- Shortened the Combat overview description to "Timeline underneath."
- Removed the rendered-map disclaimer from the Overview active-area card.
- Reworked exploration tracking so ordinary movement cannot flood or dominate player activity:
  - travel checkpoints require both a configurable time interval and chunk distance;
  - chunk travel no longer resets AFK status;
  - exploration has a capped dashboard weight;
  - exploration is only selected as the primary activity when little meaningful work or combat occurred.
- Expanded live activity inference with building, demolition, mining, strip mining, woodcutting, excavation/landscaping, farming, animal care, fishing, crafting/utility, items/storage, redstone engineering, environmental work, chat/commands, PvE, PvP, travel/exploration, idle, and general activity.
- Added fishing, chat, command, teleport, consumption, interactive-block, and animal-care inputs to the activity analyzer.
- Added configurable player-log retention. It is disabled by default and preserves the UUID index, server log, combat files, and uncertain legacy lines.
- Added the Player History moderation-intelligence area:
  - player selector;
  - newest-first list of active dates;
  - daily dominant activity and category totals;
  - activity groups with examples;
  - notable evidence highlights;
  - every raw username-log line for the selected day;
  - `/api/history?player=<username>` and `/api/history?player=<username>&date=YYYY-MM-DD`.
- Added file-change-aware caching for historical day indexes without adding a database.

## 0.7.0

- Added complete player profile pages with activity mix, rolling statistics, top areas, recent activity, alerts, linked combat, and raw-log access.
- Added a dedicated grouped activity feed with category filters and raw-evidence links.
- Added a complete Alerts page merging combat flags and activity-pattern review signals.
- Added rolling dashboard statistics and CSS bar visualizations without an external chart library.
- Expanded the lightweight active-area ranking into an Insights view that acts as a non-map heatmap substitute.
- Added `/api/profile?player=<username>`.
- Expanded `/api/activity` with statistics, feed items, and activity alerts.
- Fully redesigned the dashboard UI with responsive navigation, polished cards, modals, filters, charts, and mobile layouts.
