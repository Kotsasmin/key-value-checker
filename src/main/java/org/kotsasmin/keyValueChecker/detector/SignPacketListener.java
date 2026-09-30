package org.kotsasmin.keyValueChecker.detector;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUpdateSign;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.kotsasmin.keyValueChecker.data.DataManager;
import org.kotsasmin.keyValueChecker.data.PlayerRecord;
import org.kotsasmin.keyValueChecker.data.TranslationCheckItem;

import java.util.List;

public class SignPacketListener extends PacketListenerAbstract {
    private final JavaPlugin plugin;
    private final CheckManager checkManager;
    private final DataManager dataManager;

    public SignPacketListener(JavaPlugin plugin, CheckManager checkManager, DataManager dataManager) {
        this.plugin = plugin;
        this.checkManager = checkManager;
        this.dataManager = dataManager;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.UPDATE_SIGN) {
            Player player = (Player) event.getPlayer();
            CheckData check = checkManager.getActiveCheck(player.getUniqueId());

            if (check == null) return;

            WrapperPlayClientUpdateSign updateSign = new WrapperPlayClientUpdateSign(event);
            if (updateSign.getBlockPosition().equals(check.getSignLoc())) {
                // Cancel the vanilla server processing to bypass "non-editable" warnings
                event.setCancelled(true);
                checkManager.removeActiveCheck(player.getUniqueId());
                if (check.getTimeoutTask() != null) {
                    check.getTimeoutTask().cancel();
                }

                boolean flag = false;
                String detectedMod = null;
                boolean isMissingRequired = false;
                String[] lines = updateSign.getTextLines();
                List<TranslationCheckItem> currentKeys = check.getCurrentKeys();

                for (int i = 0; i < currentKeys.size(); i++) {
                    TranslationCheckItem item = currentKeys.get(i);
                    if (item == null || item.getKey() == null) continue;

                    String clientText = (i < lines.length && lines[i] != null) ? lines[i] : "";

                    if (!item.isRequired()) {
                        // Blacklist check: if client translated it, they have a disallowed mod
                        // an to metefrazei to client simainei exei to cheat/mod
                        if (!clientText.isEmpty() && !clientText.equals("fallb") && !clientText.equals(item.getKey())) {
                            flag = true;
                            detectedMod = item.getName();
                            isMissingRequired = false;
                            PlayerRecord record = dataManager.getOrCreateRecord(player.getUniqueId(), player.getName(), "detected");
                            record.addDetectedMod(item.getName());
                            record.setLastIncidentTime(System.currentTimeMillis());
                            plugin.getLogger().warning("[KVC] FLAG (Blacklist): " + player.getName() + " is using " + item.getName() + " (" + item.getKey() + ")");
                            break; // Only need to detect one per batch to flag
                        }
                    } else {
                        // Whitelist check: if client failed to translate it, they are MISSING a required mod
                        // an den to metefrazei tote leipei to mod apo to client
                        if (clientText.isEmpty() || clientText.equals("fallb") || clientText.equals(item.getKey())) {
                            flag = true;
                            detectedMod = item.getName();
                            isMissingRequired = true;
                            PlayerRecord record = dataManager.getOrCreateRecord(player.getUniqueId(), player.getName(), "missing required");
                            String missingLabel = "Missing: " + item.getName();
                            record.addDetectedMod(missingLabel);
                            record.setLastIncidentTime(System.currentTimeMillis());
                            plugin.getLogger().warning("[KVC] FLAG (Whitelist/Missing): " + player.getName() + " is missing required mod: " + item.getName() + " (" + item.getKey() + ")");
                            break;
                        }
                    }
                }

                if (flag) {
                    checkManager.enforceAction(player, detectedMod, isMissingRequired);
                } else {
                    // sinexizoume me to epomeno batch twn 4
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (player.isOnline()) {
                            checkManager.runDetectionBatch(player, check.getStartIndex() + 4);
                        }
                    });
                }
            }
        }
    }
}
