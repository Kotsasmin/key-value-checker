package org.kotsasmin.keyValueChecker.webhook;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.kotsasmin.keyValueChecker.config.ConfigManager;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class DiscordWebhookNotifier {
    private final Plugin plugin;
    private final ConfigManager configManager;
    private final HttpClient httpClient;

    public DiscordWebhookNotifier(Plugin plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    // apostoli discord webhook me gson gia na apofygoume manual string formatting
    public void sendWebhook(Player player, String action, String modsStr) {
        if (!configManager.isDiscordWebhookEnabled()) {
            return;
        }

        String url = configManager.getDiscordWebhookUrl();
        if (url == null || url.trim().isEmpty()) {
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String username = configManager.getDiscordWebhookUsername();
                String avatarUrl = configManager.getDiscordWebhookAvatarUrl().replace("%player%", player.getName());
                int color = configManager.getDiscordWebhookColor();
                String title = configManager.getDiscordWebhookTitle();
                String desc = configManager.getDiscordWebhookDescription()
                        .replace("%player%", player.getName())
                        .replace("%action%", action)
                        .replace("%mods%", modsStr);
                String footer = configManager.getDiscordWebhookFooter();

                // ftiaksimo tou json payload
                JsonObject payload = new JsonObject();
                payload.addProperty("username", username);
                payload.addProperty("avatar_url", avatarUrl);

                JsonArray embeds = new JsonArray();
                JsonObject embed = new JsonObject();
                embed.addProperty("title", title);
                embed.addProperty("description", desc);
                embed.addProperty("color", color);

                if (footer != null && !footer.isEmpty()) {
                    JsonObject footerObj = new JsonObject();
                    footerObj.addProperty("text", footer);
                    embed.add("footer", footerObj);
                }

                embeds.add(embed);
                payload.add("embeds", embeds);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                        .build();

                httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            } catch (Exception e) {
                plugin.getLogger().warning("[KVC] Failed to send Discord webhook: " + e.getMessage());
            }
        });
    }
}
