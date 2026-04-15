package com.javadigest.fetcher;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javadigest.model.Article;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.File;
import java.time.LocalDate;
import java.util.*;
import java.util.logging.Logger;

/**
 * JEP (JDK Enhancement Proposal) durum değişikliklerini takip eder.
 * https://openjdk.org/jeps/0 sayfasını tarayarak JEP durumlarını izler
 * ve durum değişikliği olduğunda Article olarak döndürür.
 */
public class JepTracker {

    private static final Logger log = Logger.getLogger(JepTracker.class.getName());
    private static final String JEPS_URL = "https://openjdk.org/jeps/0";
    private static final String JEP_STATE_FILE = "jep-state.json";
    private static final int TIMEOUT = 30_000;

    private final List<String> trackedProjects;
    private final ObjectMapper json = new ObjectMapper();

    public JepTracker(List<String> trackedProjects) {
        this.trackedProjects = trackedProjects;
    }

    public List<Article> checkForChanges() {
        List<Article> changes = new ArrayList<>();
        try {
            Map<String, JepInfo> currentJeps = scrapeJeps();
            Map<String, String> previousStates = loadState();

            for (Map.Entry<String, JepInfo> entry : currentJeps.entrySet()) {
                String jepId = entry.getKey();
                JepInfo info = entry.getValue();
                String previousStatus = previousStates.get(jepId);

                if (previousStatus != null && !previousStatus.equals(info.status)) {
                    String title = "JEP " + jepId + ": " + info.title
                            + " — " + previousStatus + " → " + info.status;
                    String url = "https://openjdk.org/jeps/" + jepId;

                    changes.add(new Article(
                            "jep-" + jepId + "-" + info.status,
                            title, url, "",
                            "openjdk-jep",
                            LocalDate.now(),
                            info.component
                    ));
                    log.info("JEP durum değişikliği: " + title);
                }
            }

            Map<String, String> newState = new HashMap<>();
            currentJeps.forEach((id, info) -> newState.put(id, info.status));
            saveState(newState);

            log.info("JEP Tracker: " + currentJeps.size() + " JEP tarandı, "
                    + changes.size() + " durum değişikliği.");
        } catch (Exception e) {
            log.warning("JEP takip hatası: " + e.getMessage());
        }
        return changes;
    }

    private Map<String, JepInfo> scrapeJeps() throws Exception {
        Map<String, JepInfo> jeps = new LinkedHashMap<>();

        Document doc = Jsoup.connect(JEPS_URL)
                .userAgent("Java-Digest-Bot/1.0")
                .timeout(TIMEOUT)
                .get();

        Elements rows = doc.select("table.jeps tbody tr, table tr");
        for (Element row : rows) {
            Elements cells = row.select("td");
            if (cells.size() < 3) continue;

            try {
                String jepLink = cells.get(0).text().trim();
                String jepId = jepLink.replaceAll("\\D+", "");
                if (jepId.isEmpty()) continue;

                String title = cells.size() > 1 ? cells.get(1).text().trim() : "";
                String status = cells.size() > 2 ? cells.get(2).text().trim() : "";
                String component = cells.size() > 3 ? cells.get(3).text().trim() : "";

                if (!isTrackedProject(title, component)) continue;

                jeps.put(jepId, new JepInfo(title, status, component));
            } catch (Exception e) {
                // skip malformed rows
            }
        }
        return jeps;
    }

    private boolean isTrackedProject(String title, String component) {
        if (trackedProjects.isEmpty()) return true;
        String combined = (title + " " + component).toLowerCase();
        return trackedProjects.stream()
                .anyMatch(p -> combined.contains(p.toLowerCase()));
    }

    private Map<String, String> loadState() {
        File file = new File(JEP_STATE_FILE);
        if (!file.exists()) return new HashMap<>();
        try {
            return json.readValue(file, new TypeReference<>() {});
        } catch (Exception e) {
            log.warning("jep-state.json okunamadı: " + e.getMessage());
            return new HashMap<>();
        }
    }

    private void saveState(Map<String, String> state) {
        try {
            json.writerWithDefaultPrettyPrinter().writeValue(new File(JEP_STATE_FILE), state);
        } catch (Exception e) {
            log.warning("jep-state.json yazılamadı: " + e.getMessage());
        }
    }

    private record JepInfo(String title, String status, String component) {}
}
