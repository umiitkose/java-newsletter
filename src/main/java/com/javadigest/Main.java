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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.logging.Logger;

public class Main {

    private static final Logger log = Logger.getLogger(Main.class.getName());
    private static final Set<String> PRIORITY_PROJECTS = Set.of(
            "amber", "valhalla", "loom", "panama", "leyden", "openjdk-jep"
    );
    private static final Set<String> DETAILED_PROJECT_CHANNELS = Set.of("amber", "valhalla", "java-jep");
    private static final int DEFAULT_SUMMARY_LIMIT = 12;
    private static final int DEFAULT_PROJECT_DETAIL_LIMIT = 20;

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
            int summaryLimit = readSummaryLimit();
            List<Article> summaryCandidates = pickSummaryCandidates(newArticles, summaryLimit);
            log.info("AI ozetleme kapsamı: " + summaryCandidates.size() + " makale (limit=" + summaryLimit + ").");

            AISummarizer summarizer = new AISummarizer(
                    config.getAi().getProvider(),
                    config.getAi().isEnabled()
            );
            String aiSummary = summarizer.summarize(summaryCandidates);
            if (!aiSummary.isBlank()) {
                log.info("Özet oluşturuldu (" + aiSummary.length() + " karakter).");
            }
            Map<String, String> perArticleSummaries = summarizer.summarizePerArticle(summaryCandidates);
            Map<String, String> channelDetailedSummaries = buildDetailedChannelSummaries(summarizer, newArticles);

            // ── 4. Slack bildirimi gönder ───────────────────────────────────
            boolean hasError = false;

            SlackNotifier slack = SlackNotifier.fromEnv();
            if (slack.hasWebhooks()) {
                try {
                    slack.sendDigest(newArticles, aiSummary, perArticleSummaries, channelDetailedSummaries);
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

    private static int readSummaryLimit() {
        String env = System.getenv("SUMMARY_MAX_ARTICLES");
        if (env == null || env.isBlank()) return DEFAULT_SUMMARY_LIMIT;
        try {
            return Math.max(1, Integer.parseInt(env.trim()));
        } catch (NumberFormatException e) {
            return DEFAULT_SUMMARY_LIMIT;
        }
    }

    private static List<Article> pickSummaryCandidates(List<Article> articles, int limit) {
        List<Article> prioritized = articles.stream()
                .filter(Main::isPriorityProjectArticle)
                .sorted(Comparator.comparing(Article::publishedDate).reversed())
                .limit(limit)
                .toList();
        if (!prioritized.isEmpty()) {
            return prioritized;
        }
        return articles.stream()
                .sorted(Comparator.comparing(Article::publishedDate).reversed())
                .limit(limit)
                .toList();
    }

    private static boolean isPriorityProjectArticle(Article article) {
        String source = article.source() != null ? article.source().toLowerCase() : "";
        String tags = article.tags() != null ? article.tags().toLowerCase() : "";
        String combined = source + " " + tags;
        return PRIORITY_PROJECTS.stream().anyMatch(combined::contains);
    }

    private static Map<String, String> buildDetailedChannelSummaries(AISummarizer summarizer, List<Article> allNewArticles) {
        int detailLimit = readProjectDetailLimit();
        Map<String, String> result = new HashMap<>();

        for (String channel : DETAILED_PROJECT_CHANNELS) {
            List<Article> projectArticles = allNewArticles.stream()
                    .filter(article -> belongsToProjectChannel(article, channel))
                    .sorted(Comparator.comparing(Article::publishedDate).reversed())
                    .limit(detailLimit)
                    .toList();
            if (projectArticles.isEmpty()) {
                continue;
            }

            String summary = summarizer.summarizeDetailedProject(channel, projectArticles);
            if (summary != null && !summary.isBlank()) {
                result.put(channel, summary);
                log.info("Detayli " + channel + " ozeti olusturuldu (" + projectArticles.size() + " makale).");
            }
        }

        return result;
    }

    private static int readProjectDetailLimit() {
        String env = System.getenv("PROJECT_DETAIL_MAX_ARTICLES");
        if (env == null || env.isBlank()) return DEFAULT_PROJECT_DETAIL_LIMIT;
        try {
            return Math.max(1, Integer.parseInt(env.trim()));
        } catch (NumberFormatException e) {
            return DEFAULT_PROJECT_DETAIL_LIMIT;
        }
    }

    private static boolean belongsToProjectChannel(Article article, String channel) {
        String source = article.source() != null ? article.source().toLowerCase() : "";
        String tags = article.tags() != null ? article.tags().toLowerCase() : "";
        String combined = source + " " + tags;
        if ("java-jep".equals(channel)) {
            return combined.contains("openjdk-jep") || combined.contains("jep");
        }
        return combined.contains(channel.toLowerCase());
    }
}
