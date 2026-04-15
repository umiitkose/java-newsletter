package com.javadigest.fetcher;

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

    private static final Set<String> TRACKED_AUTHORS = Set.of(
            "Brian Goetz", "Ron Pressler", "Gavin Bierman",
            "Maurizio Cimadamore", "Mark Reinhold", "Dan Smith",
            "Angelos Bimpoudis", "Viktor Klang", "Alan Bateman", "Paul Sandoz"
    );

    private static final List<String[]> LISTS = List.of(
            new String[]{"amber-spec-experts",
                    "https://mail.openjdk.org/pipermail/amber-spec-experts/"},
            new String[]{"valhalla-spec-observers",
                    "https://mail.openjdk.org/pipermail/valhalla-spec-observers/"},
            new String[]{"loom-dev",
                    "https://mail.openjdk.org/pipermail/loom-dev/"}
    );

    public List<Article> scrapeRecentMessages() {
        List<Article> results = new ArrayList<>();

        for (String[] listInfo : LISTS) {
            String listName = listInfo[0];
            String baseUrl = listInfo[1];
            try {
                results.addAll(scrapePipermail(listName, baseUrl));
            } catch (Exception e) {
                log.warning(listName + " scrape hatası: " + e.getMessage());
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

        // Pipermail index: <A href="2026-February/date.html">[ Date ]</A>
        Elements dateLinks = index.select("a[href$=/date.html]");
        if (dateLinks.isEmpty()) {
            log.fine(listName + ": pipermail'de aylık arşiv bulunamadı.");
            return results;
        }

        // İlk link en güncel ay (pipermail ters kronolojik sırada)
        String latestHref = dateLinks.first().attr("href");
        String monthUrl = latestHref.startsWith("http")
                ? latestHref
                : baseUrl + latestHref;

        Document monthPage = Jsoup.connect(monthUrl)
                .userAgent(USER_AGENT)
                .timeout(TIMEOUT)
                .followRedirects(true)
                .get();

        // Pipermail date page format:
        // <LI><A HREF="004351.html">Subject</A><A NAME="4351">&nbsp;</A>
        // <I>Author Name</I>
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
        for (String author : TRACKED_AUTHORS) {
            if (lower.contains(author.toLowerCase())) {
                return author;
            }
        }
        return null;
    }
}
