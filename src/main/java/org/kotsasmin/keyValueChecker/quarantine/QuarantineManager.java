package org.kotsasmin.keyValueChecker.quarantine;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.potion.PotionTypes;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerRemoveEntityEffect;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.kotsasmin.keyValueChecker.config.ConfigManager;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class QuarantineManager implements Listener {
    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private final Set<UUID> quarantined = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> kvcBlindnessApplied = new ConcurrentHashMap<>();

    public QuarantineManager(JavaPlugin plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
    }

    public boolean isQuarantined(UUID uuid) {
        if (!configManager.isQuarantineEnabled() || quarantined.isEmpty()) {
            return false;
        }
        return quarantined.contains(uuid);
    }

    public void quarantinePlayer(Player player) {
        if (!configManager.isQuarantineEnabled()) return;
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> quarantinePlayer(player));
            return;
        }
        if (quarantined.contains(player.getUniqueId())) return;

        quarantined.add(player.getUniqueId());

        // Self-heal opwsdipote an o paiktis eixe meinei me invulnerable apo proigoumena bugs
        if (player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE) {
            player.setInvulnerable(false);
        }

        if (configManager.isQuarantineSensoryBlackout()) {
            kvcBlindnessApplied.put(player.getUniqueId(), System.currentTimeMillis());
            // 15 seconds safety duration (anti gia 60s) wste an kati paei strava na fygei grigora
            player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 20 * 15, 1, false, false, false));
        }

        String title = configManager.getQuarantineTitle();
        String subtitle = configManager.getQuarantineSubtitle();
        boolean hasTitle = title != null && !title.trim().isEmpty();
        boolean hasSubtitle = subtitle != null && !subtitle.trim().isEmpty();
        if (hasTitle || hasSubtitle) {
            Component titleComp = hasTitle ? LegacyComponentSerializer.legacyAmpersand().deserialize(title) : Component.empty();
            Component subtitleComp = hasSubtitle ? LegacyComponentSerializer.legacyAmpersand().deserialize(subtitle) : Component.empty();
            Title.Times times = Title.Times.times(Duration.ofMillis(250), Duration.ofMillis(4000), Duration.ofMillis(500));
            player.showTitle(Title.title(titleComp, subtitleComp, times));
        }
    }

    public void releasePlayer(Player player, boolean sendCompletedMessage) {
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> releasePlayer(player, sendCompletedMessage));
            return;
        }

        boolean wasQuarantined = quarantined.remove(player.getUniqueId());

        if (player.isOnline()) {
            if (player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE) {
                player.setInvulnerable(false);
            }

            // Panta katharizoume ta effects an o paiktis eixe parei blindness apo to KVC
            forceClearEffects(player);

            player.clearTitle();

            if (wasQuarantined && sendCompletedMessage) {
                String completedMsg = configManager.getQuarantineCompletedMessage();
                if (completedMsg != null && !completedMsg.isEmpty()) {
                    player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(completedMsg));
                }
            }
        }
    }

    public void cleanupPlayer(Player player) {
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> cleanupPlayer(player));
            return;
        }

        UUID uuid = player.getUniqueId();
        quarantined.remove(uuid);
        forceClearEffects(player);
        kvcBlindnessApplied.remove(uuid);

        if (player.isOnline()) {
            if (player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE) {
                player.setInvulnerable(false);
            }
            player.clearTitle();
        }
    }

    public void forceClearEffects(Player player) {
        UUID uuid = player.getUniqueId();
        if (!kvcBlindnessApplied.containsKey(uuid)) return;
        if (!player.isOnline()) {
            kvcBlindnessApplied.remove(uuid);
            return;
        }

        // 1. Bukkit server-side removal
        clearBukkitEffects(player);

        // 2. Direct PacketEvents removal packet (diefkolynei 1.8 - 1.21 client sync sto Netty)
        sendPacketEventsClear(player);

        // 3. Lightweight scheduled retries gia Multiverse world transfers & ViaVersion cross-version client respawn packet sync
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && !isQuarantined(uuid)) {
                clearBukkitEffects(player);
                sendPacketEventsClear(player);
            }
        }, 4L);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && !isQuarantined(uuid)) {
                clearBukkitEffects(player);
                sendPacketEventsClear(player);
            }
        }, 12L);

        // Katharizoume to tracking map meta apo 15 sec (oso to max duration tou effect) gia na mhn kratietai mnhmh
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            kvcBlindnessApplied.remove(uuid);
        }, 300L);
    }

    private void clearBukkitEffects(Player player) {
        if (player.hasPotionEffect(PotionEffectType.BLINDNESS)) {
            player.removePotionEffect(PotionEffectType.BLINDNESS);
        }
        try {
            if (player.hasPotionEffect(PotionEffectType.DARKNESS)) {
                player.removePotionEffect(PotionEffectType.DARKNESS);
            }
        } catch (Throwable ignored) {}
    }

    private void sendPacketEventsClear(Player player) {
        try {
            WrapperPlayServerRemoveEntityEffect removeBlindness =
                    new WrapperPlayServerRemoveEntityEffect(player.getEntityId(), PotionTypes.BLINDNESS);
            PacketEvents.getAPI().getPlayerManager().sendPacket(player, removeBlindness);
        } catch (Throwable ignored) {}

        try {
            WrapperPlayServerRemoveEntityEffect removeDarkness =
                    new WrapperPlayServerRemoveEntityEffect(player.getEntityId(), PotionTypes.DARKNESS);
            PacketEvents.getAPI().getPlayerManager().sendPacket(player, removeDarkness);
        } catch (Throwable ignored) {}
    }

    public void releaseAll() {
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, this::releaseAll);
            return;
        }

        for (UUID uuid : quarantined) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                releasePlayer(player, false);
            }
        }
        quarantined.clear();
        kvcBlindnessApplied.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // Self-heal opoiodipote playerdata eixe apothikeusei Invulnerable: 1b sto Survival/Adventure
        if (player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE) {
            if (player.isInvulnerable()) {
                player.setInvulnerable(false);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        if (!configManager.isQuarantineEnabled() || kvcBlindnessApplied.isEmpty()) return;
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (isQuarantined(uuid)) {
            // An akoma ginetai scan sto neo kosmo kai exei sensory blackout,
            // refresh to effect sto client meta to world load
            if (configManager.isQuarantineSensoryBlackout()) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (player.isOnline() && isQuarantined(uuid)) {
                        player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 20 * 15, 1, false, false, false));
                    }
                }, 5L);
            }
        } else if (kvcBlindnessApplied.containsKey(uuid)) {
            // An to scan teleiwse kai o paiktis metaferthike sto Multiverse,
            // katharizoume to blindness amesws
            forceClearEffects(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (!configManager.isQuarantineEnabled() || kvcBlindnessApplied.isEmpty()) return;
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (!isQuarantined(uuid) && kvcBlindnessApplied.containsKey(uuid)) {
            forceClearEffects(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        // Fast-path: 99.9% tou xronou kanenas paiktis den einai quarantined -> 0 cost!
        if (quarantined.isEmpty()) return;

        // MHN koveis to PlayerTeleportEvent (p.x. Multiverse teleports se allo kosmo h spawn teleports)
        if (event instanceof PlayerTeleportEvent) return;

        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;

        // Optimization: An mono to yaw/pitch allakse (koitazei gyrw gyrw), epitrepoume amesws
        if (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ()) {
            return;
        }

        // Optimization: An allazei kosmo
        if (from.getWorld() != null && to.getWorld() != null && !from.getWorld().equals(to.getWorld())) {
            return;
        }

        if (!isQuarantined(event.getPlayer().getUniqueId())) return;

        // An kounithei apo ti thesi tou, ton kratame sto idio X, Y, Z alla epitrepoume na gyrizei camera
        Location locked = from.clone();
        locked.setYaw(to.getYaw());
        locked.setPitch(to.getPitch());
        event.setTo(locked);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event) {
        if (quarantined.isEmpty()) return;
        Player player = event.getPlayer();
        if (!isQuarantined(player.getUniqueId())) return;

        event.setCancelled(true);
        String msg = configManager.getQuarantineBlockedCommandMessage();
        if (msg != null && !msg.isEmpty()) {
            player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(msg));
        }
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent event) {
        if (quarantined.isEmpty()) return;
        Player player = event.getPlayer();
        if (!isQuarantined(player.getUniqueId())) return;

        event.setCancelled(true);
        String msg = configManager.getQuarantineBlockedChatMessage();
        if (msg != null && !msg.isEmpty()) {
            player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(msg));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (quarantined.isEmpty()) return;
        if (isQuarantined(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        if (quarantined.isEmpty()) return;
        if (isQuarantined(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerSwapHandItems(PlayerSwapHandItemsEvent event) {
        if (quarantined.isEmpty()) return;
        if (isQuarantined(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (quarantined.isEmpty()) return;
        if (isQuarantined(event.getWhoClicked().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (quarantined.isEmpty()) return;
        if (isQuarantined(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityDamage(EntityDamageEvent event) {
        if (quarantined.isEmpty()) return;
        if (event.getEntity() instanceof Player && isQuarantined(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (quarantined.isEmpty()) return;
        if (event.getDamager() instanceof Player && isQuarantined(event.getDamager().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onFoodLevelChange(FoodLevelChangeEvent event) {
        if (quarantined.isEmpty()) return;
        if (isQuarantined(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityTarget(EntityTargetLivingEntityEvent event) {
        if (quarantined.isEmpty()) return;
        if (event.getTarget() instanceof Player && isQuarantined(event.getTarget().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerQuit(PlayerQuitEvent event) {
        cleanupPlayer(event.getPlayer());
    }
}
