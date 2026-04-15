package com.javadigest.notifier;

import com.javadigest.model.Article;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.logging.Logger;

/**
 * Tüm makaleleri tek bir Telegram kanalına/sohbetine gönderir.
 *
 * Gerekli env değişkenleri:
 *   TELEGRAM_BOT_TOKEN  → BotFather'dan alınan token
 *   TELEGRAM_CHAT_ID    → Kanal ID'si veya kullanıcı ID'si
 */
public class TelegramNotifier implements Notifier {

    private static final Logger log = Logger.getLogger(TelegramNotifier.class.getName());
    private static final String API_BASE = "https://api.telegram.org/bot";

    private final String botToken;
    private final String chatId;
    private final HttpClient http;

    public TelegramNotifier(String botToken, String chatId) {
        this.botToken = botToken;
        this.chatId   = chatId;
        this.http     = HttpClient.newHttpClient();
    }

    @Override
    public void send(List<Article> articles) throws Exception {
        if (articles.isEmpty()) return;

        String fullMessage = buildMessage(articles);

        if (fullMessage.length() <= 4096) {
            sendMessage(fullMessage);
            return;
        }

        String header = "☕ *Java Digest — " + java.time.LocalDate.now() + "*\n\n";
        sendMessage(header);

        for (Article a : articles) {
            String msg = "👤 *" + escapeMarkdown(a.author()) + "*\n"
                    + "[" + escapeMarkdown(a.title()) + "](" + a.url() + ")\n"
                    + "_" + escapeMarkdown(a.source()) + "_";
            sendMessage(msg);
            Thread.sleep(300);
        }
    }

    private String buildMessage(List<Article> articles) {
        StringBuilder sb = new StringBuilder();
        sb.append("☕ *Java Digest — Günlük Özet*\n");
        sb.append("📅 ").append(java.time.LocalDate.now()).append("\n\n");

        articles.stream()
                .collect(java.util.stream.Collectors.groupingBy(Article::author))
                .forEach((author, items) -> {
                    sb.append("👤 *").append(escapeMarkdown(author)).append("*\n");
                    for (Article a : items) {
                        sb.append("  • [").append(escapeMarkdown(a.title())).append("](").append(a.url()).append(")\n");
                        if (a.tags() != null && !a.tags().isBlank()) {
                            sb.append("    _").append(escapeMarkdown(a.tags())).append("_\n");
                        }
                    }
                    sb.append("\n");
                });

        return sb.toString();
    }

    private void sendMessage(String text) throws Exception {
        String url = API_BASE + botToken + "/sendMessage";

        String body = "chat_id=" + URLEncoder.encode(chatId, StandardCharsets.UTF_8)
                + "&text=" + URLEncoder.encode(text, StandardCharsets.UTF_8)
                + "&parse_mode=Markdown"
                + "&disable_web_page_preview=true";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            log.warning("Telegram API hatası: " + response.statusCode() + " — " + response.body());
        }
    }

    private String escapeMarkdown(String text) {
        if (text == null) return "";
        return text.replace("_", "\\_")
                   .replace("*", "\\*")
                   .replace("`", "\\`")
                   .replace("[", "\\[");
    }
}
