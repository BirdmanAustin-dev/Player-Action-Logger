package com.playerlogger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Persists moderation alerts until a dashboard administrator closes them.
 * Closed alerts are excluded from the default queue. No reviewer identity,
 * review note, or closure time is retained.
 */
public final class AlertManager {
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    private static final Type ALERT_LIST_TYPE = new TypeToken<List<ModerationAlert>>() { }.getType();

    public record ModerationAlert(
            String id,
            String sourceKey,
            long createdAt,
            long lastSeenAt,
            String timeLabel,
            String severity,
            String category,
            String title,
            String detail,
            String player,
            String evidenceType,
            String evidenceId,
            String status
    ) { }

    public record AlertCounts(int open, int closed, int high, int medium, int low) { }

    public record AlertSnapshot(AlertCounts counts, List<ModerationAlert> alerts) { }

    private final PlayerActionLogger plugin;
    private final Path storageFile;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final Map<String, ModerationAlert> byId = new LinkedHashMap<>();
    private final Map<String, String> idBySourceKey = new LinkedHashMap<>();

    public AlertManager(PlayerActionLogger plugin) {
        this.plugin = plugin;
        this.storageFile = plugin.getDataFolder().toPath().resolve("moderation-alerts.json");
        load();
    }

    public synchronized void sync(ActivityTracker.ActivityOverview activity,
                                  List<CombatManager.CombatSummary> combatSummaries) {
        long now = System.currentTimeMillis();
        boolean changed = false;

        if (activity != null) {
            for (ActivityTracker.ActivityAlert alert : activity.alerts()) {
                String sourceKey = "activity|" + safe(alert.player()) + "|" + safe(alert.title());
                changed |= upsert(sourceKey, alert.time(), alert.severity(), alert.category(), alert.title(),
                        alert.detail(), alert.player(), alert.evidenceType(), alert.evidenceId(), now);
            }
        }

        if (combatSummaries != null) {
            for (CombatManager.CombatSummary combat : combatSummaries) {
                for (String flag : combat.flags()) {
                    String sourceKey = "combat|" + safe(combat.id()) + "|" + safe(flag);
                    String participants = String.join(", ", combat.participants());
                    changed |= upsert(sourceKey,
                            combat.endedEpochMillis() > 0 ? combat.endedEpochMillis() : combat.startedEpochMillis(),
                            severityForCombatFlag(flag), "Combat", flag,
                            participants + (combat.result().isBlank() ? "" : " — " + combat.result()),
                            participants, "combat", combat.id(), now);
                }
            }
        }

        long quietMillis = reopenAfterQuietMillis();
        List<String> removable = byId.values().stream()
                .filter(alert -> "CLOSED".equals(alert.status()) && now - alert.lastSeenAt() > quietMillis)
                .map(ModerationAlert::id)
                .toList();
        for (String id : removable) {
            ModerationAlert removed = byId.remove(id);
            if (removed != null) {
                idBySourceKey.remove(removed.sourceKey(), removed.id());
                changed = true;
            }
        }

        if (changed) save();
    }

    private boolean upsert(String sourceKey, long eventTime, String severity, String category,
                           String title, String detail, String player, String evidenceType,
                           String evidenceId, long now) {
        String existingId = idBySourceKey.get(sourceKey);
        ModerationAlert existing = existingId == null ? null : byId.get(existingId);

        // A continuously active signal that was already reviewed stays closed. If the signal
        // disappeared and later returns, it can open as a new alert after the quiet period.
        long reopenAfterMillis = reopenAfterQuietMillis();
        if (existing != null && "CLOSED".equals(existing.status())) {
            if (now - existing.lastSeenAt() <= reopenAfterMillis) {
                ModerationAlert refreshed = copyWithLastSeen(existing, now);
                byId.put(existing.id(), refreshed);
                return true;
            }
            idBySourceKey.remove(sourceKey);
            existing = null;
        }

        if (existing != null) {
            ModerationAlert updated = new ModerationAlert(existing.id(), sourceKey, existing.createdAt(), now,
                    label(eventTime), severity, category, title, detail, player, evidenceType, evidenceId,
                    existing.status());
            if (!updated.equals(existing)) {
                byId.put(existing.id(), updated);
                return true;
            }
            return false;
        }

        String id = UUID.randomUUID().toString();
        ModerationAlert created = new ModerationAlert(id, sourceKey,
                eventTime > 0 ? eventTime : now, now, label(eventTime > 0 ? eventTime : now),
                normalizeSeverity(severity), category, title, detail, player, evidenceType, evidenceId,
                "OPEN");
        byId.put(id, created);
        idBySourceKey.put(sourceKey, id);
        plugin.logSystem("MOD_ALERT_OPEN id:" + id + " severity:" + created.severity()
                + " category:" + clean(category) + " player:" + clean(player)
                + " title:" + clean(title));
        return true;
    }

