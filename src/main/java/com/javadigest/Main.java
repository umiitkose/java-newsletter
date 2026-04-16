package com.javadigest;

import com.javadigest.config.DigestConfig;
import com.javadigest.fetcher.JepTracker;
import com.javadigest.fetcher.MailingListScraper;
import com.javadigest.fetcher.RssFetcher;
import com.javadigest.generator.DigestPageGenerator;
import com.javadigest.model.Article;
import com.javadigest.notifier.SlackNotifier;
import com.javadigest.state.StateManager;
import com.javadigest.summarizer.AISummarizer;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.logging.Logger;

public class Main {

    private static final Logger log = Logger.getLogger(Main.class.getName());

    public static void main(String[] args) throws Exception {
        log.info("=== Java Digest başlıyor ===");
        String mode = System.getenv("DIGEST_MODE");
        if (mode == null) mode = "daily";
        boolean forceSummary = "true".equalsIgnoreCase(System.getenv("FORCE_SUMMARY"))
                || "test".equalsIgnoreCase(mode);
        log.info("Mod: " + mode);

        DigestConfig config = DigestConfig.load();
        log.info("AI ayarı: enabled=" + config.getAi().isEnabled()
                + ", provider=" + config.getAi().getProvider());

        // ── 1. Makaleleri paralel olarak topla ──────────────────────────────
        RssFetcher rss = new RssFetcher(config);

        record FetchTask(String name, Supplier<List<Article>> supplier) {}

        List<FetchTask> tasks = new ArrayList<>();
        tasks.add(new FetchTask("Yazar-filtreli RSS", rss::fetchAuthorFilteredFeeds));
        tasks.add(new FetchTask("Topluluk blogları", rss::fetchCommunityBlogs));
        tasks.add(new FetchTask("Mailing list RSS", rss::fetchMailingLists));

        if (config.getJep().isEnabled()) {
            JepTracker jepTracker = new JepTracker(config.getJep().getTrackedProjects());
            tasks.add(new FetchTask("JEP Tracker", jepTracker::checkForChanges));
        }

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            List<CompletableFuture<List<Article>>> futures = tasks.stream()
                    .map(task -> CompletableFuture.supplyAsync(() -> {
                        try {
                            return task.supplier().get();
                        } catch (Exception e) {
                            log.warning(task.name() + " hatası: " + e.getMessage());
                            return List.<Article>of();
                        }
                    }, executor))
                    .toList();

            List<Article> allArticles = new ArrayList<>();
            for (int i = 0; i < futures.size(); i++) {
                List<Article> result = futures.get(i).join();
                if (result.isEmpty() && tasks.get(i).name().equals("Mailing list RSS")) {
                    log.info("Mailing list RSS boş, scraper deneniyor...");
                    result = new MailingListScraper(config).scrapeRecentMessages();
                }
                allArticles.addAll(result);
            }

            if ("weekly".equals(mode)) {
                allArticles = allArticles.stream()
                        .filter(a -> a.publishedDate().isAfter(LocalDate.now().minusDays(7)))
                        .toList();
            }

            log.info("Toplam çekilen: " + allArticles.size() + " makale");

            // ── 2. Yenileri filtrele ─────────────────────────────────────────
            StateManager state = new StateManager();
            List<Article> newArticles = new ArrayList<>(state.filterNew(allArticles));
            log.info("Yeni (daha önce gönderilmemiş): " + newArticles.size() + " makale");

            if (newArticles.isEmpty()) {
                if (!forceSummary) {
                    log.info("Gönderilecek yeni içerik yok, çıkılıyor.");
                    return;
                }
                log.info("FORCE_SUMMARY aktif: son içeriklerden test özeti hazırlanıyor.");
                newArticles = allArticles.stream()
                        .sorted(Comparator.comparing(Article::publishedDate).reversed())
                        .limit(10)
                        .toList();
                if (newArticles.isEmpty()) {
                    log.info("FORCE_SUMMARY aktif ama kullanılabilir içerik yok, çıkılıyor.");
                    return;
                }
            }

            // ── 3. AI Özet (aktif değilse deterministik fallback) ───────────
            AISummarizer summarizer = new AISummarizer(
                    config.getAi().getProvider(),
                    config.getAi().isEnabled()
            );
            String aiSummary = summarizer.summarize(newArticles);
            if (!aiSummary.isBlank()) {
                log.info("Özet oluşturuldu (" + aiSummary.length() + " karakter).");
            }

            // ── 4. Slack bildirimi gönder ───────────────────────────────────
            boolean hasError = false;

            SlackNotifier slack = SlackNotifier.fromEnv();
            if (slack.hasWebhooks()) {
                try {
                    slack.sendDigest(newArticles, aiSummary);
                } catch (Exception e) {
                    log.warning("Slack gönderilemedi: " + e.getMessage());
                    hasError = true;
                }
            } else {
                log.info("Slack webhook yapılandırılmamış, atlanıyor.");
            }

            // ── 5. GitHub Pages sayfası oluştur ──────────────────────────────
            if (config.getPages().isEnabled() && !forceSummary) {
                new DigestPageGenerator(config.getPages().getOutputDir())
                        .generate(newArticles, aiSummary);
            }

            // ── 6. State güncelle ────────────────────────────────────────────
            if (!forceSummary) {
                state.markAsSeen(newArticles);
            } else {
                log.info("FORCE_SUMMARY aktif: state güncellemesi atlandı.");
            }

            log.info("=== Java Digest tamamlandı ===");

            if (hasError) {
                System.exit(1);
            }
        } finally {
            executor.shutdown();
        }
    }
}
