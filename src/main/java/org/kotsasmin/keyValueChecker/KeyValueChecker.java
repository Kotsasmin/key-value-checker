package org.kotsasmin.keyValueChecker;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.nbt.NBTByte;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.nbt.NBTList;
import com.github.retrooper.packetevents.protocol.nbt.NBTString;
import com.github.retrooper.packetevents.protocol.nbt.NBTType;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.world.blockentity.BlockEntityTypes;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUpdateSign;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockEntityData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCloseWindow;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenWindow;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenSignEditor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

public final class KeyValueChecker extends JavaPlugin implements Listener, CommandExecutor {

    private List<String> translationKeys;
    private int sendDelay;

    private static class PlayerRecord {
        String playerName;
        long lastIncidentTime;
        String lastAction;
        List<String> detectedMods;

        PlayerRecord(String playerName, long lastIncidentTime, String lastAction, List<String> detectedMods) {
            this.playerName = playerName;
            this.lastIncidentTime = lastIncidentTime;
            this.lastAction = lastAction;
            this.detectedMods = detectedMods;
        }
    }

    private final Map<UUID, PlayerRecord> persistentRecords = new ConcurrentHashMap<>();
    private File dataFile;
    private FileConfiguration dataConfig;
    
    private static class CheckData {
        int startIndex;
        List<String> currentKeys;
        Vector3i signLoc;
        BukkitTask timeoutTask;

        CheckData(int startIndex, List<String> currentKeys, Vector3i signLoc, BukkitTask timeoutTask) {
            this.startIndex = startIndex;
            this.currentKeys = currentKeys;
            this.signLoc = signLoc;
            this.timeoutTask = timeoutTask;
        }
    }

    private final Map<UUID, CheckData> checkingPlayers = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfigValues();
        loadData();

        // Register listener directly to the server's PacketEvents plugin
        PacketEvents.getAPI().getEventManager().registerListener(new PacketListenerAbstract() {
            @Override
            public void onPacketReceive(PacketReceiveEvent event) {
                if (event.getPacketType() == PacketType.Play.Client.UPDATE_SIGN) {
                    Player player = (Player) event.getPlayer();
                    CheckData check = checkingPlayers.get(player.getUniqueId());
                    
                    if (check == null) return;

                    WrapperPlayClientUpdateSign updateSign = new WrapperPlayClientUpdateSign(event);
                    if (updateSign.getBlockPosition().equals(check.signLoc)) {
                        // Cancel the vanilla server processing to bypass "non-editable" warnings
                        event.setCancelled(true);
                        check.timeoutTask.cancel();
                        checkingPlayers.remove(player.getUniqueId());

                        boolean flag = false;
                        String detectedMod = null;
                        String[] lines = updateSign.getTextLines();

                        for (int i = 0; i < check.currentKeys.size(); i++) {
                            String key = check.currentKeys.get(i);
                            if (key == null) continue;

                            String clientText = i < lines.length ? lines[i] : "";
                            
                            if (clientText != null && !clientText.isEmpty() && !clientText.equals("fallb") && !clientText.equals(key)) {
                                flag = true;
                                detectedMod = key;
                                PlayerRecord record = persistentRecords.computeIfAbsent(player.getUniqueId(), k -> new PlayerRecord(player.getName(), System.currentTimeMillis(), "detected", new ArrayList<>()));
                                if (!record.detectedMods.contains(key)) record.detectedMods.add(key);
                                record.lastIncidentTime = System.currentTimeMillis();
                                getLogger().warning("[KVC] FLAG: " + player.getName() + " is using " + key);
                                break; // Only need to detect one per batch to flag
                            }
                        }

                        if (flag) {
                            enforceAction(player, detectedMod);
                        } else {
                            Bukkit.getScheduler().runTask(KeyValueChecker.this, () -> {
                                if (player.isOnline()) {
                                    runDetectionBatch(player, check.startIndex + 4);
                                }
                            });
                        }
                    }
                }
            }
        });

