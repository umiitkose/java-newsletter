package com.javadigest.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.File;
import java.io.InputStream;
import java.util.List;
import java.util.logging.Logger;

@JsonIgnoreProperties(ignoreUnknown = true)
public class DigestConfig {

    private static final Logger log = Logger.getLogger(DigestConfig.class.getName());

    private List<String> authors = List.of();
    private List<String> keywords = List.of();
    private RssConfig rss = new RssConfig();
    private List<MailingListEntry> mailingLists = List.of();
    private JepConfig jep = new JepConfig();
    private AiConfig ai = new AiConfig();
    private PagesConfig pages = new PagesConfig();

    public static DigestConfig load() {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        File configFile = new File("config.yml");
        if (configFile.exists()) {
            try {
                DigestConfig cfg = mapper.readValue(configFile, DigestConfig.class);
                log.info("config.yml yüklendi.");
                return cfg;
            } catch (Exception e) {
                log.warning("config.yml okunamadı, varsayılan kullanılıyor: " + e.getMessage());
            }
        }
        try (InputStream is = DigestConfig.class.getResourceAsStream("/config.yml")) {
            if (is != null) {
                return mapper.readValue(is, DigestConfig.class);
            }
        } catch (Exception e) {
            log.warning("Classpath config.yml okunamadı: " + e.getMessage());
        }
        log.info("config.yml bulunamadı, varsayılan yapılandırma kullanılıyor.");
        return new DigestConfig();
    }

    // ── Getters & Setters ──

    public List<String> getAuthors() { return authors; }
    public void setAuthors(List<String> authors) { this.authors = authors; }

    public List<String> getKeywords() { return keywords; }
    public void setKeywords(List<String> keywords) { this.keywords = keywords; }

    public RssConfig getRss() { return rss; }
    public void setRss(RssConfig rss) { this.rss = rss; }

    public List<MailingListEntry> getMailingLists() { return mailingLists; }
    public void setMailingLists(List<MailingListEntry> mailingLists) { this.mailingLists = mailingLists; }

    public JepConfig getJep() { return jep; }
    public void setJep(JepConfig jep) { this.jep = jep; }

    public AiConfig getAi() { return ai; }
    public void setAi(AiConfig ai) { this.ai = ai; }

    public PagesConfig getPages() { return pages; }
    public void setPages(PagesConfig pages) { this.pages = pages; }

    // ── Nested config classes ──

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RssConfig {
        private List<RssSource> authorFiltered = List.of();
        private List<RssSource> community = List.of();

        public List<RssSource> getAuthorFiltered() { return authorFiltered; }
        public void setAuthorFiltered(List<RssSource> authorFiltered) { this.authorFiltered = authorFiltered; }
        public List<RssSource> getCommunity() { return community; }
        public void setCommunity(List<RssSource> community) { this.community = community; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RssSource {
        private String name = "";
        private String url = "";

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MailingListEntry {
        private String name = "";
        private String email = "";

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class JepConfig {
        private boolean enabled = false;
        private List<String> trackedProjects = List.of();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public List<String> getTrackedProjects() { return trackedProjects; }
        public void setTrackedProjects(List<String> trackedProjects) { this.trackedProjects = trackedProjects; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AiConfig {
        private boolean enabled = false;
        private String provider = "openai";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PagesConfig {
        private boolean enabled = false;
        private String outputDir = "docs";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getOutputDir() { return outputDir; }
        public void setOutputDir(String outputDir) { this.outputDir = outputDir; }
    }
}
