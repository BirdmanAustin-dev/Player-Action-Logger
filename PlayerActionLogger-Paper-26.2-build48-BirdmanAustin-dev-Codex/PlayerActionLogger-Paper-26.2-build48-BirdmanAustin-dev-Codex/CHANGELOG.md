# Changelog

## 0.4.3

- Updated Paper 26.2 target from build 46 to build 48.
- Updated Paper API property to `26.2.build.48-alpha`.
- Kept `api-version: '26.2'`.
- Kept `BirdmanAustin-dev` as the plugin author/maker.
- Kept the moderation features: username logs, `_uuid-index.csv`, quieter fluid logging, bucket action logs, local dashboard, Paper `AsyncChatEvent`, and background writer thread.

## 0.4.3

- Set the plugin author/maker to `BirdmanAustin-dev` in `plugin.yml`.
- Added Maven developer metadata for `BirdmanAustin-dev` as author/maintainer.
- Kept the Paper 26.2 build #46 API dependency: `26.2.build.48-alpha`.
- Kept all moderation features unchanged: quieter water/lava handling, username logs, separate `_uuid-index.csv`, local dashboard, and Paper `AsyncChatEvent`.

## 0.4.1

- Updated the Paper 26.2 API dependency from `26.2.build.40-alpha` to `26.2.build.48-alpha`.
- Kept `plugin.yml` at `api-version: '26.2'` because the Minecraft/Paper API version remains 26.2.
- No moderation feature changes were required for this bump.
- Retained quieter water/lava handling, username logs, `_uuid-index.csv`, local dashboard, and Paper `AsyncChatEvent`.

## 0.4.0

- Retargeted the project from Paper 26.1.2 to **Paper 26.2**.
- Updated Maven dependency to `io.papermc.paper:paper-api:26.2.build.40-alpha`.
- Updated `plugin.yml` to `api-version: '26.2'`.
- Kept the moderation-focused changes from the earlier fork:
  - reduced water/lava flow spam
  - separate UUID index file while preserving username-based logs
  - Paper `AsyncChatEvent` usage
  - optional dashboard auth token
  - queued/single-writer logging model

## 0.3.0

- Reworked the project as a Paper 26.1.2 target instead of a Spigot/Snapshot target.
- Switched dependency to `io.papermc.paper:paper-api`.
- Set `plugin.yml` to `api-version: '26.1.2'`.
- Replaced `AsyncPlayerChatEvent` logging with Paper `AsyncChatEvent`.
- Preserved username-based logs and added `_uuid-index.csv`.
- Disabled raw fluid flow spam by default while retaining bucket-action evidence.
