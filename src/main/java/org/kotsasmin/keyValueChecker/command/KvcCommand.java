package org.kotsasmin.keyValueChecker.command;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.kotsasmin.keyValueChecker.config.ConfigManager;
import org.kotsasmin.keyValueChecker.data.DataManager;
import org.kotsasmin.keyValueChecker.data.PlayerRecord;
import org.kotsasmin.keyValueChecker.detector.CheckManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class KvcCommand implements CommandExecutor, TabCompleter {
    private final ConfigManager configManager;
    private final DataManager dataManager;
    private final CheckManager checkManager;
    private final WhitelistCommand whitelistCommand;

    public KvcCommand(ConfigManager configManager, DataManager dataManager, CheckManager checkManager) {
        this.configManager = configManager;
        this.dataManager = dataManager;
        this.checkManager = checkManager;
        this.whitelistCommand = new WhitelistCommand(dataManager);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String cmdName = command.getName().toLowerCase();

        // 1. Xeirismos standalone entolwn gia backwards compatibility
        if (cmdName.equals("kvc-reload")) {
            return handleReload(sender);
        }
        if (cmdName.equals("kvc-list")) {
            return handleList(sender, args);
        }
        if (cmdName.equals("kvc-scan")) {
            return handleScan(sender, args);
        }
        if (cmdName.equals("kvc-whitelist")) {
            return whitelistCommand.onCommand(sender, command, label, args);
        }

        // 2. Xeirismos kentrikis entolis /kvc (kai aliases opws /keyvaluechecker)
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase();
        String[] subArgs = Arrays.copyOfRange(args, 1, args.length);

        switch (sub) {
            case "reload":
                return handleReload(sender);
            case "list":
                return handleList(sender, subArgs);
            case "rotate":
                return handleList(sender, new String[]{"rotate"});
            case "scan":
                return handleScan(sender, subArgs);
            case "whitelist":
                return whitelistCommand.onCommand(sender, command, "kvc whitelist", subArgs);
            default:
                sender.sendMessage("§c[KVC] Unknown subcommand: §e" + args[0]);
                sendHelp(sender);
                return true;
        }
    }

    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("keyvaluechecker.reload")) {
            sender.sendMessage("§cYou do not have permission to execute this command.");
            return true;
        }
        configManager.loadConfigValues();
        sender.sendMessage("§a[KVC] Configuration reloaded successfully.");
        return true;
    }

    private boolean handleList(CommandSender sender, String[] args) {
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

    private boolean handleScan(CommandSender sender, String[] args) {
        if (!sender.hasPermission("keyvaluechecker.scan")) {
            sender.sendMessage("§cYou do not have permission to execute this command.");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage("§cUsage: /kvc scan <player>");
            return true;
        }

        String targetName = args[0];
        Player target = Bukkit.getPlayer(targetName);
        if (target == null || !target.isOnline()) {
            sender.sendMessage("§cPlayer §e" + targetName + " §cis not online.");
            return true;
        }

        return checkManager.scanPlayer(sender, target);
    }

    private void sendHelp(CommandSender sender) {
        boolean canScan = sender.hasPermission("keyvaluechecker.scan");
        boolean canReload = sender.hasPermission("keyvaluechecker.reload");
        boolean canList = sender.hasPermission("keyvaluechecker.list");
        boolean canWhitelist = sender.hasPermission("keyvaluechecker.whitelist");

        if (!canScan && !canReload && !canList && !canWhitelist) {
            sender.sendMessage("§cYou do not have permission to execute this command.");
            return;
        }

        sender.sendMessage("§6=== KeyValueChecker Commands ===");
        if (canScan) {
            sender.sendMessage("§e/kvc scan <player> §7- Scan an online player for modifications");
        }
        if (canReload) {
            sender.sendMessage("§e/kvc reload §7- Reload configuration");
        }
        if (canList) {
            sender.sendMessage("§e/kvc list [rotate] §7- View or wipe detected mods history");
        }
        if (canWhitelist) {
            sender.sendMessage("§e/kvc whitelist <add|remove|list> [player] §7- Manage player whitelist");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String cmdName = command.getName().toLowerCase();

        // 1. Tab complete gia standalone entoles
        if (cmdName.equals("kvc-reload")) {
            return Collections.emptyList();
        }
        if (cmdName.equals("kvc-scan")) {
            if (args.length == 1 && sender.hasPermission("keyvaluechecker.scan")) {
                return getMatchingPlayerNames(args[0]);
            }
            return Collections.emptyList();
        }
        if (cmdName.equals("kvc-list")) {
            if (args.length == 1 && sender.hasPermission("keyvaluechecker.list")) {
                if ("rotate".startsWith(args[0].toLowerCase())) {
                    return Collections.singletonList("rotate");
                }
            }
            return Collections.emptyList();
        }
        if (cmdName.equals("kvc-whitelist")) {
            return whitelistCommand.onTabComplete(sender, command, alias, args);
        }

        // 2. Tab complete gia /kvc
        if (args.length == 1) {
            List<String> subs = new ArrayList<>();
            String input = args[0].toLowerCase();
            if (sender.hasPermission("keyvaluechecker.scan") && "scan".startsWith(input)) subs.add("scan");
            if (sender.hasPermission("keyvaluechecker.reload") && "reload".startsWith(input)) subs.add("reload");
            if (sender.hasPermission("keyvaluechecker.list") && "list".startsWith(input)) subs.add("list");
            if (sender.hasPermission("keyvaluechecker.whitelist") && "whitelist".startsWith(input)) subs.add("whitelist");
            return subs;
        }

        if (args.length > 1) {
            String sub = args[0].toLowerCase();
            String[] subArgs = Arrays.copyOfRange(args, 1, args.length);
            if (sub.equals("scan")) {
                if (subArgs.length == 1 && sender.hasPermission("keyvaluechecker.scan")) {
                    return getMatchingPlayerNames(subArgs[0]);
                }
            } else if (sub.equals("list")) {
                if (subArgs.length == 1 && sender.hasPermission("keyvaluechecker.list")) {
                    if ("rotate".startsWith(subArgs[0].toLowerCase())) {
                        return Collections.singletonList("rotate");
                    }
                }
            } else if (sub.equals("whitelist")) {
                if (sender.hasPermission("keyvaluechecker.whitelist")) {
                    return whitelistCommand.onTabComplete(sender, command, "kvc whitelist", subArgs);
                }
            }
        }

        return Collections.emptyList();
    }

    private List<String> getMatchingPlayerNames(String prefix) {
        List<String> list = new ArrayList<>();
        String lower = prefix.toLowerCase();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getName().toLowerCase().startsWith(lower)) {
                list.add(p.getName());
            }
        }
        return list;
    }
}
