# PlayerActionLogger 0.8.3

## Final audit stabilization — Paper 26.2 build #92 stable

- Native expiring profile bans and safe legacy temp-ban migration.
- Combat merge aliases/timeline ordering and offline environmental participant fixes.
- Context-only environmental evidence no longer inflates PvP activity.
- Stronger death-loot item fingerprints and confidence-aware signal merging.
- Corrected farming, ore-cluster, stored-health, container-swap, and shift-craft evidence.

# Changelog

## 0.8.3

### Combat/moderation stabilization pass

- Replaced free-form combat flag strings as alert logic with structured `CombatSignal` records containing explicit rule, kind (`ALERT`, `CONTEXT`, `MITIGATING`), severity, confidence, and detail.
- Alert severity is no longer derived by searching English text for words such as `spawn` or `death-loot`.
- Combat moderation alerts are now emitted once when an incident is finalized instead of repeatedly rescanning recent historical combat summaries.
- Context and mitigating signals are visible in combat evidence but cannot create moderation alerts.
- Successful player state-change logging now uses `MONITOR` and `ignoreCancelled=true` for block, interaction, bucket, inventory, item, crafting, animal, fishing, hanging-entity, teleport, and related handlers where cancellation matters.
- Extended successful-action handling to cancellable death, movement, piston, fire/burn, growth/spread, flow, explosion, portal, sign, and entity block-change evidence so cancelled world actions do not masquerade as completed actions.
- Bucket/fire interaction events are informational only; ActivityTracker and hazard attribution use successful bucket/ignite result events to prevent double counting.
- Kicks are explicitly separated from voluntary/unknown disconnects and cannot produce a combat-logout alert.
- Combat-log review now requires a recent disconnect after PvP damage while the player is still alive and is MEDIUM rather than automatically HIGH.
- A second kill in the repeated-kill window is contextual only; escalation begins at the third directional kill and accounts for reciprocal kills and voluntary return-to-fight context.
- Repeated-kill history requires a server-confirmed player killer and no longer falls back to the first attacker when a death has no player killer.
- Incident grouping now requires recent timing and geographic proximity before continuing or merging fights.
- Added a 60-second recent-aggression memory so a new incident can recognize that the apparent victim attacked the apparent initiator shortly beforehand.
- Indirect owner attribution through tamed entities or delayed TNT lowers initiator confidence.
- Environmental hazard attribution is now staged by hazard type, recent PvP context, and victim movement rather than proximity alone; water/drowning attribution is deliberately conservative.
- Death-loot review now identifies newly spawned death-drop item entities by UUID instead of treating any nearby matching material as the victim's loot.
- Returning identified death-loot quantities now adds mitigating return context while preserving the original pickup evidence; returned item entities remain tracked so re-pickup can be recognized.
- Added combat configuration for incident-link time/radius, aggression memory, and combat-logout review time.
- Added a separate PvP clock so unrelated fall/fire/drowning/self-damage no longer extends combat-logout eligibility.
- Context-only environmental damage no longer extends incident liveness; only credibly hostile attribution refreshes the fight timeout.
- First-retaliation timing is now captured once and cannot be overwritten by later retaliations.
- Offline environmental sources now retain indirect attribution (`recent environmental hazard`, 0.55) instead of inheriting direct-damage confidence.
- Player deaths are excluded from generic `EntityDeathEvent` PvE kill history so PvP kills no longer become `KILL PLAYER`.
- Replaced command-based ban state with Paper native profile bans and native expiration; dashboard metadata no longer controls whether a player remains banned.
- Offline players are classified as Offline before PvP/AFK logic and are excluded from the idle-player statistic.
- Historical analysis now strips optional `[uuid=...]` prefixes before classifying actions.
- Mining counts use a non-overlapping mining-break union, and strip-mining/ore-review heuristics are localized by 2x2-chunk region and 8-block Y band.
- Moving toward a suspected hazard is now mitigating/neutral evidence rather than evidence against the hazard placer.
- Hazard attribution scores both recency and distance, with recency weighted more heavily.
- `UNPROVOKED_ATTACK` alerts require exactly two participants; group melees retain context only.
- Returned death-loot item entities remain UUID-tracked; re-picking returned loot removes the return mitigation and restores outstanding loot evidence.
- Historical `BUCKET_USE`/`FIRE_TOOL` entries are informational attempts and no longer contribute to dominant environmental-work classification.
- Disabled/uninitialized activity tracking now reports unknown inactivity rather than effectively infinite inactivity.
- Dashboard API authentication now happens before handler work; query-string tokens are no longer accepted; the web server uses a bounded four-thread executor and caps form bodies at 64 KiB.
- `BEDROCK` no longer matches bed interactions, and bed-entry history is recorded only for successful bed entry.
- Interactive-block activity also respects the final `useInteractedBlock()` result so a denied block use is not counted as successful interaction.
- Structured combat signals are serialized into combat files and restored after restart instead of disappearing from the Combat API.
- Multi-player encounters now preserve a short-lived encounter-root ID across death/disconnect-created incident segments so related group-fight files can be correlated.
- Global dashboard wording now identifies PvP counts as participation records where appropriate.

