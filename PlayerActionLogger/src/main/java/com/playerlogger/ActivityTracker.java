package com.playerlogger;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maintains a lightweight, in-memory rolling activity window for the dashboard.
 * It does not create a database and does not add extra activity log files.
 */
public final class ActivityTracker {
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    public enum Kind {
        BLOCK_BREAK,
        BLOCK_PLACE,
        INVENTORY,
        CRAFT,
        FURNACE,
        FARM,
        BREED,
        BUCKET,
        FIRE,
        PVP,
        PVE,
        ITEM,
        CHUNK,
        DEATH,
        LOGIN,
        LOGOUT
    }

    public record ActivityEvent(long time, Kind kind, String material, String world,
                                int x, int y, int z, int chunkX, int chunkZ) {}

    public record PlayerActivity(
            String name,
            String uuid,
            boolean online,
            String currentActivity,
            int durationMinutes,
            String headline,
            List<String> details,
            Map<String, Integer> activityBreakdown,
            Map<String, Integer> metrics,
            int blocksPlaced,
            int blocksBroken,
            int pvpEvents,
            int pveEvents,
            int chunksVisited,
            long lastActivityEpochMillis
    ) {}

    /** A lightweight heatmap substitute: ranked chunks, not a rendered map. */
    public record AreaActivity(
            String world,
            int chunkX,
            int chunkZ,
            int score,
            int visits,
            int workEvents,
            int blockChanges,
            int combatEvents,
            String primaryActivity,
            List<String> players,
            long lastActivityEpochMillis
    ) {}

    public record ActivityFeedItem(
            String id,
            long time,
            String timeLabel,
            String category,
            String title,
            String detail,
            String player,
            String world,
            int chunkX,
            int chunkZ,
            String evidenceType,
            String evidenceId
    ) {}

    public record ActivityAlert(
            String id,
            long time,
            String timeLabel,
            String severity,
            String category,
            String title,
            String detail,
            String player,
            String evidenceType,
            String evidenceId
    ) {}

    public record DashboardStatistics(
            int onlinePlayers,
            int activePlayers,
            int idlePlayers,
            int trackedPlayers,
            int totalEvents,
            int blocksPlaced,
            int blocksBroken,
            int pvpEvents,
            int pveEvents,
            int farmingEvents,
            int craftingEvents,
            int inventoryEvents,
            int chunkTransitions,
            int activeAreas,
            Map<String, Integer> activityTotals
    ) {}

    public record PlayerProfile(
            PlayerActivity summary,
            DashboardStatistics statistics,
            List<AreaActivity> topAreas,
            List<ActivityFeedItem> recentActivity,
            List<ActivityAlert> alerts
    ) {}

    public record ActivityOverview(
            int windowMinutes,
            long generatedAt,
            Map<String, Integer> totals,
            DashboardStatistics statistics,
            List<PlayerActivity> players,
            List<AreaActivity> activeAreas,
            List<ActivityFeedItem> feed,
            List<ActivityAlert> alerts
    ) {}

    private static final class PlayerWindow {
        private volatile String name;
        private final UUID uuid;
        private final Deque<ActivityEvent> events = new ArrayDeque<>();
        private volatile long lastMeaningfulAt;
        private volatile String lastChunkKey = "";
        private volatile boolean online;

        private PlayerWindow(UUID uuid, String name, long now) {
            this.uuid = uuid;
            this.name = name;
            this.lastMeaningfulAt = now;
        }
    }

    private static final class AreaAccumulator {
        private final String world;
        private final int chunkX;
        private final int chunkZ;
        private final Set<String> players = new LinkedHashSet<>();
        private final Map<String, Integer> categories = new HashMap<>();
        private int score;
        private int visits;
        private int workEvents;
        private int blockChanges;
        private int combatEvents;
        private long lastActivity;

        private AreaAccumulator(ActivityEvent event) {
            this.world = event.world();
            this.chunkX = event.chunkX();
            this.chunkZ = event.chunkZ();
        }

