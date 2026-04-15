package com.javadigest.fetcher;

import com.javadigest.model.Article;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;

import java.net.HttpURLConnection;
import java.net.URI;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

public class RssFetcher {

    private static final Logger log = Logger.getLogger(RssFetcher.class.getName());
    private static final int CONNECT_TIMEOUT = 10_000;
    private static final int READ_TIMEOUT = 15_000;
    private static final String USER_AGENT = "Java-Digest-Bot/1.0 (https://github.com/umiitkose/java-newsletter)";

    private static final String INSIDE_JAVA_RSS = "https://inside.java/feed.xml";

    private static final Set<String> TRACKED_AUTHORS = Set.of(
            "Brian Goetz",
            "Ron Pressler",
            "Gavin Bierman",
            "Maurizio Cimadamore",
            "Mark Reinhold",
            "Dan Smith",
            "Angelos Bimpoudis",
            "Viktor Klang",
            "Alan Bateman",
            "Paul Sandoz"
    );

    public List<Article> fetchInsideJava() {
        List<Article> results = new ArrayList<>();
        int maxRetries = 2;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                SyndFeed feed = fetchFeed(INSIDE_JAVA_RSS);
                for (SyndEntry entry : feed.getEntries()) {
                    Article article = parseInsideJavaEntry(entry);
                    if (article != null) {
                        results.add(article);
                    }
                }
                break;
            } catch (Exception e) {
                log.warning("inside.java RSS fetch hatası (deneme " + attempt + "/" + maxRetries + "): " + e.getMessage());
                if (attempt < maxRetries) {
                    try { Thread.sleep(2000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                }
            }
        }
        return results;
    }

    private Article parseInsideJavaEntry(SyndEntry entry) {
        String author = entry.getAuthor() != null ? entry.getAuthor().trim() : "";
        if (author.isEmpty() && !entry.getContributors().isEmpty()) {
            author = entry.getContributors().get(0).getName();
        }
        if (author.isEmpty() && !entry.getForeignMarkup().isEmpty()) {
            author = entry.getForeignMarkup().stream()
                    .filter(e -> e.getName().equals("author"))
                    .map(e -> e.getChildText("name"))
                    .filter(s -> s != null && !s.isEmpty())
                    .findFirst().orElse("");
        }

        String finalAuthor = author;
        boolean isTracked = TRACKED_AUTHORS.stream()
                .anyMatch(a -> finalAuthor.toLowerCase().contains(a.toLowerCase()));
        if (!isTracked) return null;

        String url = entry.getLink();
        String title = entry.getTitle();
        LocalDate date = toLocalDate(entry.getPublishedDate());

        String tags = entry.getCategories().stream()
                .map(c -> c.getName())
                .reduce("", (a, b) -> a.isEmpty() ? b : a + ", " + b);

        return new Article(url, title, url, author, "inside.java", date, tags);
    }

    public List<Article> fetchInfoQ() {
        List<Article> results = new ArrayList<>();
        String url = "https://feed.infoq.com/java";
        try {
            SyndFeed feed = fetchFeed(url);
            for (SyndEntry entry : feed.getEntries()) {
                String author = entry.getAuthor() != null ? entry.getAuthor().trim() : "";
                boolean isTracked = TRACKED_AUTHORS.stream()
                        .anyMatch(a -> author.toLowerCase().contains(a.toLowerCase()));
                if (!isTracked) continue;

                String articleUrl = entry.getLink();
                results.add(new Article(
                        articleUrl, entry.getTitle(), articleUrl, author,
                        "infoq", toLocalDate(entry.getPublishedDate()), "Java"
                ));
            }
        } catch (Exception e) {
            log.warning("InfoQ RSS fetch hatası: " + e.getMessage());
        }
        return results;
    }

    /**
     * OpenJDK mailing list arşivi.
     * Pipermail/Hyperkitty standart RSS sunmuyor — bu metot best-effort çalışır.
     * Boş dönerse Main.java'da MailingListScraper devreye girer.
     */
    public List<Article> fetchMailingLists() {
        List<Article> results = new ArrayList<>();

        List<String[]> lists = List.of(
                new String[]{"amber-spec-experts",
                        "https://mail.openjdk.org/archives/list/amber-spec-experts@openjdk.org/feed/"},
                new String[]{"valhalla-spec-observers",
                        "https://mail.openjdk.org/archives/list/valhalla-spec-observers@openjdk.org/feed/"},
                new String[]{"loom-dev",
                        "https://mail.openjdk.org/archives/list/loom-dev@openjdk.org/feed/"}
        );

        for (String[] listInfo : lists) {
            String listName = listInfo[0];
            String url = listInfo[1];
            try {
                SyndFeed feed = fetchFeed(url);
                for (SyndEntry entry : feed.getEntries()) {
                    String author = entry.getAuthor() != null ? entry.getAuthor().trim() : "";
                    // RSS author formatı: "email@domain.com (Display Name)"
                    String displayName = extractDisplayName(author);

                    boolean isTracked = TRACKED_AUTHORS.stream()
                            .anyMatch(a -> displayName.toLowerCase().contains(a.toLowerCase()));
                    if (!isTracked) continue;

                    String articleUrl = entry.getLink();
                    results.add(new Article(
                            articleUrl, entry.getTitle(), articleUrl, displayName,
                            "openjdk-" + listName,
                            toLocalDate(entry.getPublishedDate()),
                            listName
                    ));
                }
                log.info(listName + " RSS: " + results.size() + " takip edilen mesaj bulundu.");
            } catch (Exception e) {
                log.warning(listName + " RSS fetch hatası: " + e.getMessage());
            }
        }
        return results;
    }

    private SyndFeed fetchFeed(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setInstanceFollowRedirects(true);
        try (XmlReader reader = new XmlReader(conn)) {
            return new SyndFeedInput().build(reader);
        }
    }

    /** "email@domain.com (Display Name)" formatından display name'i çıkarır */
    private String extractDisplayName(String author) {
        if (author == null || author.isEmpty()) return "";
        int parenStart = author.indexOf('(');
        int parenEnd = author.lastIndexOf(')');
        if (parenStart >= 0 && parenEnd > parenStart) {
            return author.substring(parenStart + 1, parenEnd).trim();
        }
        return author;
    }

    private LocalDate toLocalDate(Date date) {
        if (date == null) return LocalDate.now();
        return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }
}
