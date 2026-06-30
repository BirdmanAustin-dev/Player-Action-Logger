package com.playerlogger;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
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
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.FurnaceExtractEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public class EventListener implements Listener {
    private final PlayerActionLogger plugin;

    public EventListener(PlayerActionLogger plugin) {
        this.plugin = plugin;
    }

    @EventHandler public void onJoin(PlayerJoinEvent e) {
        boolean logIp = plugin.getConfig().getBoolean("logging.include-ip-addresses", false);
        String ip = e.getPlayer().getAddress() == null ? "unknown" : e.getPlayer().getAddress().getAddress().getHostAddress();
        plugin.log(e.getPlayer(), logIp ? "LOGIN from " + ip : "LOGIN");
    }

    @EventHandler public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        plugin.log(p, "LOGOUT");
        plugin.closeWriter(p.getUniqueId());
    }

    @EventHandler public void onKick(PlayerKickEvent e) {
        Player p = e.getPlayer();
        plugin.log(p, "KICKED: " + e.getReason());
        plugin.closeWriter(p.getUniqueId());
    }

    @EventHandler public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        plugin.log(p, String.format("DEATH: %s at %s", e.getDeathMessage(), LogUtils.loc(p.getLocation())));
    }

    @EventHandler public void onChat(AsyncChatEvent e) {
        if (plugin.getConfig().getBoolean("logging.include-chat", true)) {
            // Paper-native chat event. Convert Adventure Component to plain text for moderation logs.
            String message = PlainTextComponentSerializer.plainText().serialize(e.message());
            plugin.logPlayer(e.getPlayer().getUniqueId(), e.getPlayer().getName(), "CHAT: " + message);
        }
    }

    @EventHandler public void onCommand(PlayerCommandPreprocessEvent e) {
        if (plugin.getConfig().getBoolean("logging.include-commands", true)) plugin.log(e.getPlayer(), "CMD: " + e.getMessage());
    }

    @EventHandler public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        plugin.log(e.getPlayer(), String.format("BREAK %s at %s", b.getType(), LogUtils.loc(b)));
    }

    @EventHandler public void onPlace(BlockPlaceEvent e) {
        Block b = e.getBlock();
        plugin.log(e.getPlayer(), String.format("PLACE %s at %s", b.getType(), LogUtils.loc(b)));
    }

    @EventHandler public void onBurn(BlockBurnEvent e) {
        Block b = e.getBlock();
        plugin.logSystem(String.format("BURN %s at %s", b.getType(), LogUtils.loc(b)));
    }

    @EventHandler public void onIgnite(BlockIgniteEvent e) {
        Block b = e.getBlock();
        String log = String.format("IGNITE %s at %s cause:%s", b.getType(), LogUtils.loc(b), e.getCause());
        if (e.getPlayer() != null) plugin.log(e.getPlayer(), log); else plugin.logSystem(log);
    }

    @EventHandler public void onDecay(LeavesDecayEvent e) {
        Block b = e.getBlock();
        plugin.logSystem(String.format("DECAY %s at %s", b.getType(), LogUtils.loc(b)));
    }

    @EventHandler public void onGrow(BlockGrowEvent e) {
        Block b = e.getBlock();
        plugin.logSystem(String.format("GROW %s at %s", b.getType(), LogUtils.loc(b)));
    }

    @EventHandler public void onSpread(BlockSpreadEvent e) {
        Block s = e.getSource();
        Block b = e.getBlock();
        plugin.logSystem(String.format("SPREAD %s from %s to %s", s.getType(), LogUtils.loc(s), LogUtils.loc(b)));
    }

    @EventHandler public void onFlow(BlockFromToEvent e) {
        // This is now quiet by default. BUCKET_EMPTY/BUCKET_FILL still capture player-caused water/lava usage.
        plugin.noteFluidFlow(e.getBlock(), e.getToBlock());
    }

    @EventHandler public void onPistonExtend(BlockPistonExtendEvent e) {
        Block p = e.getBlock();
        StringBuilder sb = new StringBuilder(String.format("PISTON_EXT at %s moved:", LogUtils.loc(p)));
        e.getBlocks().forEach(b -> sb.append(' ').append(b.getType()).append('@').append(LogUtils.loc(b)));
        plugin.logSystem(sb.toString());
    }

    @EventHandler public void onPistonRetract(BlockPistonRetractEvent e) {
        Block p = e.getBlock();
        StringBuilder sb = new StringBuilder(String.format("PISTON_RET at %s moved:", LogUtils.loc(p)));
        e.getBlocks().forEach(b -> sb.append(' ').append(b.getType()).append('@').append(LogUtils.loc(b)));
        plugin.logSystem(sb.toString());
    }

    @EventHandler public void onSign(SignChangeEvent e) {
        Block b = e.getBlock();
        String[] lines = e.getLines();
        plugin.log(e.getPlayer(), String.format("SIGN at %s: [%s|%s|%s|%s]", LogUtils.loc(b),
                LogUtils.cleanOneLine(lines[0]), LogUtils.cleanOneLine(lines[1]),
                LogUtils.cleanOneLine(lines[2]), LogUtils.cleanOneLine(lines[3])));
    }

    @EventHandler public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        Block b = e.getClickedBlock();
        Action a = e.getAction();
        if (b != null && (a == Action.RIGHT_CLICK_BLOCK || a == Action.LEFT_CLICK_BLOCK)) {
            Material m = b.getType();
            if (LogUtils.isInteractive(m)) plugin.log(p, String.format("INTERACT %s with %s at %s", a, m, LogUtils.loc(b)));
        }
        ItemStack item = e.getItem();
        if (item != null && b != null) {
            Material m = item.getType();
            if (m.name().contains("BUCKET")) plugin.log(p, String.format("BUCKET_USE %s at %s", m, LogUtils.loc(b)));
            if (m == Material.FLINT_AND_STEEL || m == Material.FIRE_CHARGE) plugin.log(p, String.format("FIRE_TOOL %s at %s", m, LogUtils.loc(b)));
        }
    }

    @EventHandler public void onBedEnter(PlayerBedEnterEvent e) {
        Block b = e.getBed();
        plugin.log(e.getPlayer(), String.format("BED_ENTER at %s", LogUtils.loc(b)));
    }

    @EventHandler public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        Block b = e.getBlock();
        plugin.log(e.getPlayer(), String.format("BUCKET_EMPTY %s at %s", e.getBucket(), LogUtils.loc(b)));
    }

    @EventHandler public void onBucketFill(PlayerBucketFillEvent e) {
        Block b = e.getBlock();
        plugin.log(e.getPlayer(), String.format("BUCKET_FILL %s at %s", e.getBucket(), LogUtils.loc(b)));
    }

    @EventHandler public void onPickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Item item = e.getItem();
        ItemStack stack = item.getItemStack();
        Location l = item.getLocation();
        plugin.log(p, String.format("PICKUP %s at %s", LogUtils.formatItem(stack), LogUtils.loc(l)));
    }

    @EventHandler public void onDrop(PlayerDropItemEvent e) {
        Item item = e.getItemDrop();
        ItemStack stack = item.getItemStack();
        plugin.log(e.getPlayer(), String.format("DROP %s at %s", LogUtils.formatItem(stack), LogUtils.loc(item.getLocation())));
    }

    @EventHandler public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        ItemStack item = e.getCurrentItem();
        if (item == null || item.getType() == Material.AIR) return;
        Inventory inv = e.getClickedInventory();
        if (inv == null) return;
        plugin.log(p, String.format("INV_CLICK %s in %s slot:%d item:%s", e.getAction(), inv.getType().name(), e.getSlot(), LogUtils.formatItem(item)));
    }

    @EventHandler public void onCraft(CraftItemEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        ItemStack item = e.getCurrentItem();
        if (item != null) plugin.log(p, String.format("CRAFT %s", LogUtils.formatItem(item)));
    }

    @EventHandler public void onConsume(PlayerItemConsumeEvent e) {
        plugin.log(e.getPlayer(), String.format("CONSUME %s", LogUtils.formatItem(e.getItem())));
    }

    @EventHandler public void onFurnaceExtract(FurnaceExtractEvent e) {
        plugin.log(e.getPlayer(), String.format("FURNACE_EXTRACT %dx%s", e.getItemAmount(), e.getItemType()));
    }

    @EventHandler public void onEntityDeath(EntityDeathEvent e) {
        Player killer = e.getEntity().getKiller();
        if (killer != null) {
            Entity ent = e.getEntity();
            plugin.log(killer, String.format("KILL %s at %s", ent.getType(), LogUtils.loc(ent.getLocation())));
        }
    }

    @EventHandler public void onEntityChange(EntityChangeBlockEvent e) {
        Block b = e.getBlock();
        Entity ent = e.getEntity();
        if (ent instanceof Player p) plugin.log(p, String.format("ENT_CHANGE %s to %s at %s", b.getType(), e.getTo(), LogUtils.loc(b)));
        else plugin.logSystem(String.format("ENT_CHANGE %s changed %s to %s at %s", ent.getType(), b.getType(), e.getTo(), LogUtils.loc(b)));
    }

    @EventHandler public void onExplosion(EntityExplodeEvent e) {
        Entity ent = e.getEntity();
        Location l = e.getLocation();
        plugin.logSystem(String.format("EXPLOSION %s at %s blocks:%d", ent.getType(), LogUtils.loc(l), e.blockList().size()));
    }

    @EventHandler public void onHangingPlace(HangingPlaceEvent e) {
        if (e.getPlayer() == null) return;
        Hanging h = e.getEntity();
        plugin.log(e.getPlayer(), String.format("HANG_PLACE %s at %s", h.getType(), LogUtils.loc(h.getLocation())));
    }

    @EventHandler public void onHangingBreak(HangingBreakByEntityEvent e) {
        if (!(e.getRemover() instanceof Player p)) return;
        Hanging h = e.getEntity();
        plugin.log(p, String.format("HANG_BREAK %s at %s", h.getType(), LogUtils.loc(h.getLocation())));
    }

    @EventHandler public void onPortalCreate(PortalCreateEvent e) {
        plugin.logSystem(String.format("PORTAL_CREATE %s in %s with %d blocks", e.getReason(), e.getWorld().getName(), e.getBlocks().size()));
    }

    @EventHandler public void onStructureGrow(StructureGrowEvent e) {
        Location l = e.getLocation();
        String log = String.format("TREE_GROW %s at %s blocks:%d", e.getSpecies(), LogUtils.loc(l), e.getBlocks().size());
        if (e.getPlayer() != null) plugin.log(e.getPlayer(), log); else plugin.logSystem(log);
    }

    @EventHandler public void onTeleport(PlayerTeleportEvent e) {
        if (e.getCause() == PlayerTeleportEvent.TeleportCause.COMMAND
                || e.getCause() == PlayerTeleportEvent.TeleportCause.PLUGIN
                || e.getCause() == PlayerTeleportEvent.TeleportCause.ENDER_PEARL) {
            plugin.log(e.getPlayer(), String.format("TELEPORT %s from %s to %s", e.getCause(), LogUtils.loc(e.getFrom()), LogUtils.loc(e.getTo())));
        }
    }

    @EventHandler public void onVelocity(PlayerVelocityEvent e) {
        // Left intentionally quiet. This event can be extremely noisy; useful to keep here as a future moderation hook.
    }
}