        private void add(String playerName, ActivityEvent event) {
            players.add(playerName);
            lastActivity = Math.max(lastActivity, event.time());
            String category = categoryFor(event);
            categories.merge(category, 1, Integer::sum);

            switch (event.kind()) {
                case CHUNK -> { visits++; score += 1; }
                case BLOCK_PLACE, BLOCK_BREAK -> { blockChanges++; workEvents++; score += 4; }
                case FARM, BREED -> { workEvents++; score += 4; }
                case INVENTORY, ITEM -> { workEvents++; score += 2; }
                case CRAFT, FURNACE -> { workEvents++; score += 3; }
                case PVP -> { combatEvents++; score += 7; }
                case PVE -> { combatEvents++; score += 3; }
                case BUCKET, FIRE -> { workEvents++; score += 4; }
                case DEATH -> score += 3;
                case LOGIN, LOGOUT -> score += 1;
            }
        }

        private AreaActivity toRecord() {
            String primary = categories.entrySet().stream()
                    .max(Map.Entry.<String, Integer>comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(visits > 0 ? "Frequently visited" : "General activity");
            return new AreaActivity(world, chunkX, chunkZ, score, visits, workEvents,
                    blockChanges, combatEvents, primary, new ArrayList<>(players), lastActivity);
        }
    }

    private static final class FeedAccumulator {
        private final long bucket;
        private final String player;
        private final String category;
        private final String world;
        private final int chunkX;
        private final int chunkZ;
        private final Map<String, Integer> materials = new HashMap<>();
        private int count;
        private long latest;

        private FeedAccumulator(long bucket, String player, String category, ActivityEvent event) {
            this.bucket = bucket;
            this.player = player;
            this.category = category;
            this.world = event.world();
            this.chunkX = event.chunkX();
            this.chunkZ = event.chunkZ();
        }

        private void add(ActivityEvent event) {
            count++;
            latest = Math.max(latest, event.time());
            if (event.material() != null && !event.material().isBlank()) {
                materials.merge(event.material(), 1, Integer::sum);
            }
        }

        private ActivityFeedItem toRecord() {
            String title = feedTitle(player, category, count);
            String detail = count + " grouped event" + plural(count)
                    + " in " + world + " chunk(" + chunkX + "," + chunkZ + ")";
            String materialText = topMaterialText(materials, 3);
            if (!materialText.isBlank()) detail += "; " + materialText;
            String id = player + "-" + bucket + "-" + category.replaceAll("[^A-Za-z0-9]", "");
            return new ActivityFeedItem(id, latest, DISPLAY_TIME.format(Instant.ofEpochMilli(latest)),
                    category, title, detail, player, world, chunkX, chunkZ, "player", player);
        }
    }

    private final PlayerActionLogger plugin;
    private final Map<UUID, PlayerWindow> windows = new ConcurrentHashMap<>();

    public ActivityTracker(PlayerActionLogger plugin) {
        this.plugin = plugin;
    }

    public void initializeOnlinePlayer(Player player) {
        if (!enabled() || player == null) return;
        long now = System.currentTimeMillis();
        PlayerWindow window = windows.computeIfAbsent(player.getUniqueId(),
                ignored -> new PlayerWindow(player.getUniqueId(), player.getName(), now));
        window.name = player.getName();
        window.online = true;
        window.lastChunkKey = player.getWorld().getName() + ":" + player.getLocation().getChunk().getX()
                + ":" + player.getLocation().getChunk().getZ();
    }

    public void record(Player player, Kind kind) {
        if (player == null) return;
        record(player, kind, "", player.getLocation().getBlock());
    }

    public void record(Player player, Kind kind, Material material, Block block) {
        record(player, kind, material == null ? "" : material.name(), block);
    }

    public void record(Player player, Kind kind, String material, Block block) {
        if (!enabled() || player == null || kind == null || block == null) return;
        long now = System.currentTimeMillis();
        PlayerWindow window = windows.computeIfAbsent(player.getUniqueId(),
                ignored -> new PlayerWindow(player.getUniqueId(), player.getName(), now));
        window.name = player.getName();
        ActivityEvent event = new ActivityEvent(now, kind, material == null ? "" : material,
                block.getWorld().getName(), block.getX(), block.getY(), block.getZ(),
                block.getChunk().getX(), block.getChunk().getZ());
        synchronized (window.events) {
            window.events.addLast(event);
            prune(window, now);
            enforceEventCap(window);
        }
        if (kind == Kind.LOGIN) window.online = true;
        if (kind == Kind.LOGOUT) window.online = false;
        if (kind != Kind.LOGOUT) window.lastMeaningfulAt = now;
    }

