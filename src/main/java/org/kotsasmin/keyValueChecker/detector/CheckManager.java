package org.kotsasmin.keyValueChecker.detector;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.nbt.NBTByte;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.nbt.NBTList;
import com.github.retrooper.packetevents.protocol.nbt.NBTString;
import com.github.retrooper.packetevents.protocol.nbt.NBTType;
import com.github.retrooper.packetevents.protocol.world.blockentity.BlockEntityTypes;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockEntityData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCloseWindow;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenSignEditor;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenWindow;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.kotsasmin.keyValueChecker.config.ConfigManager;
import org.kotsasmin.keyValueChecker.data.DataManager;
import org.kotsasmin.keyValueChecker.data.PlayerRecord;
import org.kotsasmin.keyValueChecker.data.TranslationCheckItem;
import org.kotsasmin.keyValueChecker.webhook.DiscordWebhookNotifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CheckManager {
    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private final DataManager dataManager;
    private final DiscordWebhookNotifier webhookNotifier;

    private final Map<UUID, CheckData> checkingPlayers = new ConcurrentHashMap<>();

    public CheckManager(JavaPlugin plugin, ConfigManager configManager, DataManager dataManager, DiscordWebhookNotifier webhookNotifier) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.dataManager = dataManager;
        this.webhookNotifier = webhookNotifier;
    }

    public void handlePlayerJoin(Player player) {
        // an o paiktis einai whitelisted, skip
        if (dataManager.isWhitelisted(player.getName())) {
            plugin.getLogger().info("[KVC] Skipped check for whitelisted player: " + player.getName());
            return;
        }

        // skip gia bedrock / floodgate giati den exoun ta java translations
        if (isBedrockPlayer(player.getUniqueId())) {
            plugin.getLogger().info("[KVC] Skipped check for Bedrock player: " + player.getName());
            return;
        }

        // perimenoume ligo delay prin ksekinisei to check
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && !configManager.getCheckQueue().isEmpty()) {
                plugin.getLogger().info("[KVC] Starting check for player: " + player.getName());
                runDetectionBatch(player, 0);
            }
        }, configManager.getSendDelayTicks());
    }

    public void handlePlayerQuit(Player player) {
        CheckData check = checkingPlayers.remove(player.getUniqueId());
        if (check != null && check.getTimeoutTask() != null) {
            check.getTimeoutTask().cancel();
        }
    }

    public void cancelAllChecks() {
        for (CheckData check : checkingPlayers.values()) {
            if (check.getTimeoutTask() != null) {
                check.getTimeoutTask().cancel();
            }
        }
        checkingPlayers.clear();
    }

    public CheckData getActiveCheck(UUID uuid) {
        return checkingPlayers.get(uuid);
    }

    public CheckData removeActiveCheck(UUID uuid) {
        return checkingPlayers.remove(uuid);
    }

    public void runDetectionBatch(Player player, int startIndex) {
        List<TranslationCheckItem> checkQueue = configManager.getCheckQueue();
        if (startIndex >= checkQueue.size()) {
            return; // Finished checking all keys
        }

        // Place sign above head to guarantee loaded chunk
        Location loc = player.getLocation().clone();
        loc.setY(0);

        Vector3i v3i = new Vector3i(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());

        // 1 - Send Fake Sign Block Change
        int signStateId = WrappedBlockState.getByString(Material.OAK_SIGN.createBlockData().getAsString()).getGlobalId();
        WrapperPlayServerBlockChange blockChange = new WrapperPlayServerBlockChange(v3i, signStateId);
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, blockChange);

        // 2 - Build NBT Data for Sign
        List<NBTCompound> messagesList = new ArrayList<>();
        List<TranslationCheckItem> currentKeys = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            if (startIndex + i < checkQueue.size()) {
                TranslationCheckItem item = checkQueue.get(startIndex + i);
                currentKeys.add(item);

                NBTCompound translationMessage = new NBTCompound();
                translationMessage.setTag("translate", new NBTString(item.getKey()));
                translationMessage.setTag("fallback", new NBTString("fallb"));
                messagesList.add(translationMessage);
            } else {
                currentKeys.add(null);

                NBTCompound emptyMessage = new NBTCompound();
                emptyMessage.setTag("text", new NBTString(""));
                messagesList.add(emptyMessage);
            }
        }

        NBTList<NBTCompound> messages = new NBTList<>(NBTType.COMPOUND, messagesList);
        NBTCompound text = new NBTCompound();
        text.setTag("messages", messages);
        text.setTag("color", new NBTString("black"));
        text.setTag("has_glowing_text", new NBTByte((byte) 0));

        NBTCompound nbt = new NBTCompound();
        nbt.setTag("id", new NBTString("minecraft:sign"));
        nbt.setTag("front_text", text);
        nbt.setTag("back_text", text);
        nbt.setTag("is_waxed", new NBTByte((byte) 0));

        // 3 - Send Block Entity Data (NBT) to apply translation components
        WrapperPlayServerBlockEntityData blockEntityData = new WrapperPlayServerBlockEntityData(v3i, BlockEntityTypes.SIGN, nbt);
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, blockEntityData);

        // 4 - Send Open Sign Editor
        WrapperPlayServerOpenSignEditor openSign = new WrapperPlayServerOpenSignEditor(v3i, true);
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, openSign);

        // 5 - Send a fake container window to force the client to replace the sign screen, triggering UPDATE_SIGN silently
        WrapperPlayServerOpenWindow openWindow = new WrapperPlayServerOpenWindow(1, 0, Component.empty());
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, openWindow);

        // 6 - Instantly close the fake window
        WrapperPlayServerCloseWindow closeWindow = new WrapperPlayServerCloseWindow(1);
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, closeWindow);

        // 7 - Instantly restore the block
        clearFakeBlock(player, v3i);

        // Safety timeout
        BukkitTask timeoutTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            CheckData staleData = checkingPlayers.remove(player.getUniqueId());
            if (staleData != null) {
                // an den apantisei se 3 deuterolepta, mallon to kovei to cheat
                plugin.getLogger().warning("[KVC] FLAG: " + player.getName() + " blocked sign update. Possible cheat.");

                if (configManager.isKickOnBlockedCheck()) {
                    enforceAction(player, "Blocked check", false);
                } else {
                    PlayerRecord record = dataManager.getOrCreateRecord(player.getUniqueId(), player.getName(), "Blocked check");
                    record.addDetectedMod("Blocked check");
                    record.setLastIncidentTime(System.currentTimeMillis());
                    record.setLastAction("Blocked check");
                    dataManager.saveData(true);

                    if (configManager.isDiscordSendOnBlockedCheck()) {
                        webhookNotifier.sendWebhook(player, "Blocked check", "Blocked sign update");
                    }

                    // Notify admins without kicking
                    String alertFormat = configManager.getBlockedCheckAdminAlertFormat();
                    if (alertFormat != null && !alertFormat.isEmpty()) {
                        String formattedAlert = alertFormat.replace("%player%", player.getName()).replace("%mod%", "Blocked check");
                        Component adminAlert = LegacyComponentSerializer.legacyAmpersand().deserialize(formattedAlert);
                        for (Player p : Bukkit.getOnlinePlayers()) {
                            if (p.hasPermission("keyvaluechecker.notify")) {
                                p.sendMessage(adminAlert);
                            }
                        }
                    }

                    // Continue to next batch
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (player.isOnline()) {
                            runDetectionBatch(player, staleData.getStartIndex() + 4);
                        }
                    });
                }
            }
        }, 60L);

        checkingPlayers.put(player.getUniqueId(), new CheckData(startIndex, currentKeys, v3i, timeoutTask));
    }

    private void clearFakeBlock(Player player, Vector3i loc) {
        // epanafora tou kanonikou block sto client
        if (player.isOnline()) {
            Location bukkitLoc = new Location(player.getWorld(), loc.getX(), loc.getY(), loc.getZ());
            int globalId = WrappedBlockState.getByString(player.getWorld().getBlockAt(bukkitLoc).getBlockData().getAsString()).getGlobalId();
            WrapperPlayServerBlockChange restore = new WrapperPlayServerBlockChange(loc, globalId);
            PacketEvents.getAPI().getPlayerManager().sendPacket(player, restore);
        }
    }

    public void enforceAction(Player player, String modName, boolean isMissingRequired) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;

            String actionLabel = isMissingRequired ? "Missing Required Mod" : "Kicked";
            PlayerRecord record = dataManager.getOrCreateRecord(player.getUniqueId(), player.getName(), actionLabel);
            record.setLastAction(actionLabel);
            record.setLastIncidentTime(System.currentTimeMillis());
            dataManager.saveData(true);

            String webhookAction = isMissingRequired ? "Kicked (Missing Required Mod)" : "Kicked";
            if (!"Blocked check".equals(modName) || configManager.isDiscordSendOnBlockedCheck()) {
                webhookNotifier.sendWebhook(player, webhookAction, String.join(", ", record.getDetectedMods()));
            }

            String actionType = configManager.getActionType(isMissingRequired);
            List<String> content = configManager.getActionContent(isMissingRequired);

            if (content == null || content.isEmpty()) return;

            Component finalMessage = Component.empty();
            for (String line : content) {
                String formatted = line.replace("%mod%", modName).replace("%player%", player.getName());
                if (!finalMessage.equals(Component.empty())) {
                    finalMessage = finalMessage.append(Component.newline());
                }
                finalMessage = finalMessage.append(LegacyComponentSerializer.legacyAmpersand().deserialize(formatted));
            }

            if ("message".equalsIgnoreCase(actionType)) {
                player.sendMessage(finalMessage);
            } else if ("command".equalsIgnoreCase(actionType)) {
                for (String line : content) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line.replace("%mod%", modName).replace("%player%", player.getName()));
                }
            } else {
                player.kick(finalMessage);
            }

            // Notify admins in-game
            String alertFormat = configManager.getAdminAlertFormat(isMissingRequired);
            if (alertFormat != null && !alertFormat.isEmpty()) {
                String formattedAlert = alertFormat.replace("%player%", player.getName()).replace("%mod%", modName);
                Component adminAlert = LegacyComponentSerializer.legacyAmpersand().deserialize(formattedAlert);
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.hasPermission("keyvaluechecker.notify")) {
                        p.sendMessage(adminAlert);
                    }
                }
            }
        }, 5L);
    }

    private boolean isBedrockPlayer(UUID uuid) {
        // elegxos me reflection gia floodgate/geyser xwris hard dependency
        try {
            Class<?> floodgateApiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object api = floodgateApiClass.getMethod("getInstance").invoke(null);
            if ((boolean) floodgateApiClass.getMethod("isFloodgatePlayer", UUID.class).invoke(api, uuid)) {
                return true;
            }
        } catch (Exception ignored) {}

        try {
            Class<?> geyserApiClass = Class.forName("org.geysermc.geyser.api.GeyserApi");
            Object api = geyserApiClass.getMethod("api").invoke(null);
            if ((boolean) geyserApiClass.getMethod("isBedrockPlayer", UUID.class).invoke(api, uuid)) {
                return true;
            }
        } catch (Exception ignored) {}

        // Fallback check for default Floodgate UUID format
        return uuid.toString().startsWith("00000000-0000-0000-");
    }
}
