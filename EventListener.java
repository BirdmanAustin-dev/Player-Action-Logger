package com.playerlogger;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Tameable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.FurnaceExtractEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.projectiles.ProjectileSource;

import java.util.Locale;

public class EventListener implements Listener {
    private final PlayerActionLogger plugin;

    private record ResolvedDamager(Player player, String attribution, double confidence) { }

    public EventListener(PlayerActionLogger plugin) {
        this.plugin = plugin;
    }

    private ActivityTracker activity() {
        return plugin.getActivityTracker();
    }

    private CombatManager combat() {
        return plugin.getCombatManager();
    }

    @EventHandler public void onJoin(PlayerJoinEvent e) {
        boolean logIp = plugin.getConfig().getBoolean("logging.include-ip-addresses", false);
        String ip = e.getPlayer().getAddress() == null ? "unknown" : e.getPlayer().getAddress().getAddress().getHostAddress();
        String location = LogUtils.loc(e.getPlayer().getLocation());
        LogRecord evidence = plugin.log(e.getPlayer(), logIp ? "LOGIN from " + ip + " at " + location : "LOGIN at " + location);
        activity().record(e.getPlayer(), ActivityTracker.Kind.LOGIN, evidence);
        combat().onJoin(e.getPlayer());
    }

    @EventHandler public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        combat().onDisconnect(p, CombatManager.DisconnectReason.QUIT);
        LogRecord evidence = plugin.log(p, "LOGOUT at " + LogUtils.loc(p.getLocation()));
        activity().record(p, ActivityTracker.Kind.LOGOUT, evidence);
        plugin.closeWriter(p.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onKick(PlayerKickEvent e) {
        Player p = e.getPlayer();
        combat().onDisconnect(p, CombatManager.DisconnectReason.KICK);
        String reason = PlainTextComponentSerializer.plainText().serialize(e.reason());
        LogRecord evidence = plugin.log(p, "KICKED: " + reason + " at " + LogUtils.loc(p.getLocation()));
        activity().record(p, ActivityTracker.Kind.LOGOUT, evidence);
        plugin.closeWriter(p.getUniqueId());
    }

    @EventHandler public void onRespawn(PlayerRespawnEvent e) {
        combat().onRespawn(e.getPlayer(), e.getRespawnLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        String message = e.deathMessage() == null ? "null"
                : PlainTextComponentSerializer.plainText().serialize(e.deathMessage());
        LogRecord evidence = plugin.log(p, String.format("DEATH: %s at %s", message, LogUtils.loc(p.getLocation())));
        activity().record(p, ActivityTracker.Kind.DEATH, evidence);
        combat().onPlayerDeath(p, p.getKiller(), message, e.getDrops());
    }

    @EventHandler public void onChat(AsyncChatEvent e) {
        LogRecord evidence;
        if (plugin.getConfig().getBoolean("logging.include-chat", true)) {
            String message = PlainTextComponentSerializer.plainText().serialize(e.message());
            evidence = plugin.logPlayer(e.getPlayer().getUniqueId(), e.getPlayer().getName(), "CHAT: " + message);
        }
        else evidence = null;
        // AsyncChatEvent is asynchronous; capture dashboard activity back on the server thread.
        plugin.getServer().getScheduler().runTask(plugin,
                () -> activity().record(e.getPlayer(), ActivityTracker.Kind.CHAT, evidence));
    }

    @EventHandler public void onCommand(PlayerCommandPreprocessEvent e) {
        LogRecord evidence = plugin.getConfig().getBoolean("logging.include-commands", true)
                ? plugin.log(e.getPlayer(), "CMD: " + e.getMessage()) : null;
        activity().record(e.getPlayer(), ActivityTracker.Kind.COMMAND, evidence);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        LogRecord evidence = plugin.log(e.getPlayer(), String.format("BREAK %s at %s", b.getType(), LogUtils.loc(b)));
        activity().record(e.getPlayer(), ActivityTracker.Kind.BLOCK_BREAK, b.getType(), b, evidence);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onPlace(BlockPlaceEvent e) {
        Block b = e.getBlock();
        LogRecord evidence = plugin.log(e.getPlayer(), String.format("PLACE %s at %s", b.getType(), LogUtils.loc(b)));
        activity().record(e.getPlayer(), ActivityTracker.Kind.BLOCK_PLACE, b.getType(), b, evidence);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onBurn(BlockBurnEvent e) {
        Block b = e.getBlock();
        plugin.logSystem(String.format("BURN %s at %s", b.getType(), LogUtils.loc(b)));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onIgnite(BlockIgniteEvent e) {
        Block b = e.getBlock();
        String log = String.format("IGNITE %s at %s cause:%s", b.getType(), LogUtils.loc(b), e.getCause());
        if (e.getPlayer() != null) {
            LogRecord evidence = plugin.log(e.getPlayer(), log);
            activity().record(e.getPlayer(), ActivityTracker.Kind.FIRE, b.getType(), b, evidence);
            combat().recordHazard(e.getPlayer(), Material.FIRE, b.getLocation());
        } else plugin.logSystem(log);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onDecay(LeavesDecayEvent e) {
        Block b = e.getBlock();
        plugin.logSystem(String.format("DECAY %s at %s", b.getType(), LogUtils.loc(b)));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onGrow(BlockGrowEvent e) {
        Block b = e.getBlock();
        plugin.logSystem(String.format("GROW %s at %s", b.getType(), LogUtils.loc(b)));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onSpread(BlockSpreadEvent e) {
        Block s = e.getSource();
        Block b = e.getBlock();
        plugin.logSystem(String.format("SPREAD %s from %s to %s", s.getType(), LogUtils.loc(s), LogUtils.loc(b)));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onFlow(BlockFromToEvent e) {
        plugin.noteFluidFlow(e.getBlock(), e.getToBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onPistonExtend(BlockPistonExtendEvent e) {
        Block p = e.getBlock();
        StringBuilder sb = new StringBuilder(String.format("PISTON_EXT at %s moved:", LogUtils.loc(p)));
        e.getBlocks().forEach(b -> sb.append(' ').append(b.getType()).append('@').append(LogUtils.loc(b)));
        plugin.logSystem(sb.toString());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onPistonRetract(BlockPistonRetractEvent e) {
        Block p = e.getBlock();
        StringBuilder sb = new StringBuilder(String.format("PISTON_RET at %s moved:", LogUtils.loc(p)));
        e.getBlocks().forEach(b -> sb.append(' ').append(b.getType()).append('@').append(LogUtils.loc(b)));
        plugin.logSystem(sb.toString());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onSign(SignChangeEvent e) {
        Block b = e.getBlock();
        String[] lines = e.lines().stream()
                .map(PlainTextComponentSerializer.plainText()::serialize)
                .toArray(String[]::new);
        plugin.log(e.getPlayer(), String.format("SIGN at %s: [%s|%s|%s|%s]", LogUtils.loc(b),
                LogUtils.cleanOneLine(lines[0]), LogUtils.cleanOneLine(lines[1]),
                LogUtils.cleanOneLine(lines[2]), LogUtils.cleanOneLine(lines[3])));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        Block b = e.getClickedBlock();
        Action a = e.getAction();
        if (b != null && (a == Action.RIGHT_CLICK_BLOCK || a == Action.LEFT_CLICK_BLOCK)
                && e.useInteractedBlock() != Event.Result.DENY) {
            Material m = b.getType();
            if (LogUtils.isInteractive(m)) {
                LogRecord evidence = plugin.log(p, String.format("INTERACT %s with %s at %s", a, m, LogUtils.loc(b)));
                activity().record(p, ActivityTracker.Kind.INTERACT, m, b, evidence);
            }
        }
        ItemStack item = e.getItem();
        if (item != null && b != null) {
            Material m = item.getType();
            if (m.name().contains("BUCKET")) {
                // Informational only. Successful bucket state changes are counted by PlayerBucket*Event.
                plugin.log(p, String.format("BUCKET_USE %s at %s", m, LogUtils.loc(b)));
            }
            if (m == Material.FLINT_AND_STEEL || m == Material.FIRE_CHARGE) {
                // Informational only. Successful ignition is counted by BlockIgniteEvent.
                plugin.log(p, String.format("FIRE_TOOL %s at %s", m, LogUtils.loc(b)));
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBedEnter(PlayerBedEnterEvent e) {
        Event.Result useBed = e.useBed();
        boolean succeeds = useBed == Event.Result.ALLOW
                || (useBed == Event.Result.DEFAULT
                && e.enterAction().problem() == null
                && e.enterAction().canSleep().success());
        if (!succeeds) return;
        Block b = e.getBed();
        plugin.log(e.getPlayer(), String.format("BED_ENTER at %s", LogUtils.loc(b)));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        Block b = e.getBlock();
        LogRecord evidence = plugin.log(e.getPlayer(), String.format("BUCKET_EMPTY %s at %s", e.getBucket(), LogUtils.loc(b)));
        activity().record(e.getPlayer(), ActivityTracker.Kind.BUCKET, e.getBucket(), b, evidence);
        combat().recordHazard(e.getPlayer(), e.getBucket(), b.getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onBucketFill(PlayerBucketFillEvent e) {
        Block b = e.getBlock();
        LogRecord evidence = plugin.log(e.getPlayer(), String.format("BUCKET_FILL %s at %s", e.getBucket(), LogUtils.loc(b)));
        activity().record(e.getPlayer(), ActivityTracker.Kind.BUCKET, e.getBucket(), b, evidence);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onPickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Item item = e.getItem();
        ItemStack stack = item.getItemStack();
        Location l = item.getLocation();
        LogRecord evidence = plugin.log(p, String.format("PICKUP %s at %s", LogUtils.formatItem(stack), LogUtils.loc(l)));
        activity().record(p, ActivityTracker.Kind.ITEM, evidence);
        combat().onItemPickup(p, item, Math.max(1, stack.getAmount() - e.getRemaining()), l);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onDrop(PlayerDropItemEvent e) {
        Item item = e.getItemDrop();
        ItemStack stack = item.getItemStack();
        LogRecord evidence = plugin.log(e.getPlayer(), String.format("DROP %s at %s", LogUtils.formatItem(stack), LogUtils.loc(item.getLocation())));
        activity().record(e.getPlayer(), ActivityTracker.Kind.ITEM, evidence);
        combat().onItemDrop(e.getPlayer(), item, item.getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        Inventory clicked = e.getClickedInventory();
        if (clicked == null) return;

        Inventory top = e.getView().getTopInventory();
        boolean containerOnly = plugin.getConfig().getBoolean("logging.inventory.container-actions-only", true);
        boolean trackedContainer = isTrackedContainer(top);

        if (!trackedContainer) {
            if (!containerOnly && plugin.getConfig().getBoolean("logging.inventory.include-player-inventory-clicks", false)) {
                ItemStack item = e.getCurrentItem();
                if (item != null && item.getType() != Material.AIR) {
                    LogRecord evidence = plugin.log(p, String.format("INV_CLICK %s in %s slot:%d item:%s", e.getAction(),
                            clicked.getType().name(), e.getSlot(), LogUtils.formatItem(item)));
                    activity().record(p, ActivityTracker.Kind.INVENTORY, evidence);
                }
            }
            return;
        }

        boolean clickedTop = clicked.equals(top);
        // Ordinary clicks in the player's own inventory remain quiet even while a
        // chest is open. Shift-clicking into the container is still recorded.
        if (!clickedTop && !e.isShiftClick()) return;

        String action = e.getAction().name();
        String container = containerName(top);
        Location location = top.getLocation() == null ? p.getLocation() : top.getLocation();

        if (clickedTop && action.startsWith("HOTBAR_") && e.getHotbarButton() >= 0) {
            ItemStack put = p.getInventory().getItem(e.getHotbarButton());
            ItemStack take = e.getCurrentItem();
            String putText = put == null || put.getType() == Material.AIR ? "EMPTY" : LogUtils.formatItem(put);
            String takeText = take == null || take.getType() == Material.AIR ? "EMPTY" : LogUtils.formatItem(take);
            LogRecord evidence = plugin.log(p, String.format("CONTAINER_SWAP put:%s take:%s container:%s action:%s slot:%d at %s",
                    putText, takeText, container, e.getAction(), e.getSlot(), LogUtils.loc(location)));
            activity().record(p, ActivityTracker.Kind.INVENTORY, container, location.getBlock(), evidence);
            return;
        }
        if (clickedTop && action.equals("SWAP_WITH_CURSOR")) {
            ItemStack put = e.getCursor();
            ItemStack take = e.getCurrentItem();
            String putText = put == null || put.getType() == Material.AIR ? "EMPTY" : LogUtils.formatItem(put);
            String takeText = take == null || take.getType() == Material.AIR ? "EMPTY" : LogUtils.formatItem(take);
            LogRecord evidence = plugin.log(p, String.format("CONTAINER_SWAP put:%s take:%s container:%s action:%s slot:%d at %s",
                    putText, takeText, container, e.getAction(), e.getSlot(), LogUtils.loc(location)));
            activity().record(p, ActivityTracker.Kind.INVENTORY, container, location.getBlock(), evidence);
            return;
        }

        String direction;
        if (clickedTop && action.contains("PLACE")) {
            direction = "CONTAINER_PUT";
        } else if (!clickedTop && e.isShiftClick() && action.equals("MOVE_TO_OTHER_INVENTORY")) {
            direction = "CONTAINER_PUT";
        } else if (clickedTop && (action.contains("PICKUP") || action.equals("MOVE_TO_OTHER_INVENTORY")
                || action.contains("DROP"))) {
            direction = "CONTAINER_TAKE";
        } else {
            direction = "CONTAINER_MOVE";
        }

        ItemStack item;
        if (clickedTop && action.startsWith("HOTBAR_") && e.getHotbarButton() >= 0) {
            item = p.getInventory().getItem(e.getHotbarButton());
        } else if (clickedTop && (action.contains("PLACE") || action.equals("SWAP_WITH_CURSOR"))) {
            item = e.getCursor();
        } else {
            item = e.getCurrentItem();
        }
        if (item == null || item.getType() == Material.AIR) return;

        LogRecord evidence = plugin.log(p, String.format("%s %s container:%s action:%s slot:%d at %s", direction,
                LogUtils.formatItem(item), container, e.getAction(), e.getSlot(), LogUtils.loc(location)));
        activity().record(p, ActivityTracker.Kind.INVENTORY, container, location.getBlock(), evidence);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onInventoryDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        Inventory top = e.getView().getTopInventory();
        if (!isTrackedContainer(top)) return;
        boolean touchesContainer = e.getRawSlots().stream().anyMatch(slot -> slot >= 0 && slot < top.getSize());
        if (!touchesContainer) return;
        ItemStack item = e.getOldCursor();
        if (item == null || item.getType() == Material.AIR) return;
        String container = containerName(top);
        Location location = top.getLocation() == null ? p.getLocation() : top.getLocation();
        LogRecord evidence = plugin.log(p, String.format("CONTAINER_DRAG %s container:%s slots:%d at %s",
                LogUtils.formatItem(item), container, e.getRawSlots().size(), LogUtils.loc(location)));
        activity().record(p, ActivityTracker.Kind.INVENTORY, container, location.getBlock(), evidence);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onCraft(CraftItemEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        ItemStack item = e.getCurrentItem();
        if (item != null) {
            String mode = e.isShiftClick() ? "CRAFT_RECIPE_SHIFT" : "CRAFT_RECIPE";
            String note = e.isShiftClick() ? " output-total:variable" : "";
            LogRecord evidence = plugin.log(p, String.format("%s result:%s%s", mode, LogUtils.formatItem(item), note));
            activity().record(p, ActivityTracker.Kind.CRAFT, evidence);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onConsume(PlayerItemConsumeEvent e) {
        LogRecord evidence = plugin.log(e.getPlayer(), String.format("CONSUME %s", LogUtils.formatItem(e.getItem())));
        activity().record(e.getPlayer(), ActivityTracker.Kind.CONSUME, e.getItem().getType(), e.getPlayer().getLocation().getBlock(), evidence);
    }

    @EventHandler public void onFurnaceExtract(FurnaceExtractEvent e) {
        LogRecord evidence = plugin.log(e.getPlayer(), String.format("FURNACE_EXTRACT %dx%s", e.getItemAmount(), e.getItemType()));
        activity().record(e.getPlayer(), ActivityTracker.Kind.FURNACE, evidence);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onBreed(EntityBreedEvent e) {
        if (!(e.getBreeder() instanceof Player player)) return;
        LogRecord evidence = plugin.log(player, String.format("BREED %s at %s", e.getEntity().getType(), LogUtils.loc(e.getEntity().getLocation())));
        activity().record(player, ActivityTracker.Kind.BREED, evidence);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onShear(PlayerShearEntityEvent e) {
        LogRecord evidence = plugin.log(e.getPlayer(), String.format("SHEAR %s at %s", e.getEntity().getType(), LogUtils.loc(e.getEntity().getLocation())));
        activity().record(e.getPlayer(), ActivityTracker.Kind.ANIMAL, evidence);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof LivingEntity victim)) return;
        double finalDamage = event.getFinalDamage();
        if (finalDamage <= 0) return;
        double victimHealthAfter = Math.max(0.0, victim.getHealth() - finalDamage);

        if (event instanceof EntityDamageByEntityEvent byEntity) {
            ResolvedDamager resolved = resolvePlayerDamager(byEntity.getDamager());
            Player playerDamager = resolved == null ? null : resolved.player();

            if (victim instanceof Player victimPlayer && playerDamager != null && !playerDamager.equals(victimPlayer)) {
                combat().handlePvpHit(playerDamager, victimPlayer, event.getCause().name(),
                        weaponDescription(playerDamager, byEntity.getDamager()), finalDamage, victimHealthAfter,
                        resolved.attribution(), resolved.confidence());
                return;
            }

            if (playerDamager != null && !(victim instanceof Player)) {
                if (!plugin.getConfig().getBoolean("combat.pve.enabled", true)) return;
                if (!plugin.getConfig().getBoolean("combat.pve.log-passive-mobs", false) && victim instanceof Animals) return;
                String line = String.format(Locale.ROOT,
                        "PVE_HIT target:%s damage:%.1f target_health:%s weapon:%s at %s",
                        victim.getType(), finalDamage, healthText(victimHealthAfter, maxHealth(victim)),
                        weaponDescription(playerDamager, byEntity.getDamager()), LogUtils.loc(victim.getLocation()));
                LogRecord evidence = plugin.log(playerDamager, line);
                activity().record(playerDamager, ActivityTracker.Kind.PVE, evidence);
                return;
            }

            if (victim instanceof Player victimPlayer) {
                String source = sourceDescription(byEntity.getDamager());
                LogRecord evidence = plugin.log(victimPlayer, String.format(Locale.ROOT,
                        "PVE_DAMAGE source:%s damage:%.1f health:%s cause:%s at %s",
                        source, finalDamage, healthText(victimHealthAfter, maxHealth(victimPlayer)),
                        event.getCause(), LogUtils.loc(victimPlayer.getLocation())));
                activity().record(victimPlayer, ActivityTracker.Kind.PVE, evidence);
                return;
            }
        }

        if (victim instanceof Player player) {
            combat().handleEnvironmentalDamage(player, event.getCause().name(), finalDamage, victimHealthAfter);
        }
    }


    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onFish(PlayerFishEvent e) {
        if (e.getState() != PlayerFishEvent.State.CAUGHT_FISH
                && e.getState() != PlayerFishEvent.State.CAUGHT_ENTITY) return;
        String caught = e.getCaught() == null ? e.getState().name() : e.getCaught().getType().name();
        LogRecord evidence = plugin.log(e.getPlayer(), String.format("FISH %s at %s", caught, LogUtils.loc(e.getPlayer().getLocation())));
        activity().record(e.getPlayer(), ActivityTracker.Kind.FISH, evidence);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onEntityDeath(EntityDeathEvent e) {
        // Player deaths are handled by PlayerDeathEvent/CombatManager. Do not also label
        // them as generic "KILL PLAYER" PvE history.
        if (e.getEntity() instanceof Player) return;
        Player killer = e.getEntity().getKiller();
        if (killer != null) {
            Entity ent = e.getEntity();
            plugin.log(killer, String.format("KILL %s at %s", ent.getType(), LogUtils.loc(ent.getLocation())));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onEntityChange(EntityChangeBlockEvent e) {
        Block b = e.getBlock();
        Entity ent = e.getEntity();
        if (ent instanceof Player p) plugin.log(p, String.format("ENT_CHANGE %s to %s at %s", b.getType(), e.getTo(), LogUtils.loc(b)));
        else plugin.logSystem(String.format("ENT_CHANGE %s changed %s to %s at %s", ent.getType(), b.getType(), e.getTo(), LogUtils.loc(b)));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onExplosion(EntityExplodeEvent e) {
        Entity ent = e.getEntity();
        Location l = e.getLocation();
        plugin.logSystem(String.format("EXPLOSION %s at %s blocks:%d", ent.getType(), LogUtils.loc(l), e.blockList().size()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onHangingPlace(HangingPlaceEvent e) {
        if (e.getPlayer() == null) return;
        Hanging h = e.getEntity();
        plugin.log(e.getPlayer(), String.format("HANG_PLACE %s at %s", h.getType(), LogUtils.loc(h.getLocation())));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onHangingBreak(HangingBreakByEntityEvent e) {
        if (!(e.getRemover() instanceof Player p)) return;
        Hanging h = e.getEntity();
        plugin.log(p, String.format("HANG_BREAK %s at %s", h.getType(), LogUtils.loc(h.getLocation())));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onPortalCreate(PortalCreateEvent e) {
        plugin.logSystem(String.format("PORTAL_CREATE %s in %s with %d blocks", e.getReason(), e.getWorld().getName(), e.getBlocks().size()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onStructureGrow(StructureGrowEvent e) {
        Location l = e.getLocation();
        String log = String.format("TREE_GROW %s at %s blocks:%d", e.getSpecies(), LogUtils.loc(l), e.getBlocks().size());
        if (e.getPlayer() != null) plugin.log(e.getPlayer(), log); else plugin.logSystem(log);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onTeleport(PlayerTeleportEvent e) {
        combat().onTeleport(e.getPlayer());
        LogRecord evidence = null;
        if (e.getCause() == PlayerTeleportEvent.TeleportCause.COMMAND
                || e.getCause() == PlayerTeleportEvent.TeleportCause.PLUGIN
                || e.getCause() == PlayerTeleportEvent.TeleportCause.ENDER_PEARL) {
            evidence = plugin.log(e.getPlayer(), String.format("TELEPORT %s from %s to %s", e.getCause(), LogUtils.loc(e.getFrom()), LogUtils.loc(e.getTo())));
        }
        activity().record(e.getPlayer(), ActivityTracker.Kind.TELEPORT, evidence);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onMove(PlayerMoveEvent e) {
        combat().onMove(e.getPlayer(), e.getFrom(), e.getTo());
        if (e.getTo() != null && (e.getFrom().getChunk().getX() != e.getTo().getChunk().getX()
                || e.getFrom().getChunk().getZ() != e.getTo().getChunk().getZ()
                || e.getFrom().getWorld() != e.getTo().getWorld())) {
            activity().recordChunkTransition(e.getPlayer());
        }
    }

    @EventHandler public void onVelocity(PlayerVelocityEvent e) {
        // Intentionally quiet. Movement packets and velocity changes are not written to logs.
    }

    private boolean isTrackedContainer(Inventory inventory) {
        if (inventory == null) return false;
        InventoryType type = inventory.getType();
        if (type == InventoryType.PLAYER || type == InventoryType.CRAFTING || type == InventoryType.CREATIVE) {
            return false;
        }
        if (inventory.getLocation() != null) {
            String block = inventory.getLocation().getBlock().getType().name();
            if (block.contains("CHEST") || block.contains("BARREL") || block.contains("SHULKER")
                    || block.contains("HOPPER") || block.contains("DISPENSER") || block.contains("DROPPER")) {
                return true;
            }
        }
        String name = type.name();
        return name.contains("CHEST") || name.contains("BARREL") || name.contains("SHULKER")
                || name.contains("HOPPER") || name.contains("DISPENSER") || name.contains("DROPPER")
                || name.contains("ENDER_CHEST");
    }

    private String containerName(Inventory inventory) {
        if (inventory != null && inventory.getLocation() != null) {
            String block = inventory.getLocation().getBlock().getType().name();
            if (!block.equals("AIR")) return block;
        }
        return inventory == null ? "UNKNOWN" : inventory.getType().name();
    }

    private ResolvedDamager resolvePlayerDamager(Entity damager) {
        if (damager instanceof Player player) return new ResolvedDamager(player, "direct player damage", 1.0);
        if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player player) return new ResolvedDamager(player, "player-fired projectile", 0.95);
            if (shooter instanceof Tameable tameable && tameable.getOwner() instanceof Player player) {
                return new ResolvedDamager(player, "projectile attributed through a tamed entity", 0.65);
            }
        }
        if (damager instanceof Tameable tameable && tameable.getOwner() instanceof Player player) {
            return new ResolvedDamager(player, "damage attributed through a tamed entity", 0.65);
        }
        if (damager instanceof TNTPrimed tnt && tnt.getSource() instanceof Player player) {
            return new ResolvedDamager(player, "delayed TNT attributed to its source player", 0.65);
        }
        return null;
    }

    private String sourceDescription(Entity damager) {
        if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Entity entity) return entity.getType().name() + "/" + projectile.getType().name();
            return projectile.getType().name();
        }
        return damager.getType().name();
    }

    private String weaponDescription(Player attacker, Entity directDamager) {
        if (directDamager instanceof Projectile projectile) {
            return projectile.getType().name() + "/" + mainHand(attacker);
        }
        if (directDamager instanceof TNTPrimed) return "TNT";
        if (directDamager instanceof Tameable) return directDamager.getType().name();
        return mainHand(attacker);
    }

    private String mainHand(Player player) {
        ItemStack item = player.getInventory().getItemInMainHand();
        return item == null || item.getType() == Material.AIR ? "EMPTY_HAND" : item.getType().name();
    }

    private double maxHealth(LivingEntity entity) {
        AttributeInstance attribute = entity.getAttribute(Attribute.MAX_HEALTH);
        return attribute == null ? 20.0 : attribute.getValue();
    }

    private String healthText(double health, double max) {
        return String.format(Locale.ROOT, "%.1f/%.1f", Math.max(0.0, health), max);
    }
}
