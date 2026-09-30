package org.kotsasmin.keyValueChecker.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.kotsasmin.keyValueChecker.config.ConfigManager;
import org.kotsasmin.keyValueChecker.data.DataManager;
import org.kotsasmin.keyValueChecker.data.PlayerRecord;

import java.util.Map;
import java.util.UUID;

public class KvcCommand implements CommandExecutor {
    private final ConfigManager configManager;
    private final DataManager dataManager;

    public KvcCommand(ConfigManager configManager, DataManager dataManager) {
        this.configManager = configManager;
        this.dataManager = dataManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // reload tis rithmiseis
        if (command.getName().equalsIgnoreCase("kvc-reload")) {
            if (!sender.hasPermission("keyvaluechecker.reload")) {
                sender.sendMessage("§cYou do not have permission to execute this command.");
                return true;
            }
            configManager.loadConfigValues();
            sender.sendMessage("§aKeyValueChecker config reloaded.");
            return true;
        }

        // emfanish log h katharisma me rotate
        if (command.getName().equalsIgnoreCase("kvc-list")) {
            if (!sender.hasPermission("keyvaluechecker.list")) {
                sender.sendMessage("§cYou do not have permission to execute this command.");
                return true;
            }

            if (args.length > 0 && args[0].equalsIgnoreCase("rotate")) {
                dataManager.clearRecords();
                dataManager.saveData(true);
                sender.sendMessage("§a[KVC] Log history wiped successfully.");
                return true;
            }

            sender.sendMessage("§6--- Detected Mods History ---");
            Map<UUID, PlayerRecord> records = dataManager.getPersistentRecords();
            if (records.isEmpty()) {
                sender.sendMessage("§eNo detections on record.");
            } else {
                for (Map.Entry<UUID, PlayerRecord> entry : records.entrySet()) {
                    PlayerRecord rec = entry.getValue();
                    String timeAgo = DataManager.formatTimeAgo(rec.getLastIncidentTime());
                    sender.sendMessage("§c" + rec.getPlayerName() + " §7[" + timeAgo + "] §8(Last: " + rec.getLastAction() + ")");
                    sender.sendMessage("  §7Flags: §e" + String.join(", ", rec.getDetectedMods()));
                }
            }
            return true;
        }

        return false;
    }
}