    public void recordChunkTransition(Player player) {
        if (!enabled() || player == null) return;
        long now = System.currentTimeMillis();
        String chunkKey = player.getWorld().getName() + ":" + player.getLocation().getChunk().getX()
                + ":" + player.getLocation().getChunk().getZ();
        PlayerWindow window = windows.computeIfAbsent(player.getUniqueId(),
                ignored -> new PlayerWindow(player.getUniqueId(), player.getName(), now));
        window.name = player.getName();
        if (chunkKey.equals(window.lastChunkKey)) return;
        window.lastChunkKey = chunkKey;
        record(player, Kind.CHUNK, "", player.getLocation().getBlock());
    }

    public long secondsSinceMeaningful(UUID uuid) {
        PlayerWindow window = windows.get(uuid);
        if (window == null) return Long.MAX_VALUE;
        return Math.max(0, (System.currentTimeMillis() - window.lastMeaningfulAt) / 1000L);
    }

    public ActivityOverview snapshot() {
        long now = System.currentTimeMillis();
        if (!enabled()) {
            DashboardStatistics emptyStats = new DashboardStatistics(0, 0, 0, 0, 0, 0, 0,
                    0, 0, 0, 0, 0, 0, 0, Map.of());
            return new ActivityOverview(windowMinutes(), now, Map.of(), emptyStats,
                    List.of(), List.of(), List.of(), List.of());
        }

        List<PlayerActivity> players = new ArrayList<>();
        Map<String, Integer> totals = new LinkedHashMap<>();
        Map<String, AreaAccumulator> areas = new HashMap<>();
        List<ActivityEvent> allEvents = new ArrayList<>();
        Map<String, List<ActivityEvent>> eventsByPlayer = new LinkedHashMap<>();

        for (PlayerWindow window : windows.values()) {
            List<ActivityEvent> events = copyEvents(window, now);
            if (events.isEmpty() && !window.online) continue;
            PlayerActivity activity = summarize(window, events, window.online, now);
            players.add(activity);
            totals.merge(activity.currentActivity(), 1, Integer::sum);
            eventsByPlayer.put(window.name, events);
            allEvents.addAll(events);

            for (ActivityEvent event : events) {
                String key = event.world() + ":" + event.chunkX() + ":" + event.chunkZ();
                areas.computeIfAbsent(key, ignored -> new AreaAccumulator(event)).add(window.name, event);
            }
        }

        players.sort(Comparator.comparing(PlayerActivity::online).reversed()
                .thenComparing(Comparator.comparingLong(PlayerActivity::lastActivityEpochMillis).reversed()));

        List<AreaActivity> activeAreas = rankedAreas(areas);
        List<ActivityFeedItem> feed = buildFeed(eventsByPlayer);
        List<ActivityAlert> alerts = buildAlerts(players, now);
        DashboardStatistics statistics = statistics(players, allEvents, activeAreas, totals);

        return new ActivityOverview(windowMinutes(), now, totals, statistics, players,
                activeAreas, feed, alerts);
    }

    public PlayerProfile profile(String requestedName) {
        if (requestedName == null || requestedName.isBlank()) return null;
        long now = System.currentTimeMillis();
        PlayerWindow window = windows.values().stream()
                .filter(w -> w.name.equalsIgnoreCase(requestedName))
                .findFirst().orElse(null);
        if (window == null) return null;

        List<ActivityEvent> events = copyEvents(window, now);
        PlayerActivity summary = summarize(window, events, window.online, now);
        Map<String, List<ActivityEvent>> onlyPlayer = Map.of(window.name, events);
        List<ActivityFeedItem> feed = buildFeed(onlyPlayer).stream().limit(profileFeedLimit()).toList();
        List<ActivityAlert> alerts = buildAlerts(List.of(summary), now);

        Map<String, AreaAccumulator> areaMap = new HashMap<>();
        for (ActivityEvent event : events) {
            String key = event.world() + ":" + event.chunkX() + ":" + event.chunkZ();
            areaMap.computeIfAbsent(key, ignored -> new AreaAccumulator(event)).add(window.name, event);
        }
        List<AreaActivity> topAreas = rankedAreas(areaMap).stream().limit(6).toList();
        DashboardStatistics stats = statistics(List.of(summary), events, topAreas,
                Map.of(summary.currentActivity(), 1));
        return new PlayerProfile(summary, stats, topAreas, feed, alerts);
    }

