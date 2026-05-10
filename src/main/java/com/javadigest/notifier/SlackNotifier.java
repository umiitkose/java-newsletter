package com.javadigest.notifier;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javadigest.fetcher.JepEvent;
import com.javadigest.model.Article;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 *   Java JEP → SLACK_WEBHOOK_JEP
 *   Java News→ SLACK_WEBHOOK_JAVA_NEWS  (anlık JEP olayları için)
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
        addIfPresent(webhooks, "java-jep", "SLACK_WEBHOOK_JEP");
        addIfPresent(webhooks, "java-news","SLACK_WEBHOOK_JAVA_NEWS");
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
            Map<String, String> articleSummaries,
            Map<String, String> channelSummaries
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

            String payload = buildPayload(
                    channelKey,
                    channelArticles,
                    generalSummary,
                    articleSummaries,
                    channelSummaries
            );
            postToWebhook(webhookUrl, payload);
            log.info("Slack #" + channelKey + ": " + channelArticles.size() + " makale gönderildi.");
        }
    }

    /**
     * Her makaleyi tags alanına bakarak bir kanala atar.
     * Birden fazla tag varsa en spesifik projeye gönderir.
     */
    private Map<String, List<Article>> groupByChannel(List<Article> articles) {
        List<String> priority = List.of("openjdk-jep", "valhalla", "loom", "amber", "leyden", "panama");

        return articles.stream().collect(Collectors.groupingBy(article -> {
            String tags = article.tags() != null ? article.tags().toLowerCase() : "";
            String source = article.source() != null ? article.source().toLowerCase() : "";
            String combined = tags + " " + source;

            for (String project : priority) {
                if (combined.contains(project)) {
                    return "openjdk-jep".equals(project) ? "java-jep" : project;
                }
            }
            return "general";
        }));
    }

    /** Slack Block Kit formatında özet odaklı mesaj oluştur */
    private String buildPayload(
            String channelKey,
            List<Article> articles,
            String generalSummary,
            Map<String, String> articleSummaries,
            Map<String, String> channelSummaries
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
        if (channelSummaries != null
                && channelSummaries.containsKey(channelKey)
                && channelSummaries.get(channelKey) != null
                && !channelSummaries.get(channelKey).isBlank()) {
            effectiveSummary = channelSummaries.get(channelKey);
        } else if ("general".equals(channelKey) && generalSummary != null && !generalSummary.isBlank()) {
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
            case "amber"     -> "🟠";
            case "valhalla"  -> "🟣";
            case "loom"      -> "🟢";
            case "leyden"    -> "🔵";
            case "panama"    -> "🟡";
            case "java-jep"  -> "🧭";
            case "java-news" -> "📰";
            default          -> "☕";
        };
    }

    private String capitalize(String channel) {
        if (channel == null || channel.isEmpty()) return channel;
        if ("java-jep".equals(channel)) return "JEP";
        if ("java-news".equals(channel)) return "News";
        return Character.toUpperCase(channel.charAt(0)) + channel.substring(1);
    }

    // ── JEP Event akışı ──────────────────────────────────────────────────────

    /**
     * JEP olaylarını {@code channelKey} kanalına anlık olarak (her olay ayrı mesaj)
     * gönderir. Webhook tanımlı değilse sessizce general'a düşer; o da yoksa
     * yalnızca log üretir. {@code spotlightReleases} içine giren olaylar başlıkta
     * vurgulanır (örn. JDK 27 odaklı bildirim).
     */
    public void sendJepEvents(List<JepEvent> events, Set<Integer> spotlightReleases, String channelKey) {
        if (events == null || events.isEmpty()) return;

        String key = (channelKey == null || channelKey.isBlank()) ? "java-news" : channelKey;
        String webhookUrl = channelWebhooks.get(key);
        if (webhookUrl == null) {
            webhookUrl = channelWebhooks.get("general");
        }
        if (webhookUrl == null) {
            log.warning("JEP olayları için Slack webhook yok (" + key + " ve general tanımsız), atlanıyor.");
            return;
        }

        Set<Integer> spotlight = spotlightReleases == null ? Set.of() : spotlightReleases;
        int sent = 0;
        for (JepEvent event : events) {
            try {
                String payload = buildJepEventPayload(event, spotlight);
                postToWebhook(webhookUrl, payload);
                sent++;
            } catch (Exception e) {
                log.warning("JEP olayı gönderilemedi (" + event.jepId() + "): " + e.getMessage());
            }
        }
        log.info("Slack #" + key + ": " + sent + "/" + events.size() + " JEP olayı gönderildi.");
    }

    private String buildJepEventPayload(JepEvent event, Set<Integer> spotlightReleases) throws Exception {
        boolean spotlight = event.newRelease() != null && spotlightReleases.contains(event.newRelease());
        String emoji = jepEventEmoji(event.type());
        String releaseBadge = event.newRelease() != null ? " · *JDK " + event.newRelease() + "*" : "";
        if (spotlight) releaseBadge += " ⭐";

        String header = emoji + " " + event.headline();
        if (header.length() > 150) header = header.substring(0, 147) + "…";

        StringBuilder body = new StringBuilder();
        body.append("*<").append(event.url()).append("|JEP ").append(event.jepId()).append(">* ")
                .append(event.title()).append(releaseBadge).append("\n");

        switch (event.type()) {
            case NEW -> body.append("Yeni bir JEP keşfedildi (statü: *")
                    .append(event.newStatus()).append("*).");
            case STATUS_CHANGED -> body.append("Statü: *")
                    .append(event.previousStatus()).append("* → *")
                    .append(event.newStatus()).append("*");
            case RELEASE_TARGETED -> body.append("Bu JEP artık *JDK ")
                    .append(event.newRelease()).append("* için hedeflendi.");
            case RELEASE_CHANGED -> body.append("Hedef release değişti: *JDK ")
                    .append(event.previousRelease()).append("* → *JDK ")
                    .append(event.newRelease()).append("*");
            case COMPLETED -> body.append("✅ JEP *")
                    .append(event.newStatus()).append("* statüsüne geçti")
                    .append(event.newRelease() != null ? " (JDK " + event.newRelease() + ").": ".");
        }

        if (event.component() != null && !event.component().isBlank()) {
            body.append("\n_Component: ").append(event.component()).append("_");
        }

        List<Map<String, Object>> blocks = new ArrayList<>();
        blocks.add(Map.of(
                "type", "header",
                "text", Map.of("type", "plain_text", "text", header, "emoji", true)
        ));
        blocks.add(Map.of(
                "type", "section",
                "text", Map.of("type", "mrkdwn", "text", body.toString())
        ));
        blocks.add(Map.of(
                "type", "context",
                "elements", List.of(Map.of(
                        "type", "mrkdwn",
                        "text", "openjdk.org/jeps  •  Java Digest Bot"
                                + (spotlight ? "  •  ⭐ takip edilen release" : "")
                ))
        ));

        return json.writeValueAsString(Map.of("blocks", blocks, "text", header));
    }

    private String jepEventEmoji(JepEvent.Type type) {
        return switch (type) {
            case NEW              -> "🆕";
            case STATUS_CHANGED   -> "🔁";
            case RELEASE_TARGETED -> "🎯";
            case RELEASE_CHANGED  -> "🔀";
            case COMPLETED        -> "✅";
        };
    }
}
