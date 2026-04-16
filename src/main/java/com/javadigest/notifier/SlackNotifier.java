package com.javadigest.notifier;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javadigest.model.Article;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Makaleleri proje etiketine göre farklı Slack kanallarına yönlendirir.
 *
 * Kanal eşlemesi (tag → Slack webhook URL):
 *   Amber    → SLACK_WEBHOOK_AMBER
 *   Valhalla → SLACK_WEBHOOK_VALHALLA
 *   Loom     → SLACK_WEBHOOK_LOOM
 *   Leyden   → SLACK_WEBHOOK_LEYDEN
 *   Panama   → SLACK_WEBHOOK_PANAMA
 *   Diğer    → SLACK_WEBHOOK_GENERAL  (zorunlu, diğerleri opsiyonel)
 *
 * Slack Incoming Webhook URL'lerini GitHub Secrets'a ekle.
 */
public class SlackNotifier {

    private static final Logger log = Logger.getLogger(SlackNotifier.class.getName());

    /** Tag → Slack webhook URL eşlemesi */
    private final Map<String, String> channelWebhooks;
    private final HttpClient http;
    private final ObjectMapper json;

    public SlackNotifier(Map<String, String> channelWebhooks) {
        this.channelWebhooks = channelWebhooks;
        this.http = HttpClient.newHttpClient();
        this.json = new ObjectMapper();
    }

    public boolean hasWebhooks() {
        return !channelWebhooks.isEmpty();
    }

    /**
     * Env değişkenlerinden otomatik olarak SlackNotifier oluşturur.
     * SLACK_WEBHOOK_GENERAL zorunlu; diğerleri yoksa GENERAL'e düşer.
     */
    public static SlackNotifier fromEnv() {
        Map<String, String> webhooks = new java.util.HashMap<>();

        addIfPresent(webhooks, "amber",    "SLACK_WEBHOOK_AMBER");
        addIfPresent(webhooks, "valhalla", "SLACK_WEBHOOK_VALHALLA");
        addIfPresent(webhooks, "loom",     "SLACK_WEBHOOK_LOOM");
        addIfPresent(webhooks, "leyden",   "SLACK_WEBHOOK_LEYDEN");
        addIfPresent(webhooks, "panama",   "SLACK_WEBHOOK_PANAMA");
        addIfPresent(webhooks, "general",  "SLACK_WEBHOOK_GENERAL");

        return new SlackNotifier(webhooks);
    }

    private static void addIfPresent(Map<String, String> map, String key, String envVar) {
        String value = System.getenv(envVar);
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }

    public void sendDigest(
            List<Article> articles,
            String generalSummary,
            Map<String, String> articleSummaries
    ) throws Exception {
        if (articles.isEmpty()) {
            String webhookUrl = channelWebhooks.get("general");
            if (webhookUrl != null) {
                String payload = json.writeValueAsString(Map.of(
                        "text", "☕ _Java Digest: " + java.time.LocalDate.now()
                                + " için takip edilen kaynaklarda yeni içerik yok._"
                ));
                postToWebhook(webhookUrl, payload);
            }
            return;
        }

        // Makaleleri ait oldukları kanala göre grupla
        Map<String, List<Article>> byChannel = groupByChannel(articles);

        for (Map.Entry<String, List<Article>> entry : byChannel.entrySet()) {
            String channelKey = entry.getKey();
            List<Article> channelArticles = entry.getValue();

            String webhookUrl = channelWebhooks.getOrDefault(channelKey,
                    channelWebhooks.get("general"));

            if (webhookUrl == null) {
                log.warning("Slack webhook bulunamadı: " + channelKey + " ve GENERAL de yok, atlanıyor.");
                continue;
            }

            String payload = buildPayload(channelKey, channelArticles, generalSummary, articleSummaries);
            postToWebhook(webhookUrl, payload);
            log.info("Slack #" + channelKey + ": " + channelArticles.size() + " makale gönderildi.");
        }
    }

    /**
     * Her makaleyi tags alanına bakarak bir kanala atar.
     * Birden fazla tag varsa en spesifik projeye gönderir.
     */
    private Map<String, List<Article>> groupByChannel(List<Article> articles) {
        List<String> priority = List.of("valhalla", "loom", "amber", "leyden", "panama");

        return articles.stream().collect(Collectors.groupingBy(article -> {
            String tags = article.tags() != null ? article.tags().toLowerCase() : "";
            String source = article.source() != null ? article.source().toLowerCase() : "";
            String combined = tags + " " + source;

            for (String project : priority) {
                if (combined.contains(project)) return project;
            }
            return "general";
        }));
    }

