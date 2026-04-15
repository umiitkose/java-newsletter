package com.javadigest.fetcher;

import com.javadigest.model.Article;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

/**
 * RSS çalışmadığında devreye giren HTML scraper.
 * OpenJDK Hyperkitty arşiv sayfalarını okur.
 */
public class MailingListScraper {

    private static final Logger log = Logger.getLogger(MailingListScraper.class.getName());

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
            String baseUrl  = listInfo[1];
            try {
                // Ana sayfa — aylık arşiv linkleri listesi
                Document index = Jsoup.connect(baseUrl)
                        .userAgent("Mozilla/5.0 (Java digest bot)")
                        .timeout(10_000)
                        .get();

                // En son ay linkini bul (genelde son satır)
                Elements monthLinks = index.select("ul li a[href]");
                if (monthLinks.isEmpty()) continue;

                String latestMonthUrl = baseUrl + monthLinks.last().attr("href");

                // O ayın içindeki mesajları tara
                Document monthPage = Jsoup.connect(latestMonthUrl)
                        .userAgent("Mozilla/5.0 (Java digest bot)")
                        .timeout(10_000)
                        .get();

                // Her mesaj satırı: konu + gönderen
                for (Element row : monthPage.select("ul li")) {
                    String text   = row.text();
                    String author = extractAuthor(text);
                    if (author == null) continue;

                    Element link = row.selectFirst("a[href]");
                    if (link == null) continue;

                    String msgUrl = latestMonthUrl.replace("index.html", "")
                                    + link.attr("href");
                    String title  = link.text();

                    results.add(new Article(
                            msgUrl, title, msgUrl, author,
                            "openjdk-" + listName,
                            LocalDate.now(),
                            listName
                    ));
                }
            } catch (Exception e) {
                log.warning(listName + " scrape hatası: " + e.getMessage());
            }
        }
        return results;
    }

    private String extractAuthor(String rowText) {
        for (String author : TRACKED_AUTHORS) {
            // İsim satır içinde geçiyor mu?
            if (rowText.toLowerCase().contains(author.toLowerCase())) {
                return author;
            }
        }
        return null;
    }
}
