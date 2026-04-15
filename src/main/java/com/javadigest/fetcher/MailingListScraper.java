package com.javadigest.fetcher;

import com.javadigest.config.DigestConfig;
import com.javadigest.model.Article;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * OpenJDK mailing list arşivlerini HTML scraping ile tarayan fallback fetcher.
 * Pipermail arşiv sayfalarını okur.
 * RSS (RssFetcher.fetchMailingLists) başarısız olduğunda devreye girer.
 */
public class MailingListScraper {

    private static final Logger log = Logger.getLogger(MailingListScraper.class.getName());
    private static final String USER_AGENT =
            "Mozilla/5.0 (compatible; JavaDigestBot/1.0; +https://github.com/umiitkose/java-newsletter)";
    private static final int TIMEOUT = 20_000;

    private final Set<String> trackedAuthors;
    private final List<DigestConfig.MailingListEntry> mailingLists;

    public MailingListScraper(DigestConfig config) {
        this.trackedAuthors = Set.copyOf(config.getAuthors());
        this.mailingLists = config.getMailingLists();
    }

    public List<Article> scrapeRecentMessages() {
        List<Article> results = new ArrayList<>();
        for (DigestConfig.MailingListEntry ml : mailingLists) {
            String baseUrl = "https://mail.openjdk.org/pipermail/" + ml.getName() + "/";
            try {
                results.addAll(scrapePipermail(ml.getName(), baseUrl));
            } catch (Exception e) {
                log.warning(ml.getName() + " scrape hatası: " + e.getMessage());
            }
        }
        return results;
    }

    private List<Article> scrapePipermail(String listName, String baseUrl) throws Exception {
        List<Article> results = new ArrayList<>();

        Document index = Jsoup.connect(baseUrl)
                .userAgent(USER_AGENT)
                .timeout(TIMEOUT)
                .followRedirects(true)
                .get();

        Elements dateLinks = index.select("a[href$=/date.html]");
        if (dateLinks.isEmpty()) {
            log.fine(listName + ": pipermail'de aylık arşiv bulunamadı.");
            return results;
        }

        String latestHref = dateLinks.first().attr("href");
        String monthUrl = latestHref.startsWith("http")
                ? latestHref
                : baseUrl + latestHref;

        Document monthPage = Jsoup.connect(monthUrl)
                .userAgent(USER_AGENT)
                .timeout(TIMEOUT)
                .followRedirects(true)
                .get();

        for (Element li : monthPage.select("ul li")) {
            Element authorEl = li.selectFirst("i");
            if (authorEl == null) continue;

            String authorText = authorEl.text().trim();
            String matchedAuthor = extractAuthor(authorText);
            if (matchedAuthor == null) continue;

            Element link = li.selectFirst("a[href$=.html]:not([name])");
            if (link == null) continue;

            String msgHref = link.attr("href");
            String msgUrl = msgHref.startsWith("http")
                    ? msgHref
                    : monthUrl.replaceAll("[^/]+$", "") + msgHref;
            String title = link.text().trim();
            if (title.isEmpty()) continue;

            results.add(new Article(
                    msgUrl, title, msgUrl, matchedAuthor,
                    "openjdk-" + listName,
                    LocalDate.now(),
                    listName
            ));
        }

        if (!results.isEmpty()) {
            log.info(listName + " pipermail scrape: " + results.size() + " mesaj bulundu.");
        }
        return results;
    }

    private String extractAuthor(String text) {
        if (text == null) return null;
        String lower = text.toLowerCase();
        for (String author : trackedAuthors) {
            if (lower.contains(author.toLowerCase())) {
                return author;
            }
        }
        return null;
    }
}
