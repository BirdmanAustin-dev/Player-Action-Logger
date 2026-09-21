package com.playerlogger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class PlayerActionLogger extends JavaPlugin {
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private Path logsFolder;
    private Path playersFolder;
    private Path combatFolder;
    private Path webFolder;
    private Path uuidIndexFile;
    private Path serverLogFile;

    private final Map<String, BufferedWriter> playerWriters = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastIndexedNameByUuid = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> fluidFlowCounts = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> playerLineCounts = new ConcurrentHashMap<>();
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();

    private ExecutorService logExecutor;
    private ExecutorService webExecutor;
    private HttpServer webServer;
    private ActivityTracker activityTracker;
    private CombatManager combatManager;
    private DailyActivityAnalyzer dailyActivityAnalyzer;
    private AlertManager alertManager;
    private ModerationManager moderationManager;
    private BackupManager backupManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        migrateConfiguration();
        getConfig().options().copyDefaults(true);
        saveConfig();

        logExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "PlayerActionLogger-Writer");
            t.setDaemon(true);
            return t;
        });

        logsFolder = getDataFolder().toPath().resolve("logs");
        playersFolder = logsFolder.resolve("players");
        combatFolder = logsFolder.resolve("combat");
        webFolder = getDataFolder().toPath().resolve("web");
        uuidIndexFile = playersFolder.resolve("_uuid-index.csv");
        serverLogFile = logsFolder.resolve("_server.log");

        try {
            Files.createDirectories(playersFolder);
            Files.createDirectories(combatFolder);
            Files.createDirectories(webFolder);
            ensureUuidIndexHeader();
            initializePlayerLineCounts();
        } catch (IOException e) {
            getLogger().severe("Could not create logging folders: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        dailyActivityAnalyzer = new DailyActivityAnalyzer(this);
        scheduleLogRetention();
        backupManager = new BackupManager(this);
        backupManager.start();

        copyWebResource("index.html");
        copyWebResource("style.css");
        copyWebResource("app.js");

        activityTracker = new ActivityTracker(this);
        combatManager = new CombatManager(this, activityTracker);
        alertManager = new AlertManager(this);
        moderationManager = new ModerationManager(this);
        for (Player player : getServer().getOnlinePlayers()) {
            activityTracker.initializeOnlinePlayer(player);
            combatManager.initializeOnlinePlayer(player);
        }
        getServer().getPluginManager().registerEvents(new EventListener(this), this);

        getServer().getScheduler().runTaskTimer(this, combatManager::tick, 20L, 20L);
        getServer().getScheduler().runTaskTimer(this, () -> {
            moderationManager.tick();
            alertManager.syncActivity(activityTracker.snapshot());
        }, 40L, 600L);

        if (getConfig().getBoolean("logging.fluid-flow.enabled", false)
                && getConfig().getBoolean("logging.fluid-flow.aggregate", true)) {
            long seconds = Math.max(5, getConfig().getLong("logging.fluid-flow.aggregate-interval-seconds", 30));
            getServer().getScheduler().runTaskTimer(this, this::flushFluidFlowSummary, seconds * 20L, seconds * 20L);
        }

        if (getConfig().getBoolean("web-interface.enabled", true)) {
            String bindAddress = getConfig().getString("web-interface.bind-address", "127.0.0.1");
            int port = configuredWebPort();
            if (startWebServer(bindAddress, port)) {
                getLogger().info("PlayerActionLogger enabled. Web dashboard: http://" + bindAddress + ":" + port);
            } else {
                getLogger().warning("PlayerActionLogger enabled, but the web dashboard could not bind to "
                        + bindAddress + ":" + port + ". Choose another unused port in config.yml and restart.");
            }
        } else {
            getLogger().info("PlayerActionLogger enabled. Web dashboard disabled by config.");
        }
    }

    private void migrateConfiguration() {
        if (!getConfig().contains("moderation-actions.default-temporary-ban-hours", true)
                && getConfig().contains("moderation-actions.default-temporary-ban-minutes", true)) {
            long minutes = Math.max(1L,
                    getConfig().getLong("moderation-actions.default-temporary-ban-minutes", 60L));
            getConfig().set("moderation-actions.default-temporary-ban-hours", minutes / 60.0);
            getConfig().set("moderation-actions.default-temporary-ban-minutes", null);
        }
        if (!getConfig().contains("moderation-actions.maximum-temporary-ban-days", true)
                && getConfig().contains("moderation-actions.maximum-temporary-ban-minutes", true)) {
            long minutes = Math.max(1L,
                    getConfig().getLong("moderation-actions.maximum-temporary-ban-minutes", 43_200L));
            getConfig().set("moderation-actions.maximum-temporary-ban-days",
                    Math.max(1L, (minutes + 1_439L) / 1_440L));
            getConfig().set("moderation-actions.maximum-temporary-ban-minutes", null);
        }
    }

    @Override
    public void onDisable() {
        flushFluidFlowSummary();
        if (combatManager != null) combatManager.shutdown();
        if (backupManager != null) backupManager.shutdown();
        if (webServer != null) webServer.stop(0);
        if (webExecutor != null) {
            webExecutor.shutdownNow();
            webExecutor = null;
        }

        if (logExecutor != null) {
            logExecutor.execute(this::closeAllWritersQuietly);
            logExecutor.shutdown();
            try {
                if (!logExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    logExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logExecutor.shutdownNow();
            }
        }
        getLogger().info("PlayerActionLogger disabled.");
    }

    public LogRecord log(Player player, String action) {
        if (player == null || action == null) return null;
        return logPlayer(player.getUniqueId(), player.getName(), action);
    }

    public LogRecord logPlayer(UUID uuid, String username, String action) {
        if (uuid == null || username == null || action == null || logExecutor == null) return null;
        String safeUsername = safeName(username);
        String cleanAction = LogUtils.cleanOneLine(action);
        boolean includeUuid = getConfig().getBoolean("logging.include-uuid-in-player-log-lines", false);
        String timestamp = LOG_TIME.format(Instant.now());
        String line = "[" + timestamp + "] "
                + (includeUuid ? "[uuid=" + uuid + "] " : "")
                + cleanAction;
        String priorIndexedName = lastIndexedNameByUuid.put(uuid, safeUsername);
        boolean shouldAppendUuidIndex = !safeUsername.equals(priorIndexedName);

        logExecutor.execute(() -> {
            try {
                if (shouldAppendUuidIndex) appendUuidIndex(uuid, safeUsername);
                BufferedWriter writer = getPlayerWriter(safeUsername);
                writer.write(line);
                writer.newLine();
                writer.flush();
                playerLineCounts.computeIfAbsent(safeUsername, ignored -> new AtomicLong()).incrementAndGet();
                if (dailyActivityAnalyzer != null) dailyActivityAnalyzer.invalidate(safeUsername);
            } catch (IOException e) {
                getLogger().warning("Log write failed for " + safeUsername + ": " + e.getMessage());
            }
        });
        return new LogRecord(timestamp, cleanAction);
    }

    public void logSystem(String action) {
        if (action == null || logExecutor == null) return;
        String cleanAction = LogUtils.cleanOneLine(action);
        String line = "[" + LOG_TIME.format(Instant.now()) + "] " + cleanAction;
        logExecutor.execute(() -> {
            try {
                Files.writeString(serverLogFile, line + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                getLogger().warning("System log write failed: " + e.getMessage());
            }
        });
    }

    public void noteFluidFlow(Block from, Block to) {
        if (!getConfig().getBoolean("logging.fluid-flow.enabled", false)) return;
        Material type = from.getType();
        if (!LogUtils.isWaterOrLava(type)) return;

        if (!getConfig().getBoolean("logging.fluid-flow.aggregate", true)) {
            logSystem(String.format("FLOW %s from %s to %s", type, LogUtils.loc(from), LogUtils.loc(to)));
            return;
        }

        String key = String.join("|",
                from.getWorld().getName(),
                type.name(),
                String.valueOf(from.getChunk().getX()),
                String.valueOf(from.getChunk().getZ())
        );
        fluidFlowCounts.computeIfAbsent(key, ignored -> new AtomicInteger()).incrementAndGet();
    }

    public void flushFluidFlowSummary() {
        if (fluidFlowCounts.isEmpty()) return;
        Map<String, AtomicInteger> snapshot = new HashMap<>(fluidFlowCounts);
        fluidFlowCounts.clear();
        for (Map.Entry<String, AtomicInteger> entry : snapshot.entrySet()) {
            String[] parts = entry.getKey().split("\\|", -1);
            if (parts.length != 4) continue;
            logSystem(String.format("FLOW_SUMMARY %s in %s chunk(%s,%s) count:%d",
                    parts[1], parts[0], parts[2], parts[3], entry.getValue().get()));
        }
    }

    private void scheduleLogRetention() {
        if (!getConfig().getBoolean("logging.retention.enabled", false)) return;
        int days = getConfig().getInt("logging.retention.days", 0);
        if (days <= 0) return;

        requestLogRetentionCleanup();
        long hours = Math.max(1L, getConfig().getLong("logging.retention.run-interval-hours", 24L));
        long ticks = hours * 60L * 60L * 20L;
        getServer().getScheduler().runTaskTimer(this, this::requestLogRetentionCleanup, ticks, ticks);
    }

    private void requestLogRetentionCleanup() {
        if (logExecutor == null || !getConfig().getBoolean("logging.retention.enabled", false)) return;
        int days = getConfig().getInt("logging.retention.days", 0);
        if (days <= 0) return;
        logExecutor.execute(() -> performLogRetentionCleanup(days));
    }

    private void performLogRetentionCleanup(int days) {
        Instant cutoff = Instant.now().minus(days, ChronoUnit.DAYS);
        int filesChanged = 0;
        long linesRemoved = 0;
        closeAllWritersQuietly();

        try (var stream = Files.list(playersFolder)) {
            for (Path file : stream.filter(path -> Files.isRegularFile(path)
                    && path.getFileName().toString().endsWith(".log")).toList()) {
                List<String> original = Files.readAllLines(file, StandardCharsets.UTF_8);
                List<String> retained = new ArrayList<>(original.size());
                for (String line : original) {
                    if (isRetainedLogLine(line, cutoff)) retained.add(line);
                }
                if (retained.size() == original.size()) continue;

                filesChanged++;
                linesRemoved += original.size() - retained.size();
                String username = file.getFileName().toString().replaceFirst("\\.log$", "");
                if (retained.isEmpty()) {
                    Files.deleteIfExists(file);
                    playerLineCounts.remove(username);
                } else {
                    Path temp = file.resolveSibling(file.getFileName() + ".retention.tmp");
                    Files.write(temp, retained, StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
                    playerLineCounts.computeIfAbsent(username, ignored -> new AtomicLong()).set(retained.size());
                }
            }
            if (dailyActivityAnalyzer != null) dailyActivityAnalyzer.invalidateAll();
            if (filesChanged > 0) {
                getLogger().info("Log retention removed " + linesRemoved + " expired player-log lines from "
                        + filesChanged + " files (retention: " + days + " days).");
            }
        } catch (IOException e) {
            getLogger().warning("Log retention cleanup failed: " + e.getMessage());
        }
    }

    private boolean isRetainedLogLine(String line, Instant cutoff) {
        if (line == null || line.length() < 21 || line.charAt(0) != '[' || line.charAt(20) != ']') return true;
        try {
            LocalDateTime timestamp = LocalDateTime.parse(line.substring(1, 20),
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            return !timestamp.atZone(ZoneId.systemDefault()).toInstant().isBefore(cutoff);
        } catch (RuntimeException ignored) {
            // Preserve malformed or legacy lines rather than deleting uncertain evidence.
            return true;
        }
    }

    public CompletableFuture<Integer> deletePlayerHistoryDay(String requestedName, String requestedDate) {
        CompletableFuture<Integer> future = new CompletableFuture<>();
        if (logExecutor == null || requestedDate == null || !requestedDate.matches("\\d{4}-\\d{2}-\\d{2}")) {
            future.complete(-1);
            return future;
        }
        String safeUsername = safeName(requestedName);
        logExecutor.execute(() -> {
            try {
                BufferedWriter writer = playerWriters.remove(safeUsername);
                if (writer != null) writer.close();
                Path file = safePlayerLogFile(safeUsername);
                if (!Files.exists(file)) { future.complete(-1); return; }
                List<String> original = Files.readAllLines(file, StandardCharsets.UTF_8);
                String prefix = "[" + requestedDate + " ";
                List<String> retained = original.stream().filter(line -> !line.startsWith(prefix)).toList();
                int removed = original.size() - retained.size();
                if (removed > 0) {
                    if (retained.isEmpty()) {
                        Files.deleteIfExists(file);
                        playerLineCounts.remove(safeUsername);
                    } else {
                        Path temp = file.resolveSibling(file.getFileName() + ".history-delete.tmp");
                        Files.write(temp, retained, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
                        playerLineCounts.computeIfAbsent(safeUsername, ignored -> new AtomicLong()).set(retained.size());
                    }
                    if (dailyActivityAnalyzer != null) dailyActivityAnalyzer.invalidate(safeUsername);
                }
                future.complete(removed);
            } catch (IOException e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    public void closeWriter(UUID uuid) {
        // Username-based files are intentionally kept readable. Writers are closed on plugin shutdown.
        // This method remains for compatibility with listener code and future UUID-writer modes.
    }

    public Path getLogsFolder() {
        return logsFolder;
    }

    public Path getPlayersFolder() {
        return playersFolder;
    }

    public Path getCombatFolder() {
        return combatFolder;
    }

    public ActivityTracker getActivityTracker() {
        return activityTracker;
    }

    public CombatManager getCombatManager() {
        return combatManager;
    }

    public AlertManager getAlertManager() {
        return alertManager;
    }

    public void writeCombatFile(String incidentId, String content) {
        if (incidentId == null || content == null || logExecutor == null) return;
        String safe = incidentId.replaceAll("[^A-Za-z0-9_\\-]", "_");
        Path file = combatFolder.resolve(safe + ".log").normalize();
        if (!file.startsWith(combatFolder.normalize())) return;
        logExecutor.execute(() -> {
            try {
                Files.writeString(file, content, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } catch (IOException e) {
                getLogger().warning("Combat log write failed for " + safe + ": " + e.getMessage());
            }
        });
    }

    private BufferedWriter getPlayerWriter(String safeUsername) throws IOException {
        BufferedWriter existing = playerWriters.get(safeUsername);
        if (existing != null) return existing;

        Path file = safePlayerLogFile(safeUsername);
        BufferedWriter created = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        BufferedWriter raced = playerWriters.putIfAbsent(safeUsername, created);
        if (raced != null) {
            created.close();
            return raced;
        }
        return created;
    }

    private void closeAllWritersQuietly() {
        for (BufferedWriter writer : playerWriters.values()) {
            try { writer.close(); } catch (IOException ignored) {}
        }
        playerWriters.clear();
    }

    private void ensureUuidIndexHeader() throws IOException {
        if (!Files.exists(uuidIndexFile)) {
            Files.writeString(uuidIndexFile, "timestamp,username,uuid" + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW);
        }
    }

    private void appendUuidIndex(UUID uuid, String safeUsername) throws IOException {
        String row = csv(LOG_TIME.format(Instant.now())) + "," + csv(safeUsername) + "," + csv(uuid.toString()) + System.lineSeparator();
        Files.writeString(uuidIndexFile, row, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private Map<String, String> latestUuidByUsername() {
        Map<String, String> map = new HashMap<>();
        if (!Files.exists(uuidIndexFile)) return map;
        try (BufferedReader reader = Files.newBufferedReader(uuidIndexFile, StandardCharsets.UTF_8)) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (first) { first = false; continue; }
                String[] parts = splitSimpleCsv(line);
                if (parts.length >= 3) map.put(parts[1], parts[2]);
            }
        } catch (IOException ignored) {}
        return map;
    }

    private String csv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private String[] splitSimpleCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    sb.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    sb.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                out.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        out.add(sb.toString());
        return out.toArray(String[]::new);
    }

    private String safeName(String name) {
        String clean = name.replaceAll("[^A-Za-z0-9_\\-]", "_");
        return clean.isBlank() ? "unknown" : clean;
    }

    private Path safePlayerLogFile(String playerName) throws IOException {
        Path root = playersFolder.toRealPath().normalize();
        Path candidate = root.resolve(safeName(playerName) + ".log").normalize();
        if (!candidate.startsWith(root)) throw new IOException("Unsafe player log filename");
        return candidate;
    }

    private record AlertEvidence(
            String alertId, String player, String title, String detail, String timeLabel,
            String evidenceType, String evidenceId, int windowMinutes, int matchingLines,
            List<LogRecord> evidence
    ) { }

    private AlertEvidence buildAlertEvidence(AlertManager.ModerationAlert alert) throws IOException {
        if (alert == null) return null;
        if ("combat".equalsIgnoreCase(alert.evidenceType())) {
            return new AlertEvidence(alert.id(), alert.player(), alert.title(), alert.detail(), alert.timeLabel(),
                    "combat", alert.evidenceId(), 0, 0, List.of());
        }

        String player = alert.evidenceId() == null || alert.evidenceId().isBlank()
                ? alert.player() : alert.evidenceId();
        Path file = safePlayerLogFile(player);
        if (!Files.exists(file)) {
            return new AlertEvidence(alert.id(), player, alert.title(), alert.detail(), alert.timeLabel(),
                    "player", player, getConfig().getInt("activity.window-minutes", 60), 0, List.of());
        }

        int windowMinutes = Math.max(15, getConfig().getInt("activity.window-minutes", 60));
        long anchor = Math.max(0L, alert.createdAt());
        long start = anchor - windowMinutes * 60_000L;
        long end = anchor + 30_000L;
        List<LogRecord> relevant = new ArrayList<>();
        List<LogRecord> nearby = new ArrayList<>();
        int matches = 0;
        int limit = 160;

        try (BufferedReader br = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                int endBracket = line.indexOf(']');
                if (endBracket <= 1 || line.length() <= endBracket + 2) continue;
                String timestamp = line.substring(1, endBracket);
                long epoch;
                try {
                    epoch = LocalDateTime.parse(timestamp, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                } catch (RuntimeException ignored) {
                    continue;
                }
                String action = line.substring(endBracket + 2);
                if (epoch >= anchor - 120_000L && epoch <= anchor + 120_000L) {
                    appendLimited(nearby, new LogRecord(timestamp, action), 50);
                }
                if (epoch < start || epoch > end || !alertEvidenceRelevant(alert.category(), alert.title(), action)) continue;
                matches++;
                appendLimited(relevant, new LogRecord(timestamp, action), limit);
            }
        }

        List<LogRecord> evidence = relevant.isEmpty() ? nearby : relevant;
        return new AlertEvidence(alert.id(), player, alert.title(), alert.detail(), alert.timeLabel(),
                "player", player, windowMinutes, matches, List.copyOf(evidence));
    }

    private static void appendLimited(List<LogRecord> records, LogRecord record, int limit) {
        if (records.size() >= limit) records.remove(0);
        records.add(record);
    }

    private static boolean alertEvidenceRelevant(String category, String title, String action) {
        String c = category == null ? "" : category.toUpperCase(Locale.ROOT);
        String t = title == null ? "" : title.toUpperCase(Locale.ROOT);
        String a = action == null ? "" : action.toUpperCase(Locale.ROOT);
        if (c.contains("BLOCK") || t.contains("DEMOLITION") || t.contains("STRUCTURE")) {
            if (!a.startsWith("BREAK ")) return false;
            String material = actionMaterial(a);
            return isAlertStructureMaterial(material);
        }
        if (c.contains("MINING")) return a.startsWith("BREAK ");
        if (c.contains("FARM")) return a.startsWith("BREAK ") || a.startsWith("PLACE ");
        if (c.contains("ENVIRONMENT")) {
            return a.startsWith("BUCKET_") || a.startsWith("FIRE_TOOL") || a.startsWith("IGNITE ");
        }
        if (c.contains("COMBAT")) {
            return a.startsWith("PVP_") || a.startsWith("COMBAT_") || a.startsWith("DEATH:");
        }
        return true;
    }

    private static String actionMaterial(String action) {
        if (action == null) return "";
        String[] parts = action.trim().split("\\s+", 3);
        return parts.length >= 2 ? parts[1].toUpperCase(Locale.ROOT) : "";
    }

    private static boolean isAlertStructureMaterial(String material) {
        String m = material == null ? "" : material.toUpperCase(Locale.ROOT);
        if (m.isBlank() || m.endsWith("_LOG") || m.endsWith("_WOOD") || m.endsWith("_STEM") || m.endsWith("_HYPHAE")
                || m.endsWith("_ORE") || m.equals("ANCIENT_DEBRIS")
                || List.of("STONE", "DEEPSLATE", "TUFF", "NETHERRACK", "BLACKSTONE", "BASALT",
                        "GRANITE", "DIORITE", "ANDESITE", "CALCITE", "DIRT", "GRASS_BLOCK", "GRAVEL",
                        "SAND", "RED_SAND", "CLAY", "MUD", "SNOW", "SNOW_BLOCK").contains(m)) {
            return false;
        }
        if (m.equals("WHEAT") || m.equals("CARROTS") || m.equals("POTATOES") || m.equals("BEETROOTS")
                || m.equals("NETHER_WART") || m.equals("COCOA") || m.contains("STEM") || m.equals("SUGAR_CANE")
                || m.equals("BAMBOO") || m.equals("CACTUS") || m.equals("SWEET_BERRY_BUSH")
                || m.equals("PITCHER_CROP") || m.equals("TORCHFLOWER_CROP")) return false;
        return m.contains("CHEST") || m.contains("BARREL") || m.contains("SHULKER") || m.contains("HOPPER")
                || m.contains("FURNACE") || m.contains("ANVIL") || m.contains("ENCHANT") || m.contains("BREWING")
                || m.contains("REDSTONE") || m.contains("PISTON") || m.contains("OBSERVER")
                || m.contains("REPEATER") || m.contains("COMPARATOR") || m.contains("DISPENSER")
                || m.contains("DROPPER") || m.contains("LEVER") || m.contains("BUTTON")
                || m.contains("PRESSURE_PLATE") || m.contains("POWERED_RAIL")
                || m.endsWith("_PLANKS") || m.contains("BRICKS") || m.contains("CONCRETE")
                || m.contains("GLASS") || m.endsWith("_DOOR") || m.endsWith("_TRAPDOOR")
                || m.endsWith("_FENCE") || m.endsWith("_FENCE_GATE") || m.endsWith("_WALL")
                || m.endsWith("_SLAB") || m.endsWith("_STAIRS") || m.endsWith("_WOOL")
                || m.endsWith("_CARPET") || m.endsWith("_BED") || m.equals("BOOKSHELF") || m.equals("LECTERN")
                || m.equals("IRON_BARS") || m.equals("CHAIN") || m.contains("LANTERN")
                || m.contains("COPPER_GRATE") || m.contains("COPPER_DOOR") || m.contains("COPPER_TRAPDOOR");
    }

    private void copyWebResource(String filename) {
        Path target = webFolder.resolve(filename);
        String resourcePath = "web/" + filename;
        try (InputStream in = getResource(resourcePath)) {
            if (in == null) return;
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            getLogger().warning("Failed to copy " + resourcePath + ": " + e.getMessage());
        }
    }

    private int configuredWebPort() {
        int configured = getConfig().getInt("web-interface.port", 8080);
        if (configured >= 1 && configured <= 65535) return configured;
        getLogger().warning("Invalid web-interface.port " + configured
                + ". Valid ports are 1 through 65535; falling back to 8080.");
        return 8080;
    }

    private boolean startWebServer(String bindAddress, int port) {
        try {
            webServer = HttpServer.create(new InetSocketAddress(bindAddress, port), 0);
            // Authenticate before any API handler performs disk reads or calculations.
            webServer.createContext("/api/players", authenticated(new PlayersApiHandler()));
            webServer.createContext("/api/logs", authenticated(new LogsApiHandler()));
            webServer.createContext("/api/search", authenticated(new SearchApiHandler()));
            webServer.createContext("/api/activity-evidence", authenticated(exchange -> {
                ActivityTracker.ActivityEvidence evidence = activityTracker.evidence(queryParam(exchange, "id"));
                if (evidence == null) { error(exchange, 404, "This activity has expired from the rolling window. Use player history to review older evidence."); return; }
                json(exchange, evidence);
            }));
            webServer.createContext("/api/activity", authenticated(new ActivityApiHandler()));
            webServer.createContext("/api/profile", authenticated(new ProfileApiHandler()));
            webServer.createContext("/api/history/delete", authenticated(new HistoryDeleteApiHandler()));
            webServer.createContext("/api/history", authenticated(new HistoryApiHandler()));
            webServer.createContext("/api/alerts/evidence", authenticated(new AlertEvidenceApiHandler()));
            webServer.createContext("/api/alerts/close", authenticated(new AlertCloseApiHandler()));
            webServer.createContext("/api/alerts", authenticated(new AlertsApiHandler()));
            webServer.createContext("/api/moderation/action", authenticated(new ModerationActionApiHandler()));
            webServer.createContext("/api/moderation", authenticated(new ModerationApiHandler()));
            webServer.createContext("/api/combat/log", authenticated(new CombatLogApiHandler()));
            webServer.createContext("/api/combat", authenticated(new CombatApiHandler()));
            webServer.createContext("/", new FileHandler());

            // A bounded pool prevents an unauthenticated/public flood from creating an
            // unbounded number of worker threads.
            webExecutor = Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "PlayerActionLogger-Web");
                t.setDaemon(true);
                return t;
            });
            webServer.setExecutor(webExecutor);
            webServer.start();
            return true;
        } catch (IOException | IllegalArgumentException e) {
            webServer = null;
            getLogger().warning("Failed to start web server on " + bindAddress + ":" + port + ": " + e.getMessage());
            return false;
        }
    }

    private HttpHandler authenticated(HttpHandler delegate) {
        return exchange -> {
            if (!isAuthorized(exchange)) {
                error(exchange, 401, "Unauthorized");
                return;
            }
            delegate.handle(exchange);
        };
    }

    private boolean isAuthorized(HttpExchange exchange) {
        String required = getConfig().getString("web-interface.access-token", "");
        if (required == null || required.isBlank()) return true;
        String header = exchange.getRequestHeaders().getFirst("X-PlayerLogger-Token");
        // Deliberately do not accept ?token=... because URLs leak into browser history,
        // proxy logs, screenshots, and bookmarks.
        return required.equals(header);
    }

    private String queryParam(HttpExchange exchange, String key) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) return null;
        for (String part : query.split("&")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2 && kv[0].equals(key)) {
                return URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private Map<String, String> formParams(HttpExchange exchange) throws IOException {
        Map<String, String> values = new HashMap<>();
        byte[] raw = exchange.getRequestBody().readNBytes(65_537);
        if (raw.length > 65_536) throw new IOException("Request body exceeds 64 KiB");
        String body = new String(raw, StandardCharsets.UTF_8);
        for (String part : body.split("&")) {
            if (part.isBlank()) continue;
            String[] kv = part.split("=", 2);
            String key = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
            String value = kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
            values.put(key, value);
        }
        return values;
    }

    private boolean isPrivilegedActionAuthorized(HttpExchange exchange) {
        if (!isAuthorized(exchange)) return false;
        if (!getConfig().getBoolean("moderation-actions.require-access-token", true)) return true;
        String required = getConfig().getString("web-interface.access-token", "");
        return required != null && !required.isBlank();
    }

    private static long parseLong(String value) {
        if (value == null || value.isBlank()) return 0L;
        try { return Long.parseLong(value.trim()); }
        catch (NumberFormatException ignored) { return 0L; }
    }

    private boolean requirePost(HttpExchange exchange) throws IOException {
        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) return true;
        error(exchange, 405, "POST required");
        return false;
    }

    private void json(HttpExchange exchange, Object body) throws IOException {
        if (!isAuthorized(exchange)) { error(exchange, 401, "Unauthorized"); return; }
        byte[] bytes = gson.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
    }

    private void error(HttpExchange exchange, int status, String message) throws IOException {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
    }

    private void initializePlayerLineCounts() throws IOException {
        if (!Files.exists(playersFolder)) return;
        try (var stream = Files.list(playersFolder)) {
            for (Path file : stream.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".log")).toList()) {
                String name = file.getFileName().toString().replaceFirst("\\.log$", "");
                playerLineCounts.put(name, new AtomicLong(countLines(file)));
            }
        }
    }

    private long playerLineCount(String safeUsername) {
        AtomicLong count = playerLineCounts.get(safeUsername);
        return count == null ? 0L : count.get();
    }

    private long countLines(Path f) {
        try (BufferedReader br = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            return br.lines().count();
        } catch (IOException e) {
            return 0;
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private class FileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/")) path = "/index.html";

            Path root = webFolder.toRealPath().normalize();
            Path file = root.resolve(path.substring(1)).normalize();
            if (!file.startsWith(root) || !Files.exists(file) || Files.isDirectory(file)) {
                error(exchange, 404, "404 Not Found");
                return;
            }

            String contentType = "text/html; charset=utf-8";
            if (path.endsWith(".css")) contentType = "text/css; charset=utf-8";
            if (path.endsWith(".js")) contentType = "application/javascript; charset=utf-8";
            byte[] bytes = Files.readAllBytes(file);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        }
    }

    private class PlayersApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Map<String, String> uuidByName = latestUuidByUsername();
            List<PlayerSummary> summaries = new ArrayList<>();
            if (Files.exists(playersFolder)) {
                try (var stream = Files.list(playersFolder)) {
                    Path[] files = stream
                            .filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".log"))
                            .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
                            .toArray(Path[]::new);
                    for (Path f : files) {
                        String name = f.getFileName().toString().replaceFirst("\\.log$", "");
                        summaries.add(new PlayerSummary(name, uuidByName.getOrDefault(name, ""), playerLineCount(name), formatSize(Files.size(f))));
                    }
                }
            }
            json(exchange, summaries);
        }
    }

    private class LogsApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String player = queryParam(exchange, "player");
            if (player == null || player.isBlank()) { error(exchange, 400, "Missing player"); return; }
            Path file = player.equals("_server") ? serverLogFile : safePlayerLogFile(player);

            List<LogRecord> records = new ArrayList<>();
            if (Files.exists(file)) {
                try (BufferedReader br = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        int endBracket = line.indexOf(']');
                        if (endBracket > 0 && line.length() > endBracket + 2) {
                            records.add(new LogRecord(line.substring(1, endBracket), line.substring(endBracket + 2)));
                        } else {
                            records.add(new LogRecord("", line));
                        }
                    }
                }
            }
            json(exchange, records);
        }
    }

    private class ActivityApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (activityTracker == null) { error(exchange, 503, "Activity tracker unavailable"); return; }
            json(exchange, activityTracker.snapshot());
        }
    }

    private class ProfileApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (activityTracker == null) { error(exchange, 503, "Activity tracker unavailable"); return; }
            String player = queryParam(exchange, "player");
            if (player == null || player.isBlank()) { error(exchange, 400, "Missing player"); return; }
            ActivityTracker.PlayerProfile profile = activityTracker.profile(player);
            if (profile == null) { error(exchange, 404, "Player profile not found in the current activity window"); return; }
            json(exchange, profile);
        }
    }

    private class HistoryApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (dailyActivityAnalyzer == null) { error(exchange, 503, "Historical analyzer unavailable"); return; }
            String player = queryParam(exchange, "player");
            if (player == null || player.isBlank()) { error(exchange, 400, "Missing player"); return; }
            String date = queryParam(exchange, "date");
            if (date == null || date.isBlank()) {
                DailyActivityAnalyzer.HistoryIndex index = dailyActivityAnalyzer.index(player);
                if (index == null) { error(exchange, 404, "Player log not found"); return; }
                json(exchange, index);
            } else {
                DailyActivityAnalyzer.HistoryDay day = dailyActivityAnalyzer.day(player, date);
                if (day == null) { error(exchange, 404, "Player history day not found"); return; }
                json(exchange, day);
            }
        }
    }

    private class HistoryDeleteApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!requirePost(exchange)) return;
            if (!getConfig().getBoolean("dashboard.history.allow-day-deletion", true)) {
                error(exchange, 403, "History-day deletion is disabled in config.yml"); return;
            }
            if (!isPrivilegedActionAuthorized(exchange)) {
                error(exchange, 403, "A non-empty dashboard access token is required for this action"); return;
            }
            Map<String, String> form = formParams(exchange);
            String player = form.getOrDefault("player", "");
            String date = form.getOrDefault("date", "");
            String moderator = form.getOrDefault("moderator", "Dashboard administrator");
            try {
                int removed = deletePlayerHistoryDay(player, date).get(15, TimeUnit.SECONDS);
                if (removed < 0) { error(exchange, 404, "Player log or date was not found"); return; }
                logSystem("MOD_ACTION moderator:" + LogUtils.cleanOneLine(moderator)
                        + " action:DELETE_HISTORY_DAY player:" + safeName(player)
                        + " date:" + date + " removed-lines:" + removed);
                json(exchange, Map.of("success", true, "removedLines", removed, "player", safeName(player), "date", date));
            } catch (Exception e) {
                error(exchange, 500, "Could not delete history day: " + e.getMessage());
            }
        }
    }

    private class AlertEvidenceApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!isAuthorized(exchange)) { error(exchange, 401, "Unauthorized"); return; }
            if (alertManager == null) { error(exchange, 503, "Alert manager unavailable"); return; }
            String id = queryParam(exchange, "id");
            if (id == null || id.isBlank()) { error(exchange, 400, "Missing alert id"); return; }
            AlertManager.ModerationAlert alert = alertManager.find(id);
            if (alert == null) { error(exchange, 404, "Alert not found"); return; }
            AlertEvidence evidence = buildAlertEvidence(alert);
            if (evidence == null) { error(exchange, 404, "Alert evidence not found"); return; }
            json(exchange, evidence);
        }
    }

    private class AlertsApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (alertManager == null) { error(exchange, 503, "Alert manager unavailable"); return; }
            alertManager.syncActivity(activityTracker == null ? null : activityTracker.snapshot());
            boolean includeClosed = "true".equalsIgnoreCase(queryParam(exchange, "includeClosed"));
            json(exchange, alertManager.snapshot(includeClosed));
        }
    }

    private class AlertCloseApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!requirePost(exchange)) return;
            if (!isPrivilegedActionAuthorized(exchange)) {
                error(exchange, 403, "A non-empty dashboard access token is required for this action"); return;
            }
            Map<String, String> form = formParams(exchange);
            AlertManager.ModerationAlert closed = alertManager.close(form.get("id"));
            if (closed == null) { error(exchange, 404, "Alert not found"); return; }
            json(exchange, closed);
        }
    }

    private class ModerationApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (moderationManager == null) { error(exchange, 503, "Moderation manager unavailable"); return; }
            json(exchange, moderationManager.state());
        }
    }

    private class ModerationActionApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!requirePost(exchange)) return;
            if (!isPrivilegedActionAuthorized(exchange)) {
                error(exchange, 403, "A non-empty dashboard access token is required for moderation actions"); return;
            }
            Map<String, String> form = formParams(exchange);
            long minutes = parseLong(form.get("durationMinutes"));
            long expiresAt = parseLong(form.get("expiresAt"));
            try {
                ModerationManager.ModerationResult result = moderationManager.execute(
                        form.getOrDefault("action", ""), form.getOrDefault("player", ""),
                        form.getOrDefault("reason", ""), form.getOrDefault("moderator", ""),
                        minutes, expiresAt, form.getOrDefault("alertId", "")).get(15, TimeUnit.SECONDS);
                if (!result.success()) { error(exchange, 400, result.message()); return; }
                json(exchange, result);
            } catch (Exception e) {
                error(exchange, 500, "Moderation action failed: " + e.getMessage());
            }
        }
    }

    private class CombatApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (combatManager == null) { error(exchange, 503, "Combat manager unavailable"); return; }
            json(exchange, combatManager.summaries());
        }
    }

    private class CombatLogApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (combatManager == null) { error(exchange, 503, "Combat manager unavailable"); return; }
            String id = queryParam(exchange, "id");
            if (id == null || id.isBlank()) { error(exchange, 400, "Missing incident id"); return; }
            CombatManager.CombatDetail detail = combatManager.detail(id);
            if (detail == null) { error(exchange, 404, "Combat incident not found"); return; }
            json(exchange, detail);
        }
    }

    private class SearchApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            List<String> terms = new ArrayList<>();
            for (String key : List.of("term1", "term2", "term3")) {
                String term = queryParam(exchange, key);
                if (term != null && !term.isBlank()) terms.add(term.toLowerCase(Locale.ROOT));
            }
            if (terms.isEmpty()) { error(exchange, 400, "Enter at least one term"); return; }

            int maxPerFile = getConfig().getInt("logging.max-search-results-per-file", 100);
            List<Path> files = new ArrayList<>();
            if (Files.exists(serverLogFile)) files.add(serverLogFile);
            if (Files.exists(playersFolder)) {
                try (var stream = Files.list(playersFolder)) {
                    stream.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".log"))
                            .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
                            .forEach(files::add);
                }
            }
            if (Files.exists(combatFolder)) {
                try (var stream = Files.list(combatFolder)) {
                    stream.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".log"))
                            .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
                            .forEach(files::add);
                }
            }

            List<SearchResult> results = new ArrayList<>();
            for (Path file : files) {
                List<SearchResult.Match> matches = new ArrayList<>();
                try (BufferedReader br = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    String line;
                    int lineNumber = 0;
                    while ((line = br.readLine()) != null && matches.size() < maxPerFile) {
                        lineNumber++;
                        String lower = line.toLowerCase(Locale.ROOT);
                        boolean allMatch = terms.stream().allMatch(lower::contains);
                        if (allMatch) matches.add(new SearchResult.Match(lineNumber, line));
                    }
                } catch (IOException ignored) {}
                if (!matches.isEmpty()) {
                    String display;
                    if (file.equals(serverLogFile)) display = "_server";
                    else if (file.getParent() != null && file.getParent().equals(combatFolder)) {
                        display = "combat/" + file.getFileName().toString().replaceFirst("\\.log$", "");
                    } else display = file.getFileName().toString().replaceFirst("\\.log$", "");
                    results.add(new SearchResult(display, matches));
                }
            }
            json(exchange, results);
        }
    }
}
