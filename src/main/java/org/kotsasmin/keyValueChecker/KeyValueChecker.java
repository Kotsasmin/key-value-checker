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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class KeyValueChecker extends JavaPlugin implements Listener, CommandExecutor {

    private List<String> translationKeys;
    private int sendDelay;

    private final Map<UUID, List<String>> detectedMods = new ConcurrentHashMap<>();
    
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
                                detectedMods.computeIfAbsent(player.getUniqueId(), k -> new ArrayList<>()).add(key);
                                getLogger().warning("[KVC] FLAG: " + player.getName() + " is using " + key);
                                break; // Only need to detect one per batch to flag
                            }
                        }

                        // Remove the fake block we created
                        clearFakeBlock(player, check.signLoc);

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

    @Override
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
                sender.sendMessage("§6--- Detected Mods ---");
                if (detectedMods.isEmpty()) {
                    sender.sendMessage("§eNo detections.");
                } else {
                    for (Map.Entry<UUID, List<String>> entry : detectedMods.entrySet()) {
                        Player p = Bukkit.getPlayer(entry.getKey());
                        if (p != null) {
                            sender.sendMessage("§c" + p.getName() + " §7flags: §e" + String.join(", ", entry.getValue()));
                        }
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
        detectedMods.remove(player.getUniqueId());

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
        detectedMods.remove(id);
        CheckData check = checkingPlayers.remove(id);
        if (check != null) {
            check.timeoutTask.cancel();
        }
    }

    private void runDetectionBatch(Player player, int startIndex) {
        if (startIndex >= translationKeys.size()) {
            return; // Finished checking all keys
        }

        // Place sign above head to guarantee loaded chunk
        Location loc = player.getLocation().clone();
        loc.setY(loc.getBlockY() + 2);
        
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

        // 5. Instantly Force Close Window (Forces client to send UPDATE_SIGN back without seeing GUI)
        WrapperPlayServerCloseWindow closeWindow = new WrapperPlayServerCloseWindow(0);
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, closeWindow);

        // Safety timeout
        BukkitTask timeoutTask = Bukkit.getScheduler().runTaskLater(this, () -> {
            CheckData staleData = checkingPlayers.remove(player.getUniqueId());
            if (staleData != null) {
                clearFakeBlock(player, staleData.signLoc);
                getLogger().warning("[KVC] FLAG: " + player.getName() + " blocked sign update. Possible cheat.");
                detectedMods.computeIfAbsent(player.getUniqueId(), k -> new ArrayList<>()).add("Blocked check");
                
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
        }, 60L);

        checkingPlayers.put(player.getUniqueId(), new CheckData(startIndex, currentKeys, v3i, timeoutTask));
    }

    private void clearFakeBlock(Player player, Vector3i loc) {
        Bukkit.getScheduler().runTask(this, () -> {
            if (player.isOnline()) {
                Location bukkitLoc = new Location(player.getWorld(), loc.getX(), loc.getY(), loc.getZ());
                int globalId = WrappedBlockState.getByString(player.getWorld().getBlockAt(bukkitLoc).getBlockData().getAsString()).getGlobalId();
                WrapperPlayServerBlockChange restore = new WrapperPlayServerBlockChange(loc, globalId);
                PacketEvents.getAPI().getPlayerManager().sendPacket(player, restore);
            }
        });
    }

    private void enforceAction(Player player, String modName) {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!player.isOnline()) return;
            
            String action = getConfig().getString("action.type", "kick").toLowerCase();
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

            if (action.equals("message")) {
                player.sendMessage(finalMessage);
            } else if (action.equals("command")) {
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
