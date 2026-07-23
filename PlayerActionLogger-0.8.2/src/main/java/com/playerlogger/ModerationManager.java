package com.playerlogger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.bukkit.entity.Player;

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
import java.util.concurrent.CompletableFuture;

/** Executes audited moderation actions requested through the protected dashboard. */
public final class ModerationManager {
    private static final Type BAN_LIST_TYPE = new TypeToken<List<TemporaryBan>>() { }.getType();
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    public record TemporaryBan(
            String id,
            String player,
            String reason,
            String moderator,
            long createdAt,
            long expiresAt,
            String createdLabel,
            String expiresLabel,
            String alertId
    ) { }

    public record ModerationState(boolean enabled, boolean tokenRequired, List<TemporaryBan> temporaryBans) { }

    public record ModerationResult(boolean success, String message, TemporaryBan temporaryBan) { }

    private final PlayerActionLogger plugin;
    private final Path storageFile;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final Map<String, TemporaryBan> temporaryBansByPlayer = new LinkedHashMap<>();

    public ModerationManager(PlayerActionLogger plugin) {
        this.plugin = plugin;
        this.storageFile = plugin.getDataFolder().toPath().resolve("temporary-bans.json");
        load();
    }

    public synchronized ModerationState state() {
        List<TemporaryBan> bans = temporaryBansByPlayer.values().stream()
                .sorted(Comparator.comparingLong(TemporaryBan::expiresAt))
                .toList();
        return new ModerationState(enabled(), requireToken(), bans);
    }

