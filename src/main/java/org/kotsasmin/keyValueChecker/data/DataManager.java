package org.kotsasmin.keyValueChecker.data;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class DataManager {
    private final Plugin plugin;
    private final Object fileLock = new Object(); // lock gia to arxeio
    private final Map<UUID, PlayerRecord> persistentRecords = new ConcurrentHashMap<>();
    private final List<String> whitelist = new CopyOnWriteArrayList<>();

    private File dataFile;
    private FileConfiguration dataConfig;

    public DataManager(Plugin plugin) {
        this.plugin = plugin;
    }

    public void loadData() {
        dataFile = new File(plugin.getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            try {
                if (!plugin.getDataFolder().exists()) {
                    plugin.getDataFolder().mkdirs();
                }
                dataFile.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().severe("Could not create data.yml: " + e.getMessage());
            }
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
        persistentRecords.clear();

        // fortwnoume ta records apo to data.yml
        ConfigurationSection recordsSection = dataConfig.getConfigurationSection("records");
        if (recordsSection != null) {
            for (String uuidStr : recordsSection.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(uuidStr);
                    String name = dataConfig.getString("records." + uuidStr + ".name");
                    long time = dataConfig.getLong("records." + uuidStr + ".time");
                    String action = dataConfig.getString("records." + uuidStr + ".action");
                    List<String> mods = dataConfig.getStringList("records." + uuidStr + ".mods");
                    persistentRecords.put(uuid, new PlayerRecord(name, time, action, mods));
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Skipping invalid UUID in data.yml: " + uuidStr);
                }
            }
        }

        // kai to whitelist twn paiktwn pou kanoun bypass
        whitelist.clear();
        if (dataConfig.contains("whitelist")) {
            whitelist.addAll(dataConfig.getStringList("whitelist"));
        }
    }

    // async sto kanoniko gameplay, sync sto shutdown gia na min troei exception
    public void saveData(boolean async) {
        if (dataFile == null || dataConfig == null) {
            return;
        }

        if (async && plugin.isEnabled()) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, this::writeToDisk);
        } else {
            writeToDisk();
        }
    }

    private void writeToDisk() {
        synchronized (fileLock) {
            try {
                dataConfig.set("records", null);
                for (Map.Entry<UUID, PlayerRecord> entry : persistentRecords.entrySet()) {
                    String path = "records." + entry.getKey().toString();
                    PlayerRecord rec = entry.getValue();
                    dataConfig.set(path + ".name", rec.getPlayerName());
                    dataConfig.set(path + ".time", rec.getLastIncidentTime());
                    dataConfig.set(path + ".action", rec.getLastAction());
                    dataConfig.set(path + ".mods", new ArrayList<>(rec.getDetectedMods()));
                }
                dataConfig.set("whitelist", new ArrayList<>(whitelist));
                dataConfig.save(dataFile);
            } catch (IOException e) {
                plugin.getLogger().severe("Failed to save data.yml: " + e.getMessage());
            }
        }
    }

    public Map<UUID, PlayerRecord> getPersistentRecords() {
        return persistentRecords;
    }

    public PlayerRecord getOrCreateRecord(UUID uuid, String playerName, String initialAction) {
        return persistentRecords.computeIfAbsent(uuid, k ->
                new PlayerRecord(playerName, System.currentTimeMillis(), initialAction, new ArrayList<>())
        );
    }

    public void clearRecords() {
        persistentRecords.clear();
    }

    public List<String> getWhitelist() {
        return whitelist;
    }

    public boolean isWhitelisted(String playerName) {
        return whitelist.stream().anyMatch(playerName::equalsIgnoreCase);
    }

    public boolean addToWhitelist(String playerName) {
        if (isWhitelisted(playerName)) {
            return false;
        }
        return whitelist.add(playerName);
    }

    public boolean removeFromWhitelist(String playerName) {
        String match = whitelist.stream()
                .filter(playerName::equalsIgnoreCase)
                .findFirst()
                .orElse(null);
        if (match != null) {
            return whitelist.remove(match);
        }
        return false;
    }

    public static String formatTimeAgo(long timestamp) {
        long diffSeconds = (System.currentTimeMillis() - timestamp) / 1000;
        if (diffSeconds < 60) return diffSeconds + " secs ago";
        if (diffSeconds < 3600) return (diffSeconds / 60) + " mins ago";
        if (diffSeconds < 86400) return (diffSeconds / 3600) + " hours ago";
        return (diffSeconds / 86400) + " days ago";
    }
}