    /** Slack Block Kit formatında özet odaklı mesaj oluştur */
    private String buildPayload(
            String channelKey,
            List<Article> articles,
            String generalSummary,
            Map<String, String> articleSummaries
    ) throws Exception {
        String emoji = channelEmoji(channelKey);
        List<Map<String, Object>> blocks = new ArrayList<>();

        // Başlık bloğu
        blocks.add(Map.of(
                "type", "header",
                "text", Map.of("type", "plain_text", "text",
                        "Java " + capitalize(channelKey) + " — " + java.time.LocalDate.now())
        ));

        blocks.add(Map.of("type", "divider"));

        String effectiveSummary;
        if ("general".equals(channelKey) && generalSummary != null && !generalSummary.isBlank()) {
            effectiveSummary = generalSummary;
        } else {
            effectiveSummary = buildFallbackSummary(articles);
        }
        blocks.add(Map.of(
                "type", "section",
                "text", Map.of("type", "mrkdwn", "text", "*Genel Özet*\n" + effectiveSummary)
        ));

        blocks.add(Map.of("type", "divider"));

        // Kaynak bazlı linksiz kısa maddeler
        String highlights = buildSourceHighlights(articles, articleSummaries);
        blocks.add(Map.of(
                "type", "section",
                "text", Map.of("type", "mrkdwn", "text", highlights)
        ));

        blocks.add(Map.of("type", "divider"));
        blocks.add(Map.of(
                "type", "context",
                "elements", List.of(Map.of(
                        "type", "mrkdwn",
                        "text", "Java Digest Bot  •  " + articles.size() + " yeni içerik"
                ))
        ));

        return json.writeValueAsString(Map.of("blocks", blocks));
    }

    private String buildSourceHighlights(List<Article> articles, Map<String, String> articleSummaries) {
        Map<String, List<Article>> bySource = articles.stream()
                .sorted(Comparator.comparing(Article::publishedDate).reversed())
                .collect(Collectors.groupingBy(Article::source));

        StringBuilder sb = new StringBuilder("*Kaynak Bazlı Notlar*\n");
        bySource.forEach((source, sourceArticles) -> {
            sb.append("• *").append(source).append("*\n");
            sourceArticles.stream()
                    .limit(6)
                    .forEach(a -> {
                        sb.append("  - ")
                                .append(a.title())
                                .append(" — ")
                                .append(a.author() == null || a.author().isBlank() ? "Bilinmeyen yazar" : a.author())
                                .append(", ")
                                .append(a.publishedDate());

                        String summary = resolveArticleSummary(a, articleSummaries);
                        if (summary != null && !summary.isBlank()) {
                            sb.append("\n    Özet: ").append(summary);
                        }

                        sb.append("\n    ")
                                .append(a.url())
                                .append("\n");
                    });
        });
        return sb.toString();
    }

    private String resolveArticleSummary(Article article, Map<String, String> articleSummaries) {
        if (articleSummaries == null || articleSummaries.isEmpty()) {
            return null;
        }
        String summary = articleSummaries.get(article.stateKey());
        if (summary == null || summary.isBlank()) {
            return null;
        }
        return summary.replace('\n', ' ').trim();
    }

    private String buildFallbackSummary(List<Article> articles) {
        String sources = articles.stream()
                .map(Article::source)
                .filter(s -> s != null && !s.isBlank())
                .distinct()
                .sorted()
                .limit(4)
                .collect(Collectors.joining(", "));
        return "Bugün " + articles.size() + " yeni Java içeriği derlendi. "
                + (sources.isBlank()
                ? "Öne çıkan başlıklar proje gelişmeleri, arayüz iyileştirmeleri ve topluluk duyuruları üzerine."
                : "Öne çıkan kaynaklar: " + sources + ". İçerikler ağırlıkla JVM, dil özellikleri ve araç ekosistemindeki yenilikleri kapsıyor.");
    }

    private void postToWebhook(String webhookUrl, String payload) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(webhookUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            log.warning("Slack webhook hatası: " + response.statusCode() + " — " + response.body());
        }
    }

    private String channelEmoji(String channel) {
        return switch (channel) {
            case "amber"    -> "🟠";
            case "valhalla" -> "🟣";
            case "loom"     -> "🟢";
            case "leyden"   -> "🔵";
            case "panama"   -> "🟡";
            default         -> "☕";
        };
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
