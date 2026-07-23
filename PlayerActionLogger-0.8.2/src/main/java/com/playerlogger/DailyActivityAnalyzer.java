package com.playerlogger;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the durable username log on demand and summarizes it by calendar day.
 * This is intentionally file-backed and cached; it does not add a database.
 */
public final class DailyActivityAnalyzer {
    private static final Pattern LOG_LINE = Pattern.compile("^\\[(\\d{4}-\\d{2}-\\d{2}) (\\d{2}:\\d{2}:\\d{2})] (.*)$");
    private static final Pattern MATERIAL_AFTER_ACTION = Pattern.compile("^(?:BREAK|PLACE)\\s+([A-Z0-9_]+)");
    private static final Pattern LOCATION = Pattern.compile("\\((-?\\d+),(-?\\d+),(-?\\d+)\\)");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public record DaySummary(
            String date,
            String firstSeen,
            String lastSeen,
            int totalEvents,
            String dominantActivity,
            Map<String, Integer> activities,
            List<String> highlights
    ) {}

    public record HistoryIndex(
            String player,
            int totalDays,
            long totalEvents,
            List<DaySummary> days
    ) {}

    public record ActivityGroup(
            String category,
            int count,
            String firstTime,
            String lastTime,
            List<String> examples
    ) {}

    public record HistoryDay(
            String player,
            DaySummary summary,
            List<ActivityGroup> groups,
            List<LogRecord> evidence
    ) {}

    private static final class DayAccumulator {
        private final String date;
        private final Map<String, Integer> categories = new HashMap<>();
        private final Map<String, GroupAccumulator> groups = new HashMap<>();
        private final List<String> highlights = new ArrayList<>();
        private final List<LogRecord> evidence = new ArrayList<>();
        private int total;
        private String firstTime = "";
        private String lastTime = "";

        private DayAccumulator(String date) {
            this.date = date;
        }

        private void add(String time, String action, boolean includeDetail) {
            String category = categoryFor(action);
            total++;
            categories.merge(category, 1, Integer::sum);
            if (firstTime.isBlank()) firstTime = time;
            lastTime = time;
            groups.computeIfAbsent(category, GroupAccumulator::new).add(time, action);

            if (isHighlight(action) && highlights.size() < 12) highlights.add(action);
            if (includeDetail) evidence.add(new LogRecord(date + " " + time, action));
        }

        private DaySummary summary() {
            Map<String, Integer> ordered = categories.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                            .thenComparing(Map.Entry::getKey))
                    .collect(LinkedHashMap::new,
                            (map, entry) -> map.put(entry.getKey(), entry.getValue()),
                            LinkedHashMap::putAll);
            String dominant = ordered.keySet().stream().findFirst().orElse("No classified activity");
            return new DaySummary(date, firstTime, lastTime, total, dominant, ordered,
                    List.copyOf(highlights));
        }

