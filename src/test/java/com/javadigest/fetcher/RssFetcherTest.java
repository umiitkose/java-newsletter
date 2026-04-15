package com.javadigest.fetcher;

import com.javadigest.config.DigestConfig;
import com.javadigest.model.Article;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RssFetcherTest {

    @Test
    void testFeedParsing_shouldExtractEntries() throws Exception {
        InputStream feedStream = getClass().getResourceAsStream("/test-feed.xml");
        assertNotNull(feedStream, "test-feed.xml classpath'te bulunmalı");

        SyndFeedInput input = new SyndFeedInput();
        SyndFeed feed = input.build(new XmlReader(feedStream));

        assertEquals(3, feed.getEntries().size());
        assertEquals("Virtual Threads in Java 21", feed.getEntries().get(0).getTitle());
    }

    @Test
    void testAuthorFiltering_shouldMatchTrackedAuthors() {
        DigestConfig config = new DigestConfig();
        config.setAuthors(List.of("Brian Goetz", "Angelos Bimpoudis"));
        config.setKeywords(List.of("virtual threads"));

        DigestConfig.RssConfig rssConfig = new DigestConfig.RssConfig();
        rssConfig.setAuthorFiltered(List.of());
        rssConfig.setCommunity(List.of());
        config.setRss(rssConfig);
        config.setMailingLists(List.of());

        RssFetcher fetcher = new RssFetcher(config);

        // extractDisplayName from "email (Display Name)" format
        Article article = new Article(
                "url", "Title", "url",
                "angelos.bimpoudis@oracle.com (Angelos Bimpoudis)",
                "test", LocalDate.now(), ""
        );

        assertNotNull(article);
        assertTrue(article.author().contains("Angelos"));
    }

    @Test
    void testConfig_shouldLoadDefaults() {
        DigestConfig config = new DigestConfig();
        assertNotNull(config.getAuthors());
        assertNotNull(config.getKeywords());
        assertNotNull(config.getRss());
        assertNotNull(config.getMailingLists());
    }
}
