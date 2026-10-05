package org.kotsasmin.keyValueChecker.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;
import org.kotsasmin.keyValueChecker.data.TranslationCheckItem;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class ConfigManager {
    private final JavaPlugin plugin;
    private final List<TranslationCheckItem> checkQueue = new ArrayList<>();
    private int sendDelayTicks;

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadConfigValues() {
        migrateConfigIfNeeded();
        plugin.reloadConfig();

        checkQueue.clear();

        // Load Blacklist groups (required = false)
        ConfigurationSection blacklist = plugin.getConfig().getConfigurationSection("blacklist");
        if (blacklist != null) {
            for (String group : blacklist.getKeys(false)) {
                if (plugin.getConfig().getBoolean("blacklist." + group + ".enabled", true)) {
                    parseKeysSection("blacklist." + group + ".keys", false);
                }
            }
        }

        // Fallback for old configs if blacklist is empty but translation-keys exists
        if (checkQueue.isEmpty() && plugin.getConfig().contains("translation-keys")) {
            for (String k : plugin.getConfig().getStringList("translation-keys")) {
                if (k != null && !k.isEmpty()) {
                    checkQueue.add(new TranslationCheckItem(k, false));
                }
            }
        }

        // Load Whitelist groups (required = true)
        ConfigurationSection whitelist = plugin.getConfig().getConfigurationSection("whitelist");
        if (whitelist != null) {
            for (String group : whitelist.getKeys(false)) {
                if (plugin.getConfig().getBoolean("whitelist." + group + ".enabled", false)) {
                    parseKeysSection("whitelist." + group + ".keys", true);
                }
            }
        }

        // metatropi tou delay se ticks (default 40 ticks = 2s gia na exei teleiwsei to loading screen)
        if (plugin.getConfig().contains("initial-check-delay-ticks")) {
            sendDelayTicks = plugin.getConfig().getInt("initial-check-delay-ticks", 40);
        } else if (plugin.getConfig().contains("initial-check-delay-seconds")) {
            sendDelayTicks = plugin.getConfig().getInt("initial-check-delay-seconds", 2) * 20;
        } else {
            sendDelayTicks = plugin.getConfig().getInt("send-delay-ticks", 40);
        }
        if (sendDelayTicks < 1) {
            sendDelayTicks = 1;
        }

        plugin.getLogger().info("[KVC] Loaded " + checkQueue.size() + " total translation keys to check.");
    }

    private void migrateConfigIfNeeded() {
        int version = plugin.getConfig().getInt("config-version", 0);
        if (version != 2) {
            plugin.getLogger().warning("[KVC] Config is outdated (version " + version + ", expected 2).");
            plugin.getLogger().warning("[KVC] Backing up old config.yml and creating a new Version 2 config.yml...");
            File configFile = new File(plugin.getDataFolder(), "config.yml");
            if (configFile.exists()) {
                File backupFile = new File(plugin.getDataFolder(), "config.backup." + System.currentTimeMillis() + ".yml");
                boolean renamed = configFile.renameTo(backupFile);
                if (renamed) {
                    plugin.getLogger().info("[KVC] Backed up old config to: " + backupFile.getName());
                } else {
                    plugin.getLogger().warning("[KVC] Could not rename old config.yml to backup file!");
                }
            }
            plugin.saveDefaultConfig();
            plugin.reloadConfig();
            plugin.getLogger().info("[KVC] New default config.yml (Version 2) has been generated and loaded.");
        }
    }

    private void parseKeysSection(String path, boolean required) {
        List<?> list = plugin.getConfig().getList(path);
        if (list == null) return;

        for (Object obj : list) {
            if (obj instanceof Map) {
                Map<?, ?> map = (Map<?, ?>) obj;
                String key = String.valueOf(map.get("key"));
                String name = map.containsKey("name") && map.get("name") != null ? String.valueOf(map.get("name")) : key;
                if (key != null && !key.isEmpty() && !"null".equals(key)) {
                    checkQueue.add(new TranslationCheckItem(key, name, required));
                }
            } else if (obj instanceof String) {
                String str = (String) obj;
                if (str != null && !str.isEmpty()) {
                    checkQueue.add(new TranslationCheckItem(str, str, required));
                }
            }
        }
    }

    public List<TranslationCheckItem> getCheckQueue() {
        return Collections.unmodifiableList(checkQueue);
    }

    public int getSendDelayTicks() {
        return sendDelayTicks;
    }

    public boolean isKickOnBlockedCheck() {
        return plugin.getConfig().getBoolean("kick-on-blocked-check", false);
    }

    public String getActionType(boolean isMissingRequired) {
        String sectionPrefix = isMissingRequired ? "whitelist-action" : "action";
        return plugin.getConfig().getString(sectionPrefix + ".type", "kick");
    }

    public List<String> getActionContent(boolean isMissingRequired) {
        String sectionPrefix = isMissingRequired ? "whitelist-action" : "action";
        return plugin.getConfig().getStringList(sectionPrefix + ".content");
    }

    public String getAdminAlertFormat(boolean isMissingRequired) {
        String defaultAlert = isMissingRequired
                ? "&8[&cKVC&8] &e%player% &7tried to join without a required modification (&c%mod%&7)."
                : "&8[&cKVC&8] &e%player% &7tried to join with disallowed modifications.";
        String alertKey = isMissingRequired ? "whitelist-admin-alert" : "admin-alert";
        return plugin.getConfig().getString(alertKey, defaultAlert);
    }

    public String getBlockedCheckAdminAlertFormat() {
        return plugin.getConfig().getString("admin-alert", "&8[&cKVC&8] &e%player% &7blocked the sign update check.");
    }

    public boolean isDiscordWebhookEnabled() {
        return plugin.getConfig().getBoolean("discord-webhook.enabled", false);
    }

    public boolean isDiscordSendOnBlockedCheck() {
        return plugin.getConfig().getBoolean("discord-webhook.send-on-blocked-check", false);
    }

    public String getDiscordWebhookUrl() {
        return plugin.getConfig().getString("discord-webhook.url", "");
    }

    public String getDiscordWebhookUsername() {
        return plugin.getConfig().getString("discord-webhook.username", "KeyValueChecker");
    }

    public String getDiscordWebhookAvatarUrl() {
        return plugin.getConfig().getString("discord-webhook.avatar-url", "");
    }

    public int getDiscordWebhookColor() {
        return plugin.getConfig().getInt("discord-webhook.embed.color", 16711680);
    }

    public String getDiscordWebhookTitle() {
        return plugin.getConfig().getString("discord-webhook.embed.title", "Cheater Detected!");
    }

    public String getDiscordWebhookDescription() {
        return plugin.getConfig().getString("discord-webhook.embed.description", "");
    }

    public String getDiscordWebhookFooter() {
        return plugin.getConfig().getString("discord-webhook.embed.footer", "");
    }
}