    private List<ActivityEvent> copyEvents(PlayerWindow window, long now) {
        synchronized (window.events) {
            prune(window, now);
            return new ArrayList<>(window.events);
        }
    }

    private List<AreaActivity> rankedAreas(Map<String, AreaAccumulator> areas) {
        return areas.values().stream()
                .map(AreaAccumulator::toRecord)
                .filter(area -> area.score() >= activeAreaMinimumScore())
                .sorted(Comparator.comparingInt(AreaActivity::score).reversed()
                        .thenComparing(Comparator.comparingLong(AreaActivity::lastActivityEpochMillis).reversed()))
                .limit(activeAreaLimit())
                .toList();
    }

    private List<ActivityFeedItem> buildFeed(Map<String, List<ActivityEvent>> eventsByPlayer) {
        long bucketSize = Math.max(1, feedBucketMinutes()) * 60_000L;
        Map<String, FeedAccumulator> grouped = new HashMap<>();
        for (Map.Entry<String, List<ActivityEvent>> playerEvents : eventsByPlayer.entrySet()) {
            String player = playerEvents.getKey();
            for (ActivityEvent event : playerEvents.getValue()) {
                String category = categoryFor(event);
                long bucket = event.time() / bucketSize;
                String key = player + "|" + bucket + "|" + category + "|" + event.world()
                        + "|" + event.chunkX() + "|" + event.chunkZ();
                grouped.computeIfAbsent(key,
                        ignored -> new FeedAccumulator(bucket, player, category, event)).add(event);
            }
        }
        return grouped.values().stream()
                .map(FeedAccumulator::toRecord)
                .sorted(Comparator.comparingLong(ActivityFeedItem::time).reversed())
                .limit(feedLimit())
                .toList();
    }

    private List<ActivityAlert> buildAlerts(List<PlayerActivity> players, long now) {
        List<ActivityAlert> alerts = new ArrayList<>();
        for (PlayerActivity player : players) {
            Map<String, Integer> m = player.metrics();
            int broken = player.blocksBroken();
            int placed = player.blocksPlaced();
            int sensitive = m.getOrDefault("sensitiveBreaks", 0);
            int harvested = m.getOrDefault("cropsHarvested", 0);
            int planted = m.getOrDefault("cropsPlanted", 0);
            int ores = m.getOrDefault("ores", 0);
            int underground = m.getOrDefault("undergroundBreaks", 0);
            int fireBucket = m.getOrDefault("environmentalEvents", 0);
            long time = player.lastActivityEpochMillis();

            if ("Possible demolition".equals(player.currentActivity()) || (broken >= 100 && broken > placed * 5)) {
                alerts.add(alert(player, time, "HIGH", "Block activity", "Possible demolition pattern",
                        broken + " blocks broken, " + placed + " placed, and " + sensitive
                                + " sensitive block breaks in the rolling window."));
            }
            if (harvested >= 20 && planted * 2 < harvested) {
                alerts.add(alert(player, time, "MEDIUM", "Farming", "Low crop replant rate",
                        harvested + " crop harvest events compared with " + planted + " planting events."));
            }
            if (ores >= 12 && underground > 0 && ores * 5 > underground) {
                alerts.add(alert(player, time, "LOW", "Mining", "High ore-event concentration",
                        ores + " ore events among " + underground + " underground block breaks. This is a review prompt, not proof of X-ray."));
            }
            if (fireBucket >= 10) {
                alerts.add(alert(player, time, "MEDIUM", "Environmental", "Frequent fire or bucket activity",
                        fireBucket + " recent fire/bucket events were grouped in the activity window."));
            }
            if (player.pvpEvents() >= 12) {
                alerts.add(alert(player, time, "LOW", "Combat", "Sustained PvP activity",
                        player.pvpEvents() + " PvP events were recorded. Review Combat Center for incident context."));
            }
        }
        alerts.sort(Comparator.comparingInt((ActivityAlert a) -> severityRank(a.severity())).reversed()
                .thenComparing(Comparator.comparingLong(ActivityAlert::time).reversed()));
        return alerts.stream().limit(alertLimit()).toList();
    }

    private ActivityAlert alert(PlayerActivity player, long time, String severity, String category,
                                String title, String detail) {
        String id = player.name() + "-" + category.replaceAll("[^A-Za-z0-9]", "") + "-" + time;
        return new ActivityAlert(id, time, DISPLAY_TIME.format(Instant.ofEpochMilli(time)), severity,
                category, title, detail, player.name(), "player", player.name());
    }

