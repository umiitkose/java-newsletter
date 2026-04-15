package com.javadigest.fetcher;

import com.javadigest.config.DigestConfig;
import com.javadigest.model.Article;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;

import java.net.HttpURLConnection;
import java.net.URI;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.logging.Logger;

public class RssFetcher {

    private static final Logger log = Logger.getLogger(RssFetcher.class.getName());
    private static final int CONNECT_TIMEOUT = 10_000;
    private static final int READ_TIMEOUT = 15_000;
    private static final String USER_AGENT = "Java-Digest-Bot/1.0 (https://github.com/umiitkose/java-newsletter)";

    private final Set<String> trackedAuthors;
    private final Set<String> keywords;
    private final List<DigestConfig.RssSource> authorFilteredFeeds;
    private final List<DigestConfig.RssSource> communityFeeds;
    private final List<DigestConfig.MailingListEntry> mailingLists;

    public RssFetcher(DigestConfig config) {
        this.trackedAuthors = Set.copyOf(config.getAuthors());
        this.keywords = Set.copyOf(config.getKeywords());
        this.authorFilteredFeeds = config.getRss().getAuthorFiltered();
        this.communityFeeds = config.getRss().getCommunity();
        this.mailingLists = config.getMailingLists();
    }

    /**
     * Yazar filtreli RSS kaynakları (inside.java, InfoQ gibi).
     * Sadece takip edilen yazarların içerikleri döner.
     */
    public List<Article> fetchAuthorFilteredFeeds() {
        List<Article> results = new ArrayList<>();
        for (DigestConfig.RssSource source : authorFilteredFeeds) {
            try {
                SyndFeed feed = fetchFeed(source.getUrl());
                for (SyndEntry entry : feed.getEntries()) {
                    String author = resolveAuthor(entry);
                    if (!isTrackedAuthor(author)) continue;

                    String url = entry.getLink();
                    String tags = extractTags(entry);
                    results.add(new Article(
                            url, entry.getTitle(), url, author,
                            source.getName(),
                            toLocalDate(entry.getPublishedDate()),
                            tags
                    ));
                }
                log.info(source.getName() + " RSS: " + results.size() + " takip edilen makale.");
            } catch (Exception e) {
                log.warning(source.getName() + " RSS fetch hatası: " + e.getMessage());
            }
        }
        return results;
    }

    /**
     * Topluluk blogları (dev.java, Foojay, Baeldung, DZone, Spring gibi).
     * Anahtar kelime filtresi uygular — tüm makaleleri değil, ilgili olanları döndürür.
     */
    public List<Article> fetchCommunityBlogs() {
        List<Article> results = new ArrayList<>();
        for (DigestConfig.RssSource source : communityFeeds) {
            int found = 0;
            try {
                SyndFeed feed = fetchFeed(source.getUrl());
                for (SyndEntry entry : feed.getEntries()) {
                    String title = entry.getTitle() != null ? entry.getTitle() : "";
                    String tags = extractTags(entry);
                    String combined = (title + " " + tags).toLowerCase();

                    boolean relevant = isTrackedAuthor(resolveAuthor(entry))
                            || keywords.stream().anyMatch(k -> combined.contains(k.toLowerCase()));

                    if (!relevant) continue;

                    String url = entry.getLink();
                    String author = entry.getAuthor() != null ? entry.getAuthor().trim() : "";
                    results.add(new Article(
                            url, title, url, author,
                            source.getName(),
                            toLocalDate(entry.getPublishedDate()),
                            tags
                    ));
                    found++;
                }
                if (found > 0) {
                    log.info(source.getName() + " RSS: " + found + " ilgili makale.");
                }
            } catch (Exception e) {
                log.warning(source.getName() + " RSS fetch hatası: " + e.getMessage());
            }
        }
        return results;
    }

    /**
     * OpenJDK mailing list RSS feed'leri (Hyperkitty arşivi).
     * Takip edilen yazarların mesajlarını döndürür.
     */
    public List<Article> fetchMailingLists() {
        List<Article> results = new ArrayList<>();

        for (DigestConfig.MailingListEntry ml : mailingLists) {
            String feedUrl = "https://mail.openjdk.org/archives/list/" + ml.getEmail() + "/feed/";
            int found = 0;
            try {
                SyndFeed feed = fetchFeed(feedUrl);
                for (SyndEntry entry : feed.getEntries()) {
                    String author = entry.getAuthor() != null ? entry.getAuthor().trim() : "";
                    String displayName = extractDisplayName(author);

                    if (!isTrackedAuthor(displayName)) continue;

                    String articleUrl = entry.getLink();
                    results.add(new Article(
                            articleUrl, entry.getTitle(), articleUrl, displayName,
                            "openjdk-" + ml.getName(),
                            toLocalDate(entry.getPublishedDate()),
                            ml.getName()
                    ));
                    found++;
                }
                if (found > 0) {
                    log.info(ml.getName() + " RSS: " + found + " takip edilen mesaj.");
                }
            } catch (Exception e) {
                log.warning(ml.getName() + " RSS fetch hatası: " + e.getMessage());
            }
        }
        return results;
    }

    // ── Yardımcı metotlar ──

    SyndFeed fetchFeed(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setInstanceFollowRedirects(true);
        try (XmlReader reader = new XmlReader(conn)) {
            return new SyndFeedInput().build(reader);
        }
    }

    private String resolveAuthor(SyndEntry entry) {
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
        return extractDisplayName(author);
    }

    private boolean isTrackedAuthor(String author) {
        if (author == null || author.isEmpty()) return false;
        return trackedAuthors.stream()
                .anyMatch(a -> author.toLowerCase().contains(a.toLowerCase()));
    }

    private String extractDisplayName(String author) {
        if (author == null || author.isEmpty()) return "";
        int parenStart = author.indexOf('(');
        int parenEnd = author.lastIndexOf(')');
        if (parenStart >= 0 && parenEnd > parenStart) {
            return author.substring(parenStart + 1, parenEnd).trim();
        }
        return author;
    }

    private String extractTags(SyndEntry entry) {
        if (entry.getCategories() == null || entry.getCategories().isEmpty()) return "";
        return entry.getCategories().stream()
                .map(c -> c.getName())
                .filter(Objects::nonNull)
                .reduce("", (a, b) -> a.isEmpty() ? b : a + ", " + b);
    }

    private LocalDate toLocalDate(Date date) {
        if (date == null) return LocalDate.now();
        return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }
}
