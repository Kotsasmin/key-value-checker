package org.kotsasmin.keyValueChecker.command;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.kotsasmin.keyValueChecker.data.DataManager;
import org.kotsasmin.keyValueChecker.data.PlayerRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public class WhitelistCommand implements CommandExecutor, TabCompleter {
    private final DataManager dataManager;

    public WhitelistCommand(DataManager dataManager) {
        this.dataManager = dataManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("keyvaluechecker.whitelist")) {
            sender.sendMessage("§cYou do not have permission to execute this command.");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage("§cUsage: /kvc-whitelist <add|remove|list> [player]");
            return true;
        }

        String sub = args[0].toLowerCase();
        if (sub.equals("list")) {
            sender.sendMessage("§6--- Whitelisted Players ---");
            List<String> whitelist = dataManager.getWhitelist();
            if (whitelist.isEmpty()) {
                sender.sendMessage("§eNo players are whitelisted.");
            } else {
                for (String name : whitelist) {
                    sender.sendMessage("§7- §a" + name);
                }
            }
            return true;
        }

        // prosthiki sto whitelist
        if (sub.equals("add")) {
            if (args.length < 2) {
                sender.sendMessage("§cUsage: /kvc-whitelist add <player>");
                return true;
            }
            String targetName = args[1];
            if (!dataManager.addToWhitelist(targetName)) {
                sender.sendMessage("§cPlayer §e" + targetName + " §cis already whitelisted.");
                return true;
            }
            dataManager.saveData(true);
            sender.sendMessage("§aAdded §e" + targetName + " §ato the whitelist.");
            return true;
        }

        // afairesi apo to whitelist
        if (sub.equals("remove")) {
            if (args.length < 2) {
                sender.sendMessage("§cUsage: /kvc-whitelist remove <player>");
                return true;
            }
            String targetName = args[1];
            if (!dataManager.removeFromWhitelist(targetName)) {
                sender.sendMessage("§cPlayer §e" + targetName + " §cis not on the whitelist.");
                return true;
            }
            dataManager.saveData(true);
            sender.sendMessage("§aRemoved §e" + targetName + " §afrom the whitelist.");
            return true;
        }

        sender.sendMessage("§cUnknown subcommand. Use add, remove, or list.");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("keyvaluechecker.whitelist")) {
            return new ArrayList<>();
        }

        List<String> suggestions = new ArrayList<>();
        if (args.length == 1) {
            String input = args[0].toLowerCase();
            if ("add".startsWith(input)) suggestions.add("add");
            if ("remove".startsWith(input)) suggestions.add("remove");
            if ("list".startsWith(input)) suggestions.add("list");
            return suggestions;
        }

        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            String input = args[1].toLowerCase();

            // tab completion me online kai cached paiktes gia na min kanei lag spike to getOfflinePlayers
            if (sub.equals("add")) {
                Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                for (Player p : Bukkit.getOnlinePlayers()) {
                    names.add(p.getName());
                }
                for (PlayerRecord rec : dataManager.getPersistentRecords().values()) {
                    if (rec.getPlayerName() != null) {
                        names.add(rec.getPlayerName());
                    }
                }
                for (String name : names) {
                    if (name.toLowerCase().startsWith(input)) {
                        suggestions.add(name);
                    }
                }
                return suggestions;
            } else if (sub.equals("remove")) {
                for (String name : dataManager.getWhitelist()) {
                    if (name.toLowerCase().startsWith(input)) {
                        suggestions.add(name);
                    }
                }
                return suggestions;
            }
        }

        return new ArrayList<>();
    }
}