    private DashboardStatistics statistics(List<PlayerActivity> players, List<ActivityEvent> events,
                                           List<AreaActivity> activeAreas, Map<String, Integer> totals) {
        int online = (int) players.stream().filter(PlayerActivity::online).count();
        int idle = (int) players.stream().filter(p -> "Idle / AFK".equals(p.currentActivity())).count();
        int active = (int) players.stream().filter(p -> p.online()
                && !List.of("Idle / AFK", "Online", "Offline").contains(p.currentActivity())).count();
        int placed = 0;
        int broken = 0;
        int pvp = 0;
        int pve = 0;
        int farm = 0;
        int craft = 0;
        int inventory = 0;
        int chunks = 0;
        for (ActivityEvent event : events) {
            switch (event.kind()) {
                case BLOCK_PLACE -> placed++;
                case BLOCK_BREAK -> broken++;
                case PVP -> pvp++;
                case PVE -> pve++;
                case FARM, BREED -> farm++;
                case CRAFT, FURNACE -> craft++;
                case INVENTORY, ITEM -> inventory++;
                case CHUNK -> chunks++;
                default -> { }
            }
        }
        return new DashboardStatistics(online, active, idle, players.size(), events.size(), placed,
                broken, pvp, pve, farm, craft, inventory, chunks, activeAreas.size(),
                new LinkedHashMap<>(totals));
    }

