package com.playerlogger;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lightweight PvP incident grouping and compact environmental attribution.
 * PvE remains in the normal player logs and does not create incident files.
 */
public final class CombatManager {
    private static final DateTimeFormatter ID_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
            .withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter HIT_TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault());

    public record CombatSummary(
            String id,
            String type,
            long startedEpochMillis,
            long endedEpochMillis,
            String started,
            String ended,
            String location,
            String probableInitiator,
            String initiatorConfidence,
            String initiatorReason,
            List<String> participants,
            String result,
            List<String> flags,
            String status,
            Map<String, String> health
    ) {}

    public record CombatDetail(CombatSummary summary, List<String> timeline) {}

    private static final class MovementState {
        private Location previous;
        private Location current;

        private void update(Location location) {
            if (location == null || location.getWorld() == null) return;
            previous = current;
            current = location.clone();
        }
    }

    private record HazardAction(long time, UUID playerId, String playerName, Material material, Location location) {}

    private record RecentKill(long time, UUID attackerId, String attackerName, UUID victimId,
                              String victimName, Location location) {}

    private static final class RespawnContext {
        private long diedAt;
        private Location deathLocation;
        private final Set<UUID> previousOpponents = new HashSet<>();
        private long respawnedAt;
        private Location respawnLocation;
        private boolean leftRespawnArea;
        private boolean returnedToPreviousFightArea;
    }

    private static final class Incident {
        private final String id;
        private final long startedAt;
        private final Location location;
        private final Map<UUID, String> participants = Collections.synchronizedMap(new LinkedHashMap<>());
        private final List<String> timeline = Collections.synchronizedList(new ArrayList<>());
        private final Set<String> flags = Collections.synchronizedSet(new LinkedHashSet<>());
        private final Map<UUID, Integer> hitsByPlayer = new ConcurrentHashMap<>();
        private final Map<String, String> currentHealth = Collections.synchronizedMap(new LinkedHashMap<>());
        private final Map<String, Integer> remainingDeathDrops = new HashMap<>();
        private final Map<UUID, Map<String, Integer>> lootedByPlayer = new HashMap<>();

        private long lastHitAt;
        private long endedAt;
        private long lootWindowUntil;
        private UUID firstAttacker;
        private String firstAttackerName = "Unknown";
        private UUID firstVictim;
        private String firstVictimName = "Unknown";
        private boolean firstVictimRetaliated;
        private int firstAttackerHitsBeforeRetaliation;
        private long retaliationAt;
        private String initiatorConfidence = "Low";
        private String initiatorReason = "First recorded damaging player; not enough evidence yet";
        private String result = "In progress";
        private String status = "LIVE";
        private Location deathLocation;
        private String deathVictim = "";
        private boolean finalized;

        private Incident(String id, long now, Location location) {
            this.id = id;
            this.startedAt = now;
            this.lastHitAt = now;
            this.location = location == null ? null : location.clone();
        }
    }

    private final PlayerActionLogger plugin;
    private final ActivityTracker activityTracker;
    private final Map<UUID, Incident> activeByPlayer = new ConcurrentHashMap<>();
    private final Set<Incident> liveIncidents = ConcurrentHashMap.newKeySet();
    private final Set<Incident> pendingLootIncidents = ConcurrentHashMap.newKeySet();
    private final Deque<CombatSummary> recentSummaries = new ArrayDeque<>();
    private final Map<String, CombatDetail> recentDetails = new ConcurrentHashMap<>();
    private final Deque<HazardAction> hazards = new ArrayDeque<>();
    private final Map<UUID, Long> joinedAt = new ConcurrentHashMap<>();
    private final Map<UUID, Long> teleportedAt = new ConcurrentHashMap<>();
    private final Map<UUID, RespawnContext> respawnContexts = new ConcurrentHashMap<>();
    private final Deque<RecentKill> recentKills = new ArrayDeque<>();
    private final Map<UUID, MovementState> movement = new ConcurrentHashMap<>();
    private final AtomicInteger idSequence = new AtomicInteger();

    public CombatManager(PlayerActionLogger plugin, ActivityTracker activityTracker) {
        this.plugin = plugin;
        this.activityTracker = activityTracker;
        loadStoredIncidents();
    }

    public void initializeOnlinePlayer(Player player) {
        if (player == null) return;
        movement.computeIfAbsent(player.getUniqueId(), ignored -> new MovementState()).update(player.getLocation());
    }

    public void onJoin(Player player) {
        joinedAt.put(player.getUniqueId(), System.currentTimeMillis());
        movement.computeIfAbsent(player.getUniqueId(), ignored -> new MovementState()).update(player.getLocation());
    }

    public void onTeleport(Player player) {
        teleportedAt.put(player.getUniqueId(), System.currentTimeMillis());
    }

    public void onRespawn(Player player, Location location) {
        if (player == null) return;
        RespawnContext context = respawnContexts.computeIfAbsent(player.getUniqueId(), ignored -> new RespawnContext());
        context.respawnedAt = System.currentTimeMillis();
        context.respawnLocation = location == null ? player.getLocation().clone() : location.clone();
        context.leftRespawnArea = false;
        context.returnedToPreviousFightArea = false;
    }

    public void onMove(Player player, Location from, Location to) {
        if (player == null || to == null) return;
        if (from != null && from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ() && from.getWorld() == to.getWorld()) return;
        movement.computeIfAbsent(player.getUniqueId(), ignored -> new MovementState()).update(to);

        RespawnContext context = respawnContexts.get(player.getUniqueId());
        if (context == null || context.respawnedAt <= 0 || to.getWorld() == null) return;
        long age = System.currentTimeMillis() - context.respawnedAt;
        if (age > respawnTrackingSeconds() * 1000L) return;

        if (!context.leftRespawnArea && context.respawnLocation != null
                && !sameWorldAndWithin(to, context.respawnLocation, respawnRadius())) {
            context.leftRespawnArea = true;
        }
        if (context.leftRespawnArea && context.deathLocation != null
                && sameWorldAndWithin(to, context.deathLocation, returnToFightRadius())) {
            context.returnedToPreviousFightArea = true;
        }
    }

    public void recordHazard(Player player, Material material, Location location) {
        if (player == null || material == null || location == null) return;
        synchronized (hazards) {
            hazards.addLast(new HazardAction(System.currentTimeMillis(), player.getUniqueId(), player.getName(), material, location.clone()));
            pruneHazards(System.currentTimeMillis());
        }
    }

    public void handlePvpHit(Player attacker, Player victim, String cause, String weapon,
                             double finalDamage, double victimHealthAfter) {
        if (!enabled() || attacker == null || victim == null || attacker.equals(victim)) return;
        long now = System.currentTimeMillis();
        Incident incident = chooseIncident(attacker, victim, now);
        boolean created = incident.timeline.isEmpty();

        addParticipant(incident, attacker);
        addParticipant(incident, victim);
        if (created) initializeIncident(incident, attacker, victim, now);

        incident.lastHitAt = now;
        incident.hitsByPlayer.merge(attacker.getUniqueId(), 1, Integer::sum);
        if (attacker.getUniqueId().equals(incident.firstAttacker) && !incident.firstVictimRetaliated) {
            incident.firstAttackerHitsBeforeRetaliation++;
        }
        incident.currentHealth.put(attacker.getName(), healthText(attacker.getHealth(), maxHealth(attacker)));
        incident.currentHealth.put(victim.getName(), healthText(victimHealthAfter, maxHealth(victim)));

        if (attacker.getUniqueId().equals(incident.firstVictim)
                && victim.getUniqueId().equals(incident.firstAttacker)) {
            incident.firstVictimRetaliated = true;
            incident.retaliationAt = now;
        }

        String compact = String.format(Locale.ROOT,
                "HIT %s -> %s cause:%s weapon:%s damage:%.1f health:%s at %s",
                attacker.getName(), victim.getName(), cause, weapon, finalDamage,
                healthText(victimHealthAfter, maxHealth(victim)), LogUtils.loc(victim.getLocation()));
        incident.timeline.add(timelineLine(now, compact));

        String playerLine = "PVP_HIT incident:" + incident.id + " " + compact;
        plugin.log(attacker, playerLine);
        plugin.log(victim, playerLine);
        activityTracker.record(attacker, ActivityTracker.Kind.PVP);
        activityTracker.record(victim, ActivityTracker.Kind.PVP);
    }

    public void handleEnvironmentalDamage(Player victim, String cause, double finalDamage, double victimHealthAfter) {
        if (victim == null) return;
        HazardAction source = findRelevantHazard(victim, cause);
        if (source == null || source.playerId().equals(victim.getUniqueId())) {
            String sourceLabel = source == null ? "unknown" : "self";
            String compact = String.format(Locale.ROOT,
                    "ENV_DAMAGE source:%s victim:%s cause:%s damage:%.1f health:%s at %s",
                    sourceLabel, victim.getName(), cause, finalDamage,
                    healthText(victimHealthAfter, maxHealth(victim)), LogUtils.loc(victim.getLocation()));
            plugin.log(victim, compact);

            // Environmental damage during an existing PvP incident belongs in that incident's
            // evidence timeline even when no player source can be established.
            Incident active = activeByPlayer.get(victim.getUniqueId());
            if (active != null) {
                long now = System.currentTimeMillis();
                active.lastHitAt = now;
                active.currentHealth.put(victim.getName(), healthText(victimHealthAfter, maxHealth(victim)));
                active.timeline.add(timelineLine(now, compact));
            }
            return;
        }

        Player sourcePlayer = plugin.getServer().getPlayer(source.playerId());
        long now = System.currentTimeMillis();
        Incident incident;
        if (sourcePlayer != null) {
            incident = chooseIncident(sourcePlayer, victim, now);
            boolean created = incident.timeline.isEmpty();
            addParticipant(incident, sourcePlayer);
            addParticipant(incident, victim);
            if (created) initializeIncident(incident, sourcePlayer, victim, now);
        } else {
            incident = activeByPlayer.get(victim.getUniqueId());
            if (incident == null) {
                incident = new Incident(createId(now, source.playerName(), victim.getName()), now, victim.getLocation());
                liveIncidents.add(incident);
                activeByPlayer.put(victim.getUniqueId(), incident);
                incident.participants.put(source.playerId(), source.playerName());
                addParticipant(incident, victim);
                incident.firstAttacker = source.playerId();
                incident.firstAttackerName = source.playerName();
                incident.firstVictim = victim.getUniqueId();
                incident.firstVictimName = victim.getName();
                incident.timeline.add(timelineLine(now, "INCIDENT_START environmental source:" + source.material()));
                incident.timeline.add(timelineLine(now, statusLine(victim)));
            }
        }

        incident.lastHitAt = now;
        incident.currentHealth.put(victim.getName(), healthText(victimHealthAfter, maxHealth(victim)));
        String movementNote = movementRelativeToHazard(victim, source.location());
        String compact = String.format(Locale.ROOT,
                "ENV_HIT possible-source:%s material:%s victim:%s cause:%s damage:%.1f health:%s movement:%s at %s",
                source.playerName(), source.material(), victim.getName(), cause, finalDamage,
                healthText(victimHealthAfter, maxHealth(victim)), movementNote, LogUtils.loc(victim.getLocation()));
        incident.timeline.add(timelineLine(now, compact));
        incident.flags.add("Environmental damage linked to a recent player hazard action; context requires review");

        String playerLine = "PVP_ENV incident:" + incident.id + " " + compact;
        plugin.logPlayer(source.playerId(), source.playerName(), playerLine);
        plugin.log(victim, playerLine);
        activityTracker.record(victim, ActivityTracker.Kind.PVP);
        if (sourcePlayer != null) activityTracker.record(sourcePlayer, ActivityTracker.Kind.PVP);
    }

    public void onPlayerDeath(Player victim, Player killer, String deathMessage, List<ItemStack> drops) {
        Incident incident = activeByPlayer.get(victim.getUniqueId());
        if (incident == null) return;
        long now = System.currentTimeMillis();
        incident.endedAt = now;
        incident.status = "LOOT WINDOW";
        incident.result = victim.getName() + " died";
        incident.deathVictim = victim.getName();
        incident.deathLocation = victim.getLocation().clone();
        incident.timeline.add(timelineLine(now, "DEATH " + victim.getName() + ": " + LogUtils.cleanOneLine(deathMessage)
                + " at " + LogUtils.loc(victim.getLocation())));
        incident.currentHealth.put(victim.getName(), "0.0/" + oneDecimal(maxHealth(victim)));

        RespawnContext respawnContext = respawnContexts.computeIfAbsent(victim.getUniqueId(), ignored -> new RespawnContext());
        respawnContext.diedAt = now;
        respawnContext.deathLocation = victim.getLocation().clone();
        respawnContext.previousOpponents.clear();
        synchronized (incident.participants) {
            for (UUID participantId : incident.participants.keySet()) {
                if (!participantId.equals(victim.getUniqueId())) respawnContext.previousOpponents.add(participantId);
            }
        }

        UUID creditedKiller = killer == null ? incident.firstAttacker : killer.getUniqueId();
        String creditedKillerName = killer == null ? incident.firstAttackerName : killer.getName();
        if (creditedKiller != null && !creditedKiller.equals(victim.getUniqueId())) {
            int previousKills = recentKillCount(creditedKiller, victim.getUniqueId(), now);
            if (previousKills > 0) {
                incident.flags.add("Repeated kill by " + creditedKillerName + " against " + victim.getName()
                        + " within the recent-kill review window");
            }
            synchronized (recentKills) {
                recentKills.addLast(new RecentKill(now, creditedKiller, creditedKillerName,
                        victim.getUniqueId(), victim.getName(), victim.getLocation().clone()));
                pruneRecentKills(now);
            }
        }

        if (drops != null) {
            for (ItemStack item : drops) {
                if (item == null || item.getType() == Material.AIR) continue;
                incident.remainingDeathDrops.merge(item.getType().name(), item.getAmount(), Integer::sum);
            }
        }

        removeFromActive(incident);
        liveIncidents.remove(incident);
        int seconds = Math.max(10, plugin.getConfig().getInt("combat.post-death-loot-seconds", 45));
        incident.lootWindowUntil = now + seconds * 1000L;
        pendingLootIncidents.add(incident);
    }

    public void onItemPickup(Player player, ItemStack item, Location location) {
        if (player == null || item == null || location == null) return;
        long now = System.currentTimeMillis();
        for (Incident incident : new ArrayList<>(pendingLootIncidents)) {
            if (now > incident.lootWindowUntil || incident.deathLocation == null) continue;
            if (!sameWorldAndWithin(location, incident.deathLocation, lootRadius())) continue;
            String type = item.getType().name();
            int remaining = incident.remainingDeathDrops.getOrDefault(type, 0);
            if (remaining <= 0) continue;
            int matched = Math.min(remaining, item.getAmount());
            incident.remainingDeathDrops.put(type, remaining - matched);

            if (player.getName().equalsIgnoreCase(incident.deathVictim)) {
                incident.timeline.add(timelineLine(now, "OWNER_RECOVERY " + player.getName() + " recovered "
                        + matched + "x" + type + " near their death location"));
                continue;
            }

            incident.lootedByPlayer.computeIfAbsent(player.getUniqueId(), ignored -> new HashMap<>())
                    .merge(type, matched, Integer::sum);
            incident.flags.add("Death-loot pickup by another player recorded");
            incident.timeline.add(timelineLine(now, "POST_DEATH_PICKUP " + player.getName() + " picked up "
                    + matched + "x" + type + " near " + incident.deathVictim + "'s death location"));
        }
    }

    public void onItemDrop(Player player, ItemStack item, Location location) {
        if (player == null || item == null || location == null) return;
        long now = System.currentTimeMillis();
        for (Incident incident : new ArrayList<>(pendingLootIncidents)) {
            if (now > incident.lootWindowUntil || incident.deathLocation == null) continue;
            if (!sameWorldAndWithin(location, incident.deathLocation, lootRadius())) continue;
            Map<String, Integer> lootedItems = incident.lootedByPlayer.get(player.getUniqueId());
            if (lootedItems == null) continue;
            String type = item.getType().name();
            int looted = lootedItems.getOrDefault(type, 0);
            if (looted <= 0) continue;
            int returned = Math.min(looted, item.getAmount());
            lootedItems.put(type, looted - returned);
            incident.flags.add("Possible item return recorded");
            incident.timeline.add(timelineLine(now, "POSSIBLE_RETURN " + player.getName() + " dropped "
                    + returned + "x" + type + " near the death location"));
        }
    }

    public void onQuit(Player player) {
        Incident incident = activeByPlayer.get(player.getUniqueId());
        if (incident == null) return;
        long now = System.currentTimeMillis();
        incident.flags.add("Possible combat logout");
        incident.result = player.getName() + " disconnected during active combat";
        incident.timeline.add(timelineLine(now, "COMBAT_LOGOUT " + player.getName() + " health:"
                + healthText(player.getHealth(), maxHealth(player))));
        incident.endedAt = now;
        finalizeIncident(incident);
    }

    public void tick() {
        long now = System.currentTimeMillis();
        long timeout = Math.max(8, plugin.getConfig().getLong("combat.incident-timeout-seconds", 20)) * 1000L;
        for (Incident incident : new ArrayList<>(liveIncidents)) {
            if (now - incident.lastHitAt >= timeout) {
                incident.endedAt = now;
                incident.result = "Combat ended after inactivity";
                incident.timeline.add(timelineLine(now, "INCIDENT_END inactivity timeout"));
                finalizeIncident(incident);
            }
        }
        for (Incident incident : new ArrayList<>(pendingLootIncidents)) {
            if (now >= incident.lootWindowUntil) finalizeIncident(incident);
        }
        synchronized (hazards) {
            pruneHazards(now);
        }
    }

    public void shutdown() {
        long now = System.currentTimeMillis();
        Set<Incident> all = new HashSet<>(liveIncidents);
        all.addAll(pendingLootIncidents);
        for (Incident incident : all) {
            if (incident.endedAt == 0) incident.endedAt = now;
            if ("In progress".equals(incident.result)) incident.result = "Server/plugin shutdown during incident";
            finalizeIncident(incident);
        }
    }

    public List<CombatSummary> summaries() {
        List<CombatSummary> result = new ArrayList<>();
        for (Incident incident : liveIncidents) result.add(toSummary(incident));
        for (Incident incident : pendingLootIncidents) result.add(toSummary(incident));
        synchronized (recentSummaries) {
            result.addAll(recentSummaries);
        }
        result.sort(Comparator.comparingLong(CombatSummary::startedEpochMillis).reversed());
        return result.stream().distinct().limit(100).toList();
    }

    public CombatDetail detail(String id) {
        if (id == null || id.isBlank()) return null;
        for (Incident incident : liveIncidents) if (incident.id.equals(id)) return toDetail(incident);
        for (Incident incident : pendingLootIncidents) if (incident.id.equals(id)) return toDetail(incident);
        CombatDetail cached = recentDetails.get(id);
        if (cached != null) return cached;
        return readStoredDetail(id);
    }

    private Incident chooseIncident(Player attacker, Player victim, long now) {
        Incident a = activeByPlayer.get(attacker.getUniqueId());
        Incident v = activeByPlayer.get(victim.getUniqueId());
        if (a != null && v != null && a != v) {
            Incident primary = a.startedAt <= v.startedAt ? a : v;
            Incident secondary = primary == a ? v : a;
            mergeInto(primary, secondary, now);
            return primary;
        }
        if (a != null) return a;
        if (v != null) return v;
        Incident created = new Incident(createId(now, attacker.getName(), victim.getName()), now, victim.getLocation());
        liveIncidents.add(created);
        return created;
    }

    private void initializeIncident(Incident incident, Player attacker, Player victim, long now) {
        incident.firstAttacker = attacker.getUniqueId();
        incident.firstAttackerName = attacker.getName();
        incident.firstVictim = victim.getUniqueId();
        incident.firstVictimName = victim.getName();
        incident.timeline.add(timelineLine(now, "INCIDENT_START PvP at " + LogUtils.loc(victim.getLocation())));
        incident.timeline.add(timelineLine(now, statusLine(attacker)));
        incident.timeline.add(timelineLine(now, statusLine(victim)));
        incident.currentHealth.put(attacker.getName(), healthText(attacker.getHealth(), maxHealth(attacker)));
        incident.currentHealth.put(victim.getName(), healthText(victim.getHealth(), maxHealth(victim)));
        addContextFlags(incident, attacker, victim, now);
        plugin.log(attacker, "COMBAT_JOIN incident:" + incident.id + " opponent:" + victim.getName());
        plugin.log(victim, "COMBAT_JOIN incident:" + incident.id + " opponent:" + attacker.getName());
    }

    private void addContextFlags(Incident incident, Player attacker, Player victim, long now) {
        long joined = joinedAt.getOrDefault(victim.getUniqueId(), 0L);
        if (joined > 0 && now - joined <= 10_000L) incident.flags.add("Victim was attacked shortly after login");

        long teleported = teleportedAt.getOrDefault(victim.getUniqueId(), 0L);
        if (teleported > 0 && now - teleported <= 5_000L) incident.flags.add("Victim was attacked shortly after teleporting");

        RespawnContext context = respawnContexts.get(victim.getUniqueId());
        if (context != null && context.respawnedAt > 0
                && now - context.respawnedAt <= spawnReviewSeconds() * 1000L) {
            boolean samePriorOpponent = context.previousOpponents.contains(attacker.getUniqueId());
            boolean nearRespawn = context.respawnLocation != null
                    && sameWorldAndWithin(victim.getLocation(), context.respawnLocation, respawnRadius());

            if (context.returnedToPreviousFightArea) {
                incident.flags.add("Respawn context: victim left respawn and returned to the previous fight area; "
                        + "do not treat this as clear spawn killing without reviewing the timeline");
            } else if (nearRespawn && !context.leftRespawnArea) {
                String repeat = samePriorOpponent ? " by a player from the previous incident" : "";
                incident.flags.add("Possible spawn attack" + repeat
                        + ": victim was still near the respawn point shortly after respawning");
            } else if (context.leftRespawnArea) {
                incident.flags.add("Respawn context: victim had left the respawn area before this attack");
            }
        }

        if (recentKillCount(attacker.getUniqueId(), victim.getUniqueId(), now) > 0) {
            incident.flags.add("Repeat PvP encounter: the same attacker recently killed this victim");
        }

        if (victim.isSleeping()) incident.flags.add("Victim was sleeping when the first hit was recorded");
        InventoryType openType = victim.getOpenInventory().getTopInventory().getType();
        if (openType != InventoryType.CRAFTING && openType != InventoryType.PLAYER) {
            incident.flags.add("Victim had an inventory open when the first hit was recorded: " + openType);
        }
        long inactiveSeconds = activityTracker.secondsSinceMeaningful(victim.getUniqueId());
        if (inactiveSeconds >= 120) {
            incident.flags.add("Victim had no meaningful logged activity for " + inactiveSeconds
                    + " seconds before the first hit");
        }
    }

    private void mergeInto(Incident primary, Incident secondary, long now) {
        if (primary == secondary || secondary.finalized) return;
        primary.participants.putAll(secondary.participants);
        primary.timeline.add(timelineLine(now, "INCIDENT_MERGE linked with " + secondary.id));
        primary.timeline.addAll(secondary.timeline);
        primary.flags.addAll(secondary.flags);
        secondary.hitsByPlayer.forEach((playerId, count) -> primary.hitsByPlayer.merge(playerId, count, Integer::sum));
        primary.currentHealth.putAll(secondary.currentHealth);
        for (UUID id : secondary.participants.keySet()) activeByPlayer.put(id, primary);
        liveIncidents.remove(secondary);
        secondary.finalized = true;
    }

    private void addParticipant(Incident incident, Player player) {
        incident.participants.put(player.getUniqueId(), player.getName());
        activeByPlayer.put(player.getUniqueId(), incident);
        incident.currentHealth.put(player.getName(), healthText(player.getHealth(), maxHealth(player)));
    }

    private void removeFromActive(Incident incident) {
        List<UUID> ids;
        synchronized (incident.participants) { ids = new ArrayList<>(incident.participants.keySet()); }
        for (UUID id : ids) activeByPlayer.remove(id, incident);
    }

    private void finalizeIncident(Incident incident) {
        if (incident.finalized) return;
        incident.finalized = true;
        if (incident.endedAt == 0) incident.endedAt = System.currentTimeMillis();
        incident.status = "COMPLETE";

        analyzeInitiator(incident);
        if (!incident.firstVictimRetaliated && incident.hitsByPlayer.getOrDefault(incident.firstAttacker, 0) >= 2) {
            incident.flags.add("Possible unprovoked attack: first recorded victim did not retaliate; "
                    + "voice-chat, signs, and outside warnings are not known");
        }
        appendLootSummary(incident, incident.endedAt);

        List<String> participantNames;
        synchronized (incident.participants) { participantNames = new ArrayList<>(incident.participants.values()); }
        for (String name : participantNames) {
            Player online = plugin.getServer().getPlayerExact(name);
            if (online != null) incident.currentHealth.put(name, healthText(online.getHealth(), maxHealth(online)));
        }
        incident.timeline.add(timelineLine(incident.endedAt, "END_STATUS " + formatStatusMap(incident.currentHealth)));

        removeFromActive(incident);
        liveIncidents.remove(incident);
        pendingLootIncidents.remove(incident);

        CombatDetail detail = toDetail(incident);
        CombatSummary summary = detail.summary();
        recentDetails.put(incident.id, detail);
        synchronized (recentSummaries) {
            recentSummaries.addFirst(summary);
            while (recentSummaries.size() > 100) {
                CombatSummary removed = recentSummaries.removeLast();
                recentDetails.remove(removed.id());
            }
        }
        plugin.writeCombatFile(incident.id, renderFile(detail));
    }

    private CombatSummary toSummary(Incident incident) {
        analyzeInitiator(incident);
        long end = incident.endedAt;
        String ended = end == 0 ? "" : DISPLAY_TIME.format(Instant.ofEpochMilli(end));
        List<String> participants;
        List<String> flags;
        Map<String, String> health;
        synchronized (incident.participants) { participants = new ArrayList<>(incident.participants.values()); }
        synchronized (incident.flags) { flags = new ArrayList<>(incident.flags); }
        synchronized (incident.currentHealth) { health = new LinkedHashMap<>(incident.currentHealth); }
        return new CombatSummary(incident.id, "PvP", incident.startedAt, end,
                DISPLAY_TIME.format(Instant.ofEpochMilli(incident.startedAt)), ended,
                LogUtils.loc(incident.location), incident.firstAttackerName,
                incident.initiatorConfidence, incident.initiatorReason,
                participants, incident.result, flags, incident.status, health);
    }

    private CombatDetail toDetail(Incident incident) {
        List<String> timeline;
        synchronized (incident.timeline) { timeline = new ArrayList<>(incident.timeline); }
        return new CombatDetail(toSummary(incident), timeline);
    }

    private String renderFile(CombatDetail detail) {
        CombatSummary s = detail.summary();
        StringBuilder out = new StringBuilder();
        out.append("Combat Incident: ").append(s.id()).append('\n');
        out.append("Type: ").append(s.type()).append('\n');
        out.append("Started: ").append(s.started()).append('\n');
        out.append("Ended: ").append(s.ended().isBlank() ? "In progress" : s.ended()).append('\n');
        out.append("Location: ").append(s.location()).append('\n');
        out.append("Probable initiator: ").append(s.probableInitiator()).append('\n');
        out.append("Initiator confidence: ").append(s.initiatorConfidence()).append('\n');
        out.append("Initiator reason: ").append(s.initiatorReason()).append('\n');
        out.append("Participants: ").append(String.join(", ", s.participants())).append('\n');
        out.append("Result: ").append(s.result()).append('\n');
        out.append("Rule flags: ").append(s.flags().isEmpty() ? "None" : String.join("; ", s.flags())).append('\n');
        out.append("Context limitation: The plugin cannot know voice-chat warnings, verbal permission, or all off-log context.\n");
        out.append('\n').append("RAW TIMELINE").append('\n');
        for (String line : detail.timeline()) out.append(line).append('\n');
        return out.toString();
    }

    private void loadStoredIncidents() {
        Path folder = plugin.getCombatFolder();
        if (folder == null || !Files.exists(folder)) return;
        try (var stream = Files.list(folder)) {
            List<Path> files = stream
                    .filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".log"))
                    .sorted(Comparator.comparingLong(this::lastModifiedSafe).reversed())
                    .limit(100)
                    .toList();
            synchronized (recentSummaries) {
                for (Path file : files) {
                    String id = file.getFileName().toString().replaceFirst("\\.log$", "");
                    CombatDetail detail = readStoredDetail(id);
                    if (detail != null) {
                        recentDetails.put(id, detail);
                        recentSummaries.addLast(detail.summary());
                    }
                }
            }
        } catch (IOException ignored) {}
    }

    private long lastModifiedSafe(Path file) {
        try { return Files.getLastModifiedTime(file).toMillis(); }
        catch (IOException ignored) { return 0L; }
    }

    private long parseDisplayTime(String value, Path fallbackFile) {
        if (value != null && !value.isBlank() && !value.equalsIgnoreCase("In progress")) {
            try {
                return LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            } catch (RuntimeException ignored) {}
        }
        return lastModifiedSafe(fallbackFile);
    }

    private CombatDetail readStoredDetail(String id) {
        String safe = id.replaceAll("[^A-Za-z0-9_\\-]", "_");
        Path root = plugin.getCombatFolder();
        if (root == null) return null;
        Path file = root.resolve(safe + ".log").normalize();
        if (!file.startsWith(root.normalize()) || !Files.exists(file)) return null;
        try {
            List<String> all = Files.readAllLines(file, StandardCharsets.UTF_8);
            Map<String, String> headers = new HashMap<>();
            List<String> timeline = new ArrayList<>();
            boolean inTimeline = false;
            for (String line : all) {
                if (line.equals("RAW TIMELINE")) { inTimeline = true; continue; }
                if (inTimeline) {
                    if (!line.isBlank()) timeline.add(line);
                } else {
                    int colon = line.indexOf(':');
                    if (colon > 0) headers.put(line.substring(0, colon), line.substring(colon + 1).trim());
                }
            }
            List<String> participants = splitHeader(headers.getOrDefault("Participants", ""));
            List<String> flags = "None".equals(headers.get("Rule flags")) ? List.of() : splitSemicolon(headers.getOrDefault("Rule flags", ""));
            String startedText = headers.getOrDefault("Started", "");
            String endedText = headers.getOrDefault("Ended", "");
            long startedEpoch = parseDisplayTime(startedText, file);
            long endedEpoch = parseDisplayTime(endedText, file);
            CombatSummary summary = new CombatSummary(
                    headers.getOrDefault("Combat Incident", safe),
                    headers.getOrDefault("Type", "PvP"), startedEpoch, endedEpoch,
                    startedText, endedText,
                    headers.getOrDefault("Location", ""), headers.getOrDefault("Probable initiator", "Unknown"),
                    headers.getOrDefault("Initiator confidence", "Low"),
                    headers.getOrDefault("Initiator reason", "First recorded damaging player; older file"),
                    participants, headers.getOrDefault("Result", ""), flags, "COMPLETE", Map.of());
            return new CombatDetail(summary, timeline);
        } catch (IOException ignored) {
            return null;
        }
    }

    private void analyzeInitiator(Incident incident) {
        int participantCount;
        synchronized (incident.participants) { participantCount = incident.participants.size(); }
        int openingHits = Math.max(incident.firstAttackerHitsBeforeRetaliation,
                incident.hitsByPlayer.getOrDefault(incident.firstAttacker, 0));

        if (participantCount > 2) {
            incident.initiatorConfidence = "Low";
            incident.initiatorReason = incident.firstAttackerName
                    + " made the first recorded damaging action, but the incident involved multiple players";
        } else if (!incident.firstVictimRetaliated && openingHits >= 2) {
            incident.initiatorConfidence = "High from logged combat only";
            incident.initiatorReason = incident.firstAttackerName + " made the first recorded hit and delivered "
                    + openingHits + " hit" + (openingHits == 1 ? "" : "s")
                    + " without a recorded damaging retaliation";
        } else if (incident.firstVictimRetaliated && incident.firstAttackerHitsBeforeRetaliation >= 2) {
            long delayMillis = incident.retaliationAt <= 0 ? 0 : incident.retaliationAt - incident.startedAt;
            incident.initiatorConfidence = "High from logged combat only";
            incident.initiatorReason = incident.firstAttackerName + " delivered "
                    + incident.firstAttackerHitsBeforeRetaliation
                    + " recorded hits before the first damaging retaliation"
                    + (delayMillis > 0 ? " (about " + oneDecimal(delayMillis / 1000.0) + " seconds)" : "");
        } else if (incident.firstVictimRetaliated) {
            incident.initiatorConfidence = "Medium";
            incident.initiatorReason = incident.firstAttackerName
                    + " made the first recorded damaging action; the other player then retaliated";
        } else {
            incident.initiatorConfidence = "Low";
            incident.initiatorReason = incident.firstAttackerName
                    + " made the first recorded damaging action, but the incident contains limited hit evidence";
        }
    }

    private void appendLootSummary(Incident incident, long now) {
        if (incident.lootedByPlayer.isEmpty()) return;
        List<String> parts = new ArrayList<>();
        for (Map.Entry<UUID, Map<String, Integer>> playerEntry : incident.lootedByPlayer.entrySet()) {
            String name = incident.participants.getOrDefault(playerEntry.getKey(),
                    plugin.getServer().getOfflinePlayer(playerEntry.getKey()).getName());
            if (name == null || name.isBlank()) name = playerEntry.getKey().toString();
            String items = playerEntry.getValue().entrySet().stream()
                    .filter(e -> e.getValue() > 0)
                    .map(e -> e.getValue() + "x" + e.getKey())
                    .reduce((a, b) -> a + ", " + b).orElse("");
            if (!items.isBlank()) parts.add(name + ": " + items);
        }
        if (!parts.isEmpty()) incident.timeline.add(timelineLine(now, "DEATH_LOOT_SUMMARY " + String.join(" | ", parts)));
    }

    private int recentKillCount(UUID attackerId, UUID victimId, long now) {
        synchronized (recentKills) {
            pruneRecentKills(now);
            int count = 0;
            for (RecentKill kill : recentKills) {
                if (kill.attackerId().equals(attackerId) && kill.victimId().equals(victimId)) count++;
            }
            return count;
        }
    }

    private void pruneRecentKills(long now) {
        long cutoff = now - repeatedKillWindowSeconds() * 1000L;
        while (!recentKills.isEmpty() && recentKills.peekFirst().time() < cutoff) recentKills.removeFirst();
    }

    private HazardAction findRelevantHazard(Player victim, String cause) {
        long now = System.currentTimeMillis();
        synchronized (hazards) {
            pruneHazards(now);
            HazardAction best = null;
            double bestDistance = Double.MAX_VALUE;
            for (HazardAction action : hazards) {
                if (!matchesCause(action.material(), cause)) continue;
                if (action.location().getWorld() != victim.getWorld()) continue;
                double distance = action.location().distance(victim.getLocation());
                if (distance <= hazardRadius() && distance < bestDistance) {
                    best = action;
                    bestDistance = distance;
                }
            }
            return best;
        }
    }

    private void pruneHazards(long now) {
        long cutoff = now - Math.max(5, plugin.getConfig().getLong("combat.environmental-attribution-seconds", 20)) * 1000L;
        while (!hazards.isEmpty() && hazards.peekFirst().time() < cutoff) hazards.removeFirst();
    }

    private boolean matchesCause(Material material, String cause) {
        String c = cause.toUpperCase(Locale.ROOT);
        if (c.contains("DROWN")) return material == Material.WATER || material == Material.WATER_BUCKET;
        if (c.contains("LAVA") || c.contains("FIRE") || c.contains("HOT_FLOOR")) {
            return material == Material.LAVA || material == Material.LAVA_BUCKET
                    || material == Material.FLINT_AND_STEEL || material == Material.FIRE_CHARGE;
        }
        return false;
    }

    private String movementRelativeToHazard(Player player, Location hazard) {
        MovementState state = movement.get(player.getUniqueId());
        if (state == null || state.current == null || state.previous == null
                || state.current.getWorld() != hazard.getWorld() || state.previous.getWorld() != hazard.getWorld()) {
            return "unknown";
        }
        double previous = state.previous.distance(hazard);
        double current = state.current.distance(hazard);
        if (current > previous + 0.5) return "away-from-source";
        if (current + 0.5 < previous) return "toward-source";
        return "unclear";
    }

    private String createId(long now, String attacker, String victim) {
        String base = "PVP-" + ID_TIME.format(Instant.ofEpochMilli(now)) + "-"
                + safeToken(attacker) + "-vs-" + safeToken(victim);
        return base + "-" + String.format(Locale.ROOT, "%02d", idSequence.incrementAndGet() % 100);
    }

    private String statusLine(Player player) {
        return "START_STATUS " + player.getName()
                + " health:" + healthText(player.getHealth(), maxHealth(player))
                + " food:" + player.getFoodLevel() + "/20"
                + " weapon:" + weaponName(player)
                + " armor:" + armorPoints(player);
    }

    private String weaponName(Player player) {
        ItemStack item = player.getInventory().getItemInMainHand();
        return item == null || item.getType() == Material.AIR ? "EMPTY_HAND" : item.getType().name();
    }

    private int armorPoints(Player player) {
        AttributeInstance attribute = player.getAttribute(Attribute.ARMOR);
        return attribute == null ? 0 : (int) Math.round(attribute.getValue());
    }

    private double maxHealth(Player player) {
        AttributeInstance attribute = player.getAttribute(Attribute.MAX_HEALTH);
        return attribute == null ? 20.0 : attribute.getValue();
    }

    private String healthText(double health, double max) {
        return oneDecimal(Math.max(0, health)) + "/" + oneDecimal(max);
    }

    private String oneDecimal(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private String timelineLine(long time, String action) {
        return "[" + HIT_TIME.format(Instant.ofEpochMilli(time)) + "] " + LogUtils.cleanOneLine(action);
    }

    private String formatStatusMap(Map<String, String> status) {
        Map<String, String> copy;
        synchronized (status) { copy = new LinkedHashMap<>(status); }
        return copy.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                .reduce((a, b) -> a + ", " + b).orElse("unknown");
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("combat.enabled", true);
    }

    private double lootRadius() {
        return Math.max(4.0, plugin.getConfig().getDouble("combat.post-death-loot-radius-blocks", 12.0));
    }

    private double hazardRadius() {
        return Math.max(4.0, plugin.getConfig().getDouble("combat.environmental-attribution-radius-blocks", 12.0));
    }

    private long spawnReviewSeconds() {
        return Math.max(5, plugin.getConfig().getLong("combat.spawn-review-seconds", 20));
    }

    private long respawnTrackingSeconds() {
        return Math.max(spawnReviewSeconds(),
                plugin.getConfig().getLong("combat.respawn-return-tracking-seconds", 90));
    }

    private double respawnRadius() {
        return Math.max(4.0, plugin.getConfig().getDouble("combat.respawn-radius-blocks", 16.0));
    }

    private double returnToFightRadius() {
        return Math.max(6.0, plugin.getConfig().getDouble("combat.return-to-fight-radius-blocks", 24.0));
    }

    private long repeatedKillWindowSeconds() {
        return Math.max(30, plugin.getConfig().getLong("combat.repeated-kill-window-seconds", 300));
    }

    private boolean sameWorldAndWithin(Location a, Location b, double radius) {
        return a != null && b != null && a.getWorld() == b.getWorld() && a.distanceSquared(b) <= radius * radius;
    }

    private String safeToken(String value) {
        String clean = value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9_\\-]", "_");
        return clean.isBlank() ? "unknown" : clean;
    }

    private static List<String> splitHeader(String value) {
        if (value == null || value.isBlank()) return List.of();
        return List.of(value.split("\\s*,\\s*"));
    }

    private static List<String> splitSemicolon(String value) {
        if (value == null || value.isBlank()) return List.of();
        return List.of(value.split("\\s*;\\s*"));
    }
}