    public CompletableFuture<ModerationResult> execute(String action, String playerName, String reason,
                                                       String moderator, long durationMinutes, String alertId) {
        CompletableFuture<ModerationResult> future = new CompletableFuture<>();
        if (!enabled()) {
            future.complete(new ModerationResult(false, "Dashboard moderation actions are disabled in config.yml.", null));
            return future;
        }

        String safePlayer = validatePlayerName(playerName);
        if (safePlayer == null) {
            future.complete(new ModerationResult(false, "Invalid player name.", null));
            return future;
        }
        String normalizedAction = action == null ? "" : action.trim().toLowerCase(Locale.ROOT);
        String cleanReason = clean(reason).isBlank() ? "No reason supplied" : clean(reason);
        String cleanModerator = clean(moderator).isBlank() ? "Dashboard administrator" : clean(moderator);

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                ModerationResult result = switch (normalizedAction) {
                    case "kick" -> kick(safePlayer, cleanReason, cleanModerator);
                    case "ban" -> permanentBan(safePlayer, cleanReason, cleanModerator);
                    case "tempban" -> temporaryBan(safePlayer, cleanReason, cleanModerator,
                            durationMinutes, clean(alertId));
                    case "pardon" -> pardon(safePlayer, cleanModerator, cleanReason);
                    default -> new ModerationResult(false, "Unsupported moderation action.", null);
                };
                future.complete(result);
            } catch (RuntimeException e) {
                future.complete(new ModerationResult(false, "Moderation action failed: " + e.getMessage(), null));
            }
        });
        return future;
    }

    private ModerationResult kick(String playerName, String reason, String moderator) {
        Player online = plugin.getServer().getPlayerExact(playerName);
        if (online == null) return new ModerationResult(false, playerName + " is not online.", null);
        boolean dispatched = plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(),
                "kick " + playerName + " " + commandText(reason));
        audit(moderator, "KICK", playerName, reason, "");
        return new ModerationResult(dispatched, dispatched ? playerName + " was kicked." : "Kick command failed.", null);
    }

    private ModerationResult permanentBan(String playerName, String reason, String moderator) {
        boolean dispatched = plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(),
                "ban " + playerName + " " + commandText(reason));
        synchronized (this) {
            temporaryBansByPlayer.remove(playerName.toLowerCase(Locale.ROOT));
            save();
        }
        audit(moderator, "BAN", playerName, reason, "permanent");
        return new ModerationResult(dispatched, dispatched ? playerName + " was permanently banned." : "Ban command failed.", null);
    }

    private ModerationResult temporaryBan(String playerName, String reason, String moderator,
                                           long durationMinutes, String alertId) {
        long maximum = Math.max(1L, plugin.getConfig().getLong("moderation-actions.maximum-temporary-ban-minutes", 43_200L));
        long duration = durationMinutes > 0 ? Math.min(durationMinutes, maximum)
                : Math.max(1L, plugin.getConfig().getLong("moderation-actions.default-temporary-ban-minutes", 60L));
        long now = System.currentTimeMillis();
        long expires = now + duration * 60_000L;
        String banReason = reason + " (temporary ban until " + DISPLAY_TIME.format(Instant.ofEpochMilli(expires)) + ")";
        boolean dispatched = plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(),
                "ban " + playerName + " " + commandText(banReason));
        if (!dispatched) return new ModerationResult(false, "Temporary ban command failed.", null);

        TemporaryBan ban = new TemporaryBan(UUID.randomUUID().toString(), playerName, reason, moderator,
                now, expires, DISPLAY_TIME.format(Instant.ofEpochMilli(now)),
                DISPLAY_TIME.format(Instant.ofEpochMilli(expires)), alertId == null ? "" : alertId);
        synchronized (this) {
            temporaryBansByPlayer.put(playerName.toLowerCase(Locale.ROOT), ban);
            save();
        }
        audit(moderator, "TEMP_BAN", playerName, reason, "minutes:" + duration + " alert:" + clean(alertId));
        return new ModerationResult(true, playerName + " was temporarily banned for " + duration + " minutes.", ban);
    }

    private ModerationResult pardon(String playerName, String moderator, String reason) {
        boolean dispatched = plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(),
                "pardon " + playerName);
        synchronized (this) {
            temporaryBansByPlayer.remove(playerName.toLowerCase(Locale.ROOT));
            save();
        }
        audit(moderator, "PARDON", playerName, reason, "");
        return new ModerationResult(dispatched, dispatched ? playerName + " was pardoned." : "Pardon command failed.", null);
    }

    /** Called from the main server thread. */
    public void tick() {
        long now = System.currentTimeMillis();
        List<TemporaryBan> expired;
        synchronized (this) {
            expired = temporaryBansByPlayer.values().stream()
                    .filter(ban -> ban.expiresAt() <= now)
                    .toList();
        }
        for (TemporaryBan ban : expired) {
            plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), "pardon " + ban.player());
            synchronized (this) {
                temporaryBansByPlayer.remove(ban.player().toLowerCase(Locale.ROOT));
                save();
            }
            audit("PlayerActionLogger", "AUTO_PARDON", ban.player(), "Temporary ban expired", "ban-id:" + ban.id());
        }
    }

    private void audit(String moderator, String action, String player, String reason, String extra) {
        plugin.logSystem("MOD_ACTION moderator:" + clean(moderator) + " action:" + action
                + " player:" + clean(player) + " reason:" + clean(reason)
                + (extra == null || extra.isBlank() ? "" : " " + clean(extra)));
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("moderation-actions.enabled", true);
    }

    public boolean requireToken() {
        return plugin.getConfig().getBoolean("moderation-actions.require-access-token", true);
    }

    private void load() {
        if (!Files.exists(storageFile)) return;
        try {
            List<TemporaryBan> loaded = gson.fromJson(Files.readString(storageFile, StandardCharsets.UTF_8), BAN_LIST_TYPE);
            if (loaded == null) return;
            for (TemporaryBan ban : loaded) {
                if (ban == null || ban.player() == null) continue;
                temporaryBansByPlayer.put(ban.player().toLowerCase(Locale.ROOT), ban);
            }
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().warning("Could not load temporary bans: " + e.getMessage());
        }
    }

    private synchronized void save() {
        try {
            Files.createDirectories(storageFile.getParent());
            Path temp = storageFile.resolveSibling(storageFile.getFileName() + ".tmp");
            Files.writeString(temp, gson.toJson(new ArrayList<>(temporaryBansByPlayer.values())),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(temp, storageFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save temporary bans: " + e.getMessage());
        }
    }

    private static String validatePlayerName(String name) {
        if (name == null) return null;
        String trimmed = name.trim();
        return trimmed.matches("[A-Za-z0-9_]{1,16}") ? trimmed : null;
    }

    private static String commandText(String value) {
        return clean(value).replaceAll("[\\r\\n]", " ");
    }

    private static String clean(String value) {
        return LogUtils.cleanOneLine(value == null ? "" : value).trim();
    }
}
