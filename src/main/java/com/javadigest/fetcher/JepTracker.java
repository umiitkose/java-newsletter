package com.javadigest.fetcher;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javadigest.model.Article;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.File;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JEP (JDK Enhancement Proposal) listesini takip eder.
 *
 *  • https://openjdk.org/jeps/0 sayfasını tarayarak bütün JEP'leri toplar.
 *  • Her JEP için statü + (varsa) hedef JDK release bilgisini saklar.
 *  • Bir önceki çalıştırmaya göre üretilen olayları (yeni JEP, statü değişikliği,
 *    release hedeflendi, release değişti, tamamlandı) {@link JepEvent} olarak döner.
 *
 * Geriye dönük uyumluluk için {@link #checkForChanges()} hâlâ Article listesi döner.
 */
public class JepTracker {

    private static final Logger log = Logger.getLogger(JepTracker.class.getName());
    private static final String JEPS_URL = "https://openjdk.org/jeps/0";
    private static final String JEP_STATE_FILE = "jep-state.json";
    private static final int TIMEOUT = 30_000;

    private static final Pattern RELEASE_PATTERN = Pattern.compile("^\\d{1,3}$");

    private static final Map<String, String> STATUS_ABBR = Map.ofEntries(
            Map.entry("Act", "Active"),
            Map.entry("Dra", "Draft"),
            Map.entry("Sub", "Submitted"),
            Map.entry("Can", "Candidate"),
            Map.entry("Pro", "Proposed to Target"),
            Map.entry("Tar", "Targeted"),
            Map.entry("Int", "Integrated"),
            Map.entry("Com", "Completed"),
            Map.entry("Clo", "Closed"),
            Map.entry("Wit", "Withdrawn"),
            Map.entry("Rej", "Rejected"),
            Map.entry("Inf", "Informational")
    );

    private static final Set<String> COMPLETED_STATUSES = Set.of(
            "Integrated", "Completed", "Closed", "Delivered"
    );

    private final List<String> trackedProjects;
    private final ObjectMapper json = new ObjectMapper();

    public JepTracker(List<String> trackedProjects) {
        this.trackedProjects = trackedProjects;
    }

    // ── Backward-compat: eski Article akışı (digest için) ────────────────────

    /**
     * Eski API. Sadece statü değişikliği olanları Article olarak döner.
     * Yeni kullanım için {@link #pollEvents()} tercih edilmelidir.
     */
    public List<Article> checkForChanges() {
        return pollEvents().stream()
                .filter(e -> e.type() == JepEvent.Type.STATUS_CHANGED
                        || e.type() == JepEvent.Type.COMPLETED)
                .map(JepEvent::toArticle)
                .toList();
    }

    // ── Yeni event tabanlı API ───────────────────────────────────────────────

    /**
     * JEPs sayfasını tarar, bir önceki state ile karşılaştırır ve değişimleri
     * {@link JepEvent} listesi olarak döner. Aynı çalıştırmada state dosyası
     * güncellenir.
     */
    public List<JepEvent> pollEvents() {
        List<JepEvent> events = new ArrayList<>();
        try {
            Map<String, JepInfo> currentJeps = scrapeJeps();
            Map<String, JepRecord> previous = loadState();

            for (Map.Entry<String, JepInfo> entry : currentJeps.entrySet()) {
                String jepId = entry.getKey();
                JepInfo info = entry.getValue();
                JepRecord prev = previous.get(jepId);

                if (prev == null) {
                    events.add(new JepEvent(
                            JepEvent.Type.NEW, jepId, info.title, info.component,
                            null, info.status,
                            null, info.release
                    ));
                    log.info("Yeni JEP keşfedildi: " + jepId + " — " + info.title);
                    continue;
                }

                boolean statusChanged = !Objects.equals(prev.status, info.status);
                boolean releaseChanged = !Objects.equals(prev.release, info.release);

                if (statusChanged) {
                    if (info.status != null && COMPLETED_STATUSES.contains(info.status)) {
                        events.add(new JepEvent(
                                JepEvent.Type.COMPLETED, jepId, info.title, info.component,
                                prev.status, info.status,
                                prev.release, info.release
                        ));
                        log.info("JEP tamamlandı: " + jepId + " (" + info.status + ")");
                    } else {
                        events.add(new JepEvent(
                                JepEvent.Type.STATUS_CHANGED, jepId, info.title, info.component,
                                prev.status, info.status,
                                prev.release, info.release
                        ));
                        log.info("JEP statü değişti: " + jepId + " "
                                + prev.status + " → " + info.status);
                    }
                }

                if (releaseChanged) {
                    if (prev.release == null && info.release != null) {
                        events.add(new JepEvent(
                                JepEvent.Type.RELEASE_TARGETED, jepId, info.title, info.component,
                                prev.status, info.status,
                                null, info.release
                        ));
                        log.info("JEP release hedeflendi: " + jepId + " → JDK " + info.release);
                    } else if (info.release != null) {
                        events.add(new JepEvent(
                                JepEvent.Type.RELEASE_CHANGED, jepId, info.title, info.component,
                                prev.status, info.status,
                                prev.release, info.release
                        ));
                        log.info("JEP release değişti: " + jepId + " "
                                + prev.release + " → " + info.release);
                    }
                }
            }

            Map<String, JepRecord> newState = new LinkedHashMap<>();
            currentJeps.forEach((id, info) ->
                    newState.put(id, new JepRecord(info.status, info.release, info.title, info.component)));
            saveState(newState);

            log.info("JEP Tracker: " + currentJeps.size() + " JEP tarandı, "
                    + events.size() + " yeni olay.");
        } catch (Exception e) {
            log.warning("JEP takip hatası: " + e.getMessage());
        }
        return events;
    }

    // ── Scrape ───────────────────────────────────────────────────────────────

    private Map<String, JepInfo> scrapeJeps() throws Exception {
        Map<String, JepInfo> jeps = new LinkedHashMap<>();

        Document doc = Jsoup.connect(JEPS_URL)
                .userAgent("Java-Digest-Bot/1.0")
                .timeout(TIMEOUT)
                .get();

        Elements rows = doc.select("table tr");
        for (Element row : rows) {
            Elements cells = row.select("td");
            if (cells.size() < 3) continue;

            try {
                ParsedRow parsed = parseRow(cells);
                if (parsed == null) continue;
                if (!isTrackedProject(parsed.title, parsed.component)) continue;
                jeps.put(parsed.jepId, new JepInfo(
                        parsed.title, parsed.status, parsed.component, parsed.release));
            } catch (Exception ignored) {
                // skip malformed rows
            }
        }
        return jeps;
    }

    /**
     * JEPs tablolarındaki sütun sayısı tabloya göre değişir:
     *  • Process / Informational: 4 hücre →  type | status | jepId | titleLink
     *  • Submitted / Draft:        7 hücre →  type | status | comp | / | sub | jepId | titleLink
     *  • In-flight (release'siz):  7 hücre →  type | status | comp | / | sub | jepId | titleLink
     *  • In-flight (release'li):   8 hücre →  type | status | release | comp | / | sub | jepId | titleLink
     *
     * Bu metod sondan saymayı tercih eder ve release'i (varsa) 3. sütunda arar.
     */
    private ParsedRow parseRow(Elements cells) {
        if (cells.size() < 3) return null;

        String status = expandStatus(cells.get(1).text().trim());
        Element titleEl = cells.get(cells.size() - 1);
        String title = titleEl.text().trim();
        String jepId = cells.get(cells.size() - 2).text().replaceAll("\\D+", "");

        if (jepId.isEmpty() || title.isEmpty() || status.isEmpty()) return null;
        // Header / "..." satırlarını ele
        if (title.equalsIgnoreCase("title") || status.equalsIgnoreCase("status")) return null;

        Integer release = null;
        if (cells.size() >= 8) {
            String maybeRelease = cells.get(2).text().trim();
            release = parseRelease(maybeRelease);
        }

        String component = "";
        if (cells.size() >= 7) {
            int compIdx = cells.size() == 8 ? 3 : 2;
            String comp = cells.get(compIdx).text().trim();
            String sub = cells.size() > compIdx + 2 ? cells.get(compIdx + 2).text().trim() : "";
            component = (comp + (sub.isBlank() || "—".equals(sub) ? "" : " / " + sub)).trim();
            if ("—".equals(component)) component = "";
        }

        return new ParsedRow(jepId, title, status, component, release);
    }

    private String expandStatus(String raw) {
        if (raw == null || raw.isBlank()) return "";
        return STATUS_ABBR.getOrDefault(raw, raw);
    }

    private Integer parseRelease(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        Matcher m = RELEASE_PATTERN.matcher(trimmed);
        if (!m.matches()) return null;
        try {
            int v = Integer.parseInt(trimmed);
            // JDK release numarası mantıklı bir aralıkta olmalı
            if (v >= 7 && v <= 99) return v;
        } catch (NumberFormatException ignored) {}
        return null;
    }

    private boolean isTrackedProject(String title, String component) {
        if (trackedProjects == null || trackedProjects.isEmpty()) return true;
        String combined = (title + " " + component).toLowerCase();
        return trackedProjects.stream()
                .anyMatch(p -> combined.contains(p.toLowerCase()));
    }

    // ── State ────────────────────────────────────────────────────────────────

    /**
     * Eski format: { "<jepId>": "Status" }
     * Yeni format: { "<jepId>": { "status": "...", "release": 27, "title": "...", "component": "..." } }
     * İkisini de destekler.
     */
    private Map<String, JepRecord> loadState() {
        File file = new File(JEP_STATE_FILE);
        if (!file.exists()) return new HashMap<>();
        try {
            Map<String, Object> raw = json.readValue(file, new TypeReference<>() {});
            Map<String, JepRecord> result = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : raw.entrySet()) {
                Object v = e.getValue();
                if (v instanceof String s) {
                    result.put(e.getKey(), new JepRecord(s, null, null, null));
                } else if (v instanceof Map<?, ?> m) {
                    String status = asString(m.get("status"));
                    Integer release = asInt(m.get("release"));
                    String title = asString(m.get("title"));
                    String component = asString(m.get("component"));
                    result.put(e.getKey(), new JepRecord(status, release, title, component));
                }
            }
            return result;
        } catch (Exception e) {
            log.warning("jep-state.json okunamadı: " + e.getMessage());
            return new HashMap<>();
        }
    }

    private void saveState(Map<String, JepRecord> state) {
        try {
            Map<String, ObjectNode> serializable = new LinkedHashMap<>();
            for (Map.Entry<String, JepRecord> e : state.entrySet()) {
                ObjectNode node = json.createObjectNode();
                if (e.getValue().status != null) node.put("status", e.getValue().status);
                if (e.getValue().release != null) node.put("release", e.getValue().release);
                if (e.getValue().title != null) node.put("title", e.getValue().title);
                if (e.getValue().component != null) node.put("component", e.getValue().component);
                serializable.put(e.getKey(), node);
            }
            json.writerWithDefaultPrettyPrinter().writeValue(new File(JEP_STATE_FILE), serializable);
        } catch (Exception e) {
            log.warning("jep-state.json yazılamadı: " + e.getMessage());
        }
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }

    private static Integer asInt(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(o.toString()); } catch (NumberFormatException e) { return null; }
    }

    // ── İç tipler ────────────────────────────────────────────────────────────

    private record JepInfo(String title, String status, String component, Integer release) {}
    private record ParsedRow(String jepId, String title, String status, String component, Integer release) {}
    private record JepRecord(String status, Integer release, String title, String component) {}
}