- Retargeted the project to Paper API `26.2.build.92-stable`.
- Reworked demolition alerts so ordinary mining, tree chopping, crop harvesting, and terrain clearing do not trigger from high block-break counts alone.
- Narrowed sensitive-break tracking to storage, utility, and redstone infrastructure instead of crops, glass, doors, beds, and other normal-play blocks.
- Raised moderation thresholds and removed redundant routine-PvP activity alerts to reduce false positives during ordinary play.
- Fire/lava review now uses actual fire and lava-bucket signals rather than generic bucket activity.
- Alert clicks now open focused evidence from the activity window that produced the alert, with a separate button for the player's complete raw log.
- Sampled repeated container management in the live activity model so chest sorting cannot dominate normal player activity.
- Historical activity now separates pickup/drop activity from storage management and discounts repetitive item-management clicks when choosing the dominant activity.
- Added optional scheduled full-server ZIP backups.
- Backups are disabled by default and run on a dedicated background worker.
- Added configurable backup interval, destination folder, and age-based retention.
- Saves loaded worlds immediately before each live backup.
- Excludes the backup destination from the archive to prevent recursive backups.
- Deletes only matching PlayerActionLogger backup ZIPs after the configured retention period.
- Redesigned the moderation dialog with clearer action cards and improved visual hierarchy.
- Added temporary-ban lengths in minutes, hours, and days.
- Added quick temporary-ban presets.
- Added exact future unban date-and-time selection.
- Added a visible temporary-ban expiration preview and server-side maximum-duration validation.
- Replaced minute-based moderation defaults with simpler hour/day settings while retaining compatibility with older keys.
- Reduced comments and explanatory noise in the default `config.yml`.

## 0.8.2

- Simplified moderation-alert closure: closing an alert stores only its OPEN/CLOSED state.
- Removed reviewer names, review notes, closure timestamps, dashboard prompts, and alert-closure audit logging.
- Updated the Paper API target to `26.2.build.65-beta`.
- Retained durable open alerts, chest-focused inventory evidence, dashboard moderation actions, login/logout locations, temporary bans, and per-day history deletion.

## 0.8.1

- Made the dashboard listening port explicitly configurable through `web-interface.port` in `config.yml`.
- Accepts any valid TCP port from 1 through 65535.
- Added clear configuration examples and restart instructions.
- Added validation for invalid port values with a warning and fallback to port 8080.
- Improved dashboard startup reporting so the plugin only announces a dashboard URL after a successful bind.
- Added a clear warning when the configured address or port is unavailable.
- Added Codex build and verification instructions.

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