    public synchronized AlertSnapshot snapshot(boolean includeClosed) {
        List<ModerationAlert> alerts = byId.values().stream()
                .filter(alert -> includeClosed || "OPEN".equals(alert.status()))
                .sorted(Comparator.comparingInt((ModerationAlert alert) -> severityRank(alert.severity())).reversed()
                        .thenComparing(Comparator.comparingLong(ModerationAlert::createdAt).reversed()))
                .toList();

        int open = 0;
        int closed = 0;
        int high = 0;
        int medium = 0;
        int low = 0;
        for (ModerationAlert alert : byId.values()) {
            if ("OPEN".equals(alert.status())) {
                open++;
                switch (alert.severity()) {
                    case "HIGH" -> high++;
                    case "MEDIUM" -> medium++;
                    default -> low++;
                }
            } else {
                closed++;
            }
        }
        return new AlertSnapshot(new AlertCounts(open, closed, high, medium, low), alerts);
    }

    public synchronized ModerationAlert find(String id) {
        return byId.get(id);
    }

    public synchronized ModerationAlert close(String id) {
        ModerationAlert existing = byId.get(id);
        if (existing == null) return null;
        if ("CLOSED".equals(existing.status())) return existing;
        ModerationAlert closed = new ModerationAlert(existing.id(), existing.sourceKey(), existing.createdAt(),
                existing.lastSeenAt(), existing.timeLabel(), existing.severity(), existing.category(),
                existing.title(), existing.detail(), existing.player(), existing.evidenceType(), existing.evidenceId(),
                "CLOSED");
        byId.put(id, closed);
        save();
        return closed;
    }

    private ModerationAlert copyWithLastSeen(ModerationAlert alert, long lastSeenAt) {
        return new ModerationAlert(alert.id(), alert.sourceKey(), alert.createdAt(), lastSeenAt,
                alert.timeLabel(), alert.severity(), alert.category(), alert.title(), alert.detail(),
                alert.player(), alert.evidenceType(), alert.evidenceId(), alert.status());
    }

    private void load() {
        if (!Files.exists(storageFile)) return;
        try {
            String json = Files.readString(storageFile, StandardCharsets.UTF_8);
            List<ModerationAlert> loaded = gson.fromJson(json, ALERT_LIST_TYPE);
            if (loaded == null) return;
            for (ModerationAlert alert : loaded) {
                if (alert == null || alert.id() == null || alert.sourceKey() == null) continue;
                byId.put(alert.id(), alert);
                idBySourceKey.put(alert.sourceKey(), alert.id());
            }
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().warning("Could not load moderation alerts: " + e.getMessage());
        }
    }

    private void save() {
        try {
            Files.createDirectories(storageFile.getParent());
            Path temp = storageFile.resolveSibling(storageFile.getFileName() + ".tmp");
            Files.writeString(temp, gson.toJson(new ArrayList<>(byId.values())), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(temp, storageFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save moderation alerts: " + e.getMessage());
        }
    }


    private long reopenAfterQuietMillis() {
        return Math.max(1L, plugin.getConfig().getLong(
                "moderation-alerts.reopen-after-quiet-minutes", 15L)) * 60_000L;
    }

    private static String severityForCombatFlag(String flag) {
        String lower = flag == null ? "" : flag.toLowerCase(Locale.ROOT);
        if (lower.contains("spawn") || lower.contains("combat logout") || lower.contains("death-loot")
                || lower.contains("repeated kill")) return "HIGH";
        if (lower.contains("unprovoked") || lower.contains("environmental") || lower.contains("sleeping")) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private static int severityRank(String severity) {
        return switch (normalizeSeverity(severity)) {
            case "HIGH" -> 3;
            case "MEDIUM" -> 2;
            default -> 1;
        };
    }

    private static String normalizeSeverity(String severity) {
        String normalized = severity == null ? "LOW" : severity.toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "HIGH", "MEDIUM" -> normalized;
            default -> "LOW";
        };
    }

    private static String label(long epochMillis) {
        return DISPLAY_TIME.format(Instant.ofEpochMilli(Math.max(0L, epochMillis)));
    }

    private static String clean(String value) {
        return LogUtils.cleanOneLine(value == null ? "" : value);
    }

    private static String safe(String value) {
        return clean(value).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-");
    }
}
