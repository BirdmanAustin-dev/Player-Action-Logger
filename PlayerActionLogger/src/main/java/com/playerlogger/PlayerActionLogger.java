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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
    private HttpServer webServer;
    private ActivityTracker activityTracker;
    private CombatManager combatManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
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

        copyWebResource("index.html");
        copyWebResource("style.css");
        copyWebResource("app.js");

        activityTracker = new ActivityTracker(this);
        combatManager = new CombatManager(this, activityTracker);
        for (Player player : getServer().getOnlinePlayers()) {
            activityTracker.initializeOnlinePlayer(player);
            combatManager.initializeOnlinePlayer(player);
        }
        getServer().getPluginManager().registerEvents(new EventListener(this), this);

        getServer().getScheduler().runTaskTimer(this, combatManager::tick, 20L, 20L);

        if (getConfig().getBoolean("logging.fluid-flow.enabled", false)
                && getConfig().getBoolean("logging.fluid-flow.aggregate", true)) {
            long seconds = Math.max(5, getConfig().getLong("logging.fluid-flow.aggregate-interval-seconds", 30));
            getServer().getScheduler().runTaskTimer(this, this::flushFluidFlowSummary, seconds * 20L, seconds * 20L);
        }

        if (getConfig().getBoolean("web-interface.enabled", true)) {
            String bindAddress = getConfig().getString("web-interface.bind-address", "127.0.0.1");
            int port = getConfig().getInt("web-interface.port", 8080);
            startWebServer(bindAddress, port);
            getLogger().info("PlayerActionLogger enabled. Web dashboard: http://" + bindAddress + ":" + port);
        } else {
            getLogger().info("PlayerActionLogger enabled. Web dashboard disabled by config.");
        }
    }

    @Override
    public void onDisable() {
        flushFluidFlowSummary();
        if (combatManager != null) combatManager.shutdown();
        if (webServer != null) webServer.stop(0);

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

    public void log(Player player, String action) {
        if (player == null || action == null) return;
        logPlayer(player.getUniqueId(), player.getName(), action);
    }

    public void logPlayer(UUID uuid, String username, String action) {
        if (uuid == null || username == null || action == null || logExecutor == null) return;
        String safeUsername = safeName(username);
        String cleanAction = LogUtils.cleanOneLine(action);
        boolean includeUuid = getConfig().getBoolean("logging.include-uuid-in-player-log-lines", false);
        String line = "[" + LOG_TIME.format(Instant.now()) + "] "
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
            } catch (IOException e) {
                getLogger().warning("Log write failed for " + safeUsername + ": " + e.getMessage());
            }
        });
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

    private void startWebServer(String bindAddress, int port) {
        try {
            webServer = HttpServer.create(new InetSocketAddress(bindAddress, port), 0);
            webServer.createContext("/api/players", new PlayersApiHandler());
            webServer.createContext("/api/logs", new LogsApiHandler());
            webServer.createContext("/api/search", new SearchApiHandler());
            webServer.createContext("/api/activity", new ActivityApiHandler());
            webServer.createContext("/api/profile", new ProfileApiHandler());
            webServer.createContext("/api/combat/log", new CombatLogApiHandler());
            webServer.createContext("/api/combat", new CombatApiHandler());
            webServer.createContext("/", new FileHandler());
            webServer.setExecutor(Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "PlayerActionLogger-Web");
                t.setDaemon(true);
                return t;
            }));
            webServer.start();
        } catch (IOException e) {
            getLogger().warning("Failed to start web server: " + e.getMessage());
        }
    }

    private boolean isAuthorized(HttpExchange exchange) {
        String required = getConfig().getString("web-interface.access-token", "");
        if (required == null || required.isBlank()) return true;
        String header = exchange.getRequestHeaders().getFirst("X-PlayerLogger-Token");
        String queryToken = queryParam(exchange, "token");
        return required.equals(header) || required.equals(queryToken);
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
