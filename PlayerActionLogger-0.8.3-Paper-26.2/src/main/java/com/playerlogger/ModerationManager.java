package com.playerlogger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.destroystokyo.paper.profile.PlayerProfile;
import io.papermc.paper.ban.BanListType;
import net.kyori.adventure.text.Component;
import org.bukkit.BanEntry;
import org.bukkit.OfflinePlayer;
import org.bukkit.ban.ProfileBanList;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerKickEvent;

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

    public record ModerationState(
            boolean enabled,
            boolean tokenRequired,
            long defaultTemporaryBanMinutes,
            long maximumTemporaryBanMinutes,
            List<TemporaryBan> temporaryBans
    ) { }

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
        return new ModerationState(enabled(), requireToken(), defaultMinutes(), maximumMinutes(), bans);
    }

    public CompletableFuture<ModerationResult> execute(String action, String playerName, String reason,
                                                       String moderator, long durationMinutes, long expiresAtMillis,
                                                       String alertId) {
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
                            durationMinutes, expiresAtMillis, clean(alertId));
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
        if (online == null || !online.isConnected()) return new ModerationResult(false, playerName + " is not online.", null);
        online.kick(Component.text(reason), PlayerKickEvent.Cause.PLUGIN);
        audit(moderator, "KICK", playerName, reason, "direct-player-api");
        return new ModerationResult(true, playerName + " was kicked.", null);
    }

    private ModerationResult permanentBan(String playerName, String reason, String moderator) {
        OfflinePlayer target = plugin.getServer().getOfflinePlayer(playerName);
        BanEntry<?> entry = target.ban(reason, (Instant) null, banSource(moderator));
        if (entry == null || !target.isBanned() || entry.getExpiration() != null) {
            audit(moderator, "BAN_FAILED", playerName, reason, "native ban state not confirmed");
            return new ModerationResult(false, "Permanent ban could not be confirmed.", null);
        }
        synchronized (this) {
            temporaryBansByPlayer.remove(playerName.toLowerCase(Locale.ROOT));
            save();
        }
        Player online = plugin.getServer().getPlayerExact(playerName);
        if (online != null && online.isConnected()) online.kick(Component.text(reason), PlayerKickEvent.Cause.PLUGIN);
        audit(moderator, "BAN", playerName, reason, "native-profile-ban");
        return new ModerationResult(true, playerName + " was permanently banned.", null);
    }

    private ModerationResult temporaryBan(String playerName, String reason, String moderator,
                                           long durationMinutes, long requestedExpiresAt, String alertId) {
        long now = System.currentTimeMillis();
        long maximum = maximumMinutes();
        long duration;
        long expires;

        if (requestedExpiresAt > 0L) {
            if (requestedExpiresAt <= now + 30_000L) {
                return new ModerationResult(false, "The unban date must be in the future.", null);
            }
            duration = Math.max(1L, (requestedExpiresAt - now + 59_999L) / 60_000L);
            if (duration > maximum) {
                return new ModerationResult(false, "Temporary bans may not exceed "
                        + humanDuration(maximum) + ".", null);
            }
            expires = requestedExpiresAt;
        } else {
            duration = durationMinutes > 0L ? durationMinutes : defaultMinutes();
            if (duration < 1L) duration = 1L;
            if (duration > maximum) {
                return new ModerationResult(false, "Temporary bans may not exceed "
                        + humanDuration(maximum) + ".", null);
            }
            expires = now + duration * 60_000L;
        }

        String banReason = reason + " (temporary ban until "
                + DISPLAY_TIME.format(Instant.ofEpochMilli(expires)) + ")";
        OfflinePlayer target = plugin.getServer().getOfflinePlayer(playerName);
        ProfileBanList banList = plugin.getServer().getBanList(BanListType.PROFILE);
        PlayerProfile profile = target.getPlayerProfile();
        BanEntry<PlayerProfile> existing = banList.getBanEntry(profile);
        if (existing != null && isActive(existing) && !ownsTemporaryBan(existing)) {
            String existingType = existing.getExpiration() == null ? "permanent" : "externally managed temporary";
            audit(moderator, "TEMP_BAN_REJECTED", playerName, reason, "existing " + existingType + " ban preserved");
            return new ModerationResult(false, "Player already has an existing " + existingType + " ban; it was not replaced.", null);
        }
        BanEntry<?> nativeEntry = target.ban(banReason, Instant.ofEpochMilli(expires), banSource(moderator));
        if (nativeEntry == null || !target.isBanned() || nativeEntry.getExpiration() == null) {
            audit(moderator, "TEMP_BAN_FAILED", playerName, reason, "native expiring ban not confirmed");
            return new ModerationResult(false, "Temporary ban could not be confirmed by Paper's native ban list.", null);
        }

        TemporaryBan ban = new TemporaryBan(UUID.randomUUID().toString(), playerName, reason, moderator,
                now, expires, DISPLAY_TIME.format(Instant.ofEpochMilli(now)),
                DISPLAY_TIME.format(Instant.ofEpochMilli(expires)), alertId == null ? "" : alertId);
        synchronized (this) {
            temporaryBansByPlayer.put(playerName.toLowerCase(Locale.ROOT), ban);
            save();
        }
        Player online = plugin.getServer().getPlayerExact(playerName);
        if (online != null && online.isConnected()) online.kick(Component.text(banReason), PlayerKickEvent.Cause.PLUGIN);
        audit(moderator, "TEMP_BAN", playerName, reason,
                "duration:" + humanDuration(duration) + " alert:" + clean(alertId) + " native-expiry:true");
        return new ModerationResult(true, playerName + " was temporarily banned until "
                + ban.expiresLabel() + " (" + humanDuration(duration) + ").", ban);
    }

    private ModerationResult pardon(String playerName, String moderator, String reason) {
        OfflinePlayer target = plugin.getServer().getOfflinePlayer(playerName);
        ProfileBanList banList = plugin.getServer().getBanList(BanListType.PROFILE);
        PlayerProfile profile = target.getPlayerProfile();
        if (!banList.isBanned(profile)) return new ModerationResult(false, playerName + " is not currently banned.", null);
        banList.pardon(profile);
        if (target.isBanned()) {
            audit(moderator, "PARDON_FAILED", playerName, reason, "native ban still present");
            return new ModerationResult(false, "Pardon could not be confirmed; ban state was preserved.", null);
        }
        synchronized (this) {
            temporaryBansByPlayer.remove(playerName.toLowerCase(Locale.ROOT));
            save();
        }
        audit(moderator, "PARDON", playerName, reason, "native-profile-ban");
        return new ModerationResult(true, playerName + " was pardoned.", null);
    }

    /** Paper's native expiring ban entry is authoritative; this tick reconciles dashboard metadata. */
    public void tick() {
        ProfileBanList banList = plugin.getServer().getBanList(BanListType.PROFILE);
        long now = System.currentTimeMillis();
        boolean changed = false;
        synchronized (this) {
            var it = temporaryBansByPlayer.entrySet().iterator();
            while (it.hasNext()) {
                TemporaryBan ban = it.next().getValue();
                OfflinePlayer target = plugin.getServer().getOfflinePlayer(ban.player());
                PlayerProfile profile = target.getPlayerProfile();
                BanEntry<PlayerProfile> entry = banList.getBanEntry(profile);

                if (entry == null || !isActive(entry)) {
                    it.remove(); changed = true; continue;
                }

                // Migration from pre-native 0.8.3 temp bans: those were permanent command bans
                // plus temporary-bans.json. Convert only entries whose own ban reason carries the
                // old temporary-ban marker, so an unrelated permanent admin ban is never pardoned.
                if (entry.getExpiration() == null && legacyTemporaryMarker(entry)) {
                    if (ban.expiresAt() <= now) {
                        banList.pardon(profile);
                        it.remove(); changed = true;
                        audit("PlayerActionLogger", "LEGACY_TEMPBAN_EXPIRED", ban.player(),
                                "Migrated legacy temporary ban had already expired", "native-pardon");
                        continue;
                    }
                    BanEntry<?> migrated = target.ban(entry.getReason(), Instant.ofEpochMilli(ban.expiresAt()),
                            banSource(ban.moderator()));
                    if (migrated != null && migrated.getExpiration() != null) {
                        changed = true;
                        audit("PlayerActionLogger", "LEGACY_TEMPBAN_MIGRATED", ban.player(),
                                "Converted legacy timer to native expiring ban", "expires:" + ban.expiresLabel());
                    }
                } else if (!ownsTemporaryBan(entry)) {
                    // The player has a real ban not owned by this temp-ban system. Keep the ban,
                    // but drop stale dashboard temp metadata so it can never auto-affect that ban.
                    it.remove(); changed = true;
                }
            }

            // Rebuild missing dashboard metadata from Paper-native temp bans owned by this plugin.
            for (BanEntry<PlayerProfile> entry : banList.<BanEntry<PlayerProfile>>getEntries()) {
                if (!ownsTemporaryBan(entry) || !isActive(entry)) continue;
                PlayerProfile banTarget = entry.getBanTarget();
                String player = banTarget == null ? null : banTarget.getName();
                if (player == null || player.isBlank()) continue;
                String key = player.toLowerCase(Locale.ROOT);
                if (temporaryBansByPlayer.containsKey(key)) continue;
                long created = entry.getCreated().getTime();
                long expires = entry.getExpiration().getTime();
                String moderator = entry.getSource().substring("PlayerActionLogger:".length());
                TemporaryBan reconstructed = new TemporaryBan(
                        UUID.nameUUIDFromBytes((player + ":" + created + ":" + expires).getBytes(StandardCharsets.UTF_8)).toString(),
                        player, clean(entry.getReason()), moderator, created, expires,
                        DISPLAY_TIME.format(Instant.ofEpochMilli(created)),
                        DISPLAY_TIME.format(Instant.ofEpochMilli(expires)), "");
                temporaryBansByPlayer.put(key, reconstructed);
                changed = true;
            }
            if (changed) save();
        }
    }

    private boolean legacyTemporaryMarker(BanEntry<?> entry) {
        String reason = entry == null ? null : entry.getReason();
        return reason != null && reason.toLowerCase(Locale.ROOT).contains("temporary ban until");
    }

    private String banSource(String moderator) {
        return "PlayerActionLogger:" + clean(moderator);
    }

    private boolean ownsTemporaryBan(BanEntry<?> entry) {
        return entry != null && entry.getExpiration() != null
                && entry.getSource() != null && entry.getSource().startsWith("PlayerActionLogger:");
    }

    private boolean isActive(BanEntry<?> entry) {
        return entry != null && (entry.getExpiration() == null || entry.getExpiration().getTime() > System.currentTimeMillis());
    }

    private void audit(String moderator, String action, String player, String reason, String extra) {
        plugin.logSystem("MOD_ACTION moderator:" + clean(moderator) + " action:" + action
                + " player:" + clean(player) + " reason:" + clean(reason)
                + (extra == null || extra.isBlank() ? "" : " " + clean(extra)));
    }

    private long defaultMinutes() {
        if (plugin.getConfig().contains("moderation-actions.default-temporary-ban-hours")) {
            double hours = Math.max(1.0 / 60.0,
                    plugin.getConfig().getDouble("moderation-actions.default-temporary-ban-hours", 1.0));
            return Math.max(1L, Math.round(hours * 60.0));
        }
        return Math.max(1L, plugin.getConfig().getLong(
                "moderation-actions.default-temporary-ban-minutes", 60L));
    }

    private long maximumMinutes() {
        if (plugin.getConfig().contains("moderation-actions.maximum-temporary-ban-days")) {
            long days = Math.max(1L,
                    plugin.getConfig().getLong("moderation-actions.maximum-temporary-ban-days", 30L));
            return Math.min(days, 365_000L) * 1_440L;
        }
        return Math.max(1L, plugin.getConfig().getLong(
                "moderation-actions.maximum-temporary-ban-minutes", 43_200L));
    }

    private static String humanDuration(long minutes) {
        long days = minutes / 1_440L;
        long hours = (minutes % 1_440L) / 60L;
        long remainingMinutes = minutes % 60L;
        if (days > 0L) {
            return hours > 0L ? days + " day(s) " + hours + " hour(s)" : days + " day(s)";
        }
        if (hours > 0L) {
            return remainingMinutes > 0L ? hours + " hour(s) " + remainingMinutes + " minute(s)"
                    : hours + " hour(s)";
        }
        return remainingMinutes + " minute(s)";
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