        getServer().getPluginManager().registerEvents(this, this);
        getCommand("kvc-list").setExecutor(this);
        getCommand("kvc-reload").setExecutor(this);
        
        getLogger().info("KeyValueChecker enabled via pure PacketEvents NBT.");
    }

    private void loadConfigValues() {
        reloadConfig();
        translationKeys = getConfig().getStringList("translation-keys");
        if (getConfig().contains("initial-check-delay-seconds")) {
            sendDelay = getConfig().getInt("initial-check-delay-seconds", 1) * 20;
        } else {
            sendDelay = getConfig().getInt("send-delay-ticks", 20);
        }
    }

    private void loadData() {
        dataFile = new File(getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            try {
                dataFile.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
        persistentRecords.clear();
        if (dataConfig.contains("records")) {
            for (String uuidStr : dataConfig.getConfigurationSection("records").getKeys(false)) {
                UUID uuid = UUID.fromString(uuidStr);
                String name = dataConfig.getString("records." + uuidStr + ".name");
                long time = dataConfig.getLong("records." + uuidStr + ".time");
                String action = dataConfig.getString("records." + uuidStr + ".action");
                List<String> mods = dataConfig.getStringList("records." + uuidStr + ".mods");
                persistentRecords.put(uuid, new PlayerRecord(name, time, action, mods));
            }
        }
    }

    private void saveData() {
        dataConfig.set("records", null);
        for (Map.Entry<UUID, PlayerRecord> entry : persistentRecords.entrySet()) {
            String path = "records." + entry.getKey().toString();
            dataConfig.set(path + ".name", entry.getValue().playerName);
            dataConfig.set(path + ".time", entry.getValue().lastIncidentTime);
            dataConfig.set(path + ".action", entry.getValue().lastAction);
            dataConfig.set(path + ".mods", entry.getValue().detectedMods);
        }
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                dataConfig.save(dataFile);
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }

    private String formatTimeAgo(long timestamp) {
        long diffSeconds = (System.currentTimeMillis() - timestamp) / 1000;
        if (diffSeconds < 60) return diffSeconds + " secs ago";
        if (diffSeconds < 3600) return (diffSeconds / 60) + " mins ago";
        if (diffSeconds < 86400) return (diffSeconds / 3600) + " hours ago";
        return (diffSeconds / 86400) + " days ago";
    }

    private void sendWebhook(Player player, String action, String modsStr) {
        if (!getConfig().getBoolean("discord-webhook.enabled", false)) return;
        
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                String url = getConfig().getString("discord-webhook.url", "");
                if (url.isEmpty()) return;
                
                String username = getConfig().getString("discord-webhook.username", "KeyValueChecker");
                String avatarUrl = getConfig().getString("discord-webhook.avatar-url", "").replace("%player%", player.getName());
                int color = getConfig().getInt("discord-webhook.embed.color", 16711680);
                String title = getConfig().getString("discord-webhook.embed.title", "Cheater Detected!");
                String desc = getConfig().getString("discord-webhook.embed.description", "").replace("%player%", player.getName()).replace("%action%", action).replace("%mods%", modsStr);
                String footer = getConfig().getString("discord-webhook.embed.footer", "");
                
                username = username.replace("\"", "\\\"");
                avatarUrl = avatarUrl.replace("\"", "\\\"");
                title = title.replace("\"", "\\\"");
                desc = desc.replace("\"", "\\\"").replace("\n", "\\n");
                footer = footer.replace("\"", "\\\"");
                
                String payload = "{"
                    + "\"username\": \"" + username + "\","
                    + "\"avatar_url\": \"" + avatarUrl + "\","
                    + "\"embeds\": [{"
                    + "\"title\": \"" + title + "\","
                    + "\"description\": \"" + desc + "\","
                    + "\"color\": " + color + ","
                    + "\"footer\": {\"text\": \"" + footer + "\"}"
                    + "}]"
                    + "}";

                HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
                
                client.send(request, HttpResponse.BodyHandlers.discarding());
            } catch (Exception e) {
                getLogger().warning("[KVC] Failed to send Discord webhook: " + e.getMessage());
            }
        });
    }

    @Override
    public void onDisable() {
        if (dataFile != null) {
            saveData();
        }
    }
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("kvc-reload")) {
            if (sender.hasPermission("keyvaluechecker.reload")) {
                loadConfigValues();
                sender.sendMessage("§aKeyValueChecker config reloaded.");
                return true;
            }
        }
        if (command.getName().equalsIgnoreCase("kvc-list")) {
            if (sender.hasPermission("keyvaluechecker.list")) {
                if (args.length > 0 && args[0].equalsIgnoreCase("rotate")) {
                    persistentRecords.clear();
                    saveData();
                    sender.sendMessage("§a[KVC] Log history wiped successfully.");
                    return true;
                }

                sender.sendMessage("§6--- Detected Mods History ---");
                if (persistentRecords.isEmpty()) {
                    sender.sendMessage("§eNo detections on record.");
                } else {
                    for (Map.Entry<UUID, PlayerRecord> entry : persistentRecords.entrySet()) {
                        PlayerRecord rec = entry.getValue();
                        String timeAgo = formatTimeAgo(rec.lastIncidentTime);
                        sender.sendMessage("§c" + rec.playerName + " §7[" + timeAgo + "] §8(Last: " + rec.lastAction + ")");
                        sender.sendMessage("  §7Flags: §e" + String.join(", ", rec.detectedMods));
                    }
                }
                return true;
            }
        }
        return false;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        if (isBedrockPlayer(player.getUniqueId())) {
            getLogger().info("[KVC] Skipped check for Bedrock player: " + player.getName());
            return;
        }

        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (player.isOnline() && !translationKeys.isEmpty()) {
                getLogger().info("[KVC] Starting check for player: " + player.getName());
                runDetectionBatch(player, 0);
            }
        }, sendDelay);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        CheckData check = checkingPlayers.remove(id);
        if (check != null) {
            check.timeoutTask.cancel();
        }
    }

    private boolean isBedrockPlayer(UUID uuid) {
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

    private void runDetectionBatch(Player player, int startIndex) {
        if (startIndex >= translationKeys.size()) {
            return; // Finished checking all keys
        }

        // Place sign above head to guarantee loaded chunk
        Location loc = player.getLocation().clone();
        loc.setY(0);
        
        Vector3i v3i = new Vector3i(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());

        // 1. Send Fake Sign Block Change
        int signStateId = WrappedBlockState.getByString(Material.OAK_SIGN.createBlockData().getAsString()).getGlobalId();
        WrapperPlayServerBlockChange blockChange = new WrapperPlayServerBlockChange(v3i, signStateId);
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, blockChange);

        // 2. Build NBT Data for Sign
        List<NBTCompound> messagesList = new ArrayList<>();
        List<String> currentKeys = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            if (startIndex + i < translationKeys.size()) {
                String key = translationKeys.get(startIndex + i);
                currentKeys.add(key);
                
                NBTCompound translationMessage = new NBTCompound();
                translationMessage.setTag("translate", new NBTString(key));
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

        // 3. Send Block Entity Data (NBT) to apply translation components
        WrapperPlayServerBlockEntityData blockEntityData = new WrapperPlayServerBlockEntityData(v3i, BlockEntityTypes.SIGN, nbt);
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, blockEntityData);

        // 4. Send Open Sign Editor
        WrapperPlayServerOpenSignEditor openSign = new WrapperPlayServerOpenSignEditor(v3i, true);
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, openSign);

        // 5. Send a fake container window to force the client to replace the sign screen, triggering UPDATE_SIGN silently
        WrapperPlayServerOpenWindow openWindow = new WrapperPlayServerOpenWindow(1, 0, Component.empty());
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, openWindow);

        // 6. Instantly close the fake window
        WrapperPlayServerCloseWindow closeWindow = new WrapperPlayServerCloseWindow(1);
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, closeWindow);

        // 7. Instantly restore the block
        clearFakeBlock(player, v3i);

        // Safety timeout
        BukkitTask timeoutTask = Bukkit.getScheduler().runTaskLater(this, () -> {
            CheckData staleData = checkingPlayers.remove(player.getUniqueId());
            if (staleData != null) {
                getLogger().warning("[KVC] FLAG: " + player.getName() + " blocked sign update. Possible cheat.");
                
                if (getConfig().getBoolean("kick-on-blocked-check", false)) {
                    enforceAction(player, "Blocked check");
                } else {
                    PlayerRecord record = persistentRecords.computeIfAbsent(player.getUniqueId(), k -> new PlayerRecord(player.getName(), System.currentTimeMillis(), "Blocked check", new ArrayList<>()));
                    if (!record.detectedMods.contains("Blocked check")) record.detectedMods.add("Blocked check");
                    record.lastIncidentTime = System.currentTimeMillis();
                    record.lastAction = "Blocked check";
                    saveData();
                    sendWebhook(player, "Blocked check", "Blocked sign update");

                    // Notify admins without kicking
                    String alertFormat = getConfig().getString("admin-alert", "&8[&cKVC&8] &e%player% &7blocked the sign update check.");
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
                    Bukkit.getScheduler().runTask(KeyValueChecker.this, () -> {
                        if (player.isOnline()) {
                            runDetectionBatch(player, staleData.startIndex + 4);
                        }
                    });
                }
            }
        }, 60L);

        checkingPlayers.put(player.getUniqueId(), new CheckData(startIndex, currentKeys, v3i, timeoutTask));
    }

    private void clearFakeBlock(Player player, Vector3i loc) {
        if (player.isOnline()) {
            Location bukkitLoc = new Location(player.getWorld(), loc.getX(), loc.getY(), loc.getZ());
            int globalId = WrappedBlockState.getByString(player.getWorld().getBlockAt(bukkitLoc).getBlockData().getAsString()).getGlobalId();
            WrapperPlayServerBlockChange restore = new WrapperPlayServerBlockChange(loc, globalId);
            PacketEvents.getAPI().getPlayerManager().sendPacket(player, restore);
        }
    }

    private void enforceAction(Player player, String modName) {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!player.isOnline()) return;
            
            PlayerRecord record = persistentRecords.computeIfAbsent(player.getUniqueId(), k -> new PlayerRecord(player.getName(), System.currentTimeMillis(), "Kicked", new ArrayList<>()));
            record.lastAction = "Kicked";
            record.lastIncidentTime = System.currentTimeMillis();
            saveData();

            sendWebhook(player, "Kicked", String.join(", ", record.detectedMods));

            String actionType = getConfig().getString("action.type", "kick");
            List<String> content = getConfig().getStringList("action.content");
            
            if (content == null || content.isEmpty()) return;
            
            Component finalMessage = Component.empty();
            for (String line : content) {
                String formatted = line.replace("%mod%", modName).replace("%player%", player.getName());
                if (!finalMessage.equals(Component.empty())) {
                    finalMessage = finalMessage.append(Component.newline());
                }
                finalMessage = finalMessage.append(LegacyComponentSerializer.legacyAmpersand().deserialize(formatted));
            }

            if (actionType.equals("message")) {
                player.sendMessage(finalMessage);
            } else if (actionType.equals("command")) {
                for (String line : content) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line.replace("%mod%", modName).replace("%player%", player.getName()));
                }
            } else {
                player.kick(finalMessage);
            }
            
            // Notify admins in-game
            String alertFormat = getConfig().getString("admin-alert", "&8[&cKVC&8] &e%player% &7tried to join with disallowed modifications.");
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
}