    private PlayerActivity summarize(PlayerWindow window, List<ActivityEvent> events, boolean online, long now) {
        Map<Kind, Integer> counts = new HashMap<>();
        Map<String, Integer> placedMaterials = new HashMap<>();
        Set<String> chunks = new HashSet<>();
        int undergroundBreaks = 0;
        int stoneBreaks = 0;
        int ores = 0;
        int torches = 0;
        int cropsHarvested = 0;
        int cropsPlanted = 0;
        int sensitiveBreaks = 0;
        int redstonePlaced = 0;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        long firstEvent = now;
        long lastEvent = 0;

        for (ActivityEvent event : events) {
            counts.merge(event.kind(), 1, Integer::sum);
            firstEvent = Math.min(firstEvent, event.time());
            lastEvent = Math.max(lastEvent, event.time());
            chunks.add(event.world() + ":" + event.chunkX() + ":" + event.chunkZ());

            if (event.kind() == Kind.BLOCK_PLACE) {
                placedMaterials.merge(event.material(), 1, Integer::sum);
                if (isTorch(event.material())) torches++;
                if (isCrop(event.material())) cropsPlanted++;
                if (isRedstone(event.material())) redstonePlaced++;
            } else if (event.kind() == Kind.BLOCK_BREAK) {
                if (event.y() <= 20) {
                    undergroundBreaks++;
                    minY = Math.min(minY, event.y());
                    maxY = Math.max(maxY, event.y());
                }
                if (isStoneLike(event.material())) stoneBreaks++;
                if (isOre(event.material())) ores++;
                if (isCrop(event.material())) cropsHarvested++;
                if (isSensitive(event.material())) sensitiveBreaks++;
            }
        }

        int placed = counts.getOrDefault(Kind.BLOCK_PLACE, 0);
        int broken = counts.getOrDefault(Kind.BLOCK_BREAK, 0);
        int inventory = counts.getOrDefault(Kind.INVENTORY, 0) + counts.getOrDefault(Kind.ITEM, 0);
        int craft = counts.getOrDefault(Kind.CRAFT, 0) + counts.getOrDefault(Kind.FURNACE, 0);
        int farm = counts.getOrDefault(Kind.FARM, 0) + counts.getOrDefault(Kind.BREED, 0)
                + cropsHarvested + cropsPlanted;
        int pvp = counts.getOrDefault(Kind.PVP, 0);
        int pve = counts.getOrDefault(Kind.PVE, 0);
        int chunkCount = counts.getOrDefault(Kind.CHUNK, 0);
        int yRange = minY == Integer.MAX_VALUE ? 999 : maxY - minY;
        long secondsIdle = Math.max(0, (now - window.lastMeaningfulAt) / 1000L);

        Map<String, Integer> breakdown = new LinkedHashMap<>();
        breakdown.put("Building", Math.max(0, placed - redstonePlaced - cropsPlanted));
        breakdown.put("Mining", undergroundBreaks + ores);
        breakdown.put("Strip mining", isLikelyStripMining(undergroundBreaks, stoneBreaks, yRange, torches)
                ? undergroundBreaks : 0);
        breakdown.put("Farming", farm);
        breakdown.put("Exploration", chunkCount);
        breakdown.put("Crafting / smelting", craft);
        breakdown.put("Storage / inventory", inventory);
        breakdown.put("Redstone", redstonePlaced);
        breakdown.put("PvE", pve);
        breakdown.put("PvP", pvp);
        breakdown.put("Idle", secondsIdle >= idleMinutes() * 60L ? 1 : 0);

        Map<String, Integer> metrics = new LinkedHashMap<>();
        metrics.put("events", events.size());
        metrics.put("undergroundBreaks", undergroundBreaks);
        metrics.put("ores", ores);
        metrics.put("torches", torches);
        metrics.put("cropsHarvested", cropsHarvested);
        metrics.put("cropsPlanted", cropsPlanted);
        metrics.put("sensitiveBreaks", sensitiveBreaks);
        metrics.put("redstonePlaced", redstonePlaced);
        metrics.put("inventoryEvents", inventory);
        metrics.put("craftingEvents", craft);
        metrics.put("environmentalEvents", counts.getOrDefault(Kind.BUCKET, 0) + counts.getOrDefault(Kind.FIRE, 0));

        String current;
        String headline;
        List<String> details = new ArrayList<>();
        boolean activePvp = events.stream().anyMatch(e -> e.kind() == Kind.PVP && now - e.time() <= 30_000L);

        if (activePvp) {
            current = "PvP combat";
            headline = pvp + " recorded PvP event" + plural(pvp);
            details.add("Use Combat Center for the linked incident timeline.");
        } else if (secondsIdle >= idleMinutes() * 60L) {
            current = "Idle / AFK";
            headline = "No meaningful activity for " + formatMinutes(secondsIdle / 60);
        } else if (broken >= 20 && broken > Math.max(placed * 3, 20) && sensitiveBreaks >= 2) {
            current = "Possible demolition";
            headline = broken + " blocks broken and " + placed + " placed";
            details.add(sensitiveBreaks + " sensitive block break" + plural(sensitiveBreaks));
        } else if (redstonePlaced >= 5 && redstonePlaced >= Math.max(3, placed / 3)) {
            current = "Redstone";
            headline = redstonePlaced + " redstone component placement" + plural(redstonePlaced);
            addTopMaterials(details, "Components", placedMaterials, 4, ActivityTracker::isRedstone);
        } else if (isLikelyStripMining(undergroundBreaks, stoneBreaks, yRange, torches)) {
            current = "Likely strip mining";
            headline = undergroundBreaks + " underground blocks broken";
            details.add("Y range: " + minY + " to " + maxY);
            details.add("Ores found: " + ores + "; torches placed: " + torches);
        } else if (farm >= 8) {
            current = "Farming";
            headline = cropsHarvested + " harvested / " + cropsPlanted + " replanted";
            int bred = counts.getOrDefault(Kind.BREED, 0);
            if (bred > 0) details.add("Animals bred: " + bred);
        } else if (undergroundBreaks >= 20 || ores >= 3) {
            current = "Mining";
            headline = broken + " blocks broken; " + ores + " ore block" + plural(ores);
            if (minY != Integer.MAX_VALUE) details.add("Underground Y range: " + minY + " to " + maxY);
        } else if (placed >= 8 && placed > Math.max(4, broken * 1.15)) {
            current = "Building";
            headline = placed + " placed / " + broken + " broken";
            addTopMaterials(details, "Primary materials", placedMaterials, 4, material -> true);
            details.add("Activity spread across " + chunks.size() + " chunk" + plural(chunks.size()));
        } else if (craft >= 4 && craft >= inventory / 2) {
            current = "Crafting / smelting";
            headline = craft + " crafting or furnace action" + plural(craft);
        } else if (inventory >= 10) {
            current = "Storage / inventory";
            headline = inventory + " inventory interaction" + plural(inventory);
        } else if (pve >= 4) {
            current = "PvE combat";
            headline = pve + " PvE event" + plural(pve);
        } else if (pvp >= 2) {
            current = "PvP combat";
            headline = pvp + " PvP event" + plural(pvp);
            details.add("Use Combat Center for incident evidence.");
        } else if (chunkCount >= 8 && placed + broken + inventory + craft + farm + pvp + pve < 15) {
            current = "Exploring";
            headline = chunkCount + " chunk transition" + plural(chunkCount);
            details.add("Movement is summarized in memory and is not added to the player log.");
        } else if (!events.isEmpty()) {
            current = "General activity";
            headline = events.size() + " recent event" + plural(events.size());
        } else {
            current = online ? "Online" : "Offline";
            headline = online ? "No classified activity yet" : "No activity in the current window";
        }

        if (details.size() < 3 && placed + broken > 0) details.add("Blocks: " + placed + " placed, " + broken + " broken");
        if (details.size() < 3 && pvp + pve > 0) details.add("Combat: " + pvp + " PvP, " + pve + " PvE events");
        if (details.size() < 3 && chunkCount > 0) details.add("Visited " + chunks.size() + " unique chunks");

        int duration = events.isEmpty() ? 0 : Math.max(1,
                (int) Duration.ofMillis(Math.max(0, lastEvent - firstEvent)).toMinutes());
        return new PlayerActivity(window.name, window.uuid.toString(), online, current, duration,
                headline, details, breakdown, metrics, placed, broken, pvp, pve, chunks.size(),
                Math.max(lastEvent, window.lastMeaningfulAt));
    }

