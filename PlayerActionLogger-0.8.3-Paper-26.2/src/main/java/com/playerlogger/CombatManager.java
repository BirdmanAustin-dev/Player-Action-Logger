package com.playerlogger;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
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
import java.util.Base64;
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
            String encounterRootId,
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
            List<CombatSignal> signals,
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

    private record AggressionKey(UUID attackerId, UUID victimId) {}

    private static final class AggressionState {
        private long lastDamageAt;
        private double totalDamage;

        private AggressionState(long lastDamageAt, double totalDamage) {
            this.lastDamageAt = lastDamageAt;
            this.totalDamage = totalDamage;
        }
    }

    private record TrackedDeathDrop(String fingerprint, String material, int amount) {}

    private record PairKillStats(int attackerKillsVictim, int victimKillsAttacker) {}

    /**
     * Short-lived continuity marker for multi-player encounters. A death or disconnect can
     * legitimately end one incident segment while the remaining participants keep fighting;
     * the root ID lets later segments be correlated without forcing one huge incident object.
     */
    private record RecentEncounterRoot(String rootId, long time, Location location) {}

    public enum DisconnectReason { QUIT, KICK, TIMEOUT, UNKNOWN }

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
        private String encounterRootId;
        private final long startedAt;
        private final Location location;
        private final Map<UUID, String> participants = Collections.synchronizedMap(new LinkedHashMap<>());
        private final List<String> timeline = Collections.synchronizedList(new ArrayList<>());
        private final Map<CombatSignal.Rule, CombatSignal> signals = Collections.synchronizedMap(new LinkedHashMap<>());
        private final Map<UUID, Integer> hitsByPlayer = new ConcurrentHashMap<>();
        private final Map<String, String> currentHealth = Collections.synchronizedMap(new LinkedHashMap<>());
        private final Map<String, Integer> expectedDeathDrops = new HashMap<>();
        private final Set<UUID> preExistingNearbyItemEntities = new HashSet<>();
        private final Map<UUID, TrackedDeathDrop> trackedDeathDrops = new HashMap<>();
        private final Map<UUID, Map<String, Integer>> lootedByPlayer = new HashMap<>();
        private final Map<String, String> lootFingerprintLabels = new HashMap<>();

        /** Last combat/evidence timestamp used for incident grouping and timeout. */
        private long lastHitAt;
        /** Last actual PvP or credibly attributed hostile-player damage timestamp. */
        private long lastPvpAt;
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
        private Location lastCombatLocation;
        private boolean priorAggressionByVictim;
        private double firstAttackAttributionConfidence = 1.0;
        private String firstAttackAttribution = "direct player damage";
        private String deathVictim = "";
        private boolean finalized;

        private Incident(String id, long now, Location location) {
            this.id = id;
            this.encounterRootId = id;
            this.startedAt = now;
            this.lastHitAt = now;
            this.lastPvpAt = 0L;
            this.location = location == null ? null : location.clone();
            this.lastCombatLocation = location == null ? null : location.clone();
        }
    }

    private final PlayerActionLogger plugin;
    private final ActivityTracker activityTracker;
    private final Map<UUID, Incident> activeByPlayer = new ConcurrentHashMap<>();
    private final Set<Incident> liveIncidents = ConcurrentHashMap.newKeySet();
    private final Set<Incident> pendingLootIncidents = ConcurrentHashMap.newKeySet();
    private final Deque<CombatSummary> recentSummaries = new ArrayDeque<>();
    private final Map<String, CombatDetail> recentDetails = new ConcurrentHashMap<>();
    private final Map<String, String> incidentAliases = new ConcurrentHashMap<>();
    private final Deque<HazardAction> hazards = new ArrayDeque<>();
    private final Map<UUID, Long> joinedAt = new ConcurrentHashMap<>();
    private final Map<UUID, Long> teleportedAt = new ConcurrentHashMap<>();
    private final Map<UUID, RespawnContext> respawnContexts = new ConcurrentHashMap<>();
    private final Deque<RecentKill> recentKills = new ArrayDeque<>();
    private final Map<UUID, MovementState> movement = new ConcurrentHashMap<>();
    private final Map<AggressionKey, AggressionState> recentAggression = new ConcurrentHashMap<>();
    private final Map<UUID, RecentEncounterRoot> recentEncounterRoots = new ConcurrentHashMap<>();
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
        handlePvpHit(attacker, victim, cause, weapon, finalDamage, victimHealthAfter,
                "direct player damage", 1.0);
    }

    public void handlePvpHit(Player attacker, Player victim, String cause, String weapon,
                             double finalDamage, double victimHealthAfter,
                             String attribution, double attributionConfidence) {
        if (!enabled() || attacker == null || victim == null || attacker.equals(victim)) return;
        long now = System.currentTimeMillis();
        Incident incident = chooseIncident(attacker, victim, now);
        boolean created = incident.timeline.isEmpty();

        addParticipant(incident, attacker);
        addParticipant(incident, victim);
        if (created) initializeIncident(incident, attacker, victim, now, attribution, attributionConfidence);

        incident.lastHitAt = now;
        incident.lastPvpAt = now;
        incident.lastCombatLocation = victim.getLocation().clone();
        incident.hitsByPlayer.merge(attacker.getUniqueId(), 1, Integer::sum);
        if (attacker.getUniqueId().equals(incident.firstAttacker) && !incident.firstVictimRetaliated) {
            incident.firstAttackerHitsBeforeRetaliation++;
        }
        incident.currentHealth.put(attacker.getName(), healthText(attacker.getHealth(), maxHealth(attacker)));
        incident.currentHealth.put(victim.getName(), healthText(victimHealthAfter, maxHealth(victim)));

        if (attacker.getUniqueId().equals(incident.firstVictim)
                && victim.getUniqueId().equals(incident.firstAttacker)
                && !incident.firstVictimRetaliated) {
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
        recordAggression(attacker.getUniqueId(), victim.getUniqueId(), now, finalDamage);
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

            // Keep unrelated/self environmental damage as context, but do not refresh the
            // PvP or incident-liveness clocks. Otherwise a fall/fire/drowning event can turn
            // a later disconnect into a false combat-logout alert.
            Incident active = activeByPlayer.get(victim.getUniqueId());
            if (active != null) {
                long now = System.currentTimeMillis();
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
            if (created) initializeIncident(incident, sourcePlayer, victim, now, "recent environmental hazard", 0.55);
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
                incident.firstAttackAttribution = "recent environmental hazard";
                incident.firstAttackAttributionConfidence = 0.55;
                incident.timeline.add(timelineLine(now, "INCIDENT_START environmental source:" + source.material()
                        + " attribution:recent environmental hazard confidence:0.55"));
                incident.timeline.add(timelineLine(now, statusLine(victim)));
            }
        }

        // Attributed third-party hazard owners are participants even while offline; only online
        // players are inserted into activeByPlayer.
        incident.participants.put(source.playerId(), source.playerName());
        incident.lastCombatLocation = victim.getLocation().clone();
        incident.currentHealth.put(victim.getName(), healthText(victimHealthAfter, maxHealth(victim)));
        String movementNote = movementRelativeToHazard(victim, source.location());
        String compact = String.format(Locale.ROOT,
                "ENV_HIT possible-source:%s material:%s victim:%s cause:%s damage:%.1f health:%s movement:%s at %s",
                source.playerName(), source.material(), victim.getName(), cause, finalDamage,
                healthText(victimHealthAfter, maxHealth(victim)), movementNote, LogUtils.loc(victim.getLocation()));
        incident.timeline.add(timelineLine(now, compact));
        boolean credibleHostileAttribution = classifyEnvironmentalSignal(incident, source, victim, movementNote, now);
        if (credibleHostileAttribution) {
            // Only credible hostile attribution extends the fight itself. Context-only
            // environmental damage may be recorded in the timeline without keeping an
            // otherwise-ended PvP incident alive.
            incident.lastHitAt = now;
            incident.lastPvpAt = now;
        }

        String evidencePrefix = credibleHostileAttribution ? "PVP_ENV" : "ENV_CONTEXT";
        String playerLine = evidencePrefix + " incident:" + incident.id + " " + compact;
        plugin.logPlayer(source.playerId(), source.playerName(), playerLine);
        plugin.log(victim, playerLine);
        if (credibleHostileAttribution) {
            activityTracker.record(victim, ActivityTracker.Kind.PVP);
            if (sourcePlayer != null) activityTracker.record(sourcePlayer, ActivityTracker.Kind.PVP);
        }
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
        boolean victimReturnedToFight = respawnContext.returnedToPreviousFightArea;
        respawnContext.diedAt = now;
        respawnContext.deathLocation = victim.getLocation().clone();
        respawnContext.previousOpponents.clear();
        synchronized (incident.participants) {
            for (UUID participantId : incident.participants.keySet()) {
                if (!participantId.equals(victim.getUniqueId())) respawnContext.previousOpponents.add(participantId);
            }
        }

        // Repeated-kill history requires an actual server-confirmed killer. Falling back to
        // the first attacker can falsely credit an unrelated fall/environmental death.
        UUID creditedKiller = killer == null ? null : killer.getUniqueId();
        String creditedKillerName = killer == null ? "" : killer.getName();
        if (creditedKiller == null) {
            incident.timeline.add(timelineLine(now,
                    "KILL_CREDIT unknown; repeated-kill review skipped because the server reported no player killer"));
        }
        if (creditedKiller != null && !creditedKiller.equals(victim.getUniqueId())) {
            PairKillStats pair = recentKillStats(creditedKiller, victim.getUniqueId(), now);
            int currentDirectionalKills = pair.attackerKillsVictim() + 1;
            if (currentDirectionalKills == 2) {
                putSignal(incident, CombatSignal.Rule.REPEATED_KILL, CombatSignal.Kind.CONTEXT,
                        CombatSignal.Severity.LOW, 0.45,
                        "Second kill by " + creditedKillerName + " against " + victim.getName()
                                + " in the recent-kill window; contextual only");
            } else if (currentDirectionalKills >= 3) {
                boolean reciprocalFight = pair.victimKillsAttacker() > 0;
                if (reciprocalFight || victimReturnedToFight) {
                    putSignal(incident, CombatSignal.Rule.REPEATED_KILL, CombatSignal.Kind.CONTEXT,
                            CombatSignal.Severity.LOW, 0.55,
                            "Repeated kills occurred, but the recent history shows reciprocal fighting or a voluntary return to the fight area");
                } else {
                    putSignal(incident, CombatSignal.Rule.REPEATED_KILL, CombatSignal.Kind.ALERT,
                            CombatSignal.Severity.MEDIUM, 0.78,
                            creditedKillerName + " killed " + victim.getName() + " " + currentDirectionalKills
                                    + " times in the recent-kill review window without a reciprocal kill");
                }
            }
            synchronized (recentKills) {
                recentKills.addLast(new RecentKill(now, creditedKiller, creditedKillerName,
                        victim.getUniqueId(), victim.getName(), victim.getLocation().clone()));
                pruneRecentKills(now);
            }
        }

        incident.expectedDeathDrops.clear();
        if (drops != null) {
            for (ItemStack item : drops) {
                if (item == null || item.getType() == Material.AIR) continue;
                String fingerprint = itemFingerprint(item);
                incident.expectedDeathDrops.merge(fingerprint, item.getAmount(), Integer::sum);
                incident.lootFingerprintLabels.put(fingerprint, item.getType().name());
            }
        }
        capturePreExistingNearbyItems(incident);
        if (!incident.expectedDeathDrops.isEmpty()) {
            plugin.getServer().getScheduler().runTask(plugin, () -> captureSpawnedDeathDrops(incident));
        }

        rememberEncounterRoot(incident);
        removeFromActive(incident);
        liveIncidents.remove(incident);
        int seconds = Math.max(10, plugin.getConfig().getInt("combat.post-death-loot-seconds", 45));
        incident.lootWindowUntil = now + seconds * 1000L;
        pendingLootIncidents.add(incident);
    }

    public void onItemPickup(Player player, Item itemEntity, int pickedAmount, Location location) {
        if (player == null || itemEntity == null || location == null) return;
        long now = System.currentTimeMillis();
        UUID entityId = itemEntity.getUniqueId();
        for (Incident incident : new ArrayList<>(pendingLootIncidents)) {
            if (now > incident.lootWindowUntil || incident.deathLocation == null) continue;
            TrackedDeathDrop tracked = incident.trackedDeathDrops.get(entityId);
            if (tracked == null) continue;
            int matched = Math.max(1, Math.min(tracked.amount(), pickedAmount));
            int remainder = tracked.amount() - matched;
            if (remainder > 0) incident.trackedDeathDrops.put(entityId, new TrackedDeathDrop(tracked.fingerprint(), tracked.material(), remainder));
            else incident.trackedDeathDrops.remove(entityId);

            if (player.getName().equalsIgnoreCase(incident.deathVictim)) {
                incident.timeline.add(timelineLine(now, "OWNER_RECOVERY " + player.getName() + " recovered "
                        + matched + "x" + tracked.material() + " from an identified death-drop entity"));
                continue;
            }

            incident.lootedByPlayer.computeIfAbsent(player.getUniqueId(), ignored -> new HashMap<>())
                    .merge(tracked.fingerprint(), matched, Integer::sum);
            removeSignal(incident, CombatSignal.Rule.DEATH_LOOT_RETURNED);
            putSignal(incident, CombatSignal.Rule.DEATH_LOOT, CombatSignal.Kind.ALERT,
                    CombatSignal.Severity.MEDIUM, 0.92,
                    player.getName() + " picked up an item entity identified as part of "
                            + incident.deathVictim + "'s death drops");
            incident.timeline.add(timelineLine(now, "POST_DEATH_PICKUP " + player.getName() + " picked up "
                    + matched + "x" + tracked.material() + " from identified death-drop entity:" + entityId));
        }
    }

    public void onItemDrop(Player player, Item droppedEntity, Location location) {
        if (player == null || droppedEntity == null || location == null) return;
        ItemStack item = droppedEntity.getItemStack();
        if (item == null || item.getType() == Material.AIR) return;
        long now = System.currentTimeMillis();
        for (Incident incident : new ArrayList<>(pendingLootIncidents)) {
            if (now > incident.lootWindowUntil || incident.deathLocation == null) continue;
            if (!sameWorldAndWithin(location, incident.deathLocation, lootRadius())) continue;
            Map<String, Integer> lootedItems = incident.lootedByPlayer.get(player.getUniqueId());
            if (lootedItems == null) continue;
            String type = item.getType().name();
            String fingerprint = itemFingerprint(item);
            int looted = lootedItems.getOrDefault(fingerprint, 0);
            if (looted <= 0) continue;
            int returned = Math.min(looted, item.getAmount());
            lootedItems.put(fingerprint, looted - returned);

            // Preserve provenance across a return: the new dropped entity is now tracked, so
            // picking it back up can re-open the outstanding quantity instead of laundering
            // the evidence through a same-material drop.
            incident.trackedDeathDrops.put(droppedEntity.getUniqueId(), new TrackedDeathDrop(fingerprint, type, returned));
            incident.timeline.add(timelineLine(now, "POSSIBLE_RETURN " + player.getName() + " dropped "
                    + returned + "x" + type + " near the death location entity:" + droppedEntity.getUniqueId()));

            if (allTrackedLootReturned(incident)) {
                // Keep the original DEATH_LOOT evidence and add mitigation rather than erasing
                // the fact that the pickup happened.
                putSignal(incident, CombatSignal.Rule.DEATH_LOOT_RETURNED, CombatSignal.Kind.MITIGATING,
                        CombatSignal.Severity.LOW, 0.80,
                        "Matching identified death-loot item fingerprints were dropped near the death location before the review window closed");
            }
        }
    }

    public void onDisconnect(Player player, DisconnectReason reason) {
        Incident incident = activeByPlayer.get(player.getUniqueId());
        if (incident == null) return;
        long now = System.currentTimeMillis();
        long sinceLastCombat = incident.lastPvpAt <= 0L ? Long.MAX_VALUE
                : Math.max(0L, now - incident.lastPvpAt);
        if (reason == DisconnectReason.KICK) {
            putSignal(incident, CombatSignal.Rule.KICK_DISCONNECT, CombatSignal.Kind.CONTEXT,
                    CombatSignal.Severity.LOW, 1.0,
                    player.getName() + " was kicked during the incident; this is not treated as combat logging");
            incident.result = player.getName() + " was kicked during active combat";
            incident.timeline.add(timelineLine(now, "COMBAT_DISCONNECT reason:KICK " + player.getName()));
        } else if ((reason == DisconnectReason.QUIT || reason == DisconnectReason.UNKNOWN)
                && sinceLastCombat <= combatLogoutReviewSeconds() * 1000L && !player.isDead()) {
            double healthRatio = maxHealth(player) <= 0 ? 1.0 : player.getHealth() / maxHealth(player);
            double confidence = healthRatio <= 0.5 ? 0.82 : 0.68;
            putSignal(incident, CombatSignal.Rule.COMBAT_LOGOUT, CombatSignal.Kind.ALERT,
                    CombatSignal.Severity.MEDIUM, confidence,
                    player.getName() + " disconnected " + oneDecimal(sinceLastCombat / 1000.0)
                            + " seconds after the last PvP damage while still alive");
            incident.result = player.getName() + " disconnected during active combat";
            incident.timeline.add(timelineLine(now, "POSSIBLE_COMBAT_LOGOUT " + player.getName() + " health:"
                    + healthText(player.getHealth(), maxHealth(player))));
        } else {
            incident.result = player.getName() + " disconnected after combat activity";
            incident.timeline.add(timelineLine(now, "COMBAT_DISCONNECT reason:" + reason + " " + player.getName()));
        }
        incident.endedAt = now;
        finalizeIncident(incident);
    }

    /** Backward-compatible entry point for older callers. */
    public void onQuit(Player player) {
        onDisconnect(player, DisconnectReason.QUIT);
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
        pruneAggression(now);
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
        String resolved = resolveInMemoryAlias(id);
        for (Incident incident : liveIncidents) if (incident.id.equals(resolved)) return toDetail(incident);
        for (Incident incident : pendingLootIncidents) if (incident.id.equals(resolved)) return toDetail(incident);
        CombatDetail cached = recentDetails.get(resolved);
        if (cached != null) return cached;
        return readStoredDetail(resolved);
    }

    private String resolveInMemoryAlias(String id) {
        String current = id;
        Set<String> seen = new HashSet<>();
        while (seen.add(current)) {
            String next = incidentAliases.get(current);
            if (next == null || next.isBlank()) break;
            current = next;
        }
        return current;
    }

    private Incident chooseIncident(Player attacker, Player victim, long now) {
        Incident a = activeByPlayer.get(attacker.getUniqueId());
        Incident v = activeByPlayer.get(victim.getUniqueId());

        if (a != null && !canJoinIncident(a, attacker, victim, now)) {
            endSeparatedIncident(a, now);
            a = null;
        }
        if (v != null && v != a && !canJoinIncident(v, attacker, victim, now)) {
            endSeparatedIncident(v, now);
            v = null;
        }

        if (a != null && v != null && a != v) {
            if (canJoinIncident(a, attacker, victim, now) && canJoinIncident(v, attacker, victim, now)) {
                Incident primary = a.startedAt <= v.startedAt ? a : v;
                Incident secondary = primary == a ? v : a;
                mergeInto(primary, secondary, now);
                return primary;
            }
            endSeparatedIncident(a, now);
            endSeparatedIncident(v, now);
            a = null;
            v = null;
        }
        if (a != null) return a;
        if (v != null) return v;
        Incident created = new Incident(createId(now, attacker.getName(), victim.getName()), now, victim.getLocation());
        inheritEncounterRoot(created, attacker, victim, now);
        liveIncidents.add(created);
        return created;
    }

    private boolean canJoinIncident(Incident incident, Player attacker, Player victim, long now) {
        if (incident == null || incident.finalized) return false;
        if (now - incident.lastHitAt > incidentJoinWindowSeconds() * 1000L) return false;
        Location anchor = incident.lastCombatLocation != null ? incident.lastCombatLocation : incident.location;
        if (anchor == null || anchor.getWorld() == null) return false;
        if (attacker.getWorld() != anchor.getWorld() || victim.getWorld() != anchor.getWorld()) return false;
        double radius = incidentLinkRadius();
        return attacker.getLocation().distanceSquared(anchor) <= radius * radius
                && victim.getLocation().distanceSquared(anchor) <= radius * radius;
    }

    private void endSeparatedIncident(Incident incident, long now) {
        if (incident == null || incident.finalized) return;
        incident.endedAt = now;
        incident.result = "Combat segment ended before a geographically or temporally separate encounter";
        incident.timeline.add(timelineLine(now, "INCIDENT_END separated encounter"));
        finalizeIncident(incident);
    }

    private void initializeIncident(Incident incident, Player attacker, Player victim, long now,
                                    String attribution, double attributionConfidence) {
        incident.firstAttacker = attacker.getUniqueId();
        incident.firstAttackerName = attacker.getName();
        incident.firstVictim = victim.getUniqueId();
        incident.firstVictimName = victim.getName();
        incident.firstAttackAttribution = attribution == null ? "unknown" : attribution;
        incident.firstAttackAttributionConfidence = Math.max(0.0, Math.min(1.0, attributionConfidence));
        incident.timeline.add(timelineLine(now, "INCIDENT_START PvP at " + LogUtils.loc(victim.getLocation())
                + " encounter-root:" + incident.encounterRootId
                + " attribution:" + incident.firstAttackAttribution
                + " confidence:" + oneDecimal(incident.firstAttackAttributionConfidence)));
        if (!incident.encounterRootId.equals(incident.id)) {
            incident.timeline.add(timelineLine(now, "ENCOUNTER_CONTINUATION root:" + incident.encounterRootId
                    + " linked from a recent multi-player incident segment"));
        }
        incident.timeline.add(timelineLine(now, statusLine(attacker)));
        incident.timeline.add(timelineLine(now, statusLine(victim)));
        incident.currentHealth.put(attacker.getName(), healthText(attacker.getHealth(), maxHealth(attacker)));
        incident.currentHealth.put(victim.getName(), healthText(victim.getHealth(), maxHealth(victim)));
        incident.lastCombatLocation = victim.getLocation().clone();
        addContextFlags(incident, attacker, victim, now);
        plugin.log(attacker, "COMBAT_JOIN incident:" + incident.id + " opponent:" + victim.getName());
        plugin.log(victim, "COMBAT_JOIN incident:" + incident.id + " opponent:" + attacker.getName());
    }

    private void addContextFlags(Incident incident, Player attacker, Player victim, long now) {
        long joined = joinedAt.getOrDefault(victim.getUniqueId(), 0L);
        if (joined > 0 && now - joined <= 10_000L) {
            putSignal(incident, CombatSignal.Rule.ATTACK_AFTER_LOGIN, CombatSignal.Kind.CONTEXT,
                    CombatSignal.Severity.LOW, 0.65, "Victim was attacked shortly after login");
        }

        long teleported = teleportedAt.getOrDefault(victim.getUniqueId(), 0L);
        if (teleported > 0 && now - teleported <= 5_000L) {
            putSignal(incident, CombatSignal.Rule.ATTACK_AFTER_TELEPORT, CombatSignal.Kind.CONTEXT,
                    CombatSignal.Severity.LOW, 0.65, "Victim was attacked shortly after teleporting");
        }

        RespawnContext context = respawnContexts.get(victim.getUniqueId());
        if (context != null && context.respawnedAt > 0
                && now - context.respawnedAt <= spawnReviewSeconds() * 1000L) {
            boolean samePriorOpponent = context.previousOpponents.contains(attacker.getUniqueId());
            boolean nearRespawn = context.respawnLocation != null
                    && sameWorldAndWithin(victim.getLocation(), context.respawnLocation, respawnRadius());

            if (context.returnedToPreviousFightArea) {
                putSignal(incident, CombatSignal.Rule.RETURNED_TO_FIGHT, CombatSignal.Kind.MITIGATING,
                        CombatSignal.Severity.LOW, 0.95,
                        "Victim left respawn and voluntarily returned to the previous fight area; do not treat this as clear spawn killing");
            } else if (nearRespawn && !context.leftRespawnArea) {
                String repeat = samePriorOpponent ? " by a player from the previous incident" : "";
                boolean strongAttribution = incident.firstAttackAttributionConfidence >= 0.80;
                putSignal(incident, CombatSignal.Rule.SPAWN_ATTACK,
                        strongAttribution ? CombatSignal.Kind.ALERT : CombatSignal.Kind.CONTEXT,
                        strongAttribution ? CombatSignal.Severity.HIGH : CombatSignal.Severity.LOW,
                        Math.min(incident.firstAttackAttributionConfidence, samePriorOpponent ? 0.92 : 0.78),
                        "Possible spawn attack" + repeat + ": victim was still near the respawn point shortly after respawning"
                                + (strongAttribution ? "" : "; source attribution is indirect and requires context review"));
            } else if (context.leftRespawnArea) {
                putSignal(incident, CombatSignal.Rule.LEFT_RESPAWN, CombatSignal.Kind.MITIGATING,
                        CombatSignal.Severity.LOW, 0.90,
                        "Victim had left the respawn area before this attack");
            }
        }

        if (recentKillCount(attacker.getUniqueId(), victim.getUniqueId(), now) > 0) {
            putSignal(incident, CombatSignal.Rule.REPEAT_ENCOUNTER, CombatSignal.Kind.CONTEXT,
                    CombatSignal.Severity.LOW, 0.60,
                    "The same attacker recently killed this victim; review pair history if the encounter escalates");
        }

        AggressionState counterAggression = recentAggression.get(new AggressionKey(victim.getUniqueId(), attacker.getUniqueId()));
        if (counterAggression != null && now - counterAggression.lastDamageAt <= aggressionMemorySeconds() * 1000L) {
            incident.priorAggressionByVictim = true;
            putSignal(incident, CombatSignal.Rule.RECENT_AGGRESSION_CONTEXT, CombatSignal.Kind.MITIGATING,
                    CombatSignal.Severity.LOW, 0.90,
                    victim.getName() + " damaged " + attacker.getName() + " about "
                            + oneDecimal((now - counterAggression.lastDamageAt) / 1000.0)
                            + " seconds before this incident");
        }

        if (victim.isSleeping()) {
            boolean strongAttribution = incident.firstAttackAttributionConfidence >= 0.80;
            putSignal(incident, CombatSignal.Rule.SLEEPING_VICTIM,
                    strongAttribution ? CombatSignal.Kind.ALERT : CombatSignal.Kind.CONTEXT,
                    strongAttribution ? CombatSignal.Severity.MEDIUM : CombatSignal.Severity.LOW,
                    Math.min(incident.firstAttackAttributionConfidence, 0.85),
                    "Victim was sleeping when the first hit was recorded"
                            + (strongAttribution ? "" : "; source attribution is indirect"));
        }
        InventoryType openType = victim.getOpenInventory().getTopInventory().getType();
        if (openType != InventoryType.CRAFTING && openType != InventoryType.PLAYER) {
            putSignal(incident, CombatSignal.Rule.INVENTORY_OPEN, CombatSignal.Kind.CONTEXT,
                    CombatSignal.Severity.LOW, 0.70,
                    "Victim had an inventory open when the first hit was recorded: " + openType);
        }
        long inactiveSeconds = activityTracker.secondsSinceMeaningful(victim.getUniqueId());
        if (inactiveSeconds >= 0 && inactiveSeconds >= 120) {
            putSignal(incident, CombatSignal.Rule.INACTIVE_VICTIM, CombatSignal.Kind.CONTEXT,
                    CombatSignal.Severity.LOW, 0.60,
                    "Victim had no meaningful logged activity for " + inactiveSeconds + " seconds before the first hit");
        }
    }

    private void mergeInto(Incident primary, Incident secondary, long now) {
        if (primary == secondary || secondary.finalized) return;
        primary.participants.putAll(secondary.participants);
        if (!primary.encounterRootId.equals(secondary.encounterRootId)) {
            primary.timeline.add(timelineLine(now, "ENCOUNTER_ROOT_MERGE " + secondary.encounterRootId
                    + " -> " + primary.encounterRootId));
        }
        primary.timeline.addAll(secondary.timeline);
        primary.timeline.add(timelineLine(now, "INCIDENT_MERGE linked with " + secondary.id
                + " encounter-root:" + primary.encounterRootId));
        synchronized (primary.timeline) { primary.timeline.sort(Comparator.comparingLong(line -> timelineSortKey(line, primary.startedAt))); }
        incidentAliases.put(secondary.id, primary.id);
        writeIncidentAlias(secondary.id, primary.id);
        synchronized (secondary.signals) {
            for (CombatSignal signal : secondary.signals.values()) mergeSignal(primary, signal);
        }
        if (secondary.lastCombatLocation != null) primary.lastCombatLocation = secondary.lastCombatLocation.clone();
        primary.lastHitAt = Math.max(primary.lastHitAt, secondary.lastHitAt);
        primary.lastPvpAt = Math.max(primary.lastPvpAt, secondary.lastPvpAt);
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
        int participantCount;
        synchronized (incident.participants) { participantCount = incident.participants.size(); }
        if (participantCount == 2
                && !incident.firstVictimRetaliated
                && incident.hitsByPlayer.getOrDefault(incident.firstAttacker, 0) >= 2
                && !incident.priorAggressionByVictim
                && incident.firstAttackAttributionConfidence >= 0.80) {
            putSignal(incident, CombatSignal.Rule.UNPROVOKED_ATTACK, CombatSignal.Kind.ALERT,
                    CombatSignal.Severity.MEDIUM, Math.min(0.90, incident.firstAttackAttributionConfidence),
                    "Possible unprovoked attack: first recorded victim did not retaliate and no recent reciprocal aggression was found; "
                            + "voice-chat, signs, and outside warnings are not known");
        } else if (participantCount > 2
                && !incident.firstVictimRetaliated
                && incident.hitsByPlayer.getOrDefault(incident.firstAttacker, 0) >= 2) {
            putSignal(incident, CombatSignal.Rule.UNPROVOKED_ATTACK, CombatSignal.Kind.CONTEXT,
                    CombatSignal.Severity.LOW, 0.40,
                    "Opening hits occurred in a multi-player fight; group combat is not treated as an unprovoked-attack alert");
        }
        appendLootSummary(incident, incident.endedAt);

        List<String> participantNames;
        synchronized (incident.participants) { participantNames = new ArrayList<>(incident.participants.values()); }
        for (String name : participantNames) {
            Player online = plugin.getServer().getPlayerExact(name);
            if (online != null) incident.currentHealth.put(name, healthText(online.getHealth(), maxHealth(online)));
        }
        incident.timeline.add(timelineLine(incident.endedAt, "END_STATUS " + formatStatusMap(incident.currentHealth)));

        if (participantCount > 2) rememberEncounterRoot(incident);
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
        AlertManager alerts = plugin.getAlertManager();
        if (alerts != null) alerts.acceptCombat(summary);
    }

    private CombatSummary toSummary(Incident incident) {
        analyzeInitiator(incident);
        long end = incident.endedAt;
        String ended = end == 0 ? "" : DISPLAY_TIME.format(Instant.ofEpochMilli(end));
        List<String> participants;
        List<String> flags;
        List<CombatSignal> signals;
        Map<String, String> health;
        synchronized (incident.participants) { participants = new ArrayList<>(incident.participants.values()); }
        synchronized (incident.signals) {
            signals = new ArrayList<>(incident.signals.values());
            flags = signals.stream().map(this::displaySignal).toList();
        }
        synchronized (incident.currentHealth) { health = new LinkedHashMap<>(incident.currentHealth); }
        return new CombatSummary(incident.id, incident.encounterRootId, "PvP", incident.startedAt, end,
                DISPLAY_TIME.format(Instant.ofEpochMilli(incident.startedAt)), ended,
                LogUtils.loc(incident.location), incident.firstAttackerName,
                incident.initiatorConfidence, incident.initiatorReason,
                participants, incident.result, flags, signals, incident.status, health);
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
        out.append("Encounter Root: ").append(s.encounterRootId()).append('\n');
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
        out.append("Structured signals: ").append(encodeSignals(s.signals())).append('\n');
        out.append("Health: ").append(formatStatusMap(s.health())).append('\n');
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
        return readStoredDetail(id, new HashSet<>());
    }

    private CombatDetail readStoredDetail(String id, Set<String> seenAliases) {
        String safe = id.replaceAll("[^A-Za-z0-9_\\-]", "_");
        if (!seenAliases.add(safe)) return null;
        Path root = plugin.getCombatFolder();
        if (root == null) return null;
        Path file = root.resolve(safe + ".log").normalize();
        if (!file.startsWith(root.normalize())) return null;
        if (!Files.exists(file)) {
            Path alias = root.resolve(safe + ".alias").normalize();
            if (!alias.startsWith(root.normalize()) || !Files.exists(alias)) return null;
            try {
                String target = Files.readString(alias, StandardCharsets.UTF_8).trim();
                if (target.isBlank() || target.equals(safe)) return null;
                return readStoredDetail(target, seenAliases);
            } catch (IOException ignored) { return null; }
        }
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
            List<CombatSignal> signals = decodeSignals(headers.getOrDefault("Structured signals", ""));
            String startedText = headers.getOrDefault("Started", "");
            String endedText = headers.getOrDefault("Ended", "");
            long startedEpoch = parseDisplayTime(startedText, file);
            long endedEpoch = parseDisplayTime(endedText, file);
            CombatSummary summary = new CombatSummary(
                    headers.getOrDefault("Combat Incident", safe),
                    headers.getOrDefault("Encounter Root", headers.getOrDefault("Combat Incident", safe)),
                    headers.getOrDefault("Type", "PvP"), startedEpoch, endedEpoch,
                    startedText, endedText,
                    headers.getOrDefault("Location", ""), headers.getOrDefault("Probable initiator", "Unknown"),
                    headers.getOrDefault("Initiator confidence", "Low"),
                    headers.getOrDefault("Initiator reason", "First recorded damaging player; older file"),
                    participants, headers.getOrDefault("Result", ""), flags, signals, "COMPLETE",
                    parseStatusMap(headers.getOrDefault("Health", "")));
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

        if (incident.firstAttackAttributionConfidence < 0.80) {
            incident.initiatorConfidence = "Low";
            incident.initiatorReason = incident.firstAttackerName + " was attributed as the first source through "
                    + incident.firstAttackAttribution + "; indirect attribution lowers confidence";
        } else if (participantCount > 2) {
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
                    .map(e -> e.getValue() + "x" + incident.lootFingerprintLabels.getOrDefault(e.getKey(), "identified-item"))
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

    private PairKillStats recentKillStats(UUID attackerId, UUID victimId, long now) {
        synchronized (recentKills) {
            pruneRecentKills(now);
            int attackerKillsVictim = 0;
            int victimKillsAttacker = 0;
            for (RecentKill kill : recentKills) {
                if (kill.attackerId().equals(attackerId) && kill.victimId().equals(victimId)) attackerKillsVictim++;
                if (kill.attackerId().equals(victimId) && kill.victimId().equals(attackerId)) victimKillsAttacker++;
            }
            return new PairKillStats(attackerKillsVictim, victimKillsAttacker);
        }
    }

    private void pruneRecentKills(long now) {
        long cutoff = now - repeatedKillWindowSeconds() * 1000L;
        while (!recentKills.isEmpty() && recentKills.peekFirst().time() < cutoff) recentKills.removeFirst();
    }

    private HazardAction findRelevantHazard(Player victim, String cause) {
        long now = System.currentTimeMillis();
        long windowMillis = Math.max(5,
                plugin.getConfig().getLong("combat.environmental-attribution-seconds", 20)) * 1000L;
        double radius = hazardRadius();
        synchronized (hazards) {
            pruneHazards(now);
            HazardAction best = null;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (HazardAction action : hazards) {
                if (!matchesCause(action.material(), cause)) continue;
                if (action.location().getWorld() != victim.getWorld()) continue;
                double distance = action.location().distance(victim.getLocation());
                if (distance > radius) continue;
                long age = Math.max(0L, now - action.time());

                // Recency receives more weight than raw proximity so a stale hazard one block
                // away does not automatically beat a very recent hazard two blocks away.
                double recencyScore = 1.0 - Math.min(1.0, age / (double) windowMillis);
                double distanceScore = 1.0 - Math.min(1.0, distance / radius);
                double score = recencyScore * 0.65 + distanceScore * 0.35;
                if (score > bestScore) {
                    best = action;
                    bestScore = score;
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
        if (c.contains("LAVA") || c.contains("FIRE")) {
            return material == Material.LAVA || material == Material.LAVA_BUCKET || material == Material.FIRE
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

    private void putSignal(Incident incident, CombatSignal.Rule rule, CombatSignal.Kind kind,
                           CombatSignal.Severity severity, double confidence, String detail) {
        CombatSignal candidate = new CombatSignal(rule, kind, severity,
                Math.max(0.0, Math.min(1.0, confidence)), LogUtils.cleanOneLine(detail));
        synchronized (incident.signals) {
            CombatSignal existing = incident.signals.get(rule);
            int candidateStrength = signalStrength(candidate);
            int existingStrength = existing == null ? -1 : signalStrength(existing);
            if (existing == null || candidateStrength > existingStrength
                    || (candidateStrength == existingStrength && candidate.confidence() > existing.confidence())) {
                incident.signals.put(rule, candidate);
            }
        }
    }

    private void mergeSignal(Incident incident, CombatSignal signal) {
        if (signal == null) return;
        putSignal(incident, signal.rule(), signal.kind(), signal.severity(), signal.confidence(), signal.detail());
    }

    private void removeSignal(Incident incident, CombatSignal.Rule rule) {
        synchronized (incident.signals) { incident.signals.remove(rule); }
    }

    private int signalStrength(CombatSignal signal) {
        int kind = switch (signal.kind()) {
            case ALERT -> 30;
            case CONTEXT -> 20;
            case MITIGATING -> 10;
        };
        int severity = switch (signal.severity()) {
            case HIGH -> 3;
            case MEDIUM -> 2;
            case LOW -> 1;
        };
        return kind + severity;
    }

    private String displaySignal(CombatSignal signal) {
        String prefix = signal.kind() == CombatSignal.Kind.ALERT
                ? "ALERT " + signal.severity()
                : signal.kind().name();
        return prefix + " — " + signal.detail();
    }

    private void recordAggression(UUID attacker, UUID victim, long now, double damage) {
        AggressionKey key = new AggressionKey(attacker, victim);
        recentAggression.compute(key, (ignored, current) -> {
            if (current == null || now - current.lastDamageAt > aggressionMemorySeconds() * 1000L) {
                return new AggressionState(now, Math.max(0.0, damage));
            }
            current.lastDamageAt = now;
            current.totalDamage += Math.max(0.0, damage);
            return current;
        });
    }

    private boolean hasRecentAggression(UUID attacker, UUID victim, long now) {
        AggressionState state = recentAggression.get(new AggressionKey(attacker, victim));
        return state != null && now - state.lastDamageAt <= aggressionMemorySeconds() * 1000L;
    }

    private void pruneAggression(long now) {
        long cutoff = now - aggressionMemorySeconds() * 1000L;
        recentAggression.entrySet().removeIf(entry -> entry.getValue().lastDamageAt < cutoff);
    }

    /**
     * Classifies environmental attribution and returns true only when the evidence is strong
     * enough to refresh the PvP clock used by combat-logout detection.
     */
    private boolean classifyEnvironmentalSignal(Incident incident, HazardAction source, Player victim,
                                                String movementNote, long now) {
        boolean towardSource = "toward-source".equals(movementNote);
        boolean awayFromSource = "away-from-source".equals(movementNote);
        boolean combatContext = hasRecentAggression(source.playerId(), victim.getUniqueId(), now)
                || hasRecentAggression(victim.getUniqueId(), source.playerId(), now);
        boolean water = source.material() == Material.WATER || source.material() == Material.WATER_BUCKET;

        // Voluntarily moving toward a hazard weakens attribution against its placer.
        if (towardSource) {
            putSignal(incident, CombatSignal.Rule.ENVIRONMENTAL_ATTACK, CombatSignal.Kind.MITIGATING,
                    CombatSignal.Severity.LOW, 0.70,
                    "Victim movement was toward the suspected hazard; that weakens a claim that the placer forced the environmental damage");
            return false;
        }

        if (water) {
            if (combatContext && awayFromSource) {
                putSignal(incident, CombatSignal.Rule.ENVIRONMENTAL_ATTACK, CombatSignal.Kind.ALERT,
                        CombatSignal.Severity.LOW, 0.50,
                        "Water-related damage followed a recent nearby player-created hazard during PvP while the victim moved away from the suspected source; provenance remains uncertain");
                return true;
            }
            putSignal(incident, CombatSignal.Rule.ENVIRONMENTAL_ATTACK, CombatSignal.Kind.CONTEXT,
                    CombatSignal.Severity.LOW, combatContext ? 0.45 : 0.30,
                    "Water-related damage occurred near a recent player bucket action; proximity alone is not treated as a moderation alert");
            return false;
        }

        if (combatContext && awayFromSource) {
            putSignal(incident, CombatSignal.Rule.ENVIRONMENTAL_ATTACK, CombatSignal.Kind.ALERT,
                    CombatSignal.Severity.MEDIUM, 0.72,
                    "Fire/lava damage followed a recent nearby player-created hazard during PvP while the victim moved away from the suspected source");
            return true;
        }
        if (combatContext) {
            putSignal(incident, CombatSignal.Rule.ENVIRONMENTAL_ATTACK, CombatSignal.Kind.ALERT,
                    CombatSignal.Severity.LOW, 0.55,
                    "Fire/lava damage occurred near a recent player-created hazard with recent PvP context, but movement evidence was inconclusive");
            return true;
        }

        putSignal(incident, CombatSignal.Rule.ENVIRONMENTAL_ATTACK, CombatSignal.Kind.CONTEXT,
                CombatSignal.Severity.LOW, 0.35,
                "Environmental damage occurred near a recent player hazard action; proximity alone is not treated as an alert");
        return false;
    }

    private void capturePreExistingNearbyItems(Incident incident) {
        if (incident.deathLocation == null || incident.deathLocation.getWorld() == null) return;
        incident.preExistingNearbyItemEntities.clear();
        for (Entity entity : incident.deathLocation.getWorld().getNearbyEntities(incident.deathLocation, 4.0, 4.0, 4.0)) {
            if (entity instanceof Item) incident.preExistingNearbyItemEntities.add(entity.getUniqueId());
        }
    }

    private void captureSpawnedDeathDrops(Incident incident) {
        if (incident.deathLocation == null || incident.deathLocation.getWorld() == null || incident.finalized) return;
        Map<String, Integer> remaining = new HashMap<>(incident.expectedDeathDrops);
        for (Entity entity : incident.deathLocation.getWorld().getNearbyEntities(incident.deathLocation, 4.0, 4.0, 4.0)) {
            if (!(entity instanceof Item item)) continue;
            if (incident.preExistingNearbyItemEntities.contains(item.getUniqueId())) continue;
            ItemStack stack = item.getItemStack();
            String material = stack.getType().name();
            String fingerprint = itemFingerprint(stack);
            int expected = remaining.getOrDefault(fingerprint, 0);
            if (expected <= 0) continue;
            int matched = Math.min(expected, stack.getAmount());
            incident.trackedDeathDrops.put(item.getUniqueId(), new TrackedDeathDrop(fingerprint, material, matched));
            incident.lootFingerprintLabels.put(fingerprint, material);
            remaining.put(fingerprint, expected - matched);
            incident.timeline.add(timelineLine(System.currentTimeMillis(),
                    "DEATH_DROP_ENTITY " + item.getUniqueId() + " " + matched + "x" + material));
        }
    }

    private boolean allTrackedLootReturned(Incident incident) {
        if (incident.lootedByPlayer.isEmpty()) return false;
        for (Map<String, Integer> items : incident.lootedByPlayer.values()) {
            for (int amount : items.values()) if (amount > 0) return false;
        }
        return true;
    }

    private String itemFingerprint(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR) return "AIR";
        ItemStack one = stack.clone();
        one.setAmount(1);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(one.serializeAsBytes());
    }

    private void writeIncidentAlias(String aliasId, String primaryId) {
        Path root = plugin.getCombatFolder();
        if (root == null) return;
        try {
            Files.createDirectories(root);
            Path alias = root.resolve(aliasId.replaceAll("[^A-Za-z0-9_-]", "_") + ".alias").normalize();
            if (alias.startsWith(root.normalize())) Files.writeString(alias, primaryId, StandardCharsets.UTF_8);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not persist combat incident alias " + aliasId + ": " + e.getMessage());
        }
    }

    private Map<String, String> parseStatusMap(String value) {
        Map<String, String> result = new LinkedHashMap<>();
        if (value == null || value.isBlank() || value.equalsIgnoreCase("unknown")) return result;
        for (String part : value.split(",\s*")) {
            int equals = part.indexOf('=');
            if (equals > 0) result.put(part.substring(0, equals).trim(), part.substring(equals + 1).trim());
        }
        return result;
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

    private long timelineSortKey(String line, long incidentStartedAt) {
        if (line == null || line.length() < 13 || line.charAt(0) != '[') return Long.MAX_VALUE;
        try {
            String[] parts = line.substring(1, 13).split("[:.]");
            long value = Long.parseLong(parts[0]) * 3_600_000L
                    + Long.parseLong(parts[1]) * 60_000L
                    + Long.parseLong(parts[2]) * 1_000L
                    + Long.parseLong(parts[3]);
            var start = Instant.ofEpochMilli(incidentStartedAt).atZone(ZoneId.systemDefault()).toLocalTime();
            long startValue = start.toSecondOfDay() * 1_000L + start.getNano() / 1_000_000L;
            if (value + 43_200_000L < startValue) value += 86_400_000L;
            return value;
        } catch (RuntimeException ignored) { return Long.MAX_VALUE; }
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

    private long incidentJoinWindowSeconds() {
        return Math.max(3, plugin.getConfig().getLong("combat.incident-link-seconds", 10));
    }

    private double incidentLinkRadius() {
        return Math.max(8.0, plugin.getConfig().getDouble("combat.incident-link-radius-blocks", 48.0));
    }

    private long aggressionMemorySeconds() {
        return Math.max(20, plugin.getConfig().getLong("combat.aggression-memory-seconds", 60));
    }

    private long combatLogoutReviewSeconds() {
        return Math.max(3, plugin.getConfig().getLong("combat.combat-logout-review-seconds", 10));
    }

    private void rememberEncounterRoot(Incident incident) {
        if (incident == null) return;
        List<UUID> participantIds;
        synchronized (incident.participants) {
            if (incident.participants.size() <= 2) return;
            participantIds = new ArrayList<>(incident.participants.keySet());
        }
        long now = incident.endedAt > 0L ? incident.endedAt : System.currentTimeMillis();
        Location anchor = incident.lastCombatLocation != null ? incident.lastCombatLocation : incident.location;
        if (anchor == null || anchor.getWorld() == null) return;
        RecentEncounterRoot link = new RecentEncounterRoot(incident.encounterRootId, now, anchor.clone());
        for (UUID participantId : participantIds) recentEncounterRoots.put(participantId, link);
        pruneEncounterRoots(now);
    }

    private void inheritEncounterRoot(Incident incident, Player attacker, Player victim, long now) {
        pruneEncounterRoots(now);
        RecentEncounterRoot attackerRoot = recentEncounterRoots.get(attacker.getUniqueId());
        RecentEncounterRoot victimRoot = recentEncounterRoots.get(victim.getUniqueId());
        if (attackerRoot == null || victimRoot == null) return;
        if (!attackerRoot.rootId().equals(victimRoot.rootId())) return;

        long maxAge = Math.max(30L, aggressionMemorySeconds()) * 1000L;
        if (now - attackerRoot.time() > maxAge || now - victimRoot.time() > maxAge) return;
        double radius = incidentLinkRadius() * 1.5;
        if (!sameWorldAndWithin(attacker.getLocation(), attackerRoot.location(), radius)
                || !sameWorldAndWithin(victim.getLocation(), victimRoot.location(), radius)) return;

        incident.encounterRootId = attackerRoot.rootId();
    }

    private void pruneEncounterRoots(long now) {
        long cutoff = now - Math.max(30L, aggressionMemorySeconds()) * 1000L;
        recentEncounterRoots.entrySet().removeIf(entry -> entry.getValue().time() < cutoff);
    }

    private boolean sameWorldAndWithin(Location a, Location b, double radius) {
        return a != null && b != null && a.getWorld() == b.getWorld() && a.distanceSquared(b) <= radius * radius;
    }

    private String safeToken(String value) {
        String clean = value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9_\\-]", "_");
        return clean.isBlank() ? "unknown" : clean;
    }

    private static String encodeSignals(List<CombatSignal> signals) {
        if (signals == null || signals.isEmpty()) return "None";
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        List<String> encoded = new ArrayList<>();
        for (CombatSignal signal : signals) {
            String detail = encoder.encodeToString(signal.detail().getBytes(StandardCharsets.UTF_8));
            encoded.add(signal.rule().name() + "," + signal.kind().name() + ","
                    + signal.severity().name() + "," + String.format(Locale.ROOT, "%.3f", signal.confidence())
                    + "," + detail);
        }
        return String.join(";", encoded);
    }

    private static List<CombatSignal> decodeSignals(String value) {
        if (value == null || value.isBlank() || value.equalsIgnoreCase("None")) return List.of();
        List<CombatSignal> signals = new ArrayList<>();
        Base64.Decoder decoder = Base64.getUrlDecoder();
        for (String encoded : value.split(";")) {
            String[] parts = encoded.split(",", 5);
            if (parts.length != 5) continue;
            try {
                CombatSignal.Rule rule = CombatSignal.Rule.valueOf(parts[0]);
                CombatSignal.Kind kind = CombatSignal.Kind.valueOf(parts[1]);
                CombatSignal.Severity severity = CombatSignal.Severity.valueOf(parts[2]);
                double confidence = Double.parseDouble(parts[3]);
                String detail = new String(decoder.decode(parts[4]), StandardCharsets.UTF_8);
                signals.add(new CombatSignal(rule, kind, severity, confidence, detail));
            } catch (RuntimeException ignored) {
                // Legacy/corrupt metadata should not prevent the incident from loading.
            }
        }
        return List.copyOf(signals);
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
