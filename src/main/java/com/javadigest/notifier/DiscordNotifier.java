package com.javadigest.notifier;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javadigest.model.Article;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Discord Incoming Webhook ile bildirim gönderir.
 * Env: DISCORD_WEBHOOK_URL
 */
public class DiscordNotifier implements Notifier {

    private static final Logger log = Logger.getLogger(DiscordNotifier.class.getName());
    private static final int MAX_EMBED_DESCRIPTION = 4096;
    private static final int EMBED_COLOR = 0xFFA500; // turuncu

    private final String webhookUrl;
    private final HttpClient http;
    private final ObjectMapper json;

    public DiscordNotifier(String webhookUrl) {
        this.webhookUrl = webhookUrl;
        this.http = HttpClient.newHttpClient();
        this.json = new ObjectMapper();
    }

    public static DiscordNotifier fromEnv() {
        String url = System.getenv("DISCORD_WEBHOOK_URL");
        return url != null && !url.isBlank() ? new DiscordNotifier(url) : null;
    }

    @Override
    public void send(List<Article> articles) throws Exception {
        if (articles.isEmpty()) return;

        List<Map<String, Object>> embeds = new ArrayList<>();

        Map<String, Object> headerEmbed = Map.of(
                "title", "☕ Java Digest — " + LocalDate.now(),
                "description", articles.size() + " yeni içerik bulundu.",
                "color", EMBED_COLOR
        );
        embeds.add(headerEmbed);

        StringBuilder desc = new StringBuilder();
        int embedCount = 0;

        for (Article a : articles) {
            String line = "**[" + a.title() + "](" + a.url() + ")**\n"
                    + (a.author().isEmpty() ? "" : "👤 " + a.author() + " | ")
                    + "🗂 " + a.source()
                    + (a.tags() != null && !a.tags().isBlank() ? " | 🏷 " + a.tags() : "")
                    + "\n\n";

            if (desc.length() + line.length() > MAX_EMBED_DESCRIPTION) {
                embeds.add(Map.of(
                        "description", desc.toString(),
                        "color", EMBED_COLOR
                ));
                desc = new StringBuilder();
                embedCount++;
                if (embedCount >= 9) break; // Discord max 10 embeds
            }
            desc.append(line);
        }

        if (!desc.isEmpty()) {
            embeds.add(Map.of(
                    "description", desc.toString(),
                    "color", EMBED_COLOR
            ));
        }

        String payload = json.writeValueAsString(Map.of("embeds", embeds));
        postWebhook(payload);
        log.info("Discord: " + articles.size() + " makale gönderildi.");
    }

    private void postWebhook(String payload) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(webhookUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200 && response.statusCode() != 204) {
            log.warning("Discord webhook hatası: " + response.statusCode() + " — " + response.body());
        }
    }
}