    private static String categoryFor(ActivityEvent event) {
        return switch (event.kind()) {
            case BLOCK_PLACE -> isCrop(event.material()) ? "Farming"
                    : isRedstone(event.material()) ? "Redstone" : "Building";
            case BLOCK_BREAK -> isCrop(event.material()) ? "Farming"
                    : (event.y() <= 20 || isOre(event.material()) || isStoneLike(event.material())) ? "Mining" : "Block work";
            case INVENTORY, ITEM -> "Storage / inventory";
            case CRAFT, FURNACE -> "Crafting / smelting";
            case FARM, BREED -> "Farming";
            case PVP -> "PvP combat";
            case PVE -> "PvE combat";
            case CHUNK -> "Exploration";
            case BUCKET, FIRE -> "Environmental activity";
            case DEATH -> "Death";
            case LOGIN -> "Login";
            case LOGOUT -> "Logout";
        };
    }

    private static String feedTitle(String player, String category, int count) {
        return switch (category) {
            case "Building" -> player + " was building";
            case "Redstone" -> player + " worked on redstone";
            case "Mining" -> player + " was mining";
            case "Farming" -> player + " worked on farming";
            case "Storage / inventory" -> player + " managed items or storage";
            case "Crafting / smelting" -> player + " crafted or smelted items";
            case "PvP combat" -> player + " participated in PvP";
            case "PvE combat" -> player + " fought mobs";
            case "Exploration" -> player + " moved through new chunks";
            case "Environmental activity" -> player + " used fire or buckets";
            case "Death" -> player + " died";
            case "Login" -> player + " joined the server";
            case "Logout" -> player + " left the server";
            default -> player + " had " + count + " " + category.toLowerCase(Locale.ROOT) + " events";
        };
    }

    private static String topMaterialText(Map<String, Integer> counts, int limit) {
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(limit)
                .map(e -> e.getKey() + " (" + e.getValue() + ")")
                .reduce((a, b) -> a + ", " + b)
                .map(value -> "materials: " + value)
                .orElse("");
    }

    private boolean isLikelyStripMining(int undergroundBreaks, int stoneBreaks, int yRange, int torches) {
        return undergroundBreaks >= 40 && stoneBreaks >= undergroundBreaks * 0.65 && yRange <= 8 && torches >= 2;
    }

    private void prune(PlayerWindow window, long now) {
        long cutoff = now - windowMinutes() * 60_000L;
        while (!window.events.isEmpty() && window.events.peekFirst().time() < cutoff) window.events.removeFirst();
    }

    private void enforceEventCap(PlayerWindow window) {
        int cap = Math.max(500, plugin.getConfig().getInt("activity.max-events-per-player", 5000));
        while (window.events.size() > cap) window.events.removeFirst();
    }

