package com.javadigest.fetcher;

import com.javadigest.model.Article;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;

import java.net.URL;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

public class RssFetcher {

    private static final Logger log = Logger.getLogger(RssFetcher.class.getName());

    /**
     * inside.java'nın genel RSS feed'i — author adına göre filtrele.
     * URL: https://inside.java/feed.xml
     */
    private static final String INSIDE_JAVA_RSS = "https://inside.java/feed.xml";

    /**
     * Takip edilen yazar adları (inside.java profil adlarıyla eşleşmeli)
     */
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
        try {
            SyndFeedInput input = new SyndFeedInput();
            SyndFeed feed = input.build(new XmlReader(new URL(INSIDE_JAVA_RSS)));

            for (SyndEntry entry : feed.getEntries()) {
                String author = entry.getAuthor() != null ? entry.getAuthor().trim() : "";
                if (author.isEmpty() && !entry.getContributors().isEmpty()) {
                    author = entry.getContributors().get(0).getName();
                }
                if (author.isEmpty() && !entry.getForeignMarkup().isEmpty()) {
                    // Atom <author><name> tag'i
                    author = entry.getForeignMarkup().stream()
                            .filter(e -> e.getName().equals("author"))
                            .map(e -> e.getChildText("name"))
                            .filter(s -> s != null && !s.isEmpty())
                            .findFirst().orElse("");
                }

                // Yazar kontrolü — case-insensitive kısmi eşleşme
                String finalAuthor = author;
                boolean isTracked = TRACKED_AUTHORS.stream()
                        .anyMatch(a -> finalAuthor.toLowerCase().contains(a.toLowerCase()));

                if (!isTracked) continue;

                String url   = entry.getLink();
                String title = entry.getTitle();
                LocalDate date = toLocalDate(entry.getPublishedDate());

                // Etiketleri topla
                String tags = entry.getCategories().stream()
                        .map(c -> c.getName())
                        .reduce("", (a, b) -> a.isEmpty() ? b : a + ", " + b);

                results.add(new Article(url, title, url, author, "inside.java", date, tags));
            }
        } catch (Exception e) {
            log.warning("inside.java RSS fetch hatası, tekrar deneniyor: " + e.getMessage());
            try {
                Thread.sleep(2000);
                // aynı fetch kodunu tekrar çalıştır
                SyndFeed feed = new SyndFeedInput().build(new XmlReader(new URL(INSIDE_JAVA_RSS)));
                for (SyndEntry entry : feed.getEntries()) { /* aynı döngü */ }
            } catch (Exception retryEx) {
                log.warning("Retry da başarısız: " + retryEx.getMessage());
            }
        }
        return results;
    }

    public List<Article> fetchInfoQ() {
        List<Article> results = new ArrayList<>();
        // InfoQ Java RSS
        String url = "https://feed.infoq.com/java";
        try {
            SyndFeedInput input = new SyndFeedInput();
            SyndFeed feed = input.build(new XmlReader(new URL(url)));

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
     * OpenJDK mailing list arşivi RSS feed'leri.
     * Her liste için ayrı URL kullanılır.
     */
    public List<Article> fetchMailingLists() {
        List<Article> results = new ArrayList<>();

        // OpenJDK listelerinin RSS beslemeleri (Hyperkitty formatı)
        List<String[]> lists = List.of(
                new String[]{"amber-spec-experts",
                        "https://mail.openjdk.org/hyperkitty/list/amber-spec-experts@openjdk.org/latest/?format=rss"},
                new String[]{"valhalla-spec-observers",
                        "https://mail.openjdk.org/hyperkitty/list/valhalla-spec-observers@openjdk.org/latest/?format=rss"},
                new String[]{"loom-dev",
                        "https://mail.openjdk.org/hyperkitty/list/loom-dev@openjdk.org/latest/?format=rss"}
        );

        for (String[] listInfo : lists) {
            String listName = listInfo[0];
            String url      = listInfo[1];
            try {
                SyndFeedInput input = new SyndFeedInput();
                SyndFeed feed = input.build(new XmlReader(new URL(url)));

                for (SyndEntry entry : feed.getEntries()) {
                    String author = entry.getAuthor() != null ? entry.getAuthor().trim() : "";
                    boolean isTracked = TRACKED_AUTHORS.stream()
                            .anyMatch(a -> author.toLowerCase().contains(a.toLowerCase()));
                    if (!isTracked) continue;

                    String articleUrl = entry.getLink();
                    results.add(new Article(
                            articleUrl,
                            entry.getTitle(),
                            articleUrl,
                            author,
                            "openjdk-" + listName,
                            toLocalDate(entry.getPublishedDate()),
                            listName
                    ));
                }
            } catch (Exception e) {
                log.warning(listName + " fetch hatası: " + e.getMessage());
            }
        }
        return results;
    }

    private LocalDate toLocalDate(Date date) {
        if (date == null) return LocalDate.now();
        return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }
}