        private List<ActivityGroup> groupRecords() {
            return groups.values().stream()
                    .map(GroupAccumulator::record)
                    .sorted(Comparator.comparingInt(ActivityGroup::count).reversed()
                            .thenComparing(ActivityGroup::category))
                    .toList();
        }
    }

    private static final class GroupAccumulator {
        private final String category;
        private final List<String> examples = new ArrayList<>();
        private int count;
        private String firstTime = "";
        private String lastTime = "";

        private GroupAccumulator(String category) {
            this.category = category;
        }

        private void add(String time, String action) {
            count++;
            if (firstTime.isBlank()) firstTime = time;
            lastTime = time;
            if (examples.size() < 5) examples.add(action);
        }

        private ActivityGroup record() {
            return new ActivityGroup(category, count, firstTime, lastTime, List.copyOf(examples));
        }
    }

    private record CachedIndex(long size, long modified, HistoryIndex index) {}

    private final PlayerActionLogger plugin;
    private final Map<String, CachedIndex> cache = new ConcurrentHashMap<>();

    public DailyActivityAnalyzer(PlayerActionLogger plugin) {
        this.plugin = plugin;
    }

    public HistoryIndex index(String requestedName) {
        Path file = playerFile(requestedName);
        if (file == null || !Files.exists(file)) return null;
        try {
            long size = Files.size(file);
            long modified = Files.getLastModifiedTime(file).toMillis();
            String key = safeName(requestedName).toLowerCase(Locale.ROOT);
            CachedIndex cached = cache.get(key);
            if (cached != null && cached.size == size && cached.modified == modified) return cached.index;

            Map<String, DayAccumulator> days = parse(file, null, false);
            List<DaySummary> summaries = days.values().stream()
                    .map(DayAccumulator::summary)
                    .sorted(Comparator.comparing(DaySummary::date).reversed())
                    .toList();
            long totalEvents = summaries.stream().mapToLong(DaySummary::totalEvents).sum();
            HistoryIndex index = new HistoryIndex(safeName(requestedName), summaries.size(), totalEvents, summaries);
            cache.put(key, new CachedIndex(size, modified, index));
            return index;
        } catch (IOException e) {
            plugin.getLogger().warning("Could not analyze historical log for " + requestedName + ": " + e.getMessage());
            return null;
        }
    }

    public HistoryDay day(String requestedName, String requestedDate) {
        if (requestedDate == null || requestedDate.isBlank()) return null;
        try {
            LocalDate.parse(requestedDate);
        } catch (DateTimeParseException e) {
            return null;
        }
        Path file = playerFile(requestedName);
        if (file == null || !Files.exists(file)) return null;
        try {
            DayAccumulator day = parse(file, requestedDate, true).get(requestedDate);
            if (day == null) return null;
            return new HistoryDay(safeName(requestedName), day.summary(), day.groupRecords(),
                    List.copyOf(day.evidence));
        } catch (IOException e) {
            plugin.getLogger().warning("Could not read historical day for " + requestedName + ": " + e.getMessage());
            return null;
        }
    }

    public void invalidate(String username) {
        if (username == null) return;
        cache.remove(safeName(username).toLowerCase(Locale.ROOT));
    }

    public void invalidateAll() {
        cache.clear();
    }

    private Map<String, DayAccumulator> parse(Path file, String onlyDate, boolean includeDetail) throws IOException {
        Map<String, DayAccumulator> days = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher matcher = LOG_LINE.matcher(line);
                if (!matcher.matches()) continue;
                String date = matcher.group(1);
                if (onlyDate != null && !onlyDate.equals(date)) continue;
                String time = matcher.group(2);
                String action = matcher.group(3);
                days.computeIfAbsent(date, DayAccumulator::new).add(time, action, includeDetail);
            }
        }
        return days;
    }

    private Path playerFile(String requestedName) {
        if (requestedName == null || requestedName.isBlank()) return null;
        Path root = plugin.getPlayersFolder();
        if (root == null) return null;
        Path file = root.resolve(safeName(requestedName) + ".log").normalize();
        return file.startsWith(root.normalize()) ? file : null;
    }

    private static String safeName(String name) {
        String clean = name == null ? "" : name.replaceAll("[^A-Za-z0-9_\\-]", "_");
        return clean.isBlank() ? "unknown" : clean;
    }

    private static String categoryFor(String action) {
        String upper = action.toUpperCase(Locale.ROOT);
        String material = materialFrom(action);

        if (upper.startsWith("PVP_") || upper.startsWith("COMBAT_JOIN")) return "PvP combat";
        if (upper.startsWith("PVE_") || upper.startsWith("KILL ")) return "PvE combat";
        if (upper.startsWith("DEATH:")) return "Deaths";
        if (upper.startsWith("CHAT:")) return "Chatting";
        if (upper.startsWith("CMD:")) return "Commands";
        if (upper.startsWith("LOGIN") || upper.startsWith("LOGOUT") || upper.startsWith("KICKED:")) return "Server sessions";
        if (upper.startsWith("TELEPORT") || upper.startsWith("PORTAL_")) return "Travel";
        if (upper.startsWith("FISH ")) return "Fishing";
        if (upper.startsWith("BREED ") || upper.startsWith("SHEAR ")) return "Animal care";
        if (upper.startsWith("CRAFT ") || upper.startsWith("FURNACE_EXTRACT")
                || containsAny(upper, " WITH FURNACE", " WITH BLAST_FURNACE", " WITH SMOKER", " WITH ANVIL", " WITH ENCHANT", " WITH BREWING")) {
            return "Crafting / utility";
        }
        if (upper.startsWith("INV_CLICK") || upper.startsWith("CONTAINER_") || upper.startsWith("PICKUP ") || upper.startsWith("DROP ")
                || containsAny(upper, " WITH CHEST", " WITH BARREL", " WITH SHULKER", " WITH HOPPER")) {
            return "Items / storage";
        }
        if (upper.startsWith("BUCKET_") || upper.startsWith("FIRE_TOOL") || upper.startsWith("IGNITE ")) return "Environmental work";
        if (upper.startsWith("SIGN ")) return "Signs / communication";
        if (upper.startsWith("BED_ENTER")) return "Resting";
        if (upper.startsWith("PLACE ")) {
            if (isCrop(material)) return "Farming";
            if (isRedstone(material)) return "Redstone engineering";
            return "Building";
        }
        if (upper.startsWith("BREAK ")) {
            if (isCrop(material)) return "Farming";
            if (isLog(material)) return "Woodcutting";
            if (isOre(material) || isStone(material) || yLevel(action) <= 20) return "Mining";
            if (isExcavation(material)) return "Excavating / landscaping";
            return "Block removal";
        }
        if (upper.startsWith("INTERACT ")) return "World interaction";
        if (upper.startsWith("CONSUME ")) return "Food / potions";
        if (upper.startsWith("HANG_")) return "Decorating";
        return "Other activity";
    }

    private static boolean isHighlight(String action) {
        String upper = action.toUpperCase(Locale.ROOT);
        return upper.startsWith("DEATH:") || upper.startsWith("PVP_") || upper.startsWith("COMBAT_JOIN")
                || upper.contains("ELYTRA") || upper.contains("ANCIENT_DEBRIS") || upper.contains("DIAMOND_ORE")
                || upper.startsWith("BUCKET_EMPTY LAVA") || upper.startsWith("IGNITE ")
                || upper.startsWith("KICKED:") || upper.startsWith("TELEPORT ");
    }

    private static String materialFrom(String action) {
        Matcher matcher = MATERIAL_AFTER_ACTION.matcher(action.toUpperCase(Locale.ROOT));
        return matcher.find() ? matcher.group(1) : "";
    }

    private static int yLevel(String action) {
        Matcher matcher = LOCATION.matcher(action);
        if (!matcher.find()) return Integer.MAX_VALUE;
        try {
            return Integer.parseInt(matcher.group(2));
        } catch (NumberFormatException ignored) {
            return Integer.MAX_VALUE;
        }
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) if (value.contains(needle)) return true;
        return false;
    }

    private static boolean isCrop(String material) {
        String m = material.toUpperCase(Locale.ROOT);
        return m.equals("WHEAT") || m.equals("CARROTS") || m.equals("POTATOES") || m.equals("BEETROOTS")
                || m.equals("NETHER_WART") || m.equals("COCOA") || m.contains("STEM") || m.equals("SUGAR_CANE")
                || m.equals("BAMBOO") || m.equals("CACTUS") || m.equals("SWEET_BERRY_BUSH")
                || m.equals("PITCHER_CROP") || m.equals("TORCHFLOWER_CROP");
    }

    private static boolean isOre(String material) {
        String m = material.toUpperCase(Locale.ROOT);
        return m.endsWith("_ORE") || m.equals("ANCIENT_DEBRIS");
    }

    private static boolean isStone(String material) {
        String m = material.toUpperCase(Locale.ROOT);
        return containsAny(m, "STONE", "DEEPSLATE", "TUFF", "NETHERRACK", "BLACKSTONE", "BASALT", "GRANITE", "DIORITE", "ANDESITE", "CALCITE");
    }

    private static boolean isLog(String material) {
        String m = material.toUpperCase(Locale.ROOT);
        return m.endsWith("_LOG") || m.endsWith("_WOOD") || m.endsWith("_STEM") || m.endsWith("_HYPHAE");
    }

    private static boolean isExcavation(String material) {
        String m = material.toUpperCase(Locale.ROOT);
        return containsAny(m, "DIRT", "GRASS_BLOCK", "SAND", "GRAVEL", "CLAY", "MUD", "SNOW", "TERRACOTTA");
    }

    private static boolean isRedstone(String material) {
        String m = material.toUpperCase(Locale.ROOT);
        return containsAny(m, "REDSTONE", "PISTON", "OBSERVER", "REPEATER", "COMPARATOR", "DISPENSER",
                "DROPPER", "HOPPER", "LEVER", "BUTTON", "PRESSURE_PLATE", "POWERED_RAIL", "TARGET");
    }
}