    private boolean enabled() { return plugin.getConfig().getBoolean("activity.enabled", true); }
    private int windowMinutes() { return Math.max(15, plugin.getConfig().getInt("activity.window-minutes", 60)); }
    private int idleMinutes() { return Math.max(2, plugin.getConfig().getInt("activity.idle-minutes", 10)); }
    private int activeAreaLimit() { return Math.max(3, plugin.getConfig().getInt("activity.active-areas-limit", 12)); }
    private int activeAreaMinimumScore() { return Math.max(2, plugin.getConfig().getInt("activity.active-area-min-score", 6)); }
    private int feedBucketMinutes() { return Math.max(1, plugin.getConfig().getInt("dashboard.feed-bucket-minutes", 5)); }
    private int feedLimit() { return Math.max(10, plugin.getConfig().getInt("dashboard.feed-limit", 60)); }
    private int profileFeedLimit() { return Math.max(5, plugin.getConfig().getInt("dashboard.profile-feed-limit", 20)); }
    private int alertLimit() { return Math.max(5, plugin.getConfig().getInt("dashboard.alert-limit", 40)); }

    private interface MaterialFilter { boolean test(String material); }

    private static void addTopMaterials(List<String> details, String label, Map<String, Integer> counts,
                                        int limit, MaterialFilter filter) {
        List<Map.Entry<String, Integer>> top = counts.entrySet().stream()
                .filter(e -> !e.getKey().isBlank() && filter.test(e.getKey()))
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(limit)
                .toList();
        if (!top.isEmpty()) {
            details.add(label + ": " + top.stream().map(e -> e.getKey() + " (" + e.getValue() + ")")
                    .reduce((a, b) -> a + ", " + b).orElse(""));
        }
    }

    private static boolean isCrop(String material) {
        String m = material == null ? "" : material.toUpperCase(Locale.ROOT);
        return m.equals("WHEAT") || m.equals("CARROTS") || m.equals("POTATOES") || m.equals("BEETROOTS")
                || m.equals("NETHER_WART") || m.equals("COCOA") || m.contains("STEM")
                || m.equals("SUGAR_CANE") || m.equals("BAMBOO") || m.equals("CACTUS")
                || m.equals("SWEET_BERRY_BUSH") || m.equals("PITCHER_CROP") || m.equals("TORCHFLOWER_CROP");
    }

    private static boolean isOre(String material) {
        String m = material == null ? "" : material.toUpperCase(Locale.ROOT);
        return m.endsWith("_ORE") || m.equals("ANCIENT_DEBRIS");
    }

    private static boolean isTorch(String material) {
        return material != null && material.toUpperCase(Locale.ROOT).contains("TORCH");
    }

    private static boolean isStoneLike(String material) {
        String m = material == null ? "" : material.toUpperCase(Locale.ROOT);
        return m.equals("STONE") || m.equals("DEEPSLATE") || m.equals("TUFF") || m.equals("NETHERRACK")
                || m.equals("BLACKSTONE") || m.equals("BASALT") || m.equals("GRANITE")
                || m.equals("DIORITE") || m.equals("ANDESITE") || m.equals("CALCITE");
    }

    private static boolean isSensitive(String material) {
        String m = material == null ? "" : material.toUpperCase(Locale.ROOT);
        return m.contains("CHEST") || m.contains("BARREL") || m.contains("SHULKER")
                || m.contains("DOOR") || m.contains("BED") || m.contains("GLASS")
                || isCrop(m) || isRedstone(m);
    }

    private static boolean isRedstone(String material) {
        String m = material == null ? "" : material.toUpperCase(Locale.ROOT);
        return m.contains("REDSTONE") || m.contains("PISTON") || m.contains("OBSERVER")
                || m.contains("REPEATER") || m.contains("COMPARATOR") || m.contains("DISPENSER")
                || m.contains("DROPPER") || m.contains("HOPPER") || m.contains("LEVER")
                || m.contains("BUTTON") || m.contains("PRESSURE_PLATE") || m.contains("POWERED_RAIL");
    }

    private static int severityRank(String severity) {
        return switch (severity) {
            case "HIGH" -> 3;
            case "MEDIUM" -> 2;
            default -> 1;
        };
    }

    private static String plural(int value) { return value == 1 ? "" : "s"; }

    private static String formatMinutes(long minutes) {
        if (minutes < 60) return minutes + "m";
        return (minutes / 60) + "h " + (minutes % 60) + "m";
    }
}
