package org.kotsasmin.keyValueChecker;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.kotsasmin.keyValueChecker.command.KvcCommand;
import org.kotsasmin.keyValueChecker.command.WhitelistCommand;
import org.kotsasmin.keyValueChecker.config.ConfigManager;
import org.kotsasmin.keyValueChecker.data.DataManager;
import org.kotsasmin.keyValueChecker.detector.CheckManager;
import org.kotsasmin.keyValueChecker.detector.SignPacketListener;
import org.kotsasmin.keyValueChecker.quarantine.QuarantineManager;
import org.kotsasmin.keyValueChecker.webhook.DiscordWebhookNotifier;

public final class KeyValueChecker extends JavaPlugin implements Listener {

    private ConfigManager configManager;
    private DataManager dataManager;
    private DiscordWebhookNotifier webhookNotifier;
    private QuarantineManager quarantineManager;
    private CheckManager checkManager;
    private PacketListenerAbstract packetListener;

    @Override
    public void onEnable() {
        // elegxos an leipei to packetevents dependency
        if (getServer().getPluginManager().getPlugin("packetevents") == null) {
            getLogger().severe("[KVC] PacketEvents is missing! KeyValueChecker requires PacketEvents to work.");
            getLogger().severe("[KVC] Download it from: https://modrinth.com/plugin/packetevents");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        saveDefaultConfig();

        configManager = new ConfigManager(this);
        configManager.loadConfigValues();

        dataManager = new DataManager(this);
        dataManager.loadData();

        quarantineManager = new QuarantineManager(this, configManager);
        webhookNotifier = new DiscordWebhookNotifier(this, configManager);
        checkManager = new CheckManager(this, configManager, dataManager, webhookNotifier, quarantineManager);

        // vazoume to packet listener kai kratame to reference gia na einai reload safe
        packetListener = new SignPacketListener(this, checkManager, dataManager);
        PacketEvents.getAPI().getEventManager().registerListener(packetListener);

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(quarantineManager, this);

        // commands
        KvcCommand kvcCommand = new KvcCommand(configManager, dataManager, checkManager);

        PluginCommand mainCmd = getCommand("kvc");
        if (mainCmd != null) {
            mainCmd.setExecutor(kvcCommand);
            mainCmd.setTabCompleter(kvcCommand);
        }

        PluginCommand scanCmd = getCommand("kvc-scan");
        if (scanCmd != null) {
            scanCmd.setExecutor(kvcCommand);
            scanCmd.setTabCompleter(kvcCommand);
        }

        PluginCommand reloadCmd = getCommand("kvc-reload");
        if (reloadCmd != null) {
            reloadCmd.setExecutor(kvcCommand);
            reloadCmd.setTabCompleter(kvcCommand);
        }

        PluginCommand listCmd = getCommand("kvc-list");
        if (listCmd != null) {
            listCmd.setExecutor(kvcCommand);
            listCmd.setTabCompleter(kvcCommand);
        }

        PluginCommand whitelistCmd = getCommand("kvc-whitelist");
        if (whitelistCmd != null) {
            whitelistCmd.setExecutor(kvcCommand);
            whitelistCmd.setTabCompleter(kvcCommand);
        }

        getLogger().info("KeyValueChecker enabled via pure PacketEvents NBT.");
    }

    @Override
    public void onDisable() {
        // vgazoume to packet listener sto reload gia na min diplo-akouei
        if (packetListener != null) {
            try {
                if (PacketEvents.getAPI() != null && PacketEvents.getAPI().getEventManager() != null) {
                    PacketEvents.getAPI().getEventManager().unregisterListener(packetListener);
                }
            } catch (Exception e) {
                getLogger().warning("[KVC] Failed to unregister PacketEvents listener: " + e.getMessage());
            }
            packetListener = null;
        }

        // akyrwsi twn pending checks
        if (checkManager != null) {
            checkManager.cancelAllChecks();
        }

        if (quarantineManager != null) {
            quarantineManager.releaseAll();
        }

        // synchronous save gia na min petaksei IllegalPluginAccessException sto shutdown
        if (dataManager != null) {
            dataManager.saveData(false);
        }

        getLogger().info("KeyValueChecker disabled successfully.");
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (checkManager != null) {
            checkManager.handlePlayerJoin(event.getPlayer());
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (checkManager != null) {
            checkManager.handlePlayerQuit(event.getPlayer());
        }
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public DataManager getDataManager() {
        return dataManager;
    }

    public CheckManager getCheckManager() {
        return checkManager;
    }

    public QuarantineManager getQuarantineManager() {
        return quarantineManager;
    }
}
