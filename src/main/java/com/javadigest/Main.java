package com.javadigest;

import com.javadigest.fetcher.MailingListScraper;
import com.javadigest.fetcher.RssFetcher;
import com.javadigest.model.Article;
import com.javadigest.notifier.Notifier;
import com.javadigest.notifier.SlackNotifier;
import com.javadigest.notifier.TelegramNotifier;
import com.javadigest.state.StateManager;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

public class Main {

    private static final Logger log = Logger.getLogger(Main.class.getName());

    public static void main(String[] args) throws Exception {
        log.info("=== Java Digest başlıyor ===");
        String mode = System.getenv("DIGEST_MODE");
        if (mode == null) mode = "daily";
        log.info("Mod: " + mode);


        // ── 1. Makaleleri topla ──────────────────────────────────────────
        List<Article> allArticles = new ArrayList<>();

        RssFetcher rss = new RssFetcher();
        allArticles.addAll(rss.fetchInsideJava());
        allArticles.addAll(rss.fetchInfoQ());

        // Mailing lists: önce RSS dene, boşsa scraper devreye girer
        List<Article> mailingArticles = rss.fetchMailingLists();
        if (mailingArticles.isEmpty()) {
            log.info("Mailing list RSS boş, scraper deneniyor...");
            mailingArticles = new MailingListScraper().scrapeRecentMessages();
        }
        allArticles.addAll(mailingArticles);

        if ("weekly".equals(mode)) {
            allArticles = allArticles.stream()
                    .filter(a -> a.publishedDate().isAfter(LocalDate.now().minusDays(7)))
                    .toList();
        }

        log.info("Toplam çekilen: " + allArticles.size() + " makale");

        // ── 2. Yenileri filtrele ─────────────────────────────────────────
        StateManager state = new StateManager();
        List<Article> newArticles = state.filterNew(allArticles);
        log.info("Yeni (daha önce gönderilmemiş): " + newArticles.size() + " makale");

        if (newArticles.isEmpty()) {
            log.info("Gönderilecek yeni içerik yok, çıkılıyor.");
            return;
        }

        // ── 3. Bildirim gönder ───────────────────────────────────────────
        boolean hasError = false;

        // Telegram
        String telegramToken  = System.getenv("TELEGRAM_BOT_TOKEN");
        String telegramChatId = System.getenv("TELEGRAM_CHAT_ID");

        if (telegramToken != null && telegramChatId != null) {
            try {
                Notifier telegram = new TelegramNotifier(telegramToken, telegramChatId);
                telegram.send(newArticles);
            } catch (Exception e) {
                log.warning("Telegram gönderilemedi: " + e.getMessage());
                hasError = true;
            }
        } else {
            log.warning("TELEGRAM_BOT_TOKEN veya TELEGRAM_CHAT_ID eksik, atlanıyor.");
        }

        // Slack
        SlackNotifier slack = SlackNotifier.fromEnv();
        if (slack.hasWebhooks()) {
            try {
                slack.send(newArticles);
            } catch (Exception e) {
                log.warning("Slack gönderilemedi: " + e.getMessage());
                hasError = true;
            }
        } else {
            log.info("Slack webhook yapılandırılmamış, atlanıyor.");
        }

        // ── 4. State güncelle ────────────────────────────────────────────
        // Hata olsa bile başarıyla gönderilenlerin state'ini kaydet
        state.markAsSeen(newArticles);

        log.info("=== Java Digest tamamlandı ===");

        if (hasError) {
            System.exit(1); // GitHub Actions'ta hata olarak işaretlenir
        }
    }
}
