package org.kotsasmin.keyValueChecker.quarantine;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
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
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.kotsasmin.keyValueChecker.config.ConfigManager;

import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class QuarantineManager implements Listener {
    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private final Map<UUID, QuarantineState> quarantined = new ConcurrentHashMap<>();

    private static class QuarantineState {
        final boolean wasInvulnerable;

        QuarantineState(boolean wasInvulnerable) {
            this.wasInvulnerable = wasInvulnerable;
        }
    }

    public QuarantineManager(JavaPlugin plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
    }

    public boolean isQuarantined(UUID uuid) {
        return quarantined.containsKey(uuid);
    }

    public void quarantinePlayer(Player player) {
        if (!configManager.isQuarantineEnabled()) return;
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> quarantinePlayer(player));
            return;
        }
        if (quarantined.containsKey(player.getUniqueId())) return;

        boolean wasInvulnerable = player.isInvulnerable();
        quarantined.put(player.getUniqueId(), new QuarantineState(wasInvulnerable));

        player.setInvulnerable(true);

        if (configManager.isQuarantineSensoryBlackout()) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 20 * 60, 1, false, false, false));
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

        QuarantineState state = quarantined.remove(player.getUniqueId());
        if (state == null) return;

        if (player.isOnline()) {
            player.setInvulnerable(state.wasInvulnerable);

            if (configManager.isQuarantineSensoryBlackout()) {
                player.removePotionEffect(PotionEffectType.BLINDNESS);
                try {
                    player.removePotionEffect(PotionEffectType.DARKNESS);
                } catch (Throwable ignored) {}
            }

            player.clearTitle();

            if (sendCompletedMessage) {
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

        QuarantineState state = quarantined.remove(player.getUniqueId());
        if (state == null) return;

        if (player.isOnline()) {
            player.setInvulnerable(state.wasInvulnerable);
            if (configManager.isQuarantineSensoryBlackout()) {
                player.removePotionEffect(PotionEffectType.BLINDNESS);
                try {
                    player.removePotionEffect(PotionEffectType.DARKNESS);
                } catch (Throwable ignored) {}
            }
            player.clearTitle();
        }
    }

    public void releaseAll() {
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, this::releaseAll);
            return;
        }

        for (UUID uuid : quarantined.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                releasePlayer(player, false);
            }
        }
        quarantined.clear();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!isQuarantined(event.getPlayer().getUniqueId())) return;

        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;

        // An kounithei apo ti thesi tou, ton kratame sto idio X, Y, Z alla epitrepoume na gyrizei camera
        if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
            Location locked = from.clone();
            locked.setYaw(to.getYaw());
            locked.setPitch(to.getPitch());
            event.setTo(locked);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event) {
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
        if (isQuarantined(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        if (isQuarantined(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerSwapHandItems(PlayerSwapHandItemsEvent event) {
        if (isQuarantined(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (isQuarantined(event.getWhoClicked().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (isQuarantined(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player && isQuarantined(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player && isQuarantined(event.getDamager().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onFoodLevelChange(FoodLevelChangeEvent event) {
        if (isQuarantined(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityTarget(EntityTargetLivingEntityEvent event) {
        if (event.getTarget() instanceof Player && isQuarantined(event.getTarget().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerQuit(PlayerQuitEvent event) {
        cleanupPlayer(event.getPlayer());
    }
}
